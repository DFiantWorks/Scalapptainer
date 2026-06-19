package demo

import scalapptainer.*

/** Run a graphical app from inside a container, displayed on the host.
  *
  * Builds a minimal image with `xclock` (from `x11-apps`), then `.withX11()` forwards the host display into the
  * container — backend-aware: the X11 socket on native Linux and WSL2/WSLg, XQuartz over TCP on macOS/Lima. A little
  * clock window appears on your desktop.
  *
  * Run with: ./mill examples.runMain demo.GuiDemo (then close the clock window to finish)
  */
object GuiDemo:

  def main(args: Array[String]): Unit =
    println(s"Backend: ${Apptainer.backend.name}")

    val definition =
      """Bootstrap: docker
        |From: ubuntu:24.04
        |%post
        |    apt-get update
        |    apt-get install -y --no-install-recommends x11-apps
        |    rm -rf /var/lib/apt/lists/*
        |""".stripMargin

    println("Building image 'xclock' (first run installs x11-apps; reused afterwards)...")
    val gui = Apptainer.build(
      definition,
      name = "xclock",
      enableNonRootBuild = true
    )
    println(s"Image: ${gui.ref}")

    println("Opening an xclock window — close it to finish.")
    // .withX11() adds the bind mounts + DISPLAY this backend needs; then just run the app.
    val code = gui.withX11().run("xclock")
    println(s"xclock exited with code $code.")
