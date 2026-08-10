package scalapptainer

import scalapptainer.commands.*

/** The main entry point to Scalapptainer.
  *
  * An `Apptainer` is bound to a [[Backend]] and lazily provisions Apptainer in user mode (via [[ApptainerInstaller]])
  * on first use. It exposes two layers:
  *
  *   - a thin escape hatch — [[exec]] / [[shell]] — taking a raw argv; and
  *   - a typed DSL — [[run]] of an [[scalapptainer.commands.ApptainerCommand]] plus the convenience wrappers ([[pull]],
  *     [[build]], [[inspect]], ...).
  *
  * The companion `object Apptainer` is itself a ready-to-use instance bound to the auto-detected backend, so
  * `Apptainer.run(...)`, `Apptainer.version`, etc. work directly. Use `Apptainer(...)` for a custom runner/config or
  * `Apptainer.forBackend` to bind an explicit backend (e.g. in tests).
  */
sealed class Apptainer(val backend: Backend) {

  /** Provisions and resolves Apptainer inside [[backend]] (in user mode), memoized. */
  val installer: ApptainerInstaller = new ApptainerInstaller(backend)

  /** Verify the backend prerequisite is present (throws [[BackendUnavailableException]] with install instructions
    * otherwise).
    */
  def checkAvailable(): Unit = backend.checkAvailable()

  /** The absolute path to the resolved `apptainer` inside the backend (installing in user mode on first call).
    */
  def apptainerPath: String = installer.ensure()

  // --- Thin core / escape hatch ---------------------------------------------

  /** Run `apptainer` with a raw argument vector, capturing output. */
  def exec(args: Seq[String], stdin: Option[String] = None): ProcResult = {
    backend.checkAvailable()
    val bin = installer.ensure()
    val result = backend.runner.run(ProcSpec(backend.wrapApptainer(bin, args), stdin = stdin, env = usernsEnv))
    // A non-zero exit whose output carries the user-namespace signature (e.g. "Could not write info to setgroups")
    // means the backend blocked Apptainer's rootless engine — rethrow as an actionable UserNamespaceException with
    // per-backend remedies rather than letting the opaque stderr surface as a generic command failure. This also
    // covers a system/managed Apptainer that was never subjected to the install-time user-namespace probe.
    if (result.failed && UserNamespaceException.looksLikeUsernsFailure(s"${result.stderr}\n${result.stdout}"))
      throw UserNamespaceException.atRuntime(backend, result.err)
    result
  }

  /** Run `apptainer` with a raw argument vector, inheriting stdio (interactive). Returns the exit code. Use this for
    * `apptainer shell` and other interactive sessions.
    */
  def execInteractive(args: Seq[String]): Int = {
    backend.checkAvailable()
    val bin = installer.ensure()
    backend.runner.runInteractive(ProcSpec(backend.wrapApptainer(bin, args), env = usernsEnv))
  }

  /** Environment additions applied to every Apptainer invocation on this backend.
    *
    * When running as root, Apptainer's default (privileged) engine tries to claim the full root capability set. In a
    * restricted environment whose capability bounding set is reduced — common in containers and CI runners, which often
    * drop capabilities such as `CAP_SYS_RESOURCE` — the `starter` aborts before the container even starts with
    * `Requesting capability set 0x... while permitted capability set is 0x...`. Setting `APPTAINER_USERNS=1` (the
    * environment form of the `--userns` flag) forces Apptainer's rootless, user-namespace engine, which becomes root
    * *inside* a user namespace instead of claiming host capabilities, so containers launch exactly as they do for an
    * unprivileged caller.
    *
    * It is applied only when the backend runs as root. An unprivileged caller already uses the rootless engine, so
    * leaving that path untouched is deliberate: it means a system *setuid-root* Apptainer — the documented fallback for
    * hosts that forbid unprivileged user namespaces (see the README's Troubleshooting section) — is never forced off
    * its setuid engine. The non-action subcommands (`pull`, `build`, `inspect`, `--version`) have no `--userns` flag
    * and simply ignore the variable, so applying it uniformly here is safe.
    *
    * Override with `SCALAPPTAINER_USERNS`: `0`/`false`/`no`/`off` disables it; any other non-empty value forces it on
    * regardless of uid (e.g. to point a non-root run at a non-setuid Apptainer in a capability-restricted container).
    */
  private def usernsEnv: Map[String, String] = {
    val enabled = sys.env.get("SCALAPPTAINER_USERNS").map(_.trim.toLowerCase).filter(_.nonEmpty) match {
      case Some("0" | "false" | "no" | "off") => false
      case Some(_)                            => true
      case None                               => backend.runsAsRoot
    }
    if (enabled) Map("APPTAINER_USERNS" -> "1") else Map.empty
  }

