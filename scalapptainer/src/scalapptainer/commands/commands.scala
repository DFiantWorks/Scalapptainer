package scalapptainer.commands

/** A typed Apptainer command. `args` is the full argv passed to `apptainer`
  * (subcommand, options and operands) — i.e. everything after the `apptainer`
  * binary itself. The thin core ([[scalapptainer.Apptainer.exec]]) executes it.
  */
sealed trait ApptainerCommand {
  def args: Seq[String]
}

/** `apptainer run [options] <image> [app args...]` */
final case class RunCommand(
    image: String,
    appArgs: Seq[String] = Nil,
    options: ExecOptions = ExecOptions.empty
) extends ApptainerCommand {
  def args: Seq[String] = ("run" +: options.toArgs) ++ (image +: appArgs)

  def withOptions(f: ExecOptions => ExecOptions): RunCommand = copy(options = f(options))
  def args(a: String*): RunCommand = copy(appArgs = appArgs ++ a)
}

/** `apptainer exec [options] <image> <command...>` */
final case class ExecCommand(
    image: String,
    command: Seq[String],
    options: ExecOptions = ExecOptions.empty
) extends ApptainerCommand {
  def args: Seq[String] = ("exec" +: options.toArgs) ++ (image +: command)

  def withOptions(f: ExecOptions => ExecOptions): ExecCommand = copy(options = f(options))
}

/** `apptainer shell [options] <image>` */
final case class ShellCommand(
    image: String,
    options: ExecOptions = ExecOptions.empty
) extends ApptainerCommand {
  def args: Seq[String] = ("shell" +: options.toArgs) :+ image

  def withOptions(f: ExecOptions => ExecOptions): ShellCommand = copy(options = f(options))
}

/** `apptainer pull [--force] [--dir D] [output] <uri>` */
final case class PullCommand(
    uri: String,
    dest: Option[String] = None,
    force: Boolean = false,
    dir: Option[String] = None
) extends ApptainerCommand {
  def args: Seq[String] = {
    val b = Seq.newBuilder[String]
    b += "pull"
    if (force) b += "--force"
    dir.foreach { d => b += "--dir"; b += d }
    dest.foreach(b += _)
    b += uri
    b.result()
  }
}

/** `apptainer build [--sandbox] [--force] [--fakeroot] <output> <source>`
  * where `source` is a definition file, a sandbox directory, or a container URI.
  */
final case class BuildCommand(
    output: String,
    source: String,
    sandbox: Boolean = false,
    force: Boolean = false,
    fakeroot: Boolean = false
) extends ApptainerCommand {
  def args: Seq[String] = {
    val b = Seq.newBuilder[String]
    b += "build"
    if (sandbox) b += "--sandbox"
    if (force) b += "--force"
    if (fakeroot) b += "--fakeroot"
    b += output
    b += source
    b.result()
  }
}

/** `apptainer inspect [selectors] [--json] <image>` */
final case class InspectCommand(
    image: String,
    labels: Boolean = false,
    deffile: Boolean = false,
    runscript: Boolean = false,
    environment: Boolean = false,
    json: Boolean = false
) extends ApptainerCommand {
  def args: Seq[String] = {
    val b = Seq.newBuilder[String]
    b += "inspect"
    if (labels) b += "--labels"
    if (deffile) b += "--deffile"
    if (runscript) b += "--runscript"
    if (environment) b += "--environment"
    if (json) b += "--json"
    b += image
    b.result()
  }
}

/** The `apptainer instance ...` family. */
sealed trait InstanceCommand extends ApptainerCommand

object InstanceCommand {

  /** `apptainer instance start [options] <image> <name> [start args...]` */
  final case class Start(
      image: String,
      name: String,
      options: ExecOptions = ExecOptions.empty,
      startArgs: Seq[String] = Nil
  ) extends InstanceCommand {
    def args: Seq[String] =
      (Seq("instance", "start") ++ options.toArgs) ++ (image +: name +: startArgs)

    def withOptions(f: ExecOptions => ExecOptions): Start = copy(options = f(options))
  }

  /** `apptainer instance stop [--all] [--force] [name]` */
  final case class Stop(
      name: String = "",
      all: Boolean = false,
      force: Boolean = false
  ) extends InstanceCommand {
    def args: Seq[String] = {
      val b = Seq.newBuilder[String]
      b += "instance"; b += "stop"
      if (force) b += "--force"
      if (all) b += "--all" else b += name
      b.result()
    }
  }

  /** `apptainer instance list [--json]` */
  final case class ListInstances(json: Boolean = false) extends InstanceCommand {
    def args: Seq[String] = {
      val b = Seq.newBuilder[String]
      b += "instance"; b += "list"
      if (json) b += "--json"
      b.result()
    }
  }
}
