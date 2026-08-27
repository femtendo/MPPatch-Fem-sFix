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

package moe.lymia.mppatch.util

import moe.lymia.mppatch.core.{Platform, PlatformType}

import java.io.{FileOutputStream, OutputStreamWriter, PrintWriter}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import scala.util.{Failure, Success, Try}

/** A single preflight check result. */
case class PreflightResult(name: String, ok: Boolean, detail: String)

/** Preflight checks + a deterministic fatal-failure log writer shared by the
  * GUI installer and the CLI.
  *
  * The fatal log is always written to
  *   Windows: %LOCALAPPDATA%/MPPatch/logs/mppatch-installer_<yyyyMMdd-HHmmss>.log
  *   Linux:   ~/.local/share/MPPatch/logs/mppatch-installer_<yyyyMMdd-HHmmss>.log
  * and contains the version string, OS, the exception stack and a numbered
  * preflight result list so that a silent install failure is never silent.
  */
object InstallerPreflight {
  val minFreeDiskMb: Long = 100L
  private val nameFormat   = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")

  def platformLabel: String = PlatformType.currentPlatform match {
    case PlatformType.Win32 => "Windows"
    case PlatformType.Linux => "Linux"
    case PlatformType.MacOS => "macOS"
    case _                  => "Unknown (" + System.getProperty("os.name") + ")"
  }

  /** Directory where fatal logs are stored, per platform. */
  def logDirectory: Path = PlatformType.currentPlatform match {
    case PlatformType.Win32 =>
      sys.env
        .get("LOCALAPPDATA")
        .map(Paths.get(_).resolve("MPPatch").resolve("logs"))
        .getOrElse(Paths.get(System.getProperty("user.home", ".")).resolve("MPPatch/logs"))
    case PlatformType.Linux =>
      Paths.get(System.getProperty("user.home", ".")).resolve(".local/share/MPPatch/logs")
    case _ =>
      Paths.get(System.getProperty("user.home", ".")).resolve(".MPPatch/logs")
  }

  /** Detection of Steam library / install paths via the existing platform logic. */
  def steamRoots: Seq[Path] = Platform.currentPlatform match {
    case Some(p) => Try(p.defaultSystemPaths).getOrElse(Seq.empty[Path])
    case None    => Seq.empty[Path]
  }

  /** Runs all preflight checks, returning a numbered-ready result list.
    *
    * @param installDir
    *   the Civ 5 install directory, if it is known yet.
    * @param checkFor
    *   the files that must exist in the install root (from the install
    *   script's `checkFor` list, e.g. `CivilizationV.exe` on win32).
    */
  def run(installDir: Option[Path], checkFor: Seq[String]): Seq[PreflightResult] = {
    val probe = installDir.filter(Files.isDirectory(_))
    Seq(
      platformCheck(),
      steamPathCheck(),
      civ5ExeCheck(probe, checkFor),
      diskSpaceCheck(probe),
      writeAccessCheck(installDir)
    )
  }

  private def platformCheck(): PreflightResult = {
    val ok = PlatformType.currentPlatform match {
      case PlatformType.Win32 | PlatformType.Linux => true
      case _                                       => false
    }
    PreflightResult("Platform detected & supported", ok, platformLabel)
  }

  private def steamPathCheck(): PreflightResult = {
    val roots = steamRoots
    val detail =
      if (roots.nonEmpty) roots.mkString("; ")
      else "no Steam library folders found (expected for non-Steam/non-installed installs)"
    PreflightResult("Steam library path detected", roots.nonEmpty, detail)
  }

  private def civ5ExeCheck(installDir: Option[Path], checkFor: Seq[String]): PreflightResult = {
    val (ok, detail) = installDir match {
      case Some(dir) =>
        val missing = checkFor.filterNot(f => Files.exists(dir.resolve(f)))
        if (missing.isEmpty) (true, checkFor.mkString(", ") + " present in " + dir)
        else (false, "missing required files: " + missing.mkString(", "))
      case None =>
        (false, "no install directory selected")
    }
    PreflightResult("Civilization V executables present (checkFor)", ok, detail)
  }

