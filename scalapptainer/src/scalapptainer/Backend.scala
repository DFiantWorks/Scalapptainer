package scalapptainer

/** User-overridable backend settings.
  *
  * Defaults are read from the environment so consumers can point Scalapptainer
  * at a specific WSL2 distro or Lima instance without code changes:
  *   - `SCALAPPTAINER_WSL_DISTRO`  (default: the WSL default distro)
  *   - `SCALAPPTAINER_LIMA_INSTANCE` (default: `default`)
  */
final case class BackendConfig(
    wslDistro: Option[String] = sys.env.get("SCALAPPTAINER_WSL_DISTRO").filter(_.nonEmpty),
    limaInstance: String = sys.env.getOrElse("SCALAPPTAINER_LIMA_INSTANCE", "default")
)

object BackendConfig {
  val default: BackendConfig = BackendConfig()
}

/** A place where Apptainer can run. On Linux this is the host itself; on Windows
  * it is a WSL2 distro; on macOS it is a Lima VM.
  *
  * A backend knows how to (a) wrap an Apptainer argv into something the host can
  * execute, (b) run an arbitrary shell snippet *inside* the backend (used for
  * provisioning), and (c) translate host paths into backend paths.
  */
abstract class Backend(val runner: CommandRunner) {

  /** The host OS this backend is for. */
  def os: Os

  /** Human-readable backend name, e.g. "WSL2" or "Lima". */
  def name: String

  /** Host-argv prefix that turns a trailing `program arg...` into an execution
    * *inside* the backend without a shell. Empty for the native Linux backend.
    */
  protected def commandPrefix: Seq[String]

  /** Build the host argv to run `apptainerPath` (an absolute path inside the
    * backend) with the given Apptainer arguments.
    */
  final def wrapApptainer(apptainerPath: String, args: Seq[String]): Seq[String] =
    commandPrefix ++ (apptainerPath +: args)

  /** Build the host argv to directly exec `program` with `args` inside the backend. */
  final def wrapExec(program: String, args: Seq[String]): Seq[String] =
    commandPrefix ++ (program +: args)

  /** Run a `bash` snippet inside the backend, capturing its output. */
  final def runShell(script: String, stdin: Option[String] = None): ProcResult =
    runner.run(ProcSpec(commandPrefix ++ Seq("bash", "-lc", script), stdin = stdin))

  /** Whether `tool` is resolvable on the backend's PATH. */
  final def hasCommand(tool: String): Boolean =
    runShell(s"command -v ${ShellQuote.single(tool)} >/dev/null 2>&1").succeeded

  /** The backend user's home directory (resolved once). */
  lazy val home: String = {
    val r = runShell("printf %s \"$HOME\"").throwIfFailed()
    r.out
  }

  /** The backend CPU architecture (resolved once via `uname -m`). */
  lazy val arch: Arch = Arch.parse(runShell("uname -m").throwIfFailed().out)

  /** Scalapptainer's per-user cache directory inside the backend. */
  def cacheDir: String = s"$home/.scalapptainer"

  /** Translate a host path into the path visible inside the backend. */
  def translatePath(hostPath: String): String

  /** Verify the backend is usable; throw [[BackendUnavailableException]] with
    * actionable instructions otherwise.
    */
  def checkAvailable(): Unit

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
    case Os.Other =>
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

  def checkAvailable(): Unit =
    tryHost(Seq("bash", "-lc", "true")) match {
      case Some(r) if r.succeeded => ()
      case _ =>
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

  /** Map a Windows path (e.g. `C:\Users\me\img.sif`) to its WSL path
    * (`/mnt/c/Users/me/img.sif`). Paths that already look POSIX are passed through.
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

  def checkAvailable(): Unit = {
    val ok = tryHost(commandPrefix :+ "true").exists(_.succeeded)
    if (!ok)
      throw new BackendUnavailableException(
        s"""WSL2 is required to run Apptainer on Windows, but it does not appear to be available${config.wslDistro.fold("")(d => s" (distro '$d')")}.
           |
           |Install it from an elevated PowerShell, then restart Windows:
           |    wsl --install
           |
           |Verify with:
           |    wsl --status
           |
           |Docs: https://learn.microsoft.com/windows/wsl/install""".stripMargin
      )
  }
}

/** macOS: Apptainer runs inside a Lima VM, invoked through `limactl shell`. */
final class LimaBackend(runner: CommandRunner, config: BackendConfig) extends Backend(runner) {
  def os: Os = Os.MacOS
  def name: String = "Lima"

  private def instance: String = config.limaInstance

  protected def commandPrefix: Seq[String] = Seq("limactl", "shell", instance)

  /** Lima mounts the host home directory at the same path inside the VM, so
    * macOS POSIX paths are already valid guest paths.
    */
  def translatePath(hostPath: String): String = hostPath

  def checkAvailable(): Unit = {
    val limaPresent = tryHost(Seq("limactl", "--version")).exists(_.succeeded)
    if (!limaPresent)
      throw new BackendUnavailableException(
        """Lima is required to run Apptainer on macOS, but `limactl` was not found.
          |
          |Install and start it with:
          |    brew install lima
          |    limactl start
          |
          |Docs: https://lima-vm.io""".stripMargin
      )

    val running = tryHost(Seq("limactl", "list", instance, "--format", "{{.Status}}"))
      .filter(_.succeeded)
      .map(_.out.toLowerCase)
      .exists(_.contains("running"))
    if (!running)
      throw new BackendUnavailableException(
        s"""The Lima instance '$instance' is not running.
           |
           |Start it with:
           |    limactl start $instance
           |
           |(Set SCALAPPTAINER_LIMA_INSTANCE to use a different instance.)""".stripMargin
      )
  }
}