  // --- Typed DSL ------------------------------------------------------------

  /** Execute a typed command, capturing output. */
  def run(command: ApptainerCommand): ProcResult = exec(command.args)

  /** Execute a typed command interactively (inherited stdio), returning the exit code. */
  def runInteractive(command: ApptainerCommand): Int = execInteractive(command.args)

  /** Execute a typed command with inherited stdio, throwing [[ApptainerCommandException]] on a non-zero exit.
    *
    * Because stdio is inherited (so a TTY-attached caller sees Apptainer's own live output — e.g. a `pull`/`build`
    * progress bar — rather than a captured, replayed-at-the-end blob), no output is captured; the thrown exception
    * therefore carries only the exit code and argv.
    */
  def runInteractiveChecked(command: ApptainerCommand): Unit = {
    val code = runInteractive(command)
    if (code != 0)
      throw new ApptainerCommandException(
        ProcResult(code, "", "(stdio was inherited/streamed to the terminal, not captured)", command.args)
      )
  }

  def run(image: String, appArgs: String*): ProcResult =
    run(RunCommand(image, appArgs.toSeq))

  def execIn(image: String, command: String*): ProcResult =
    run(ExecCommand(image, command.toSeq))

  /** Open an interactive shell in the image (inherited stdio). */
  def shell(image: String, options: ExecOptions = ExecOptions.empty): Int =
    runInteractive(ShellCommand(image, options))

  /** The directory where Scalapptainer caches built/pulled images: `~/.scalapptainer/images` inside the backend. */
  def imagesDir: String = s"${backend.cacheDir}/images"

  /** Wrap an existing image reference — a local SIF path or a container URI — as an [[ApptainerImage]] handle (with no
    * options yet).
    */
  def image(ref: String): ApptainerImage = new ApptainerImage(this, ref, ExecOptions.empty)

  /** Pull an OCI/library image into a SIF and return a handle to it.
    *
    * By default the SIF lands in the image cache ([[imagesDir]]) as `<name>.sif`, where `name` is derived from the URI
    * (e.g. `docker://r0d0s/fpga_tools:latest` -> `fpga_tools`) unless given explicitly. Pass `dest` to choose an exact
    * output path instead. If the target already exists it is reused (no re-pull) unless `force = true`. Throws
    * [[ApptainerCommandException]] if the pull fails.
    *
    * `interactive = true` inherits the caller's stdio for the pull instead of capturing it. When the caller is attached
    * to a real terminal this surfaces Apptainer's own live download progress bar (Apptainer gates the bar on stdout
    * being a TTY, so a captured/piped pull shows nothing until it completes). With no TTY (CI, piped, or a detached
    * build server) it is harmless — Apptainer simply prints no bar, exactly as in the captured case.
    */
  def pull(
      uri: String,
      name: String = "",
      dest: Option[String] = None,
      force: Boolean = false,
      interactive: Boolean = false
  ): ApptainerImage = {
    val output = dest match {
      case Some(d) => ensureParentDir(d); d
      case None    => cacheImagePath(if (name.nonEmpty) name else Apptainer.deriveName(uri))
    }
    val img = image(output)
    if (!force && img.exists) img
    else {
      val cmd = PullCommand(uri, Some(output), force)
      if (interactive) runInteractiveChecked(cmd)
      else run(cmd).throwIfFailed()
      img
    }
  }

