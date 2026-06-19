package scalapptainer

/** Minimal POSIX-shell quoting, used when Scalapptainer assembles `bash -lc` snippets for provisioning inside a
  * backend. (Normal Apptainer invocations are passed as a raw argv via `wsl.exe -e` / `limactl shell` and need no
  * quoting.)
  */
object ShellQuote {

  /** Single-quote `s` for safe inclusion in a POSIX shell command. Embedded single quotes are handled with the classic
    * `'\''` idiom.
    */
  def single(s: String): String =
    "'" + s.replace("'", "'\\''") + "'"
}
