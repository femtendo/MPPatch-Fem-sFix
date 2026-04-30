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

import Config.*
import Utils.*
import sbt.*
import sbt.Keys.*

object LuaJITBuild {
  val coreCount = java.lang.Runtime.getRuntime.availableProcessors

  // WSL1 cannot execute 32-bit Linux ELF, which LuaJIT requires for host tools (minilua/buildvm).
  // WSL2 runs a real Linux kernel and supports 32-bit ELF — only WSL1 needs to be skipped.
  // Both report "microsoft" in osrelease; WSL2 additionally reports "wsl2".
  lazy val isWsl1: Boolean =
    !System.getProperty("os.name", "").toLowerCase.contains("windows") && {
      try {
        val rel = scala.io.Source.fromFile("/proc/sys/kernel/osrelease").mkString.toLowerCase
        rel.contains("microsoft") && !rel.contains("wsl2")
      } catch { case _: Exception => false }
    }

  // Patch build script
  val settings = Seq(
    Keys.luajitCacheDir  := crossTarget.value / "luajit-cache",
    Keys.luajitSourceDir := baseDirectory.value / "src" / "patch" / "luajit",
    Keys.luajitFiles := {
      val patchDirectory = Keys.luajitCacheDir.value / "output"
      val logger         = streams.value.log
      IO.createDirectory(patchDirectory)

      if (isWsl1) {
        logger.warn("WSL1 detected: skipping LuaJIT build (32-bit host tools cannot run on WSL1).")
        Seq.empty
      } else

      for (
        platform <- Seq(PlatformType.Win32, PlatformType.Linux)
        if PlatformType.currentPlatform.shouldBuildNative(platform)
      ) yield {
        val (env, outputFile, extension) =
          platform match {
            case PlatformType.Win32 =>
              val isWindows = System.getProperty("os.name").toLowerCase.contains("windows")
              val (includePath, libPaths) = if (isWindows) {
                val baseDir = baseDirectory.value.toString.replace('\\', '/')
                val mingwDir = s"$baseDir/build-tools/mingw32/mingw32"
                val mingwGccLib = s"$mingwDir/lib/gcc/i686-w64-mingw32/15.2.0"
                val mingwLib = s"$mingwDir/lib"
                val mingwTargetLib = s"$mingwDir/i686-w64-mingw32/lib"
                val mingwInclude = s"$mingwDir/i686-w64-mingw32/include"
                (s"-isystem $mingwInclude", s"-L $mingwGccLib -L $mingwLib -L $mingwTargetLib")
              } else {
                // Linux: use system MinGW-w64 headers/libs (from mingw-w64 package)
                ("-isystem /usr/i686-w64-mingw32/include", "-L /usr/i686-w64-mingw32/lib")
              }
              val tgtFlags = s"--target=i686-w64-mingw32 $includePath $libPaths -O2 ${config_common_secureFlags.mkString(" ")} -static-libgcc -Wl,--start-group -lmsvcr90 -Wno-unused-command-line-argument ${config_win32_secureFlags.mkString(" ")}"
              // HOST_CC builds host tools (minilua, buildvm) that run on the build machine.
              // They must be 32-bit to match the i686 target (LuaJIT checks pointer-size parity).
              // On Windows, i686 target produces WoW64-runnable binaries.
              // On Linux, -m32 produces native 32-bit Linux binaries (requires gcc-multilib).
              val hostCc = if (isWindows) s"clang --target=i686-w64-mingw32 $includePath $libPaths" else "clang -m32"
              (
                Map(
                  "CROSS"        -> config_mingw_prefix,
                  "TARGET_SYS"   -> "Windows",
                  "HOST_CC"      -> hostCc,
                  "STATIC_CC"    -> "clang",
                  "DYNAMIC_CC"   -> "clang -fPIC",
                  "TARGET_LD"    -> "clang",
                  "TARGET_FLAGS" -> tgtFlags
                ),
                "src/lua51.dll",
                ".dll"
              )
            case PlatformType.Linux =>
              (
                Map(
                  "HOST_CC"      -> s"$config_linux_cc -m32",
                  "STATIC_CC"    -> config_linux_cc,
                  "DYNAMIC_CC"   -> s"${config_linux_cc} -fPIC",
                  "TARGET_LD"    -> config_linux_cc,
                  "TARGET_FLAGS" -> (s"--target=$config_target_linux" +: "-O2" +: (config_common_secureFlags ++ Seq(s"--target=$config_target_linux"))).mkString(" ")
                ),
                "src/libluajit.so",
                ".so"
              )
          }
        val excludeDeps = Set(
          "lj_bcdef.h",
          "lj_ffdef.h",
          "lj_libdef.h",
          "lj_recdef.h",
          "lj_folddef.h",
          "buildvm_arch.h",
          "vmdef.lua"
        )
        val dependencies = Path
          .allSubpaths(Keys.luajitSourceDir.value)
          .filter { case (_, x) =>
            (x.endsWith(".c") || x.endsWith(".h") || x.endsWith("Makefile") || x.endsWith(".lua")) &&
            !excludeDeps.contains(x.split("/").last)
          }
          .map(_._1)

        val outTarget = patchDirectory / s"luajit_${platform.name}$extension"
        val outputPath =
          trackDependencies(Keys.luajitCacheDir.value / (platform + "_c_out"), dependencies.toSet) {
            logger.info("Compiling Luajit for " + platform)
            make(Keys.luajitSourceDir.value, Seq("clean"), env)
            make(Keys.luajitSourceDir.value, Seq(), env)
            IO.copyFile(Keys.luajitSourceDir.value / outputFile, outTarget)
            outTarget
          }

        LuaJITPatchFile(platform, outTarget)
      }
    }
  )

  def make(dir: File, actions: Seq[String], env: Map[String, String]) =
    runProcess(
      config_make +: "--trace" +: "-C" +: dir.toString +: "-j" +: coreCount.toString +:
        (actions ++ env.map(x => s"${x._1}=${x._2}"))
    )

  case class LuaJITPatchFile(platform: PlatformType, file: File)

  object Keys {
    val luajitCacheDir  = SettingKey[File]("luajit-cache-dir")
    val luajitSourceDir = SettingKey[File]("luajit-source-dir")
    val luajitFiles     = TaskKey[Seq[LuaJITPatchFile]]("luajit-files")
  }
}