  /** Build an image and return a handle to it. `source` may be:
    *   - **inline def contents** — a string holding the definition file itself (contains a newline or starts with
    *     `Bootstrap:`); it is written into the backend and built from there;
    *   - a **bare reference** (no leading `/`, `./`, `../`, drive letter or `scheme://`) — looked up on the JVM
    *     classpath first, so a `.def` packaged with the app is built from; otherwise treated as a path;
    *   - an explicit **path** (a def file or sandbox directory) or a container **URI**, used as-is.
    *
    * (See `resolveSource`.)
    *
    * By default the SIF lands in the image cache ([[imagesDir]]) as `<name>.sif`, where `name` is derived from the
    * source basename (e.g. `tools.def` -> `tools`, defaulting to `image` for inline contents) unless given explicitly —
    * a `name` is recommended for inline defs. Pass `dest` to choose an exact output path instead. If the target already
    * exists it is reused (no rebuild) unless `force = true`. Throws [[ApptainerCommandException]] if the build fails.
    *
    * Building from a *definition file* runs its `%post` as (emulated) root. Apptainer normally does this via
    * user-namespace `--fakeroot`, which needs the `newuidmap`/`newgidmap` helpers (the `uidmap` package) whenever the
    * user has an `/etc/subuid` entry. On a host with such an entry but without those helpers — and no root to install
    * them — that path fails hard.
    *
    * `enableNonRootBuild = true` makes an unprivileged def-file build work anyway, without needing root or `uidmap`: it
    * passes `--ignore-subuid`, so Apptainer ignores the subuid entry and builds via a root-mapped user namespace (no
    * `newuidmap` needed), faking multi-uid ownership with its bundled `faked`. That emulated-root build is lower
    * fidelity than real root (some `%post` operations may differ) and slower, so it is **opt-in** and prints a one-time
    * note; for a *published* image, prefer real root (CI/Docker) or install `uidmap`.
    *
    * `mksquashfsArgs`, when given, is passed verbatim to the SIF-packing `mksquashfs` (e.g. `Some("-processors 8")`).
    * It is omitted by default, letting Apptainer pick its own packing parallelism. (Apptainer 1.5.1's bundled
    * `mksquashfs` could segfault at full parallelism on memory-constrained backends — apptainer#3577 — but that is
    * fixed in the pinned 1.5.2; if you must run against an older system Apptainer that still hits it, pass an explicit
    * cap such as `Some("-processors 1")`.)
    *
    * `interactive = true` inherits the caller's stdio for the build instead of capturing it, so a TTY-attached caller
    * sees Apptainer's own live progress (base-image download bars, `%post` output). With no TTY it is harmless. See
    * [[pull]] for the same option on downloads.
    */
  def build(
      source: String,
      name: String = "",
      dest: Option[String] = None,
      sandbox: Boolean = false,
      force: Boolean = false,
      mksquashfsArgs: Option[String] = None,
      enableNonRootBuild: Boolean = false,
      interactive: Boolean = false
  ): ApptainerImage = {
    val imageName = if (name.nonEmpty) name else Apptainer.defaultName(source)
    val output = dest match {
      case Some(d) => ensureParentDir(d); d
      case None    => cacheImagePath(imageName)
    }
    val img = image(output)
    if (!force && img.exists) img
    else {
      if (enableNonRootBuild) Apptainer.warnNonRootBuildOnce()
      val cmd = BuildCommand(
        output,
        resolveSource(source, imageName),
        sandbox = sandbox,
        force = force,
        // The non-root build goes through the root-mapped (non-subuid) path, avoiding the newuidmap requirement.
        ignoreSubuid = enableNonRootBuild,
        mksquashfsArgs = mksquashfsArgs
      )
      if (interactive) runInteractiveChecked(cmd)
      else run(cmd).throwIfFailed()
      img
    }
  }

