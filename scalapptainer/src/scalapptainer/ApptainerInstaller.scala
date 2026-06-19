package scalapptainer

/** Ensures an `apptainer` executable is available inside a [[Backend]], installing
  * it in user mode (no root) on first use, and caches the resolved path.
  *
  * Resolution order:
  *   1. an `apptainer` already on the backend PATH (e.g. a system install);
  *   2. a previous Scalapptainer-managed install under the backend cache;
  *   3. a fresh unprivileged install via Apptainer's `install-unprivileged.sh`,
  *      run with [[VendoredTools]] on PATH so `curl`/`rpm2cpio`/`cpio` are present.
  */
final class ApptainerInstaller(
    backend: Backend,
    installerUrl: String = ApptainerInstaller.defaultInstallerUrl
) {

  @volatile private var resolved: Option[String] = None

  /** Absolute path to a usable `apptainer` inside the backend, installing if needed. */
  def ensure(): String = resolved match {
    case Some(p) => p
    case None =>
      synchronized {
        resolved.getOrElse {
          val p = resolve()
          resolved = Some(p)
          p
        }
      }
  }

  private def managedBin: String = s"${backend.cacheDir}/apptainer/bin/apptainer"

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
    val installDir = s"${backend.cacheDir}/apptainer"
    val pathPrefix = VendoredTools.ensure(backend)
    val exportPath =
      pathPrefix.map(d => s"export PATH=${ShellQuote.single(d)}:\"$$PATH\"\n").getOrElse("")

    val q = ShellQuote.single(installDir)
    val u = ShellQuote.single(installerUrl)
    val script =
      s"""set -e
         |${exportPath}mkdir -p $q
         |curl -fsSL $u | bash -s - $q
         |""".stripMargin

    val r = backend.runShell(script)
    if (r.failed)
      throw new InstallationException(
        s"""Failed to install Apptainer in user mode inside the ${backend.name} backend.
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

  /** Where the unprivileged installer script is fetched from. Overridable via
    * `SCALAPPTAINER_INSTALLER_URL` (e.g. to pin a specific Apptainer release tag).
    */
  def defaultInstallerUrl: String =
    sys.env.getOrElse(
      "SCALAPPTAINER_INSTALLER_URL",
      "https://raw.githubusercontent.com/apptainer/apptainer/main/tools/install-unprivileged.sh"
    )
}
