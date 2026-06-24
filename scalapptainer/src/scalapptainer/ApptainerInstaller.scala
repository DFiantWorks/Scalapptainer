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
  *   1. an `apptainer` already on the backend PATH (e.g. a system install) — used as-is, but if its version is older
  *      than the pinned one a one-time warning is emitted (silence with `SCALAPPTAINER_SKIP_VERSION_CHECK`);
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
      if (p.nonEmpty) {
        warnIfSystemApptainerOutdated(p)
        return p
      }
    }
    if (isExecutable(managedBin)) return managedBin
    install()
    managedBin
  }

  /** A system Apptainer on PATH is used as-is (resolution step 1), even if it predates the version this Scalapptainer
    * release pins — which is how someone keeps running on an older box, but also how they miss an upstream fix (e.g.
    * the mksquashfs segfault fixed in 1.5.2). When the system version is older than the pinned one, emit a one-time
    * heads-up. It is advisory only: nothing is changed and the system install is still used. Silence it by setting
    * `SCALAPPTAINER_SKIP_VERSION_CHECK` (any non-empty value).
    */
  private def warnIfSystemApptainerOutdated(path: String): Unit = {
    if (sys.env.get("SCALAPPTAINER_SKIP_VERSION_CHECK").exists(_.nonEmpty)) return
    val r = backend.runShell(s"${ShellQuote.single(path)} --version")
    if (r.failed) return
    for {
      found <- ApptainerInstaller.parseVersion(r.out)
      if ApptainerInstaller.compareVersions(found, version) < 0
    } ApptainerInstaller.warnOutdatedSystemVersionOnce(path, found, version)
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

  /** The Apptainer release this Scalapptainer version pins. The value is defined once in `build.mill`
    * (`Versions.apptainer`), emitted into the `scalapptainer/build.properties` classpath resource by Mill, and read back
    * here; bump it there in lockstep with a Scalapptainer release to adopt a new Apptainer (e.g. to pick up an upstream
    * fix). The new version installs into its own `~/.scalapptainer/<version>` directory.
    */
  val DefaultApptainerVersion: String = buildProperty("apptainer.version")

  /** Reads a property from the Mill-generated `scalapptainer/build.properties` classpath resource. */
  private def buildProperty(key: String): String = {
    val resource = "/scalapptainer/build.properties"
    val props = new java.util.Properties()
    Option(getClass.getResourceAsStream(resource)) match {
      case Some(in) => try props.load(in) finally in.close()
      case None     =>
        throw new IllegalStateException(s"Missing build resource '$resource' (generated by Mill from Versions.apptainer)")
    }
    Option(props.getProperty(key)).filter(_.nonEmpty).getOrElse {
      throw new IllegalStateException(s"Property '$key' missing or empty in '$resource'")
    }
  }

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

  /** Extract the dotted version number from `apptainer --version` output, e.g. `"apptainer version 1.5.2"` -> `Some(
    * "1.5.2")`. Returns `None` if no version-looking token is found.
    */
  private[scalapptainer] def parseVersion(versionOutput: String): Option[String] =
    """\d+(?:\.\d+)*(?:[-+][0-9A-Za-z.-]+)?""".r.findFirstIn(versionOutput.trim)

  /** Compare two dotted version strings numerically: negative if `a < b`, zero if equal, positive if `a > b`. Numeric
    * components are compared as integers (missing trailing components count as 0, so `1.5` == `1.5.0`). When the numeric
    * parts are equal, a pre-release/build suffix (e.g. `-rc.1`) sorts *below* the plain release, matching SemVer.
    */
  private[scalapptainer] def compareVersions(a: String, b: String): Int = {
    val numbered = """^(\d+(?:\.\d+)*)(.*)$""".r
    def split(v: String): (Seq[Int], String) = v.trim match {
      case numbered(nums, rest) => (nums.split('.').map(_.toInt).toSeq, rest)
      case _                    => (Seq.empty, v.trim)
    }
    val (na, ra) = split(a)
    val (nb, rb) = split(b)
    val numericCmp =
      (0 until math.max(na.length, nb.length)).iterator
        .map(i => na.applyOrElse(i, (_: Int) => 0).compare(nb.applyOrElse(i, (_: Int) => 0)))
        .find(_ != 0)
        .getOrElse(0)
    if (numericCmp != 0) numericCmp
    else (ra.isEmpty, rb.isEmpty) match {
      case (true, true)   => 0
      case (true, false)  => 1 // a is a plain release, b has a pre-release suffix -> a is newer
      case (false, true)  => -1
      case (false, false) => ra.compare(rb)
    }
  }

  @volatile private var versionWarned = false

  /** Print the outdated-system-Apptainer heads-up at most once per process (see [[warnIfSystemApptainerOutdated]]). */
  private[scalapptainer] def warnOutdatedSystemVersionOnce(path: String, found: String, pinned: String): Unit =
    synchronized {
      if (!versionWarned) {
        versionWarned = true
        Console.err.println(
          s"[scalapptainer] the system Apptainer on PATH ($path) is version $found, older than the $pinned this " +
            s"Scalapptainer release pins. It is being used as-is, but you may be missing upstream fixes. To use $pinned, " +
            s"remove the system Apptainer from PATH (Scalapptainer will then install $pinned under ~/.scalapptainer), or " +
            s"upgrade the system install. Silence this warning with SCALAPPTAINER_SKIP_VERSION_CHECK=1."
        )
      }
    }
}
