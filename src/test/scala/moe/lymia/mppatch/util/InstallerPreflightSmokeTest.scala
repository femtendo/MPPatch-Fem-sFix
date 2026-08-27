/*
 * Deterministic smoke test for the fatal-failure log + preflight checks.
 *
 * Run with: sbt -batch "Test/runMain moe.lymia.mppatch.util.InstallerPreflightSmokeTest"
 *
 * Verifies that InstallerPreflight.writeFatalLog creates a timestamped
 * mppatch-installer_<yyyyMMdd-HHmmss>.log in the platform log directory
 * containing the version string, OS, the exception stack and a numbered
 * preflight result list -- i.e. that a simulated install failure never stays
 * silent.
 */
package moe.lymia.mppatch.util

import java.nio.file.Files

object InstallerPreflightSmokeTest:
  def main(args: Array[String]): Unit =
    // Run preflight against an empty temp dir (simulates missing Civ5 files).
    val dir = Files.createTempDirectory("mppatch-preflight-")
    val preflight = InstallerPreflight.run(Some(dir), Seq("CivilizationV.exe", "steam_api.dll"))
    println("=== preflight results ===")
    preflight.zipWithIndex.foreach { case (r, i) =>
      val tag = if (r.ok) "PASS" else "FAIL"
      println(f"${i + 1}%2d. [$tag] ${r.name}: ${r.detail}")
    }

    // Simulate a fatal failure and write the log.
    val ex = new RuntimeException("simulated install blast", new IllegalStateException("root cause"))
    val path = InstallerPreflight.writeFatalLog(ex, VersionInfo.versionString, preflight)

    println("=== fatal log path ===")
    println(path)
    println("=== fatal log exists ===")
    println(Files.exists(path))
    println("=== fatal log contents (first 40 lines) ===")
    Files.readAllLines(path).stream().limit(40).forEach(l => println(l))

    // Assertions used by the deterministic verify gate.
    val content = Files.readString(path)
    val checks = Map(
      "log exists"               -> Files.exists(path),
      "filename pattern"         -> path.getFileName.toString.matches("mppatch-installer_\\d{8}-\\d{6}\\.log"),
      "contains version"         -> content.contains(VersionInfo.versionString),
      "contains OS"              -> (content.contains(System.getProperty("os.name")) && content.contains("OS:")),
      "contains exception class" -> content.contains("RuntimeException"),
      "contains stack"           -> content.contains("InstallerPreflightSmokeTest"),
      "contains preflight list"  -> content.contains("[FAIL] Civilization V executables present"),
      "contains numbered list"  -> (content.contains("1. [") && (content.contains("[FAIL]") || content.contains("[PASS]")))
    )
    println("=== verify gate ===")
    checks.foreach { case (k, v) => println(f"$k: $v") }
    if (!checks.values.forall(identity)) sys.error("SMOKE TEST FAILED: one or more verify checks were false")
    else println("SMOKE TEST OK: all verify checks pass")
