/*
 * Copyright (c) 2015-2026 Lymia Kanokawa <lymia@lymia.moe>
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

package moe.lymia.mppatch.ui

import com.formdev.flatlaf.fonts.roboto.FlatRobotoFont
import com.formdev.flatlaf.{FlatIntelliJLaf, FlatLaf}
import moe.lymia.mppatch.core.{InstallScript, PatchPackage, PatchStatus, Platform}
import moe.lymia.mppatch.services.{ErrList, FingerprintService, PatchService, ScanService, StageService}
import moe.lymia.mppatch.util.io.ResourceDataSource
import moe.lymia.mppatch.util.{InstallerPreflight, SimpleLogger}
import play.api.libs.json.Json

import java.awt.{BorderLayout, Dimension, GridLayout}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import javax.swing.table.AbstractTableModel
import javax.swing.{SwingUtilities, JButton, JComboBox, JFrame, JLabel, JOptionPane, JPanel,
  JScrollPane, JTable, BorderFactory, DefaultComboBoxModel}
import scala.jdk.CollectionConverters.*

/** The Fem's MPPatch Launcher entry point (v1.0 product entry).
  *
  * The legacy Installer UI remains reachable as the compatibility bootstrap
  * path by passing `--legacy-installer` on the command line, which delegates
  * to [[MPPatchInstaller]].
  */
object LauncherWindow:
  val title = "Fem's MPPatch Launcher"

  /** The patch packages installed by default (see MPPatchCLI). */
  val defaultPackages: Set[String] = Set("logging", "luajit", "multiplayer")

  /** The launcher's data root, derived from InstallerPreflight's log-directory
    * conventions: %LOCALAPPDATA%/MPPatch (Windows) or ~/.local/share/MPPatch
    * (Linux). Profiles live under `<dataRoot>/profiles/`.
    */
  def dataRoot: Path =
    val logDir = InstallerPreflight.logDirectory
    Option(logDir.getParent).getOrElse(logDir)

  def main(args: Array[String]): Unit =
    if (args.contains("--legacy-installer")) MPPatchInstaller.main(args)
    else
      System.setProperty("awt.useSystemAAFontSettings", "on")
      System.setProperty("swing.aatext", "true")
      FlatRobotoFont.install()
      FlatLaf.setPreferredFontFamily(FlatRobotoFont.FAMILY)
      FlatLaf.setPreferredLightFontFamily(FlatRobotoFont.FAMILY_LIGHT)
      FlatLaf.setPreferredSemiboldFontFamily(FlatRobotoFont.FAMILY_SEMIBOLD)
      FlatIntelliJLaf.setup()

      val model = new LauncherModel(dataRoot)
      SwingUtilities.invokeLater(() => {
        val frame = new LauncherWindowFrame(model)
        frame.setVisible(true)
      })

/** Headless model for the launcher shell.
  *
  * Owns all profile create/delete/persist logic plus the mod enable/disable
  * state and the StageService seam. It never opens a real window, so it is
  * fully testable without a display.
  */
