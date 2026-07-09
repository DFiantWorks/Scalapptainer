package scalapptainer

import java.util.Base64

/** Ensures the userland tools required by Apptainer's unprivileged installer are available *inside the backend* —
  * without needing root.
  *
  * A minimal WSL2 / Lima / CI distro (e.g. a bare Debian container like Scastie's) frequently lacks `rpm2cpio` (an
  * RPM-world tool that Debian/Ubuntu never ship) and `cpio`, sometimes `curl`, and — crucially — the decompressor the
  * RPM payload needs (Apptainer's EL RPMs are `xz`-compressed). Installing these via `apt`/`dnf` would require root,
  * defeating the user-mode goal, so Scalapptainer vendors static Linux binaries as JAR resources and materialises
  * whatever is missing into the backend cache directory.
  *
  * Two kinds of tool are vendored:
  *   - `curl` and `rpm2cpio` each have their own resource (a static binary and a POSIX script respectively);
  *   - `cpio` and the RPM-payload decompressors (`xz`/`gzip`/`bzip2`) are *applets of a single vendored busybox*.
  *     busybox is a multi-call binary that dispatches on `argv[0]`, so each applet is materialised as a symlink to the
  *     one busybox binary rather than a separate copy. (busybox has no zstd applet, so a zstd-compressed RPM still
  *     needs a system `zstd`.)
  *
  * Already-present system copies are always preferred; the vendored binaries are a fallback only. The result is a PATH
  * prefix (the directory holding whatever was materialised) the installer is run with, or `None` if everything needed
  * was already present.
  */
object VendoredTools {

  /** Tools vendored as their own resource (a static binary or a POSIX script). */
  private val ownTools: Seq[String] = Seq("curl", "rpm2cpio")

  /** Multi-call applets provided by the single vendored busybox binary: the installer's `cpio`, plus the decompressors
    * the vendored `rpm2cpio` shells out to for compressed RPM payloads. Each is materialised as a symlink to the
    * busybox binary, only when missing from the backend PATH.
    *
    * The names match what this Alpine busybox actually exposes (and what `rpm2cpio` invokes): it provides `xzcat` and
    * `unlzma` but **not** an `xz` applet, so the xz/lzma payloads are handled via those. busybox has no `zstd` applet,
    * so a zstd-compressed RPM still needs a system `zstd`.
    */
  private val busyboxApplets: Seq[String] = Seq("cpio", "gzip", "bzip2", "xzcat", "unlzma")

  /** Every tool this object can provide. */
  val requiredTools: Seq[String] = ownTools ++ busyboxApplets

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

  /** Make sure every tool the installer needs is callable inside `backend`.
    *
    * @return
    *   `Some(binDir)` — a directory (inside the backend) to prepend to PATH that holds the materialised vendored tools
    *   — or `None` if all tools were already present on the backend PATH.
    */
  def ensure(backend: Backend): Option[String] = {
    val missingOwn = ownTools.filterNot(backend.hasCommand)
    val missingApplets = busyboxApplets.filterNot(backend.hasCommand)
    if (missingOwn.isEmpty && missingApplets.isEmpty) return None

    val arch = backend.arch
    val binDir = s"${backend.cacheDir}/tools/bin"
    backend.runShell(s"mkdir -p ${ShellQuote.single(binDir)}").throwIfFailed()

    missingOwn.foreach(tool => materialiseIfAbsent(backend, arch, tool, s"$binDir/$tool"))

    if (missingApplets.nonEmpty) {
      // A single busybox binary backs cpio and the decompressors; each applet is a symlink to it, named so busybox's
      // argv[0] dispatch runs the right applet.
      materialiseIfAbsent(backend, arch, "busybox", s"$binDir/busybox")
      missingApplets.foreach(applet => symlinkIfAbsent(backend, "busybox", s"$binDir/$applet"))
    }

    Some(binDir)
  }

  /** Materialise the vendored `tool` to `dest` unless an executable is already there (e.g. from a previous run). */
  private def materialiseIfAbsent(backend: Backend, arch: Arch, tool: String, dest: String): Unit =
    if (!backend.runShell(s"test -x ${ShellQuote.single(dest)}").succeeded)
      materialise(backend, arch, tool, dest)

  /** Symlink `dest` -> `target` (a name relative to `dest`'s own directory) inside the backend, unless `dest` already
    * exists. A relative target keeps the link valid wherever the backend cache lives.
    */
  private def symlinkIfAbsent(backend: Backend, target: String, dest: String): Unit =
    if (!backend.runShell(s"test -e ${ShellQuote.single(dest)}").succeeded)
      backend.runShell(s"ln -s ${ShellQuote.single(target)} ${ShellQuote.single(dest)}").throwIfFailed()

  /** Decode a vendored binary into `dest` inside the backend via a base64 pipe.
    *
    * base64 is used rather than a raw byte pipe or a host->guest file copy because it is robust across the `wsl.exe` /
    * `limactl shell` boundary regardless of how (or whether) the host filesystem is mounted into the guest, and avoids
    * any binary-mangling on the Windows pipe. `base64` itself is part of coreutils and is universally present.
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
