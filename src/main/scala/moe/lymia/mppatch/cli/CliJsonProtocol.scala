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

import moe.lymia.mppatch.core.PatchStatus
import play.api.libs.json.*

case class CliResult(
    success: Boolean,
    command: String,
    civPath: String,
    status: Option[String],
    previousStatus: Option[String],
    message: String,
    logFiles: Map[String, Option[String]]
)

object CliResult:
  def statusName(s: PatchStatus): String = s match
    case PatchStatus.NotInstalled(_) => "NotInstalled"
    case PatchStatus.Installed       => "Installed"
    case PatchStatus.CanUninstall    => "CanUninstall"
    case PatchStatus.PackageChange   => "PackageChange"
    case PatchStatus.NeedsUpdate     => "NeedsUpdate"
    case PatchStatus.FilesValidated  => "FilesValidated"
    case PatchStatus.TargetUpdated   => "TargetUpdated"
    case PatchStatus.UnknownUpdate   => "UnknownUpdate"
    case PatchStatus.FilesCorrupted  => "FilesCorrupted"
    case PatchStatus.NeedsCleanup    => "NeedsCleanup"
    case PatchStatus.NeedsValidation => "NeedsValidation"

  implicit val writes: OWrites[CliResult] = Json.writes[CliResult]

  def success(
      command: String,
      civPath: String,
      status: PatchStatus,
      message: String,
      logFiles: Map[String, Option[String]] = Map.empty
  ): CliResult =
    CliResult(
      success = true,
      command = command,
      civPath = civPath,
      status = Some(statusName(status)),
      previousStatus = None,
      message = message,
      logFiles = logFiles
    )

  def successCheck(
      civPath: String,
      status: PatchStatus,
      message: String
  ): CliResult =
    CliResult(
      success = true,
      command = "check",
      civPath = civPath,
      status = Some(statusName(status)),
      previousStatus = None,
      message = message,
      logFiles = Map.empty
    )

  def error(
      command: String,
      civPath: String,
      message: String,
      previousStatus: Option[PatchStatus] = None
  ): CliResult =
    CliResult(
      success = false,
      command = command,
      civPath = civPath,
      status = None,
      previousStatus = previousStatus.map(statusName),
      message = message,
      logFiles = Map.empty
    )
