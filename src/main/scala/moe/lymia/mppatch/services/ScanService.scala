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

package moe.lymia.mppatch.services

import moe.lymia.mppatch.core.{PatchPackage, Platform}
import moe.lymia.mppatch.util.Logger

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*

/** Discovers Civilization V installations and installed mods in a
  * UI-independent way. Shared by the CLI and the future launcher.
  */
object ScanService {

  /** Whether {@code root} is a valid Civilization V installation directory. */
  def isCivInstall(pkg: PatchPackage, root: Path): Boolean =
    Files.exists(root) && Files.isDirectory(root) && pkg.detectInstallationPlatform(root).isDefined

  /** The platform's candidate roots to probe for a Civ5 install. */
  def defaultRoots(platform: Platform): Seq[Path] =
    platform.defaultSystemPaths

  /** Discovers every valid Civ install under the platform's default roots. */
  def discoverInstalls(pkg: PatchPackage, platform: Platform): Seq[Path] =
    defaultRoots(platform).filter(isCivInstall(pkg, _))

  /** Resolves the Civ install directory to act on: an explicitly supplied
    * root if valid, otherwise auto-detected from the platform's default roots.
    *
    * @return
    *   the install root, or {@code None} when no valid installation exists.
    */
  def resolveInstall(
      pkg: PatchPackage,
      platform: Platform,
      explicitRoot: Option[Path],
      log: Logger
  ): Option[Path] =
    explicitRoot match
      case Some(path) =>
        if (isCivInstall(pkg, path)) Some(path)
        else {
          log.warn(s"Explicit path is not a valid Civ5 installation: $path")
          None
        }
      case None =>
        log.info("Auto-detecting Civ5 installation...")
        discoverInstalls(pkg, platform).headOption match {
          case Some(path) =>
            log.info(s"  Found: $path")
            Some(path)
          case None => None
        }

  /** Discovers installed Civilization V mods under a root directory.
    *
    * A mod is recognized either by a {@code .civ5mod} archive or by a
    * {@code .modinfo} descriptor file (in which case the directory containing
    * the descriptor is treated as the installed mod root).
    *
    * @return
    *   the mod package paths found, sorted and deduplicated.
    */
  def discoverMods(root: Path, maxDepth: Int = 8): Seq[Path] =
    if (!Files.isDirectory(root)) Seq.empty
    else
      try {
        val stream = Files.walk(root, maxDepth)
        try {
          stream.iterator().asScala
            .filter(p => Files.isRegularFile(p))
            .flatMap { p =>
              val name = p.getFileName.toString
              if (name.endsWith(".modinfo")) Some(p.getParent)
              else if (name.endsWith(".civ5mod")) Some(p)
              else None
            }
            .toSeq
            .distinct
            .sorted
        } finally stream.close()
      } catch {
        case _: Exception => Seq.empty
      }
}