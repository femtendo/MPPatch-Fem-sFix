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
import moe.lymia.mppatch.util.{Logger, SimpleLogger, VersionInfo}
import moe.lymia.mppatch.util.io.ResourceDataSource
import play.api.libs.json.Json

import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.time.Instant

object MPPatchCLI:
  private val exitSuccess     = 0
  private val exitError       = 1
  private val exitBadArgs     = 2
  private val exitPathInvalid = 3

  def main(args: Array[String]): Unit =
    val result = CliConfig.parse(args) match
      case Left(usage) =>
        System.err.println(usage)
        sys.exit(exitBadArgs)

      case Right(config) =>
        val stderrLogger = new SimpleLogger(
          new OutputStreamWriter(System.err, StandardCharsets.UTF_8)
        )
        val logger = stderrLogger

        logger.info(s"MPPatch CLI v${VersionInfo.versionString}")
        logger.info(s"Command: ${config.command}")

        val platform = createPlatform()
        val pkg      = new PatchPackage(ResourceDataSource("builtin_patch"))
        val resolvedPath = resolveCivPath(config.civPath, pkg, platform, logger) match
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

        executeCommand(config.command, config, resolvedPath, pathStr, installScript, platform, logger)

    printJson(result)
    if result.success then sys.exit(exitSuccess)
    else sys.exit(exitError)

  private def executeCommand(
      cmd: CliCommand,
      config: CliConfig,
      path: Path,
      pathStr: String,
      installScript: InstallScript,
      platform: Platform,
      logger: Logger
  ): CliResult =
    val installer = new PatchInstaller(path, installScript, platform, logger)

    cmd match
      case CliCommand.Check =>
        val status = installer.checkPatchStatus(config.packages)
        logger.info(s"Patch status: ${CliResult.statusName(status)}")
        CliResult.successCheck(pathStr, status, s"Patch status: ${CliResult.statusName(status)}")

      case CliCommand.Install =>
        val prevStatus = installer.checkPatchStatus(config.packages)
        logger.info(s"Previous status: ${CliResult.statusName(prevStatus)}")

        val currentStatus = prevStatus match
          case PatchStatus.Installed | PatchStatus.PackageChange | PatchStatus.NeedsUpdate |
              PatchStatus.FilesCorrupted | PatchStatus.TargetUpdated | PatchStatus.FilesValidated =>
            installer.safeUpdate(config.packages)
            installer.checkPatchStatus(config.packages)

          case PatchStatus.NotInstalled(_) =>
            installer.safeUpdate(config.packages)
            installer.checkPatchStatus(config.packages)

          case PatchStatus.CanUninstall | PatchStatus.UnknownUpdate =>
            logger.info("Uninstalling old version first...")
            installer.safeUninstall()
            installer.safeUpdate(config.packages)
            installer.checkPatchStatus(config.packages)

          case other =>
            outputError("install", pathStr, s"Cannot safely install: unexpected state ${CliResult.statusName(other)}")
            return CliResult.error("install", pathStr,
              s"Cannot safely install: unexpected state ${CliResult.statusName(other)}",
              Some(other))

        val logFiles = scanLogFiles(path)
        logger.info(s"Install complete. Status: ${CliResult.statusName(currentStatus)}")
        CliResult.success("install", pathStr, currentStatus, "Patch installed successfully.", logFiles)

      case CliCommand.Uninstall =>
        val prevStatus = installer.checkPatchStatus(config.packages)
        logger.info(s"Previous status: ${CliResult.statusName(prevStatus)}")

        prevStatus match
          case PatchStatus.NotInstalled(_) =>
            CliResult.success("uninstall", pathStr, prevStatus, "Patch is not installed.")

          case PatchStatus.Installed | PatchStatus.PackageChange | PatchStatus.NeedsUpdate |
              PatchStatus.CanUninstall | PatchStatus.UnknownUpdate | PatchStatus.FilesCorrupted |
              PatchStatus.TargetUpdated | PatchStatus.FilesValidated =>
            installer.safeUninstall()
            val newStatus = installer.checkPatchStatus(config.packages)
            logger.info(s"Uninstall complete. Status: ${CliResult.statusName(newStatus)}")
            CliResult.success("uninstall", pathStr, newStatus, "Patch uninstalled successfully.")

          case other =>
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

  private def resolveCivPath(
      explicitPath: Option[Path],
      pkg: PatchPackage,
      platform: Platform,
      logger: Logger
  ): Option[Path] =
    explicitPath match
      case Some(path) =>
        if isValidCivInstall(path, pkg) then Some(path)
        else
          logger.warn(s"Explicit path is not a valid Civ5 installation: $path")
          None
      case None =>
        logger.info("Auto-detecting Civ5 installation...")
        val validPaths = for
          path <- platform.defaultSystemPaths
          if isValidCivInstall(path, pkg)
        yield
          logger.info(s"  Found: $path")
          path
        validPaths.headOption

  private def isValidCivInstall(root: Path, pkg: PatchPackage): Boolean =
    Files.exists(root) && Files.isDirectory(root) && pkg.detectInstallationPlatform(root).isDefined

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
