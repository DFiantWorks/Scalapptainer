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
