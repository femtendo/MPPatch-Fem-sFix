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

import Utils.*
import sbt.*
import sbt.Keys.*

import java.util.UUID

object NativePatchBuild {
  // Patch build script
  val settings = Seq(
    Keys.nativeVersions := {
      val crateDir = baseDirectory.value / "src" / "patch" / "mppatch-core"
      val logger   = streams.value.log

      for (
        platform <- Seq[PlatformType](PlatformType.Win32, PlatformType.Linux)
        if PlatformType.currentPlatform.shouldBuildNative(platform)
      ) yield {
        val (rustTarget, outName, targetName) = platform match {
          case PlatformType.Win32 => ("i686-pc-windows-gnu", "mppatch_core.dll", "mppatch_core.dll")
          case PlatformType.Linux => ("i686-unknown-linux-gnu", "mppatch_core.so", "libmppatch_core.so")
          case _                  => sys.error("unreachable")
        }

        // run Cargo
        val buildId = UUID.randomUUID()
        val stubDir = baseDirectory.value / "src" / "patch" / "stub"
        runProcess(
          Seq("cargo", "build", "--target", rustTarget, "--release"),
          crateDir,
          Map(
            "MPPATCH_VERSION" -> version.value,
            "MPPATCH_BUILDID" -> buildId.toString,
            "LUA_LIB_NAME"    -> "lua51_Win32",
            "LUA_LIB"         -> stubDir.getAbsolutePath
          )
        )

        // make the patches list
        PatchFile(outName, crateDir / "target" / rustTarget / "release" / targetName, buildId.toString)
      }
    },
    Keys.win32Wrapper := {
      if (PlatformType.currentPlatform.shouldBuildNative(PlatformType.Win32)) {
        val __ = Keys.nativeVersions.value // make an artifical dependency
        val coreDir = baseDirectory.value / "src" / "patch" / "mppatch-core"
        val targetDir = coreDir / "target" / "i686-pc-windows-gnu" / "release"
        val coreDll   = targetDir / "mppatch_core.dll"
        val wrapperDef = targetDir / "mppatch_core_wrapper_forwarder.def"
        val wrapperDll = targetDir / "mppatch_core_wrapper.dll"
        // Use Python script (works on both Windows and Linux) instead of bash
        runProcess(Seq("python3", "scripts/python/build-win32-wrapper.py",
          coreDll.getAbsolutePath, wrapperDef.getAbsolutePath, wrapperDll.getAbsolutePath))
        Some(wrapperDll)
      } else None
    },
    Keys.lua51Forwarder := {
      if (PlatformType.currentPlatform.shouldBuildNative(PlatformType.Win32)) {
        val __ = Keys.nativeVersions.value // ensure native build has run
        val luajitFiles = LuaJITBuild.Keys.luajitFiles.value
        luajitFiles.find(_.platform == PlatformType.Win32) match {
          case None =>
            streams.value.log.warn("No Win32 LuaJIT build available; skipping lua51 forwarder.")
            None
          case Some(luajitPatch) =>
            val luajitDll = luajitPatch.file
            val targetDir = luajitDll.getParentFile
            val defFile = targetDir / "lua51_forwarder.def"
            val forwarderDll = targetDir / "lua51.dll"

            runProcess(Seq("python3", "scripts/python/build-lua51-forwarder.py",
              luajitDll.getAbsolutePath, defFile.getAbsolutePath, forwarderDll.getAbsolutePath))

            // Clean up temporary build artifacts
            val stubObj = targetDir / "stub.obj"
            val stubC   = targetDir / "stub.c"
            if (stubObj.exists) stubObj.delete()
            if (stubC.exists) stubC.delete()
            if (defFile.exists) defFile.delete()

            Some(forwarderDll)
        }
      } else None
    }
  )

  case class PatchFile(name: String, file: File, buildId: String)
  object Keys {
    val nativeVersions = TaskKey[Seq[PatchFile]]("mppatch-native-versions")
    val win32Wrapper   = TaskKey[Option[File]]("mppatch-native-win32-wrapper")
    val lua51Forwarder = TaskKey[Option[File]]("mppatch-lua51-forwarder")
  }
}
