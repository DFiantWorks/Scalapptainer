package scalapptainer

import scalapptainer.commands.ExecOptions

/** User-overridable backend settings.
  *
  * Defaults are read from the environment so consumers can point Scalapptainer at a specific WSL2 distro or Lima
  * instance without code changes:
  *   - `SCALAPPTAINER_WSL_DISTRO` (default: the WSL default distro)
  *   - `SCALAPPTAINER_LIMA_INSTANCE` (default: `default`)
  */
final case class BackendConfig(
    wslDistro: Option[String] = sys.env.get("SCALAPPTAINER_WSL_DISTRO").filter(_.nonEmpty),
    limaInstance: String = sys.env.getOrElse("SCALAPPTAINER_LIMA_INSTANCE", "default")
)

object BackendConfig {
  val default: BackendConfig = BackendConfig()
}

/** A place where Apptainer can run. On Linux this is the host itself; on Windows it is a WSL2 distro; on macOS it is a
  * Lima VM.
  *
  * A backend knows how to (a) wrap an Apptainer argv into something the host can execute, (b) run an arbitrary shell
  * snippet *inside* the backend (used for provisioning), and (c) translate host paths into backend paths.
  */
abstract class Backend(val runner: CommandRunner) {

  /** The host OS this backend is for. */
  def os: Os

  /** Human-readable backend name, e.g. "WSL2" or "Lima". */
  def name: String

  /** Host-argv prefix that turns a trailing `program arg...` into an execution *inside* the backend without a shell.
    * Empty for the native Linux backend.
    */
  protected def commandPrefix: Seq[String]

  /** Build the host argv to run `apptainerPath` (an absolute path inside the backend) with the given Apptainer
    * arguments.
    */
  final def wrapApptainer(apptainerPath: String, args: Seq[String]): Seq[String] =
    commandPrefix ++ (apptainerPath +: args)

  /** Build the host argv to directly exec `program` with `args` inside the backend. */
  final def wrapExec(program: String, args: Seq[String]): Seq[String] =
    commandPrefix ++ (program +: args)

  /** Run a `bash` snippet inside the backend, capturing its output.
    *
    * Verifies the backend prerequisite first ([[checkAvailable]]), so provisioning shell-outs (`home`, `cacheDir`,
    * `mkdir`, ...) surface the actionable [[BackendUnavailableException]] rather than a raw "command not found" from a
    * missing `limactl`/`wsl.exe` or a cryptic error from a stopped VM. The check is memoized, so it costs nothing after
    * the first success, and the per-backend probe uses direct host argv (not `runShell`), so there is no recursion.
    */
  final def runShell(script: String, stdin: Option[String] = None): ProcResult = {
    checkAvailable()
    runner.run(ProcSpec(commandPrefix ++ Seq("bash", "-lc", script), stdin = stdin))
  }

  private val commandCache = scala.collection.concurrent.TrieMap.empty[String, Boolean]

  /** Whether `tool` is resolvable on the backend's PATH. Memoized per tool — the probe shell-out runs at most once for
    * each command name.
    */
  final def hasCommand(tool: String): Boolean =
    commandCache.getOrElseUpdate(
      tool,
      runShell(s"command -v ${ShellQuote.single(tool)} >/dev/null 2>&1").succeeded
    )

  /** The backend user's home directory (resolved once). */
  lazy val home: String = {
    val r = runShell("printf %s \"$HOME\"").throwIfFailed()
    r.out
  }

  /** The backend CPU architecture (resolved once via `uname -m`). */
  lazy val arch: Arch = Arch.parse(runShell("uname -m").throwIfFailed().out)

  /** Whether this backend forbids the unprivileged user namespace Apptainer's rootless engine needs (resolved once).
    *
    * We probe with `unshare -rU true`, which both *creates* a user namespace and *writes the uid/gid mapping* (and the
    * `deny` to `/proc/self/setgroups` that precedes the gid map). That second step is the one that fails on locked-down
    * hosts — a bare `unshare -U` (create only) succeeds there, so the engine would still die later with
    * `Could not write info to setgroups: Permission denied`. Probing the full map dance reproduces exactly what
    * Apptainer does, so the install-time guard catches that case up front instead of letting a doomed container run.
    *
    * An explicit permission denial counts as blocked; a missing `unshare` (we can't tell) does not — so we never
    * false-positive on a backend that simply lacks the probe binary.
    */
  lazy val unprivilegedUsernsBlocked: Boolean = {
    val r = runShell("unshare -rU true")
    if (r.succeeded) false
    else {
      val msg = s"${r.out}\n${r.err}".toLowerCase
      msg.contains("operation not permitted") || msg.contains("permission denied")
    }
  }

  /** Scalapptainer's per-user cache directory inside the backend. */
  def cacheDir: String = s"$home/.scalapptainer"

