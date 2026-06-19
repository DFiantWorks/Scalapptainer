package scalapptainer.commands

/** A bind mount, rendered as Apptainer's `src[:dest[:ro]]` `--bind` spec. */
final case class BindMount(
    source: String,
    dest: Option[String] = None,
    readOnly: Boolean = false
) {
  def spec: String = {
    val base = dest.fold(source)(d => s"$source:$d")
    if (readOnly) s"$base:ro" else base
  }
}

object BindMount {
  def apply(source: String, dest: String): BindMount = BindMount(source, Some(dest))
  def apply(source: String, dest: String, readOnly: Boolean): BindMount =
    BindMount(source, Some(dest), readOnly)
}

/** Options shared by the `run`, `exec` and `shell` (and `instance start`) subcommands. Build with the fluent helpers,
  * e.g. {{{ExecOptions().fakeroot().bind("/data", "/data").env("DEBUG" -> "1")}}}
  */
final case class ExecOptions(
    binds: Seq[BindMount] = Nil,
    env: Map[String, String] = Map.empty,
    fakeroot: Boolean = false,
    writable: Boolean = false,
    writableTmpfs: Boolean = false,
    contain: Boolean = false,
    containAll: Boolean = false,
    cleanEnv: Boolean = false,
    nv: Boolean = false,
    rocm: Boolean = false,
    noHome: Boolean = false,
    home: Option[String] = None,
    workdir: Option[String] = None,
    pwd: Option[String] = None,
    extraArgs: Seq[String] = Nil
) {

  /** Render these options to Apptainer argv fragments (deterministic order). */
  def toArgs: Seq[String] = {
    val b = Seq.newBuilder[String]
    binds.foreach { m => b += "--bind"; b += m.spec }
    env.toSeq.sortBy(_._1).foreach { case (k, v) => b += "--env"; b += s"$k=$v" }
    if (fakeroot) b += "--fakeroot"
    if (writable) b += "--writable"
    if (writableTmpfs) b += "--writable-tmpfs"
    if (contain) b += "--contain"
    if (containAll) b += "--containall"
    if (cleanEnv) b += "--cleanenv"
    if (nv) b += "--nv"
    if (rocm) b += "--rocm"
    if (noHome) b += "--no-home"
    home.foreach { h => b += "--home"; b += h }
    workdir.foreach { w => b += "--workdir"; b += w }
    pwd.foreach { p => b += "--pwd"; b += p }
    b ++= extraArgs
    b.result()
  }

  def bind(mounts: BindMount*): ExecOptions = copy(binds = binds ++ mounts)
  def bind(source: String, dest: String): ExecOptions = copy(binds = binds :+ BindMount(source, dest))
  def env(vars: (String, String)*): ExecOptions = copy(env = env ++ vars)
  def fakeroot(v: Boolean = true): ExecOptions = copy(fakeroot = v)
  def writable(v: Boolean = true): ExecOptions = copy(writable = v)
  def writableTmpfs(v: Boolean = true): ExecOptions = copy(writableTmpfs = v)
  def contain(v: Boolean = true): ExecOptions = copy(contain = v)
  def containAll(v: Boolean = true): ExecOptions = copy(containAll = v)
  def cleanEnv(v: Boolean = true): ExecOptions = copy(cleanEnv = v)
  def nv(v: Boolean = true): ExecOptions = copy(nv = v)
  def rocm(v: Boolean = true): ExecOptions = copy(rocm = v)
  def noHome(v: Boolean = true): ExecOptions = copy(noHome = v)
  def home(path: String): ExecOptions = copy(home = Some(path))
  def workdir(path: String): ExecOptions = copy(workdir = Some(path))
  def pwd(path: String): ExecOptions = copy(pwd = Some(path))

  /** Append raw passthrough arguments for flags not modelled here. */
  def arg(args: String*): ExecOptions = copy(extraArgs = extraArgs ++ args)
}

object ExecOptions {
  val empty: ExecOptions = ExecOptions()
}
