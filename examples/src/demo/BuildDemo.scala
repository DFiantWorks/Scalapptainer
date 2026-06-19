package demo

import scalapptainer.*

/** Build a minimal image from an inline definition file, then use a bind mount to pass a file back out to the host.
  *
  * The image is built **unprivileged** (no root): `enableNonRootBuild` lets Apptainer run the definition file's `%post`
  * via a user namespace. The SIF is cached and reused.
  *
  * Run with: ./mill examples.runMain demo.BuildDemo
  */
object BuildDemo:

  def main(args: Array[String]): Unit =
    println(s"Backend: ${Apptainer.backend.name}")

    // The definition file is just a string. Ubuntu + the `figlet` ASCII-banner tool.
    val definition =
      """Bootstrap: docker
        |From: ubuntu:24.04
        |%post
        |    apt-get update
        |    apt-get install -y --no-install-recommends figlet
        |    rm -rf /var/lib/apt/lists/*
        |""".stripMargin

    println("Building image 'figlet' (first run installs figlet; reused afterwards)...")
    val figlet = Apptainer.build(
      definition,
      name = "figlet",
      enableNonRootBuild = true, // build without root
      mksquashfsArgs = Some("-processors 1") // WSL2 + Apptainer 1.5.1: dodge a known mksquashfs segfault (#3577)
    )
    println(s"Image: ${figlet.ref}\n")

    // Run the tool in the image.
    println(figlet.exec("figlet", "Scalapptainer").out)

    // Bind-mount a host directory at /out and write a file through it — it appears on the
    // host. Host paths are translated to the backend's view automatically.
    val outDir = os.pwd / "examples" / "work"
    os.makeDir.all(outDir)
    figlet
      .bind(Apptainer.hostPath(outDir.toString), "/out")
      .exec("sh", "-c", "figlet 'made with Apptainer' > /out/banner.txt")

    val banner = outDir / "banner.txt"
    println(s"Container wrote ${os.size(banner)} bytes to the host file $banner :\n")
    println(os.read(banner))
