package scalapptainer

/** Base type for all exceptions thrown by Scalapptainer. */
sealed class ScalapptainerException(message: String, cause: Throwable = null) extends RuntimeException(message, cause)

/** Thrown when the required platform backend (WSL2 on Windows, Lima on macOS) is not available. The message carries
  * actionable, OS-specific instructions for the user to install/enable the prerequisite.
  */
final class BackendUnavailableException(message: String) extends ScalapptainerException(message)

/** Thrown when an Apptainer command (or a setup command) exits non-zero. */
final class ApptainerCommandException(val result: ProcResult)
    extends ScalapptainerException(
      s"""Command failed with exit code ${result.exitCode}:
         |  ${result.command.mkString(" ")}
         |--- stderr ---
         |${result.err}""".stripMargin
    )

/** Thrown when Scalapptainer cannot provision Apptainer or its installer dependencies inside the backend.
  */
final class InstallationException(message: String, cause: Throwable = null)
    extends ScalapptainerException(message, cause)

/** Thrown when the backend cannot provide the unprivileged user namespace Apptainer's rootless engine needs to run a
  * container. This surfaces in two ways, both funnelled here with backend-specific remedies:
  *
  *   - **proactively**, at install time, when [[Backend.unprivilegedUsernsBlocked]] detects the failure up front
  *     (`UserNamespaceException.atInstall`); and
  *   - **reactively**, when an Apptainer command we run fails with the tell-tale signature — typically
  *     `Could not write info to setgroups: Permission denied` followed by
  *     `Error while waiting event for user namespace mappings: no event received` (`UserNamespaceException.atRuntime`),
  *     which catches it even for a system/managed Apptainer that skipped the install-time probe.
  *
  * The kernel may permit creating a namespace while the sandbox (seccomp/AppArmor) or an already-nested namespace still
  * blocks writing the uid/gid mapping — common on locked-down CI runners, online playgrounds (e.g. Scastie), a plain
  * (non-`apptainer`-template) Lima VM, or recent distros that restrict unprivileged user namespaces.
  */
final class UserNamespaceException(message: String) extends ScalapptainerException(message)

object UserNamespaceException {

  /** True if `text` (a command's combined stderr/stdout) carries the signature of a failed unprivileged user-namespace
    * setup — Apptainer's rootless engine, or our own `unshare` probe. Used to turn an opaque non-zero exit into an
    * actionable [[UserNamespaceException]].
    */
  def looksLikeUsernsFailure(text: String): Boolean = {
    val t = text.toLowerCase
    t.contains("setgroups") ||
    t.contains("user namespace mappings") ||
    t.contains("no event received") ||
    t.contains("failed to create user namespace") ||
    (t.contains("unshare") && (t.contains("operation not permitted") || t.contains("permission denied")))
  }

  /** Built when the install-time probe ([[Backend.unprivilegedUsernsBlocked]]) shows the backend cannot map a user
    * namespace, before any container is run.
    */
  def atInstall(backend: Backend): UserNamespaceException =
    new UserNamespaceException(
      s"""The ${backend.name} backend forbids the unprivileged user namespace Apptainer's rootless engine needs to run
         |containers: creating one (or writing its uid/gid mapping) is denied. Scalapptainer detected this up front,
         |before fetching and installing Apptainer into a backend where it could never run.
         |${remedy(backend)}""".stripMargin
    )

  /** Built when a container command actually failed with the user-namespace signature (see [[looksLikeUsernsFailure]]),
    * e.g. on a system/managed Apptainer that was never subjected to the install-time probe.
    */
  def atRuntime(backend: Backend, observed: String): UserNamespaceException =
    new UserNamespaceException(
      s"""Apptainer could not set up the unprivileged user namespace its rootless engine needs to run this container,
         |in the ${backend.name} backend:
         |${indent(observed.trim)}
         |
         |This is not a problem with the image or your code — the backend is blocking Apptainer from writing the user
         |namespace's uid/gid mapping (the `setgroups`/mapping step).
         |${remedy(backend)}""".stripMargin
    )

  private def indent(s: String): String =
    s.linesIterator.map("    " + _).mkString("\n")

  /** Backend-specific advice for restoring unprivileged user namespaces. */
  private def remedy(backend: Backend): String = backend.os match {
    case Os.MacOS =>
      """
        |On macOS the Lima VM must be configured for Apptainer's unprivileged user namespaces. Scalapptainer
        |auto-provisions one from Lima's `apptainer` template (which sets this up correctly), so this error means
        |you pointed SCALAPPTAINER_LIMA_INSTANCE at a custom VM that lacks the configuration. Either unset it to
        |use the auto-provisioned VM, or recreate yours from the template:
        |    limactl stop   <instance> && limactl delete <instance>
        |    limactl start --name=<instance> template:apptainer
        |""".stripMargin
    case Os.Windows =>
      """
        |On Windows this is unusual: WSL2 normally permits unprivileged user namespaces. Make sure the distro is WSL2,
        |not WSL1 (WSL1 cannot create them) — check with:
        |    wsl -l -v        (the VERSION column must read 2; convert with `wsl --set-version <distro> 2`)
        |""".stripMargin
    case _ =>
      """
        |On Linux this usually means the host — or the container/VM Scalapptainer runs inside — restricts unprivileged
        |user namespaces. Either let Apptainer skip them, or re-enable them:
        |  - Easiest, no security trade-off: install the setuid-root build of Apptainer, which does not use user
        |    namespaces at all. Scalapptainer then picks it up from PATH automatically. On Debian/Ubuntu it lives in
        |    the Apptainer PPA, so add that first:
        |        sudo add-apt-repository -y ppa:apptainer/ppa
        |        sudo apt-get update && sudo apt-get install -y apptainer-suid
        |    (RPM distros: install the `apptainer-suid` package from EPEL or the Apptainer repo.)
        |  - Or re-enable unprivileged user namespaces. Ubuntu 23.10+/24.04 restrict them via AppArmor:
        |        sudo sysctl -w kernel.apparmor_restrict_unprivileged_userns=0
        |    Older Debian/RHEL kernels may instead need:
        |        sudo sysctl -w kernel.unprivileged_userns_clone=1
        |        sudo sysctl -w user.max_user_namespaces=15000
        |  - If you are running inside another container (Docker/Podman/CI), launch it so it can nest user namespaces
        |    (e.g. do not drop CAP_SETUID/CAP_SETGID, avoid a `setgroups`-restricting seccomp profile), or run on a
        |    real host/VM instead.
        |
        |Heavily sandboxed environments (many CI runners, online playgrounds such as Scastie) block user namespaces
        |with no unprivileged workaround — there the setuid-root `apptainer-suid` build (which needs root to install)
        |is the only option.
        |""".stripMargin
  }
}
