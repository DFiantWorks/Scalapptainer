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

/** Thrown when Scalapptainer would install Apptainer in user mode but the backend forbids creating unprivileged user
  * namespaces — which Apptainer's rootless engine needs to run containers. The kernel may permit them while the
  * container sandbox (seccomp/AppArmor) still denies the `unshare(CLONE_NEWUSER)` syscall, as on locked-down CI runners
  * and online playgrounds (e.g. Scastie). Checked only at install time; a setuid-root or already-installed Apptainer is
  * not re-checked.
  */
final class UserNamespaceException(backendName: String)
    extends ScalapptainerException(
      s"""This $backendName environment forbids creating unprivileged user namespaces, which Apptainer's rootless
         |engine needs to run containers. The kernel may allow them while the container sandbox (seccomp/AppArmor)
         |still blocks the unshare(CLONE_NEWUSER) syscall — common in restricted CI runners and online code
         |playgrounds such as Scastie.
         |
         |There is no unprivileged workaround: Apptainer needs either a setuid-root install (requires root) or the
         |ability to create a user namespace. Run on a host, VM or CI that permits unprivileged user namespaces.
         |
         |If you are pointing Scalapptainer at a setuid-root Apptainer that does not need user namespaces, set
         |SCALAPPTAINER_SKIP_USERNS_CHECK=1 to skip this check.""".stripMargin
    )
