package scalapptainer

import utest.*

object BackendTests extends TestSuite {

  private def ok(spec: ProcSpec, out: String = "") = ProcResult(0, out, "", spec.argv)
  private def fail(spec: ProcSpec) = ProcResult(1, "", "", spec.argv)

  val tests = Tests {

    test("command prefixes / apptainer wrapping") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv())

      val linux = new LinuxBackend(r)
      assert(linux.wrapApptainer("/p/apptainer", Seq("run", "i.sif")) ==
        Seq("/p/apptainer", "run", "i.sif"))

      val wsl = new Wsl2Backend(r, BackendConfig())
      assert(wsl.wrapApptainer("/p/apptainer", Seq("run", "i.sif")) ==
        Seq("wsl.exe", "-e", "/p/apptainer", "run", "i.sif"))

      val wslDistro = new Wsl2Backend(r, BackendConfig(wslDistro = Some("Ubuntu")))
      assert(wslDistro.wrapApptainer("/p/apptainer", Seq("run")) ==
        Seq("wsl.exe", "-d", "Ubuntu", "-e", "/p/apptainer", "run"))

      val lima = new LimaBackend(r, BackendConfig(limaInstance = "dev"))
      assert(lima.wrapApptainer("/p/apptainer", Seq("run")) ==
        Seq("limactl", "shell", "dev", "/p/apptainer", "run"))
    }

    test("runShell wraps a bash -lc through the backend") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv())
      new Wsl2Backend(r, BackendConfig()).runShell("echo hi")
      assert(r.calls.last.argv == Seq("wsl.exe", "-e", "bash", "-lc", "echo hi"))
    }

    test("home and arch are resolved from the backend") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(home = "/home/bob", uname = "aarch64"))
      val b = new LinuxBackend(r)
      assert(b.home == "/home/bob")
      assert(b.arch == Arch.Aarch64)
    }

    test("WSL2 checkAvailable passes when probe succeeds") {
      val r = new RecordingRunner(spec =>
        if (spec.argv == Seq("wsl.exe", "-e", "true")) ok(spec) else fail(spec))
      new Wsl2Backend(r, BackendConfig()).checkAvailable() // no throw
    }

    test("WSL2 checkAvailable instructs to install when unavailable") {
      val r = new RecordingRunner(_ => ProcResult(1, "", "", Nil))
      val ex = intercept[BackendUnavailableException](
        new Wsl2Backend(r, BackendConfig()).checkAvailable())
      assert(ex.getMessage.contains("wsl --install"))
    }

    test("WSL2 checkAvailable handles a missing wsl.exe (thrown)") {
      val r = new RecordingRunner(_ => throw new RuntimeException("not found"))
      val ex = intercept[BackendUnavailableException](
        new Wsl2Backend(r, BackendConfig()).checkAvailable())
      assert(ex.getMessage.contains("WSL2 is required"))
    }

    test("Lima checkAvailable: missing limactl -> brew instructions") {
      val r = new RecordingRunner(_ => throw new RuntimeException("no limactl"))
      val ex = intercept[BackendUnavailableException](
        new LimaBackend(r, BackendConfig()).checkAvailable())
      assert(ex.getMessage.contains("brew install lima"))
    }

    test("Lima checkAvailable: instance not running -> start instructions") {
      val r = new RecordingRunner(spec =>
        spec.argv match {
          case Seq("limactl", "--version") => ok(spec, "limactl version 1.0.0")
          case Seq("limactl", "list", _*)  => ok(spec, "Stopped")
          case _                           => ok(spec)
        })
      val ex = intercept[BackendUnavailableException](
        new LimaBackend(r, BackendConfig(limaInstance = "default")).checkAvailable())
      assert(ex.getMessage.contains("limactl start default"))
    }

    test("Lima checkAvailable passes when instance running") {
      val r = new RecordingRunner(spec =>
        spec.argv match {
          case Seq("limactl", "--version") => ok(spec, "limactl version 1.0.0")
          case Seq("limactl", "list", _*)  => ok(spec, "Running")
          case _                           => ok(spec)
        })
      new LimaBackend(r, BackendConfig()).checkAvailable() // no throw
    }

    test("checkAvailable is memoised: the probe runs only once") {
      val r = new RecordingRunner(spec =>
        if (spec.argv == Seq("wsl.exe", "-e", "true")) ok(spec) else fail(spec))
      val b = new Wsl2Backend(r, BackendConfig())
      b.checkAvailable(); b.checkAvailable(); b.checkAvailable()
      assert(r.calls.count(_.argv == Seq("wsl.exe", "-e", "true")) == 1)
    }

    test("hasCommand is memoised per tool") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("git")))
      val b = new LinuxBackend(r)
      assert(b.hasCommand("git"), b.hasCommand("git"))      // present, queried twice
      assert(!b.hasCommand("nope"), !b.hasCommand("nope"))  // absent, queried twice
      val probes = r.scripts.count(_.startsWith("command -v "))
      assert(probes == 2) // one per distinct tool, not per call
    }

    test("home and arch are resolved once") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv())
      val b = new LinuxBackend(r)
      b.home; b.home; b.arch; b.arch
      assert(r.scripts.count(_.contains("""printf %s "$HOME"""")) == 1)
      assert(r.scripts.count(_ == "uname -m") == 1)
    }
  }
}
