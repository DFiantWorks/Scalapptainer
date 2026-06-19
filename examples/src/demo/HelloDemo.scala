package demo

import scalapptainer.*

/** The simplest Scalapptainer demo: pull a tiny public image and run commands in it.
  *
  * `Apptainer` auto-detects the backend (native Linux, WSL2, or macOS/Lima) and installs Apptainer in user mode on
  * first use — no root, no manual setup.
  *
  * Run with: ./mill examples.run
  */
object HelloDemo:

  def main(args: Array[String]): Unit =
    println(s"Backend: ${Apptainer.backend.name}")

    // Pull a ~3 MB Alpine image into the cache (~/.scalapptainer/images/alpine.sif).
    // It is reused on later runs. `pull` returns a handle to the image.
    val alpine = Apptainer.pull("docker://alpine:latest")
    println(s"Image:   ${alpine.ref}\n")

    // Run commands inside the image. `exec` returns the captured result; `.out` is its
    // trimmed standard output.
    println(alpine.exec("cat", "/etc/os-release").out)
    println()
    println("uname -a -> " + alpine.exec("uname", "-a").out)
    println("echo     -> " + alpine.exec("sh", "-c", "echo Hello from inside the container!").out)