  /** The cache SIF path `<imagesDir>/<name>.sif`, creating the cache dir first. */
  private def cacheImagePath(name: String): String = {
    backend.runShell(s"mkdir -p ${ShellQuote.single(imagesDir)}").throwIfFailed()
    s"$imagesDir/$name.sif"
  }

  /** Ensure the parent directory of an explicit caller-supplied output path exists. The derived-dest path creates the
    * images cache dir via [[cacheImagePath]]; an explicit `dest` bypasses that, so its parent must be created here —
    * otherwise `apptainer pull`/`build` into a not-yet-existing directory fails with "could not open temporary file for
    * copy: ... no such file or directory".
    */
  private def ensureParentDir(path: String): Unit = {
    val slash = path.lastIndexOf('/')
    if (slash > 0)
      backend.runShell(s"mkdir -p ${ShellQuote.single(path.substring(0, slash))}").throwIfFailed()
  }

  /** Resolve a build `source` to something `apptainer build` can read:
    *   - inline def contents -> written into the backend as `<name>.def` and built from there;
    *   - a bare reference that matches a JVM classpath resource -> the resource is materialised into the backend;
    *   - anything else (an explicit path, a URI, or a bare name with no matching resource) -> used unchanged.
    */
  private def resolveSource(source: String, name: String): String =
    if (Apptainer.isInlineDef(source))
      materialiseDef(s"$name.def", source.getBytes(java.nio.charset.StandardCharsets.UTF_8))
    else if (Apptainer.looksLikePath(source)) source
    else
      Apptainer.resourceBytes(source) match {
        case Some(bytes) => materialiseDef(Apptainer.basename(source), bytes)
        case None        => source
      }

  /** Write def `bytes` to `<cacheDir>/build/<filename>` inside the backend (base64 over the backend boundary, as for
    * the vendored tools — robust regardless of how/whether the host filesystem is mounted) and return its backend path.
    */
  private def materialiseDef(filename: String, bytes: Array[Byte]): String = {
    val dir = s"${backend.cacheDir}/build"
    val dest = s"$dir/$filename"
    backend.runShell(s"mkdir -p ${ShellQuote.single(dir)}").throwIfFailed()
    val b64 = java.util.Base64.getEncoder.encodeToString(bytes)
    backend.runShell(s"base64 -d > ${ShellQuote.single(dest)}", stdin = Some(b64)).throwIfFailed()
    dest
  }

  def inspect(image: String): ProcResult = run(InspectCommand(image, labels = true))

  // --- Misc -----------------------------------------------------------------

  /** Apptainer version string, e.g. "apptainer version 1.4.1" (resolved once). */
  lazy val version: String = exec(Seq("--version")).throwIfFailed().out

  /** Translate a host path into the path visible inside the backend (e.g. a Windows `C:\...` path into its `/mnt/c/...`
    * WSL form), for use in bind mounts and image paths.
    */
  def hostPath(path: String): String = backend.translatePath(path)
}

/** The default, ready-to-use `Apptainer`, bound to the auto-detected backend (native Linux / WSL2 / Lima).
  * `Apptainer.run(...)`, `Apptainer.version`, etc. operate on this instance; its backend prerequisite check and
  * user-mode install still happen lazily on first actual use.
  */
object Apptainer extends Apptainer(Backend.detect()) {

  /** Create an `Apptainer` for the current host with a custom runner/config, auto-detecting the backend (native Linux /
    * WSL2 / Lima).
    */
  def apply(
      runner: CommandRunner = CommandRunner.default,
      config: BackendConfig = BackendConfig.default
  ): Apptainer = new Apptainer(Backend.detect(runner, config))

