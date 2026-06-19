package scalapptainer

/** The result of running a command on the host (which may itself be a wrapped invocation into a WSL2/Lima backend).
  *
  * @param exitCode
  *   process exit code
  * @param stdout
  *   captured standard output
  * @param stderr
  *   captured standard error
  * @param command
  *   the host argv that was executed (useful for diagnostics)
  */
final case class ProcResult(
    exitCode: Int,
    stdout: String,
    stderr: String,
    command: Seq[String]
) {

  /** True when the process exited with status 0. */
  def succeeded: Boolean = exitCode == 0

  /** True when the process exited with a non-zero status. */
  def failed: Boolean = !succeeded

  /** Standard output with surrounding whitespace trimmed. */
  def out: String = stdout.trim

  /** Standard error with surrounding whitespace trimmed. */
  def err: String = stderr.trim

  /** Return this result if it succeeded, otherwise throw. */
  def throwIfFailed(): ProcResult =
    if (succeeded) this
    else throw new ApptainerCommandException(this)
}