  /** Bind mounts + environment that forward the host X11 display into a container on this backend, so a GUI app inside
    * the container renders on the host. Empty (no binds, no `DISPLAY`) when no display is available.
    *
    * The default suits an X server reachable through the `/tmp/.X11-unix` unix socket — native Linux, and WSL2's WSLg —
    * binding that socket and passing the backend's `$DISPLAY`. Backends without a shared socket (e.g. macOS/Lima
    * reaching XQuartz over TCP) override this.
    */
  def x11Forwarding: ExecOptions = {
    val display = runShell("printf %s \"$DISPLAY\"").out
    if (display.isEmpty) ExecOptions.empty
    else {
      val withDisplay = ExecOptions.empty.env("DISPLAY" -> display)
      if (runShell("test -d /tmp/.X11-unix").succeeded) withDisplay.bind("/tmp/.X11-unix", "/tmp/.X11-unix")
      else withDisplay
    }
  }

  /** Translate a host path into the path visible inside the backend. */
  def translatePath(hostPath: String): String

  /** Verify the backend is usable; throw [[BackendUnavailableException]] with actionable instructions otherwise.
    * Memoized: the probe runs once and, once it has succeeded, subsequent calls are no-ops (a failed probe is not
    * cached, so a later call re-probes — useful if a prerequisite is started mid-session).
    */
  final def checkAvailable(): Unit = availabilityChecked

  // A lazy val whose initialiser throws is not cached by Scala, so failures re-probe
  // while a success is remembered — exactly the semantics we want here.
  private lazy val availabilityChecked: Unit = probeAvailable()

  /** Per-backend availability probe; throws [[BackendUnavailableException]] when the backend prerequisite (WSL2 / Lima)
    * is missing. Invoked at most once on success.
    */
  protected def probeAvailable(): Unit

  /** Run a host probe argv, returning None if the executable is missing or errors. */
  protected final def tryHost(argv: Seq[String]): Option[ProcResult] =
    try Some(runner.run(ProcSpec(argv)))
    catch { case _: Throwable => None }
}

object Backend {

  /** Auto-detect the appropriate backend for the current host. */
  def detect(
      runner: CommandRunner = CommandRunner.default,
      config: BackendConfig = BackendConfig.default
  ): Backend = Platform.os match {
    case Os.Linux   => new LinuxBackend(runner)
    case Os.Windows => new Wsl2Backend(runner, config)
    case Os.MacOS   => new LimaBackend(runner, config)
    case Os.Other   =>
      throw new BackendUnavailableException(
        s"Unsupported host OS '${Platform.osName}'. Scalapptainer supports Linux, Windows (WSL2) and macOS (Lima)."
      )
  }
}

/** Native Linux: Apptainer runs directly on the host. */
final class LinuxBackend(runner: CommandRunner) extends Backend(runner) {
  def os: Os = Os.Linux
  def name: String = "native-linux"
  protected def commandPrefix: Seq[String] = Nil

  def translatePath(hostPath: String): String = hostPath

  protected def probeAvailable(): Unit =
    tryHost(Seq("bash", "-lc", "true")) match {
      case Some(r) if r.succeeded => ()
      case _                      =>
        throw new BackendUnavailableException(
          "A POSIX shell ('bash') is required on the Linux host but could not be executed."
        )
    }
}

/** Windows: Apptainer runs inside a WSL2 distro, invoked through `wsl.exe`. */
final class Wsl2Backend(runner: CommandRunner, config: BackendConfig) extends Backend(runner) {
  def os: Os = Os.Windows
  def name: String = "WSL2"

  private def distroOpt: Seq[String] = config.wslDistro.toSeq.flatMap(d => Seq("-d", d))

  protected def commandPrefix: Seq[String] = Seq("wsl.exe") ++ distroOpt ++ Seq("-e")

  /** ` (distro 'X')` when a specific distro was requested, else empty — for sharpening error messages. */
  private def distroNote: String = config.wslDistro.fold("")(d => s" (distro '$d')")

  /** Map a Windows path (e.g. `C:\Users\me\img.sif`) to its WSL path (`/mnt/c/Users/me/img.sif`). Paths that already
    * look POSIX are passed through.
    */
  def translatePath(hostPath: String): String = {
    val DriveLetter = """^([A-Za-z]):[\\/](.*)$""".r
    hostPath match {
      case DriveLetter(drive, rest) =>
        s"/mnt/${drive.toLowerCase}/${rest.replace('\\', '/')}"
      case other =>
        other.replace('\\', '/')
    }
  }

