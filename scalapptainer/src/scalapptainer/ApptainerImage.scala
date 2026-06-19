package scalapptainer

import scalapptainer.commands.*

/** A handle to an Apptainer image — a local SIF path or a container URI — bound to an [[Apptainer]] instance and
  * carrying a set of accumulated exec [[scalapptainer.commands.ExecOptions options]] (bind mounts, environment, ...).
  *
  * Obtained from [[Apptainer.build]], [[Apptainer.pull]] or [[Apptainer.image]]. The fluent `bind` / `env` /
  * `withOptions` methods return a *new* handle with the extra options; the terminal verbs (`exec`, `run`, `shell`,
  * `inspect`) execute against [[ref]] with whatever options have accumulated. It is immutable, so a base handle stays
  * reusable:
  *
  * {{{
  *   val tools = Apptainer.build("tools.def", name = "tools", enableNonRootBuild = true)
  *   tools.exec("mytool", "--version")
  *   tools.bind("/data", "/data").env("DEBUG" -> "1").run()
  * }}}
  */
final class ApptainerImage private[scalapptainer] (
    val apptainer: Apptainer,
    val ref: String,
    val options: ExecOptions
) {

  private def updated(o: ExecOptions): ApptainerImage = new ApptainerImage(apptainer, ref, o)

  // --- Fluent configuration (immutable) -------------------------------------

  /** Add bind mounts. */
  def bind(mounts: BindMount*): ApptainerImage = updated(options.bind(mounts*))

  /** Add a `source -> dest` bind mount. */
  def bind(source: String, dest: String): ApptainerImage = updated(options.bind(source, dest))

  /** Add environment variables. */
  def env(vars: (String, String)*): ApptainerImage = updated(options.env(vars*))

  /** Transform the underlying options — the escape hatch for the less-common flags (fakeroot, writable, pwd, nv, ...).
    */
  def withOptions(f: ExecOptions => ExecOptions): ApptainerImage = updated(f(options))

  /** Forward the host X11 display into the container so a GUI app renders on the host, adapting to the backend (the
    * `/tmp/.X11-unix` socket on native Linux and WSL2/WSLg, XQuartz over TCP on macOS/Lima — see
    * [[scalapptainer.Backend.x11Forwarding]]). Returns the image unchanged, with a one-time warning, when no display is
    * available.
    */
  def withX11(): ApptainerImage = {
    val x = apptainer.backend.x11Forwarding
    if (x.binds.isEmpty && x.env.isEmpty) {
      Apptainer.warnNoDisplayOnce()
      this
    } else updated(options.bind(x.binds*).env(x.env.toSeq*))
  }

  // --- Terminal verbs (apply the accumulated options) -----------------------

  /** `apptainer exec [options] <image> <command...>`, capturing output. */
  def exec(command: String*): ProcResult = apptainer.run(ExecCommand(ref, command.toSeq, options))

  /** `apptainer exec [options] <image> <command...>` with inherited stdio (interactive); returns the exit code. */
  def execInteractive(command: String*): Int = apptainer.runInteractive(ExecCommand(ref, command.toSeq, options))

  /** `apptainer run [options] <image> [app args...]`, capturing output. */
  def run(appArgs: String*): ProcResult = apptainer.run(RunCommand(ref, appArgs.toSeq, options))

  /** `apptainer run [options] <image> [app args...]` with inherited stdio (interactive); returns the exit code. */
  def runInteractive(appArgs: String*): Int = apptainer.runInteractive(RunCommand(ref, appArgs.toSeq, options))

  /** Open an interactive shell in the image (inherited stdio); returns the exit code. */
  def shell(): Int = apptainer.runInteractive(ShellCommand(ref, options))

  /** `apptainer inspect --labels <image>`. */
  def inspect(): ProcResult = apptainer.inspect(ref)

  // --- Misc -----------------------------------------------------------------

  /** The image reference (local SIF path or container URI). */
  def path: String = ref

  /** Whether the image file exists on the backend (a local SIF). */
  def exists: Boolean = apptainer.backend.runShell(s"test -f ${ShellQuote.single(ref)}").succeeded

  override def toString: String = s"ApptainerImage($ref)"
}
