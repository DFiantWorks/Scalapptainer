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
    backend.runner.run(ProcSpec(backend.wrapApptainer(bin, args), stdin = stdin))
  }

  /** Run `apptainer` with a raw argument vector, inheriting stdio (interactive). Returns the exit code. Use this for
    * `apptainer shell` and other interactive sessions.
    */
  def execInteractive(args: Seq[String]): Int = {
    backend.checkAvailable()
    val bin = installer.ensure()
    backend.runner.runInteractive(ProcSpec(backend.wrapApptainer(bin, args)))
  }

  // --- Typed DSL ------------------------------------------------------------

  /** Execute a typed command, capturing output. */
  def run(command: ApptainerCommand): ProcResult = exec(command.args)

  /** Execute a typed command interactively (inherited stdio), returning the exit code. */
  def runInteractive(command: ApptainerCommand): Int = execInteractive(command.args)

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
    */
  def pull(
      uri: String,
      name: String = "",
      dest: Option[String] = None,
      force: Boolean = false
  ): ApptainerImage = {
    val output = resolveOutput(uri, name, dest)
    val img = image(output)
    if (!force && img.exists) img
    else {
      run(PullCommand(uri, Some(output), force)).throwIfFailed()
      img
    }
  }

  /** Build an image and return a handle to it. `source` is a definition file, a sandbox directory or a container URI.
    *
    * By default the SIF lands in the image cache ([[imagesDir]]) as `<name>.sif`, where `name` is derived from the
    * source basename (e.g. `tools.def` -> `tools`) unless given explicitly. Pass `dest` to choose an exact output path
    * instead. If the target already exists it is reused (no rebuild) unless `force = true`. Throws
    * [[ApptainerCommandException]] if the build fails.
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
    * `mksquashfsArgs` is passed verbatim to the SIF-packing `mksquashfs` (e.g. `Some("-processors 1")`).
    */
  def build(
      source: String,
      name: String = "",
      dest: Option[String] = None,
      sandbox: Boolean = false,
      force: Boolean = false,
      mksquashfsArgs: Option[String] = None,
      enableNonRootBuild: Boolean = false
  ): ApptainerImage = {
    val output = resolveOutput(source, name, dest)
    val img = image(output)
    if (!force && img.exists) img
    else {
      if (enableNonRootBuild) Apptainer.warnNonRootBuildOnce()
      run(
        BuildCommand(
          output,
          source,
          sandbox = sandbox,
          force = force,
          // The non-root build goes through the root-mapped (non-subuid) path, avoiding the newuidmap requirement.
          ignoreSubuid = enableNonRootBuild,
          mksquashfsArgs = mksquashfsArgs
        )
      ).throwIfFailed()
      img
    }
  }

  /** Resolve the SIF output path for [[build]] / [[pull]]: an explicit `dest`, or `<imagesDir>/<name>.sif` (creating
    * the cache dir first), deriving `name` from `ref` when not given.
    */
  private def resolveOutput(ref: String, name: String, dest: Option[String]): String =
    dest.getOrElse {
      val n = if (name.nonEmpty) name else Apptainer.deriveName(ref)
      backend.runShell(s"mkdir -p ${ShellQuote.single(imagesDir)}").throwIfFailed()
      s"$imagesDir/$n.sif"
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
