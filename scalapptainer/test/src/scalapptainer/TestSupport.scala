package scalapptainer

import scala.collection.mutable

/** A [[CommandRunner]] that records every invocation and answers via a pluggable handler, so backend / installer /
  * vendored-tools logic can be exercised with no real WSL2, Lima or Apptainer present.
  */
class RecordingRunner(handler: ProcSpec => ProcResult) extends CommandRunner {
  val calls: mutable.ArrayBuffer[ProcSpec] = mutable.ArrayBuffer.empty

  def run(spec: ProcSpec): ProcResult = {
    calls += spec
    handler(spec).copy(command = spec.argv)
  }

  override def runInteractive(spec: ProcSpec): Int = {
    calls += spec
    handler(spec).exitCode
  }

  /** The `bash -lc` script of a recorded call, if it was a shell invocation. */
  def scriptOf(spec: ProcSpec): Option[String] = {
    val i = spec.argv.indexOf("-lc")
    if (i >= 0 && i + 1 < spec.argv.length) Some(spec.argv(i + 1)) else None
  }

  /** All recorded shell scripts, in order. */
  def scripts: Seq[String] = calls.toSeq.flatMap(scriptOf)
}

object RecordingRunner {

  /** A handler that simulates a Linux backend environment.
    *
    * @param present
    *   commands resolvable on the backend PATH (`command -v`)
    * @param home
    *   value of `$HOME`
    * @param uname
    *   output of `uname -m`
    * @param hasApptainer
    *   whether a Scalapptainer-managed apptainer already exists
    */
  def linuxEnv(
      present: Set[String] = Set("bash", "curl", "rpm2cpio", "cpio", "xz", "gzip", "bzip2", "base64"),
      home: String = "/home/me",
      uname: String = "x86_64",
      hasApptainer: Boolean = false,
      imageExists: Boolean = false,
      display: String = ""
  ): ProcSpec => ProcResult = { spec =>
    def ok(out: String = ""): ProcResult = ProcResult(0, out, "", spec.argv)
    def fail(): ProcResult = ProcResult(1, "", "", spec.argv)

    val i = spec.argv.indexOf("-lc")
    if (i < 0 || i + 1 >= spec.argv.length) ok() // direct exec (apptainer run ...)
    else {
      val script = spec.argv(i + 1).trim
      if (script == "true") ok()
      else if (script.contains("""printf %s "$HOME"""")) ok(home)
      else if (script.contains("""printf %s "$DISPLAY"""")) ok(display)
      else if (script == "uname -m") ok(uname)
      else if (script.startsWith("command -v ")) {
        val tool = script
          .stripPrefix("command -v ")
          .trim
          .takeWhile(c => c != ' ' && c != '>')
          .trim
          .stripPrefix("'")
          .stripSuffix("'")
        if (present.contains(tool)) ok(s"/usr/bin/$tool") else fail()
      } else if (script.startsWith("test -x")) {
        if (script.contains("/bin/apptainer") && hasApptainer) ok() else fail()
      } else if (script.startsWith("test -f")) {
        if (imageExists) ok() else fail() // image cache existence probe (ApptainerImage.exists)
      } else if (script.startsWith("test -e")) {
        fail() // vendored-tool symlink targets do not exist yet, so they get created
      } else ok() // mkdir, base64 writes, ln -s, the installer pipeline, etc.
    }
  }
}