  private def diskSpaceCheck(probe: Option[Path]): PreflightResult = {
    val name = f"Disk space >= ${minFreeDiskMb}MB"
    probe match {
      case Some(dir) =>
        Try(Files.getFileStore(dir).getUsableSpace) match {
          case Success(bytes) =>
            val mb   = bytes / (1024L * 1024L)
            val free = mb >= minFreeDiskMb
            PreflightResult(name, free, f"$mb%d MB free on ${dir}")
          case Failure(e) =>
            PreflightResult(name, false, "could not query disk space: " + e.getMessage)
        }
      case None =>
        PreflightResult(name, true, "no install directory selected, skipped")
    }
  }

  private def writeAccessCheck(installDir: Option[Path]): PreflightResult = {
    val name = "Write/admin access to install directory"
    installDir match {
      case Some(dir) =>
        val target = if (Files.exists(dir)) dir else Option(dir.getParent).getOrElse(dir)
        Try {
          val tmp = Files.createTempFile(target, ".mppatch_preflight_", ".tmp")
          Try(Files.deleteIfExists(tmp))
        } match {
          case Success(_) => PreflightResult(name, true, "write access OK (" + target + ")")
          case Failure(e) =>
            PreflightResult(name, false, "write access denied (" + target + "): " + e.getMessage)
        }
      case None =>
        PreflightResult(name, true, "no install directory selected, skipped")
    }
  }

  /** Renders the preflight list as numbered PASS/FAIL lines (for logs, the
    * terminal and error dialogs).
    */
  def preflightText(preflight: Seq[PreflightResult]): String =
    if (preflight.isEmpty) "  (no preflight results collected)"
    else preflight.zipWithIndex.map { case (r, i) =>
      val tag = if (r.ok) "PASS" else "FAIL"
      f"${i + 1}%2d. [$tag] ${r.name}: ${r.detail}"
    }.mkString("\n")

  /** Writes a timestamped fatal-failure log in the platform log directory and
    * returns its path. Does not throw -- logging is best effort, since a
    * failing logger must never crash the failure path itself.
    */
  def writeFatalLog(t: Throwable, version: String, preflight: Seq[PreflightResult]): Path = {
    val dir  = logDirectory
    val file = dir.resolve(s"mppatch-installer_${nameFormat.format(LocalDateTime.now)}.log")
    try {
      Files.createDirectories(dir)
      val writer = new PrintWriter(
        new OutputStreamWriter(new FileOutputStream(file.toFile), StandardCharsets.UTF_8)
      )
      try {
        writer.println("=== MPPatch installer fatal failure log ===")
        writer.println(s"Timestamp: ${LocalDateTime.now}")
        writer.println(s"Version: $version")
        writer.println(
          s"OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} " +
            s"(${System.getProperty("os.arch")}), platform: $platformLabel"
        )
        writer.println(s"JDK: ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
        writer.println()
        writer.println("--- Exception ---")
        writer.println(s"${t.getClass.getName}: ${t.getMessage}")
        if (t.getStackTrace.nonEmpty) t.getStackTrace.foreach(ste => writer.println(s"  at $ste"))
        writer.println()
        writer.println("--- Preflight results ---")
        writer.println(preflightText(preflight))
        writer.flush()
      } finally writer.close()
      file
    } catch {
      case e: Throwable =>
        System.err.println("WARN: could not write fatal log to " + file + ": " + e.getMessage)
        Try {
          // Fall back to the current directory so the log always lands somewhere.
          val fallback = Paths.get(".").toAbsolutePath.normalize.resolve(file.getFileName)
          val writer = new PrintWriter(
            new OutputStreamWriter(new FileOutputStream(fallback.toFile), StandardCharsets.UTF_8)
          )
          try {
            writer.println("=== MPPatch installer fatal failure log (fallback location) ===")
            writer.println(s"Timestamp: ${LocalDateTime.now}")
            writer.println(s"Version: $version")
            writer.println(s"OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
            writer.println(s"${t.getClass.getName}: ${t.getMessage}")
            writer.println(preflightText(preflight))
          } finally writer.close()
          fallback
        }.getOrElse(file)
    }
  }
}
