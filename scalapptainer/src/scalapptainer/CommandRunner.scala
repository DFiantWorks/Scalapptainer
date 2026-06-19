package scalapptainer

/** A fully-specified host process invocation.
  *
  * @param argv
  *   the host argv (already wrapped for the backend, if any)
  * @param stdin
  *   optional text to feed to the process' standard input
  * @param cwd
  *   optional working directory
  * @param env
  *   extra environment variables (added to the inherited environment)
  */
final case class ProcSpec(
    argv: Seq[String],
    stdin: Option[String] = None,
    cwd: Option[os.Path] = None,
    env: Map[String, String] = Map.empty
)

/** Abstraction over actually executing a host command.
  *
  * The real implementation ([[OsLibRunner]]) shells out via os-lib. Tests inject a recording/stub runner so that all
  * argv-building and backend logic can be exercised without a real Apptainer, WSL2 or Lima present.
  */
trait CommandRunner {
  def run(spec: ProcSpec): ProcResult

  /** Convenience overload for a bare argv with no stdin/cwd/env. */
  final def run(argv: Seq[String]): ProcResult = run(ProcSpec(argv))

  /** Run with the parent process' stdio inherited (for interactive sessions such as `apptainer shell`). Returns the
    * exit code. The default implementation falls back to capturing [[run]]; real runners override it.
    */
  def runInteractive(spec: ProcSpec): Int = run(spec).exitCode
}

object CommandRunner {

  /** The default runner, executing commands on the host via os-lib. */
  def default: CommandRunner = OsLibRunner
}

/** Executes commands on the host using os-lib. */
object OsLibRunner extends CommandRunner {
  def run(spec: ProcSpec): ProcResult = {
    require(spec.argv.nonEmpty, "Cannot run an empty argv")
    val invoked = os
      .proc(spec.argv)
      .call(
        cwd = spec.cwd.orNull,
        env = if (spec.env.isEmpty) null else spec.env,
        stdin = spec.stdin.getOrElse(""),
        stdout = os.Pipe,
        stderr = os.Pipe,
        check = false,
        propagateEnv = true
      )
    ProcResult(
      exitCode = invoked.exitCode,
      stdout = invoked.out.text(),
      stderr = invoked.err.text(),
      command = spec.argv
    )
  }

  override def runInteractive(spec: ProcSpec): Int = {
    require(spec.argv.nonEmpty, "Cannot run an empty argv")
    os.proc(spec.argv)
      .call(
        cwd = spec.cwd.orNull,
        env = if (spec.env.isEmpty) null else spec.env,
        stdin = os.Inherit,
        stdout = os.Inherit,
        stderr = os.Inherit,
        check = false,
        propagateEnv = true
      )
      .exitCode
  }
}
