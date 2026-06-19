package scalapptainer

import java.util.Base64

/** Ensures the userland tools required by Apptainer's unprivileged installer
  * (`curl`, `rpm2cpio`, `cpio`) are available *inside the backend* — without
  * needing root.
  *
  * A minimal WSL2/Lima distro frequently lacks `rpm2cpio` (an RPM-world tool that
  * Debian/Ubuntu never ship) and sometimes `curl`/`cpio`, and installing them via
  * `apt`/`dnf` would require root, defeating the user-mode goal. So Scalapptainer
  * vendors static Linux binaries of these tools as JAR resources and, for any tool
  * not already present on the backend PATH, materialises the vendored copy into the
  * backend cache directory.
  *
  * Already-present system copies are always preferred; the vendored binaries are a
  * fallback only. The result is a PATH prefix (the directory containing whatever was
  * materialised) that the installer is run with, or `None` if every tool was already
  * present.
  */
object VendoredTools {

  /** The tools the unprivileged installer requires, in PATH order. */
  val requiredTools: Seq[String] = Seq("curl", "rpm2cpio", "cpio")

  /** Classpath location of a vendored tool binary for a given architecture. */
  private def resourcePath(arch: Arch, tool: String): String =
    s"/scalapptainer/tools/linux-${arch.resourceName}/$tool"

  /** Read a vendored binary from the classpath, if present. */
  private def readResource(arch: Arch, tool: String): Option[Array[Byte]] = {
    val path = resourcePath(arch, tool)
    Option(getClass.getResourceAsStream(path)).map { in =>
      try in.readAllBytes()
      finally in.close()
    }
  }

  /** Make sure every required tool is callable inside `backend`.
    *
    * @return `Some(binDir)` — a directory (inside the backend) to prepend to PATH
    *         that holds the materialised vendored tools — or `None` if all tools
    *         were already present on the backend PATH.
    */
  def ensure(backend: Backend): Option[String] = {
    val missing = requiredTools.filterNot(backend.hasCommand)
    if (missing.isEmpty) return None

    val arch = backend.arch
    val binDir = s"${backend.cacheDir}/tools/bin"
    backend.runShell(s"mkdir -p ${ShellQuote.single(binDir)}").throwIfFailed()

    missing.foreach { tool =>
      // It may already have been materialised by a previous run.
      val dest = s"$binDir/$tool"
      val alreadyThere =
        backend.runShell(s"test -x ${ShellQuote.single(dest)}").succeeded
      if (!alreadyThere) materialise(backend, arch, tool, dest)
    }

    Some(binDir)
  }

  /** Decode a vendored binary into `dest` inside the backend via a base64 pipe.
    *
    * base64 is used rather than a raw byte pipe or a host->guest file copy because
    * it is robust across the `wsl.exe` / `limactl shell` boundary regardless of how
    * (or whether) the host filesystem is mounted into the guest, and avoids any
    * binary-mangling on the Windows pipe. `base64` itself is part of coreutils and
    * is universally present.
    */
  private def materialise(backend: Backend, arch: Arch, tool: String, dest: String): Unit = {
    val bytes = readResource(arch, tool).getOrElse {
      throw new InstallationException(
        s"""'$tool' is not available on the ${backend.name} backend PATH, and no vendored
           |binary is bundled for architecture '${arch.resourceName}'
           |(expected classpath resource ${resourcePath(arch, tool)}).
           |
           |Either install '$tool' inside the backend, or add the vendored binary by running
           |the vendoring script: vendor/refresh-tools.sh
           |""".stripMargin
      )
    }
    val b64 = Base64.getEncoder.encodeToString(bytes)
    val q = ShellQuote.single(dest)
    backend
      .runShell(s"base64 -d > $q && chmod +x $q", stdin = Some(b64))
      .throwIfFailed()
  }
}
