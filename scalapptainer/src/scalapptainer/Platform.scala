package scalapptainer

/** The host operating system Scalapptainer is running on. */
enum Os {
  case Linux, Windows, MacOS, Other
}

/** A CPU architecture, used both for the host and for the backend environment. */
enum Arch {
  case X86_64, Aarch64, Other

  /** The token Scalapptainer uses in vendored-resource directory names. */
  def resourceName: String = this match {
    case X86_64  => "x86_64"
    case Aarch64 => "aarch64"
    case Other   => "unknown"
  }
}

object Arch {

  /** Map the output of `uname -m` (or `os.arch`) to an [[Arch]]. */
  def parse(raw: String): Arch = raw.trim.toLowerCase match {
    case "x86_64" | "amd64" | "x64"               => Arch.X86_64
    case "aarch64" | "arm64" | "armv8" | "arm64e" => Arch.Aarch64
    case _                                        => Arch.Other
  }
}

/** Detection of the host platform.
  *
  * Note: this describes the *host* JVM. The architecture of the Linux backend (WSL2 / Lima VM) is resolved separately
  * at runtime via `uname -m`, since it may differ from the host (e.g. an x86_64 emulated guest on Apple Silicon).
  */
object Platform {

  /** Raw `os.name` system property. */
  def osName: String = sys.props.getOrElse("os.name", "")

  /** Raw `os.arch` system property. */
  def osArch: String = sys.props.getOrElse("os.arch", "")

  lazy val os: Os = {
    val n = osName.toLowerCase
    if (n.contains("win")) Os.Windows
    else if (n.contains("mac") || n.contains("darwin")) Os.MacOS
    else if (n.contains("nux") || n.contains("nix") || n.contains("aix")) Os.Linux
    else Os.Other
  }

  /** The host architecture. */
  lazy val arch: Arch = Arch.parse(osArch)
}