class LauncherModel(val dataRoot: Path, val defaultProfileId: String = "Default"):

  def profilesDir: Path  = dataRoot.resolve("profiles")
  def manifestFile: Path = profilesDir.resolve("profiles.json")

  private def ensureDirs(): Unit =
    Files.createDirectories(profilesDir)

  private def persist(ids: Seq[String]): Unit =
    ensureDirs()
    Files.write(manifestFile, Json.prettyPrint(Json.toJson(ids)).getBytes(StandardCharsets.UTF_8))

  /** The list of profile ids recorded in the profiles.json manifest. */
  def profileIds(): Seq[String] =
    ensureDirs()
    if (Files.exists(manifestFile))
      try Json.parse(Files.readString(manifestFile)).asOpt[Seq[String]].getOrElse(Seq.empty)
      catch { case _: Exception => Seq.empty }
    else Seq.empty

  private def profileDir(id: String): Path = profilesDir.resolve(id)

  /** Ensures a default profile exists, returning its id. */
  def ensureDefaultProfile(): String =
    val current = profileIds()
    if (current.isEmpty)
      createProfile(defaultProfileId) match
        case Right(id) => id
        case Left(_)   => defaultProfileId
    else current.head

  /** Creates a new profile (records it in the manifest and creates its dir). */
  def createProfile(id: String): Either[ErrList, String] =
    val cleaned = id.trim
    if (cleaned.isEmpty) Left(ErrList.single("Profile name cannot be empty"))
    else
      ensureDirs()
      Files.createDirectories(profileDir(cleaned))
      val current = profileIds()
      if (current.contains(cleaned)) Left(ErrList.single(s"Profile '$cleaned' already exists"))
      else
        persist(current :+ cleaned)
        Right(cleaned)

  /** Deletes a profile, removing it from the manifest and its directory. */
  def deleteProfile(id: String): Either[ErrList, String] =
    val current = profileIds()
    if (!current.contains(id)) Left(ErrList.single(s"Profile '$id' is not present"))
    else
      persist(current.filterNot(_ == id))
      deleteRecursively(profileDir(id))
      Right(id)

  /** Directory for a profile, ensured to exist. */
  def activeProfileDir(id: String): Path =
    ensureDirs()
    Files.createDirectories(profileDir(id))
    profileDir(id)

  /** Enabled mod paths for the given profile (persisted per-profile). */
  def enabledMods(id: String): Seq[Path] =
    val f = profileDir(id).resolve("enabled.txt")
    if (!Files.exists(f)) Seq.empty
    else
      try
        Files.readAllLines(f).asScala.toSeq.map(Paths.get(_)).filter(_.isAbsolute).distinct.sorted
      catch { case _: Exception => Seq.empty }

  /** Persists the enabled set for a profile. */
  def setEnabledMods(id: String, mods: Seq[Path]): Unit =
    ensureDirs()
    Files.createDirectories(profileDir(id))
    val f = profileDir(id).resolve("enabled.txt")
    Files.write(f, mods.distinct.sorted.map(_.toString).mkString("\n").getBytes(StandardCharsets.UTF_8))

  /** StageService seam: lays out mod copies for a profile. The real
    * implementation lands in a later milestone; the wiring exists now.
    */
  def stage(profileId: String, mods: Seq[Path], targetRoot: Path): Either[ErrList, String] =
    StageService.stage(profileId, mods, targetRoot) match
      case Right(p)  => Right(p.toString)
      case Left(err) => Left(err)

  /** FingerprintService: canonical playset fingerprint over the enabled mods. */
  def playsetFingerprint(profileId: String): String =
    FingerprintService.fingerprint(enabledMods(profileId))

  private def deleteRecursively(p: Path): Unit =
    if (Files.exists(p))
      val stream = Files.walk(p)
      try
        val all = stream.iterator().asScala.toSeq
        // delete children (deepest) before parents
        all.sortBy(_.getNameCount).reverse
          .foreach(x => try Files.deleteIfExists(x) catch { case _: Exception => () })
      finally stream.close()