  /** Create an `Apptainer` bound to an explicit backend (primarily for testing). */
  def forBackend(backend: Backend): Apptainer = new Apptainer(backend)

  /** Derive a cache image name from a build source / pull URI: strip any `scheme://`, take the last path segment, then
    * drop a `:tag` and a file extension. E.g. `docker://r0d0s/fpga_tools:latest` -> `fpga_tools`, `/a/b/tools.def` ->
    * `tools`.
    */
  private[scalapptainer] def deriveName(ref: String): String = {
    val noScheme = ref.split("://", 2).last
    val segment = noScheme.replace('\\', '/').split('/').filter(_.nonEmpty).lastOption.getOrElse(noScheme)
    val noTag = segment.takeWhile(_ != ':')
    val dot = noTag.lastIndexOf('.')
    if (dot > 0) noTag.substring(0, dot) else noTag
  }

  /** True if `ref` is an explicit filesystem path or a container URI — so [[Apptainer.build]] uses it as-is rather than
    * trying the classpath: an absolute path, a `./`/`../` relative path, a Windows drive path, or a `scheme://` URI.
    */
  private[scalapptainer] def looksLikePath(ref: String): Boolean =
    ref.startsWith("/") || ref.startsWith("./") || ref.startsWith("../") ||
      ref.matches("""^[A-Za-z]:[\\/].*""") || ref.contains("://")

  /** True if `source` is inline definition-file *contents* rather than a reference: it spans multiple lines or starts
    * with the `Bootstrap:` header. References (paths, URIs, resource names) are single-line and never start that way.
    */
  private[scalapptainer] def isInlineDef(source: String): Boolean =
    source.contains('\n') || source.trim.toLowerCase.startsWith("bootstrap:")

  /** The default cache image name for a build `source`: `image` for inline def contents, else [[deriveName]]. */
  private[scalapptainer] def defaultName(source: String): String =
    if (isInlineDef(source)) "image" else deriveName(source)

  /** The last path segment of `ref` (its filename). */
  private[scalapptainer] def basename(ref: String): String =
    ref.replace('\\', '/').split('/').filter(_.nonEmpty).lastOption.getOrElse(ref)

  /** Read a classpath resource as bytes, if present — trying the thread context classloader then this class's. */
  private[scalapptainer] def resourceBytes(path: String): Option[Array[Byte]] = {
    def read(cl: ClassLoader): Option[Array[Byte]] =
      Option(cl).flatMap(c => Option(c.getResourceAsStream(path))).map { in =>
        try in.readAllBytes()
        finally in.close()
      }
    read(Thread.currentThread.getContextClassLoader).orElse(read(classOf[Apptainer].getClassLoader))
  }

  @volatile private var noDisplayWarned = false

  /** Warn at most once per process that [[ApptainerImage.withX11]] found no host display to forward. */
  private[scalapptainer] def warnNoDisplayOnce(): Unit = synchronized {
    if (!noDisplayWarned) {
      noDisplayWarned = true
      Console.err.println(
        "[scalapptainer] withX11(): no host display detected on this backend (DISPLAY is unset); the container will " +
          "run without X11 forwarding. On WSL2 ensure WSLg is available; on macOS/Lima start XQuartz with network " +
          "clients allowed."
      )
    }
  }

  @volatile private var nonRootBuildWarned = false

  /** Emit the non-root-build fidelity caveat at most once per process (see [[Apptainer.build]] `enableNonRootBuild`).
    */
  private[scalapptainer] def warnNonRootBuildOnce(): Unit = synchronized {
    if (!nonRootBuildWarned) {
      nonRootBuildWarned = true
      Console.err.println(
        "[scalapptainer] enableNonRootBuild=true: building unprivileged with emulated root (root-mapped namespace + " +
          "faked). Ownership/capabilities may be imperfect and the build is slower. For higher fidelity install the " +
          "'uidmap' package on the backend, or build with real root in CI/Docker."
      )
    }
  }
}
