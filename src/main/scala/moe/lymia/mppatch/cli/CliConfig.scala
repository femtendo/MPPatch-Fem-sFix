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

import java.nio.file.{Path, Paths}

enum CliCommand:
  case Install
  case Uninstall
  case Check

case class CliConfig(
    command: CliCommand,
    civPath: Option[Path] = None,
    packages: Set[String] = Set("logging", "luajit", "multiplayer"),
    verbose: Boolean = false
)

object CliConfig:
  private val defaultPackages = Set("logging", "luajit", "multiplayer")

  def parse(args: Array[String]): Either[String, CliConfig] =
    if args.isEmpty then Left(usageText)
    else parseArgs(args.toList, CliConfig(command = CliCommand.Check))

  private def parseArgs(remaining: List[String], config: CliConfig): Either[String, CliConfig] =
    remaining match
      case Nil => Right(config)
      case "--help" :: _ => Left(usageText)
      case "--verbose" :: tail =>
        parseArgs(tail, config.copy(verbose = true))
      case "--json" :: tail =>
        parseArgs(tail, config)
      case "--path" :: path :: tail =>
        parseArgs(tail, config.copy(civPath = Some(Paths.get(path))))
      case "--packages" :: pkgList :: tail =>
        val pkgs = pkgList.split(",").map(_.trim).filter(_.nonEmpty).toSet
        parseArgs(tail, config.copy(packages = pkgs))
      case "--path" :: _ =>
        Left("Missing value for --path")
      case "--packages" :: _ =>
        Left("Missing value for --packages")
      case arg :: tail if !arg.startsWith("--") =>
        parseCommand(arg) match
          case Some(cmd) => parseArgs(tail, config.copy(command = cmd))
          case None      => Left(s"Unknown command: $arg\n\n$usageText")
      case unknown :: _ =>
        Left(s"Unknown option: $unknown\n\n$usageText")

  private def parseCommand(s: String): Option[CliCommand] = s.toLowerCase match
    case "install"   => Some(CliCommand.Install)
    case "uninstall" => Some(CliCommand.Uninstall)
    case "check"     => Some(CliCommand.Check)
    case "status"    => Some(CliCommand.Check)
    case _           => None

  val usageText: String =
    """Usage: mppatch-cli <command> [options]
      |
      |Commands:
      |  install      Install or update the patch
      |  uninstall    Remove the patch
      |  check        Check current patch status (read-only)
      |
      |Options:
      |  --path <dir>        Civ5 installation directory (auto-detected if omitted)
      |  --packages <list>   Comma-separated packages (default: logging,luajit,multiplayer)
      |  --verbose           Verbose logging to stderr
      |  --help              Print this help
      |
      |Output is JSON on stdout. Exit codes: 0=success, 1=error, 2=bad args.
      |""".stripMargin
