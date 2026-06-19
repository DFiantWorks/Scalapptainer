package fpga_demo

import scalapptainer.*
import scalapptainer.commands.*

/** A self-contained demonstration of using Scalapptainer locally to drive the `r0d0s/fpga_tools` Docker image through
  * Apptainer.
  *
  * On this Windows host the call below auto-detects the WSL2 backend, installs Apptainer in user mode on first use
  * (using the bundled curl/rpm2cpio/cpio), pulls the OCI image into a SIF, runs the bundled FPGA tools, and finally
  * synthesizes a small Verilog design with Yosys — writing the netlist back out to the host via a bind mount.
  *
  * Run with: ./mill examples.run
  */
object FpgaToolsDemo:

  private val Image = "docker://r0d0s/fpga_tools:latest"

  def main(args: Array[String]): Unit =
    // Recommended usage: the `Apptainer` object is itself a ready-to-use instance,
    // bound to the auto-detected backend (native-linux / WSL2 / Lima). Just call it.

    section("1. Backend & user-mode Apptainer")
    println(s"Detected backend : ${Apptainer.backend.name}")
    Apptainer.checkAvailable() // throws BackendUnavailableException with fix-it steps
    // First touch of `version` provisions Apptainer in user mode (no root).
    println(s"Apptainer        : ${Apptainer.version}")
    println(s"Resolved binary  : ${Apptainer.apptainerPath}")
    println(s"Backend home     : ${Apptainer.backend.home}")

    section("2. Pull the FPGA-tools image into a SIF")
    // `pull` caches the SIF under ~/.scalapptainer/images (backend-native, fast) and reuses
    // it on later runs, returning a handle we drive for the rest of the demo.
    println(s"Pulling $Image (first run downloads ~3.6 GB; reused thereafter)")
    val img = Apptainer.pull(Image, name = "fpga_tools")
    println(s"Image: ${img.ref}")

    section("3. Image labels")
    println(img.inspect().out)

    section("4. Tool versions inside the container")
    val probes = Seq(
      "yosys" -> Seq("yosys", "--version"),
      "iverilog" -> Seq("iverilog", "-V"),
      "verilator" -> Seq("verilator", "--version")
    )
    for (name, cmd) <- probes do
      val r = img.exec(cmd*)
      val line = (r.out + "\n" + r.err).linesIterator.find(_.trim.nonEmpty).getOrElse("(no output)")
      println(f"$name%-10s : ${line.trim}")

    section("5. Synthesize counter.v with Yosys (bind-mounted host design)")
    // The design lives in this module's resources (version-controlled); outputs go
    // to a runtime-only work directory (git-ignored). Both host paths are translated
    // to their in-backend view (C:\... -> /mnt/c/...) and bind-mounted into the
    // container: the design read-only at /design, the work dir writable at /work.
    val designDir = os.pwd / "examples" / "resources" / "fpga_demo"
    val workDir = os.pwd / "examples" / "work"
    os.makeDir.all(workDir)

    val guestDesign = Apptainer.hostPath(designDir.toString)
    val guestWork = Apptainer.hostPath(workDir.toString)
    println(s"Design (ro)  : $guestDesign -> /design")
    println(s"Work   (rw)  : $guestWork -> /work")

    val res = img
      .bind(BindMount(guestDesign, "/design", readOnly = true))
      .bind(guestWork, "/work")
      .exec("yosys", "-q", "-s", "/design/synth.ys")
    if res.failed then
      println("Yosys stderr:\n" + res.err)
      sys.exit(1)

    // Yosys wrote the gate-level stats and the JSON netlist back to the host
    // through the /work bind mount.
    val stat = workDir / "stat.txt"
    if os.exists(stat) then println(os.read(stat).trim)

    val netlist = workDir / "counter.netlist.json"
    println(s"\nNetlist written to host: $netlist (${os.size(netlist)} bytes)")

    section("Done")
    println("Scalapptainer drove the full WSL2 -> Apptainer -> OCI image flow locally.")

  private def section(title: String): Unit =
    println(s"\n${"=" * 70}\n$title\n${"=" * 70}")
