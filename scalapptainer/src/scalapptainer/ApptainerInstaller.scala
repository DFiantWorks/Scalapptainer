package scalapptainer

/** Ensures an `apptainer` executable is available inside a [[Backend]], installing it in user mode (no root) on first
  * use, and caches the resolved path.
  *
  * The Apptainer release is **pinned per Scalapptainer release** (see [[ApptainerInstaller.DefaultApptainerVersion]]):
  * the unprivileged installer is told to fetch exactly that version (`install-unprivileged.sh -v <version>`) and the
  * result is installed under `~/.scalapptainer/<version>` so different pinned versions never collide. The installer
  * script itself is fetched from Apptainer's `main` branch (not the release tag) so its distro/repo selection logic
  * stays current — an older tagged script pins a now-EOL default distro whose packages have since been removed.
  *
  * Resolution order:
  *   1. an `apptainer` already on the backend PATH (e.g. a system install);
  *   2. a previous Scalapptainer-managed install of the pinned version under the backend cache;
  *   3. a fresh unprivileged install via Apptainer's `install-unprivileged.sh`, run with [[VendoredTools]] on PATH so
  *      `curl`/`rpm2cpio`/`cpio` are present.
  */
final class ApptainerInstaller(
    backend: Backend,
    version: String = ApptainerInstaller.pinnedVersion
) {

  private val installerUrl: String = ApptainerInstaller.defaultInstallerUrl

  @volatile private var resolved: Option[String] = None

  /** Absolute path to a usable `apptainer` inside the backend, installing if needed. */
  def ensure(): String = resolved match {
    case Some(p) => p
    case None    =>
      synchronized {
        resolved.getOrElse {
          val p = resolve()
          resolved = Some(p)
          p
        }
      }
  }

  /** The per-version install root, e.g. `~/.scalapptainer/1.5.1`. */
  private def installDir: String = s"${backend.cacheDir}/$version"
  private def managedBin: String = s"$installDir/bin/apptainer"

  private def resolve(): String = {
    if (backend.hasCommand("apptainer")) {
      val p = backend.runShell("command -v apptainer").throwIfFailed().out
      if (p.nonEmpty) return p
    }
    if (isExecutable(managedBin)) return managedBin
    install()
    managedBin
  }

  private def isExecutable(path: String): Boolean =
    backend.runShell(s"test -x ${ShellQuote.single(path)}").succeeded

  private def install(): Unit = {
    // We only reach install() when no system Apptainer and no prior managed install were found, so this is the
    // unprivileged user-mode install — which is pointless if the backend can't create user namespaces (Apptainer's
    // rootless engine needs them to run anything). Fail fast and clearly here, before fetching tens of MB of RPMs.
    // A setuid-root or already-installed Apptainer never reaches this point, so it is never subjected to the check.
    if (!sys.env.contains("SCALAPPTAINER_SKIP_USERNS_CHECK") && backend.unprivilegedUsernsBlocked)
      throw UserNamespaceException.atInstall(backend)

    val pathPrefix = VendoredTools.ensure(backend)
    val exportPath =
      pathPrefix.map(d => s"export PATH=${ShellQuote.single(d)}:\"$$PATH\"\n").getOrElse("")

    val q = ShellQuote.single(installDir)
    val u = ShellQuote.single(installerUrl)
    val v = ShellQuote.single(version)
    // We only reach install() when no usable managed `apptainer` was found, so any pre-existing install dir is stale
    // (e.g. a previous run interrupted mid-install, as can happen on a reused CI container like Scastie's). Clear it
    // first: `install-unprivileged.sh` aborts with "<dir>/<arch> is not empty" if its target arch dir already exists,
    // and offers no force/overwrite flag, so a leftover directory would otherwise wedge every subsequent run.
    val script =
      s"""set -e
         |${exportPath}rm -rf $q
         |mkdir -p $q
         |curl -fsSL $u | bash -s - -v $v $q
         |""".stripMargin

    val r = backend.runShell(script)
    if (r.failed)
      throw new InstallationException(
        s"""Failed to install Apptainer $version in user mode inside the ${backend.name} backend.
           |--- installer output ---
           |${r.err}""".stripMargin
      )
    if (!isExecutable(managedBin))
      throw new InstallationException(
        s"Apptainer installer completed but '$managedBin' was not produced inside the ${backend.name} backend."
      )
  }
}

object ApptainerInstaller {

  /** The Apptainer release this Scalapptainer version pins. Bump this in lockstep with a Scalapptainer release to adopt
    * a new Apptainer (e.g. to pick up an upstream fix); the new version installs into its own
    * `~/.scalapptainer/<version>` directory.
    */
  val DefaultApptainerVersion: String = "1.5.1"

  /** The pinned Apptainer version, overridable per host via `SCALAPPTAINER_APPTAINER_VERSION`. */
  def pinnedVersion: String =
    sys.env.get("SCALAPPTAINER_APPTAINER_VERSION").filter(_.nonEmpty).getOrElse(DefaultApptainerVersion)

  /** Where the unprivileged installer script is fetched from. Defaults to `install-unprivileged.sh` on Apptainer's
    * `main` branch (kept current so its distro/repo selection doesn't rot); the *version* it installs is pinned
    * separately via `-v`. Overridable via `SCALAPPTAINER_INSTALLER_URL`.
    */
  def defaultInstallerUrl: String =
    sys.env
      .get("SCALAPPTAINER_INSTALLER_URL")
      .filter(_.nonEmpty)
      .getOrElse("https://raw.githubusercontent.com/apptainer/apptainer/main/tools/install-unprivileged.sh")
}