  protected def probeAvailable(): Unit =
    // tryHost returns None only when `wsl.exe` itself could not be executed (missing); a Some with a
    // non-zero exit means wsl.exe ran but the distro could not start. Distinguishing the two lets us
    // give the right fix instead of a single catch-all "WSL not available" message.
    tryHost(commandPrefix :+ "true") match {
      case None =>
        throw new BackendUnavailableException(
          s"""WSL2 is required to run Apptainer on Windows, but `wsl.exe` could not be run$distroNote.
             |
             |Install it from an elevated PowerShell, then restart Windows:
             |    wsl --install
             |
             |Verify with:
             |    wsl --status
             |
             |Docs: https://learn.microsoft.com/windows/wsl/install""".stripMargin
        )

      case Some(r) if r.failed =>
        throw new BackendUnavailableException(
          s"""WSL is installed but no usable Linux distribution could be started$distroNote.
             |
             |List what you have (the VERSION column must read 2):
             |    wsl --list --verbose
             |
             |Install a distribution if you have none (one-time):
             |    wsl --install -d Ubuntu
             |
             |If a distro exists but won't start, enable the "Virtual Machine Platform" Windows feature
             |and virtualization in your BIOS/UEFI, then restart Windows.
             |
             |Docs: https://learn.microsoft.com/windows/wsl/install""".stripMargin
        )

      case Some(_) =>
        // The distro is reachable — but reject WSL1, the Windows analogue of a misconfigured Lima VM:
        // it has no real Linux kernel, so Apptainer's rootless engine (which needs user namespaces) can
        // never run there. The WSL2 kernel's `osrelease` carries a `-WSL2` suffix; a WSL1 kernel reads
        // like `4.4.0-...-Microsoft`. We flag WSL1 only when the kernel is clearly a Microsoft one that
        // lacks the WSL2 marker, so a user's custom-built WSL2 kernel is never misclassified.
        val osrelease = tryHost(commandPrefix ++ Seq("cat", "/proc/sys/kernel/osrelease"))
          .filter(_.succeeded)
          .map(_.out.toLowerCase)
          .getOrElse("")
        if (osrelease.contains("microsoft") && !osrelease.contains("wsl2"))
          throw new BackendUnavailableException(
            s"""The WSL distribution$distroNote runs on WSL1, which has no real Linux kernel and cannot run
               |Apptainer (its rootless engine needs user namespaces, unavailable on WSL1).
               |
               |Find the distro name and convert it to WSL2, then restart WSL:
               |    wsl --list --verbose
               |    wsl --set-version <distro> 2
               |    wsl --shutdown
               |
               |(Or point Scalapptainer at a WSL2 distro via SCALAPPTAINER_WSL_DISTRO.)
               |
               |Docs: https://learn.microsoft.com/windows/wsl/basic-commands#set-wsl-version""".stripMargin
          )
    }
}

/** macOS: Apptainer runs inside a Lima VM, invoked through `limactl shell`. */
final class LimaBackend(runner: CommandRunner, config: BackendConfig) extends Backend(runner) {
  def os: Os = Os.MacOS
  def name: String = "Lima"

  private def instance: String = config.limaInstance

  protected def commandPrefix: Seq[String] = Seq("limactl", "shell", instance)

  /** Lima mounts the host home directory at the same path inside the VM, so macOS POSIX paths are already valid guest
    * paths.
    */
  def translatePath(hostPath: String): String = hostPath

  /** XQuartz runs on the macOS host and is reached over TCP from the Lima VM (there is no shared X11 unix socket), so
    * forward `DISPLAY` to the host gateway rather than binding a socket. Requires XQuartz with "Allow connections from
    * network clients" enabled and the VM allowed via `xhost` (e.g. `xhost + 127.0.0.1`).
    */
  override def x11Forwarding: ExecOptions =
    ExecOptions.empty.env("DISPLAY" -> "host.lima.internal:0")

  protected def probeAvailable(): Unit = {
    val limaPresent = tryHost(Seq("limactl", "--version")).exists(_.succeeded)
    if (!limaPresent)
      throw new BackendUnavailableException(
        """Lima is required to run Apptainer on macOS, but `limactl` was not found.
          |
          |Install it, then create and start an Apptainer-ready VM from Lima's bundled template:
          |    brew install lima
          |    limactl start template:apptainer
          |
          |Then point Scalapptainer at that instance:
          |    export SCALAPPTAINER_LIMA_INSTANCE=apptainer
          |
          |(The `apptainer` template preconfigures the VM for Apptainer's unprivileged user
          |namespaces; a plain `limactl start` default VM fails with a `setgroups: Permission
          |denied` error instead.)
          |
          |Docs: https://lima-vm.io""".stripMargin
      )

    // `limactl list <name> --format {{.Status}}` prints the status for an existing instance and
    // nothing (exit 0) for one that does not exist — so an empty result means "no such instance".
    val status = tryHost(Seq("limactl", "list", instance, "--format", "{{.Status}}"))
      .filter(_.succeeded)
      .map(_.out.trim.toLowerCase)
      .getOrElse("")

    if (status.isEmpty)
      throw new BackendUnavailableException(
        s"""The Lima instance '$instance' does not exist.
           |
           |Create and start an Apptainer-ready VM from Lima's bundled template:
           |    limactl start template:apptainer
           |
           |Then point Scalapptainer at it:
           |    export SCALAPPTAINER_LIMA_INSTANCE=apptainer
           |
           |(The `apptainer` template preconfigures the VM for Apptainer's unprivileged user
           |namespaces; a plain default instance fails with `setgroups: Permission denied`.
           |Set SCALAPPTAINER_LIMA_INSTANCE to target a different existing instance.)""".stripMargin
      )
    else if (!status.contains("running"))
      throw new BackendUnavailableException(
        s"""The Lima instance '$instance' exists but is not running (status: $status).
           |
           |Start it with:
           |    limactl start $instance
           |
           |(Set SCALAPPTAINER_LIMA_INSTANCE to use a different instance.)""".stripMargin
      )
  }
}
