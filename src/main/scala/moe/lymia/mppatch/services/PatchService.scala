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

import moe.lymia.mppatch.core.{InstallScript, PatchInstaller, PatchStatus, Platform}
import moe.lymia.mppatch.util.Logger

import java.nio.file.Path

/** Thin, UI-independent wrapper over the core {@code PatchInstaller}
  * install/remove operations. The CLI (and the future launcher) consume these
  * instead of constructing {@code PatchInstaller} themselves.
  */
object PatchService {

  /** Outcome of an install operation. */
  enum InstallOutcome:
    /** The install succeeded; carries the final patch status. */
    case Done(status: PatchStatus)
    /** The install could not proceed because the installation was in an
      * unexpected state.
      */
    case UnexpectedState(status: PatchStatus)

  /** Outcome of an uninstall operation. */
  enum UninstallOutcome:
    /** The patch was not installed; nothing to do. */
    case NotInstalled(status: PatchStatus)
    /** The uninstall succeeded; carries the final patch status. */
    case Done(status: PatchStatus)
    /** The uninstall could not proceed because the installation was in an
      * unexpected state.
      */
    case UnexpectedState(status: PatchStatus)

  /** Constructs a {@link PatchInstaller} for the given root/script. */
  private def installer(basePath: Path, install: InstallScript, platform: Platform, log: Logger) =
    new PatchInstaller(basePath, install, platform, log)

  /** Queries the current patch status (read-only). */
  def check(
      basePath: Path,
      install: InstallScript,
      platform: Platform,
      log: Logger,
      packages: Set[String]
  ): PatchStatus =
    installer(basePath, install, platform, log).checkPatchStatus(packages)

  /** Installs or updates the patch for the given packages, applying the same
    * guarded state transitions the CLI previously performed inline.
    */
  def install(
      basePath: Path,
      install: InstallScript,
      platform: Platform,
      log: Logger,
      packages: Set[String]
  ): InstallOutcome = {
    val p          = installer(basePath, install, platform, log)
    val prevStatus = p.checkPatchStatus(packages)
    prevStatus match
      case PatchStatus.Installed | PatchStatus.PackageChange | PatchStatus.NeedsUpdate |
          PatchStatus.FilesCorrupted | PatchStatus.TargetUpdated | PatchStatus.FilesValidated |
          PatchStatus.NotInstalled(_) =>
        p.safeUpdate(packages)
        InstallOutcome.Done(p.checkPatchStatus(packages))
      case PatchStatus.CanUninstall | PatchStatus.UnknownUpdate =>
        log.info("Uninstalling old version first...")
        p.safeUninstall()
        p.safeUpdate(packages)
        InstallOutcome.Done(p.checkPatchStatus(packages))
      case other => InstallOutcome.UnexpectedState(other)
  }

  /** Removes the patch, reporting whether it was installed at all. */
  def uninstall(
      basePath: Path,
      install: InstallScript,
      platform: Platform,
      log: Logger,
      packages: Set[String]
  ): UninstallOutcome = {
    val p          = installer(basePath, install, platform, log)
    val prevStatus = p.checkPatchStatus(packages)
    prevStatus match
      case PatchStatus.NotInstalled(_) =>
        UninstallOutcome.NotInstalled(prevStatus)
      case PatchStatus.Installed | PatchStatus.PackageChange | PatchStatus.NeedsUpdate |
          PatchStatus.CanUninstall | PatchStatus.UnknownUpdate | PatchStatus.FilesCorrupted |
          PatchStatus.TargetUpdated | PatchStatus.FilesValidated =>
        p.safeUninstall()
        UninstallOutcome.Done(p.checkPatchStatus(packages))
      case other => UninstallOutcome.UnexpectedState(other)
  }
}