/** FlatLaf window for the Fem's MPPatch Launcher. */
class LauncherWindowFrame(private val model: LauncherModel) extends JFrame(LauncherWindow.title):

  private val statusPathLabel  = new JLabel("Detection in progress...")
  private val statusPatchLabel = new JLabel("")
  private val fingerprintLabel = new JLabel("")
  private val profileCombo     = new JComboBox[String]()
  private val modTableModel    = new ModTableModel()
  private val modTable         = new JTable(modTableModel)
  private val launchButton     = new JButton("Launch Civilization V")

  buildUI()

  private def buildUI(): Unit =
    setDefaultCloseOperation(3) // JFrame.EXIT_ON_CLOSE (avoid constant-folding on JDK26)
    setLayout(new BorderLayout())
    add(buildStatusPanel(), BorderLayout.NORTH)
    add(buildCenter(), BorderLayout.CENTER)
    add(buildBottom(), BorderLayout.SOUTH)
    setSize(new Dimension(720, 560))
    setLocationRelativeTo(null)
    refreshStatus()
    refreshProfiles()

  private def buildStatusPanel(): JPanel =
    val inner = new JPanel(new GridLayout(2, 1, 0, 2))
    inner.add(statusPathLabel)
    inner.add(statusPatchLabel)
    val refreshBtn = new JButton("Refresh")
    refreshBtn.addActionListener(_ => refreshStatus())
    val panel = new JPanel(new BorderLayout())
    panel.setBorder(BorderFactory.createTitledBorder("Civilization V installation"))
    panel.add(inner, BorderLayout.CENTER)
    panel.add(refreshBtn, BorderLayout.EAST)
    panel

  private def buildProfilePanel(): JPanel =
    val panel = new JPanel(new BorderLayout(8, 0))
    panel.setBorder(BorderFactory.createTitledBorder("Active profile"))
    val newBtn = new JButton("New...")
    newBtn.addActionListener(_ => onNewProfile())
    val delBtn = new JButton("Delete")
    delBtn.addActionListener(_ => onDeleteProfile())
    val buttons = new JPanel(new GridLayout(1, 2, 4, 0))
    buttons.add(newBtn)
    buttons.add(delBtn)
    profileCombo.setModel(new DefaultComboBoxModel[String](Array[String]()))
    panel.add(profileCombo, BorderLayout.CENTER)
    panel.add(buttons, BorderLayout.EAST)
    panel

  private def buildModPanel(): JPanel =
    val panel = new JPanel(new BorderLayout())
    panel.setBorder(BorderFactory.createTitledBorder("Installed mods (enable/disable)"))
    panel.add(new JScrollPane(modTable), BorderLayout.CENTER)
    panel.add(fingerprintLabel, BorderLayout.SOUTH)
    panel

  private def buildBottom(): JPanel =
    launchButton.addActionListener(_ => onLaunch())
    val panel = new JPanel(new BorderLayout())
    panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8))
    panel.add(launchButton, BorderLayout.EAST)
    panel

  private def buildCenter(): JPanel =
    val panel = new JPanel(new BorderLayout(0, 8))
    panel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8))
    panel.add(buildProfilePanel(), BorderLayout.NORTH)
    panel.add(buildModPanel(), BorderLayout.CENTER)
    panel

  // --- status / discovery ------------------------------------------------

  private def discoveredGame(): Option[(Path, PatchStatus)] =
    val pkg = new PatchPackage(ResourceDataSource("builtin_patch"))
    Platform.currentPlatform.flatMap { platform =>
      ScanService.resolveInstall(pkg, platform, None, SimpleLogger).flatMap { root =>
        pkg.detectInstallationPlatform(root).map { script =>
          val status = PatchService.check(root, script, platform, SimpleLogger, LauncherWindow.defaultPackages)
          (root, status)
        }
      }
    }

  private def civExe(root: Path, script: InstallScript): Option[Path] =
    script.script.checkFor.toSeq
      .filter(_.toLowerCase.endsWith(".exe"))
      .sortBy(_.toLowerCase)
      .map(root.resolve)
      .find(p => Files.isRegularFile(p))

  private def refreshStatus(): Unit =
    discoveredGame() match
      case Some((root, status)) =>
        statusPathLabel.setText("Detected: " + root)
        statusPatchLabel.setText("Patch status: " + statusName(status))
        val mods = ScanService.discoverMods(root)
        modTableModel.setMods(mods, Set.empty)
        fingerprintLabel.setText("Playset fingerprint: " + FingerprintService.fingerprint(mods))
      case None =>
        statusPathLabel.setText("No Civilization V installation detected.")
        statusPatchLabel.setText("Install Civilization V via Steam, then click Launch.")
        modTableModel.setMods(Seq.empty, Set.empty)
        fingerprintLabel.setText("")

  private def statusName(s: PatchStatus): String = s match
    case PatchStatus.NotInstalled(_) => "NotInstalled"
    case PatchStatus.Installed       => "Installed"
    case PatchStatus.PackageChange   => "PackageChange"
    case PatchStatus.NeedsUpdate     => "NeedsUpdate"
    case PatchStatus.CanUninstall    => "CanUninstall"
    case other                       => other.toString

  private def patchPasses(s: PatchStatus): Boolean = s match
    case PatchStatus.Installed | PatchStatus.PackageChange => true
    case _                                                 => false

  // --- profiles -----------------------------------------------------------

  private def refreshProfiles(): Unit =
    val ids = model.profileIds()
    if (ids.isEmpty)
      val defId = model.ensureDefaultProfile()
      profileCombo.setModel(new DefaultComboBoxModel[String](Array(defId)))
    else profileCombo.setModel(new DefaultComboBoxModel[String](ids.toArray))

  private def onNewProfile(): Unit =
    val name = JOptionPane.showInputDialog(this, "New profile name:", "New Profile", JOptionPane.PLAIN_MESSAGE)
    if (name != null)
      model.createProfile(name) match
        case Right(id) =>
          refreshProfiles()
          profileCombo.setSelectedItem(id)
        case Left(err) =>
          JOptionPane.showMessageDialog(this, err.message, "Profile", JOptionPane.WARNING_MESSAGE)

  private def onDeleteProfile(): Unit =
    Option(profileCombo.getSelectedItem).foreach { sel =>
      val res = JOptionPane.showConfirmDialog(this, "Delete profile '" + sel.toString + "'?",
        "Delete Profile", JOptionPane.YES_NO_OPTION)
      if (res == JOptionPane.YES_OPTION)
        model.deleteProfile(sel.toString)
        val ids = model.profileIds()
        val next =
          if (ids.contains(model.defaultProfileId)) model.defaultProfileId
          else ids.headOption.getOrElse("")
        refreshProfiles()
        if (next.nonEmpty) profileCombo.setSelectedItem(next)
    }

  // --- mods table -----------------------------------------------------------

  private class ModTableModel extends AbstractTableModel:
    private var data: Seq[Path]    = Seq.empty
    private var enabled: Set[Path] = Set.empty
    private val columns            = Seq("Enabled", "Mod")

    def setMods(mods: Seq[Path], enabledMods: Set[Path]): Unit =
      data = mods
      enabled = enabledMods
      fireTableDataChanged()

    override def getRowCount: Int    = data.size
    override def getColumnCount: Int = columns.size
    override def getColumnName(c: Int): String = columns(c)
    override def getColumnClass(c: Int): Class[?] =
      if (c == 0) classOf[java.lang.Boolean] else classOf[String]
    override def isCellEditable(r: Int, c: Int): Boolean = c == 0
    override def getValueAt(r: Int, c: Int): AnyRef =
      if (c == 0) Boolean.box(enabled contains data(r)) else data(r).toString
    override def setValueAt(aValue: AnyRef, r: Int, c: Int): Unit =
      if (c == 0)
        if (java.lang.Boolean.TRUE.equals(aValue)) enabled = enabled + data(r)
        else enabled = enabled - data(r)
        fireTableRowsUpdated(r, r)

  // --- launch ---------------------------------------------------------------

  private def onLaunch(): Unit =
    discoveredGame() match
      case Some((root, status)) if patchPasses(status) =>
        val pkg = new PatchPackage(ResourceDataSource("builtin_patch"))
        pkg.detectInstallationPlatform(root).flatMap(civExe(root, _)) match
          case Some(exe) =>
            try
              val pb = new ProcessBuilder(exe.toString)
              pb.directory(exe.getParent.toFile)
              pb.start()
              statusPatchLabel.setText("Launched " + exe.getFileName)
            catch
              case e: Exception =>
                JOptionPane.showMessageDialog(this, "Failed to launch game: " + e.getMessage, "Launch",
                  JOptionPane.ERROR_MESSAGE)
          case None =>
            JOptionPane.showMessageDialog(this, "Could not locate the Civilization V executable.", "Launch",
              JOptionPane.ERROR_MESSAGE)
      case _ => promptFirstRunInstall()

  private def promptFirstRunInstall(): Unit =
    val choice = JOptionPane.showConfirmDialog(this,
      "The Fem's MPPatch is not yet installed. Install it now?",
      "First-run patch install", JOptionPane.YES_NO_OPTION)
    if (choice == JOptionPane.YES_OPTION)
      val pkg = new PatchPackage(ResourceDataSource("builtin_patch"))
      val found = for {
        platform <- Platform.currentPlatform
        root     <- ScanService.resolveInstall(pkg, platform, None, SimpleLogger)
        script   <- pkg.detectInstallationPlatform(root)
      } yield (platform, root, script)
      found match
        case Some((pl, root, sc)) =>
          PatchService.install(root, sc, pl, SimpleLogger, LauncherWindow.defaultPackages) match
            case PatchService.InstallOutcome.Done(status) =>
              statusPatchLabel.setText("Patch status: " + statusName(status) + " (install complete)")
            case _ =>
              statusPatchLabel.setText("Install could not complete — see installer logs.")
        case None =>
          JOptionPane.showMessageDialog(this, "No Civilization V installation found.", "First-run",
            JOptionPane.ERROR_MESSAGE)