/*
 * Deterministic smoke test for LauncherModel profile CRUD + persistence.
 *
 * Run with: sbt -batch "Test/runMain moe.lymia.mppatch.ui.LauncherModelSmokeTest"
 *
 * Verifies createProfile / deleteProfile / profileIds roundtrip through the
 * profiles.json manifest and that per-profile enabled-mod sets persist. Reuses
 * a real temp data root so nothing needs a display.
 */
package moe.lymia.mppatch.ui

import java.nio.file.{Files, Path}

object LauncherModelSmokeTest:
  def main(args: Array[String]): Unit =
    val dataRoot = Files.createTempDirectory("mppatch-launcher-")

    // --- create two profiles on a fresh data root ---
    val model = new LauncherModel(dataRoot, defaultProfileId = "Default")
    val c1 = model.createProfile("Default")
    val c2 = model.createProfile("Second")
    println("=== create ===\n  Default -> " + c1 + "\n  Second  -> " + c2)

    // --- reload from disk in a fresh instance (persistence roundtrip) ---
    val reload = new LauncherModel(dataRoot)
    val ids = reload.profileIds()
    println("=== reloaded profileIds ===\n  " + ids)

    // --- per-profile enabled mods persist ---
    val modRoot = Files.createTempDirectory("mppatch-mods-")
    val modA = Files.createFile(modRoot.resolve("mod-a.civ5mod"))
    val modB = Files.createFile(modRoot.resolve("mod-b.civ5mod"))
    reload.setEnabledMods("Default", Seq(modA, modB))
    val enabled = reload.enabledMods("Default")
    println("=== enabled mods after set ===\n  " + enabled)

    // --- delete Second, verify manifest updated ---
    val dirsBeforeDelete =
      Files.isDirectory(dataRoot.resolve("profiles/Default")) && Files.isDirectory(dataRoot.resolve("profiles/Second"))
    val d = reload.deleteProfile("Second")
    println("=== delete Second ===\n  " + d + "\n  remaining: " + reload.profileIds())
    println("  Second dir gone: " + !Files.exists(dataRoot.resolve("profiles/Second")))

    // --- StageService seam: stub must report not-yet-implemented ---
    val staged = reload.stage("Default", Seq(modA), dataRoot.resolve("staged"))
    println("=== StageService seam ===\n  " + staged)

    // --- verify gate ---
    val checks = Map(
      "create Default ok"      -> c1.isRight,
      "create Second ok"       -> c2.isRight,
      "reload sees both ids"   -> (ids == Seq("Default", "Second") || (ids.contains("Default") && ids.contains("Second"))),
      "manifest exists"        -> Files.exists(dataRoot.resolve("profiles/profiles.json")),
      "profile dirs created"   -> dirsBeforeDelete,
      "enabled mods persisted" -> (enabled.sorted == Seq(modA, modB).sorted),
      "delete Second ok"       -> d.isRight,
      "reload after delete"    -> (reload.profileIds().contains("Default") && !reload.profileIds().contains("Second")),
      "profile dir removed"    -> !Files.exists(dataRoot.resolve("profiles/Second")),
      "duplicate create rejected" -> {
        val dup = new LauncherModel(dataRoot).createProfile("Default")
        dup.isLeft
      },
      "stage seam present"     -> staged.isLeft // stub not yet implemented is the expected state
    )
    println("=== verify gate ===")
    checks.foreach { case (k, v) => println(f"$k: $v") }
    if (!checks.values.forall(identity))
      sys.error("SMOKE TEST FAILED: one or more verify checks were false")
    else println("SMOKE TEST OK: all verify checks pass")