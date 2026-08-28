/*
 * Copyright (c) 2015-2023 Lymia Kanokawa <lymia@lymia.moe>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package moe.lymia.mppatch.cli

import moe.lymia.mppatch.core.*
import moe.lymia.mppatch.services.{PatchService, ScanService}
import moe.lymia.mppatch.util.{InstallerPreflight, Logger, PreflightResult, SimpleLogger, VersionInfo}
import moe.lymia.mppatch.util.io.ResourceDataSource
import play.api.libs.json.Json

import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}

object MPPatchCLI:
  private val exitSuccess     = 0
  private val exitError       = 1
  private val exitBadArgs     = 2
  private val exitPathInvalid = 3

  def main(args: Array[String]): Unit =
    var preflightResults: Seq[PreflightResult] = Seq.empty
    try {
      val result = CliConfig.parse(args) match
        case Left(CliConfig.ParseFailure.Help) =>
          // Explicit --help request: print usage and exit 0 (not an error).
          System.out.println(CliConfig.usageText)
          sys.exit(exitSuccess)

        case Left(CliConfig.ParseFailure.UsageError(message)) =>
          System.err.println(message)
          sys.exit(exitBadArgs)

        case Right(config) =>
          val stderrLogger = new SimpleLogger(
            new OutputStreamWriter(System.err, StandardCharsets.UTF_8)
          )
          val stdoutLogger = new SimpleLogger(
            new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
          )
          val logger = stderrLogger

          logger.info(s"MPPatch CLI v${VersionInfo.versionString}")
          logger.info(s"Command: ${config.command}")

          val platform = createPlatform()
          val pkg      = new PatchPackage(ResourceDataSource("builtin_patch"))
          val resolvedPath = ScanService.resolveInstall(pkg, platform, config.civPath, logger) match
            case Some(path) => path
            case None =>
              logger.error("No valid Civilization V installation found.")
              outputError(
                "check",
                config.civPath.fold("(auto-detect)")(_.toString),
                "No valid Civilization V installation found."
              )
              sys.exit(exitPathInvalid)

          val pathStr = resolvedPath.toString
          logger.info(s"Civ5 path: $pathStr")

          val installScript = pkg.detectInstallationPlatform(resolvedPath) match
            case Some(script) => script
            case None =>
              logger.error("Could not detect installation platform at path.")
              outputError("check", pathStr, "Could not detect installation platform.")
              sys.exit(exitPathInvalid)

          // Run preflight checks -- always collected so they land in any failure log.
          preflightResults =
            InstallerPreflight.run(Some(resolvedPath), installScript.script.checkFor.toSeq)
          for (r <- preflightResults)
            if (r.ok) logger.info(s"[PASS] ${r.name}: ${r.detail}")
            else logger.warn(s"[FAIL] ${r.name}: ${r.detail}")

          // --verbose prints the same diagnostic info to stdout.
          if (config.verbose) {
            stdoutLogger.info("MPPatch CLI verbose diagnostics")
            stdoutLogger.info("Version: " + VersionInfo.versionString)
            stdoutLogger.info("Platform: " + InstallerPreflight.platformLabel)
            stdoutLogger.info(
              s"OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
                s"(${System.getProperty("os.arch")})"
            )
            stdoutLogger.info(s"Command: ${config.command}")
            stdoutLogger.info(s"Civ5 path: $pathStr")
            stdoutLogger.info("Installer log directory: " + InstallerPreflight.logDirectory.toString)
            stdoutLogger.info("Preflight results:")
            InstallerPreflight.preflightText(preflightResults).split("\n").foreach(l => stdoutLogger.info(l))
          }

          executeCommand(config.command, config, resolvedPath, pathStr, installScript, platform, logger)

      printJson(result)
      if result.success then sys.exit(exitSuccess)
      else sys.exit(exitError)
    } catch {
      case t: Throwable =>
        // Never fail silently: persist a timestamped fatal log and surface the error.
        // Avoid touching VersionInfo here: if its static init is what failed,
        // re-referencing it masks the root cause with NoClassDefFoundError.
        val versionSafe = try VersionInfo.versionString catch { case _: Throwable => "<version-unavailable>" }
        val logPath = InstallerPreflight.writeFatalLog(t, versionSafe, preflightResults)
        System.err.println(s"MPPatch CLI FAILURE: ${t.getClass.getName}: ${t.getMessage}")
        System.err.println(s"Fatal log written to: $logPath")
        t.printStackTrace(System.err)
        sys.exit(exitError)
    }

  private def executeCommand(
      cmd: CliCommand,
      config: CliConfig,
      path: Path,
      pathStr: String,
      installScript: InstallScript,
      platform: Platform,
      logger: Logger
  ): CliResult =
    val packages = config.packages
    cmd match
      case CliCommand.Check =>
        val status = PatchService.check(path, installScript, platform, logger, packages)
        logger.info(s"Patch status: ${CliResult.statusName(status)}")
        CliResult.successCheck(pathStr, status, s"Patch status: ${CliResult.statusName(status)}")

      case CliCommand.Install =>
        val prevStatus = PatchService.check(path, installScript, platform, logger, packages)
        logger.info(s"Previous status: ${CliResult.statusName(prevStatus)}")

        PatchService.install(path, installScript, platform, logger, packages) match
          case PatchService.InstallOutcome.Done(currentStatus) =>
            val logFiles = scanLogFiles(path)
            logger.info(s"Install complete. Status: ${CliResult.statusName(currentStatus)}")
            CliResult.success("install", pathStr, currentStatus, "Patch installed successfully.", logFiles)
          case PatchService.InstallOutcome.UnexpectedState(other) =>
            outputError("install", pathStr, s"Cannot safely install: unexpected state ${CliResult.statusName(other)}")
            CliResult.error("install", pathStr,
              s"Cannot safely install: unexpected state ${CliResult.statusName(other)}",
              Some(other))

      case CliCommand.Uninstall =>
        val prevStatus = PatchService.check(path, installScript, platform, logger, packages)
        logger.info(s"Previous status: ${CliResult.statusName(prevStatus)}")

        PatchService.uninstall(path, installScript, platform, logger, packages) match
          case PatchService.UninstallOutcome.NotInstalled(status) =>
            CliResult.success("uninstall", pathStr, status, "Patch is not installed.")
          case PatchService.UninstallOutcome.Done(newStatus) =>
            logger.info(s"Uninstall complete. Status: ${CliResult.statusName(newStatus)}")
            CliResult.success("uninstall", pathStr, newStatus, "Patch uninstalled successfully.")
          case PatchService.UninstallOutcome.UnexpectedState(other) =>
            outputError("uninstall", pathStr, s"Cannot safely uninstall: unexpected state ${CliResult.statusName(other)}")
            CliResult.error("uninstall", pathStr,
              s"Cannot safely uninstall: unexpected state ${CliResult.statusName(other)}",
              Some(other))

  private def createPlatform(): Platform =
    PlatformType.currentPlatform match
      case PlatformType.Win32 => Platform(PlatformType.Win32).get
      case PlatformType.Linux => Platform(PlatformType.Linux).get
      case PlatformType.MacOS =>
        System.err.println("macOS is not supported.")
        sys.exit(1)
      case _ =>
        System.err.println("Unknown platform.")
        sys.exit(1)

  private def scanLogFiles(civPath: Path): Map[String, Option[String]] =
    val files = Seq(
      "ctor"       -> civPath.resolve("mppatch_ctor.txt"),
      "debugLog"   -> civPath.resolve("mppatch_debug.log"),
      "fatalError" -> civPath.resolve("mppatch_fatal_error.txt"),
      "installer"  -> civPath.resolve("mppatch_installer.log"),
      "state"      -> civPath.resolve("mppatch_install_state.xml"),
      "config"     -> civPath.resolve("mppatch_config.toml")
    )
    files.map { case (key, path) =>
      key -> (if Files.exists(path) then Some(path.toString) else None)
    }.toMap

  private def outputError(command: String, path: String, msg: String): Unit =
    val errResult = CliResult.error(command, path, msg)
    System.err.println(Json.prettyPrint(Json.toJson(errResult)))

  private def printJson(result: CliResult): Unit =
    System.out.println(Json.prettyPrint(Json.toJson(result)))