package scalapptainer

import utest.*

object BackendTests extends TestSuite {

  private def ok(spec: ProcSpec, out: String = "") = ProcResult(0, out, "", spec.argv)
  private def fail(spec: ProcSpec) = ProcResult(1, "", "", spec.argv)

  val tests = Tests {

    test("command prefixes / apptainer wrapping") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv())

      val linux = new LinuxBackend(r)
      assert(
        linux.wrapApptainer("/p/apptainer", Seq("run", "i.sif")) ==
          Seq("/p/apptainer", "run", "i.sif")
      )

      val wsl = new Wsl2Backend(r, BackendConfig())
      assert(
        wsl.wrapApptainer("/p/apptainer", Seq("run", "i.sif")) ==
          Seq("wsl.exe", "-e", "/p/apptainer", "run", "i.sif")
      )

      val wslDistro = new Wsl2Backend(r, BackendConfig(wslDistro = Some("Ubuntu")))
      assert(
        wslDistro.wrapApptainer("/p/apptainer", Seq("run")) ==
          Seq("wsl.exe", "-d", "Ubuntu", "-e", "/p/apptainer", "run")
      )

      val lima = new LimaBackend(r, BackendConfig(limaInstance = "dev"))
      assert(
        lima.wrapApptainer("/p/apptainer", Seq("run")) ==
          Seq("limactl", "shell", "dev", "/p/apptainer", "run")
      )
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

    test("WSL2 checkAvailable passes for a WSL2 distro") {
      val r = new RecordingRunner(spec =>
        spec.argv match {
          case Seq("wsl.exe", "-e", "true")                            => ok(spec)
          case Seq("wsl.exe", "-e", "cat", "/proc/sys/kernel/osrelease") => ok(spec, "5.15.167.4-microsoft-standard-WSL2")
          case _                                                       => fail(spec)
        }
      )
      new Wsl2Backend(r, BackendConfig()).checkAvailable() // no throw
    }

    test("WSL2 checkAvailable: wsl.exe present but no distro -> list/install guidance") {
      // wsl.exe runs (returns a result) but the distro probe exits non-zero.
      val r = new RecordingRunner(_ => ProcResult(1, "", "", Nil))
      val ex = assertThrows[BackendUnavailableException](new Wsl2Backend(r, BackendConfig()).checkAvailable())
      assert(ex.getMessage.contains("wsl --list --verbose"))
      assert(ex.getMessage.contains("wsl --install -d Ubuntu"))
    }

    test("WSL2 checkAvailable handles a missing wsl.exe (thrown)") {
      val r = new RecordingRunner(_ => throw new RuntimeException("not found"))
      val ex = assertThrows[BackendUnavailableException](new Wsl2Backend(r, BackendConfig()).checkAvailable())
      assert(ex.getMessage.contains("WSL2 is required"))
      assert(ex.getMessage.contains("wsl --install"))
    }

    test("WSL2 checkAvailable rejects a WSL1 distro with conversion instructions") {
      val r = new RecordingRunner(spec =>
        spec.argv match {
          case Seq("wsl.exe", "-e", "true")                            => ok(spec)
          case Seq("wsl.exe", "-e", "cat", "/proc/sys/kernel/osrelease") => ok(spec, "4.4.0-19041-Microsoft")
          case _                                                       => fail(spec)
        }
      )
      val ex = assertThrows[BackendUnavailableException](new Wsl2Backend(r, BackendConfig()).checkAvailable())
      assert(ex.getMessage.contains("WSL1"))
      assert(ex.getMessage.contains("wsl --set-version"))
    }

    test("WSL2 checkAvailable does not misclassify a custom-named WSL2 kernel as WSL1") {
      // A custom-compiled WSL2 kernel may carry neither 'microsoft' nor 'WSL2' — must not be flagged WSL1.
      val r = new RecordingRunner(spec =>
        spec.argv match {
          case Seq("wsl.exe", "-e", "true")                            => ok(spec)
          case Seq("wsl.exe", "-e", "cat", "/proc/sys/kernel/osrelease") => ok(spec, "6.6.0-mycustom")
          case _                                                       => fail(spec)
        }
      )
      new Wsl2Backend(r, BackendConfig()).checkAvailable() // no throw
    }

    test("Lima checkAvailable: missing limactl -> brew instructions") {
      val r = new RecordingRunner(_ => throw new RuntimeException("no limactl"))
      val ex = assertThrows[BackendUnavailableException](new LimaBackend(r, BackendConfig()).checkAvailable())
      assert(ex.getMessage.contains("brew install lima"))
    }

    test("Lima checkAvailable auto-provisions a missing instance from the apptainer template") {
      var created = false
      val r = new RecordingRunner(spec =>
        spec.argv match {
          case Seq("limactl", "--version")           => ok(spec, "limactl version 1.0.0")
          case s if s.contains("template:apptainer") => created = true; ok(spec)
          case Seq("limactl", "list", _*)            => ok(spec, if (created) "Running" else "") // "" => missing
          case _                                     => ok(spec)
        }
      )
      new LimaBackend(r, BackendConfig(limaInstance = "scalapptainer")).checkAvailable() // no throw
      val create = r.calls.find(_.argv.contains("template:apptainer")).get
      assert(create.argv.contains("--name=scalapptainer"))
      assert(create.argv.contains("--tty=false"))
    }

    test("Lima checkAvailable auto-starts a stopped instance (no re-create)") {
      var started = false
      val r = new RecordingRunner(spec =>
        spec.argv match {
          case Seq("limactl", "--version")              => ok(spec, "limactl version 1.0.0")
          case Seq("limactl", "start", "scalapptainer") => started = true; ok(spec)
          case Seq("limactl", "list", _*)               => ok(spec, if (started) "Running" else "Stopped")
          case _                                        => ok(spec)
        }
      )
      new LimaBackend(r, BackendConfig(limaInstance = "scalapptainer")).checkAvailable() // no throw
      assert(r.calls.exists(_.argv == Seq("limactl", "start", "scalapptainer")))
      assert(!r.calls.exists(_.argv.contains("template:apptainer"))) // a stopped instance is not re-created
    }

    test("Lima provisioning forwards --vm-type when configured") {
      var created = false
      val r = new RecordingRunner(spec =>
        spec.argv match {
          case Seq("limactl", "--version")           => ok(spec, "limactl version 1.0.0")
          case s if s.contains("template:apptainer") => created = true; ok(spec)
          case Seq("limactl", "list", _*)            => ok(spec, if (created) "Running" else "")
          case _                                     => ok(spec)
        }
      )
      new LimaBackend(r, BackendConfig(limaInstance = "scalapptainer", limaVmType = Some("qemu"))).checkAvailable()
      assert(r.calls.find(_.argv.contains("template:apptainer")).get.argv.contains("--vm-type=qemu"))
    }

    test("Lima checkAvailable throws if provisioning does not yield a running instance") {
      val r = new RecordingRunner(spec =>
        spec.argv match {
          case Seq("limactl", "--version") => ok(spec, "limactl version 1.0.0")
          case Seq("limactl", "list", _*)  => ok(spec, "") // never becomes running
          case _                           => ok(spec)
        }
      )
      val ex = assertThrows[BackendUnavailableException](
        new LimaBackend(r, BackendConfig(limaInstance = "scalapptainer")).checkAvailable()
      )
      assert(ex.getMessage.contains("still not running"))
      assert(ex.getMessage.contains("SCALAPPTAINER_LIMA_VM_TYPE"))
    }

    test("Lima checkAvailable falls back to instructions when auto-provisioning is disabled") {
      val r = new RecordingRunner(spec =>
        spec.argv match {
          case Seq("limactl", "--version") => ok(spec, "limactl version 1.0.0")
          case Seq("limactl", "list", _*)  => ok(spec, "") // missing
          case _                           => ok(spec)
        }
      )
      val ex = assertThrows[BackendUnavailableException](
        new LimaBackend(r, BackendConfig(limaInstance = "scalapptainer", limaAutoProvision = false)).checkAvailable()
      )
      assert(ex.getMessage.contains("auto-provisioning is disabled"))
      assert(!r.calls.exists(_.argv.contains("template:apptainer"))) // did not auto-create
    }

    test("runShell verifies backend availability first") {
      // A backend whose prerequisite is missing must surface BackendUnavailableException from a
      // provisioning shell-out (e.g. resolving $HOME), not a raw failure deep in the call stack.
      val r = new RecordingRunner(_ => throw new RuntimeException("no limactl"))
      val b = new LimaBackend(r, BackendConfig())
      assertThrows[BackendUnavailableException](b.runShell("printf hi"))
    }

    test("Lima checkAvailable passes when instance running (no provisioning)") {
      val r = new RecordingRunner(spec =>
        spec.argv match {
          case Seq("limactl", "--version") => ok(spec, "limactl version 1.0.0")
          case Seq("limactl", "list", _*)  => ok(spec, "Running")
          case _                           => ok(spec)
        }
      )
      val b = new LimaBackend(r, BackendConfig())
      b.checkAvailable() // no throw
      assert(!r.calls.exists(_.argv.contains("start")))
    }

    test("checkAvailable is memoised: the probe runs only once") {
      val r = new RecordingRunner(spec => if (spec.argv == Seq("wsl.exe", "-e", "true")) ok(spec) else fail(spec))
      val b = new Wsl2Backend(r, BackendConfig())
      b.checkAvailable(); b.checkAvailable(); b.checkAvailable()
      assert(r.calls.count(_.argv == Seq("wsl.exe", "-e", "true")) == 1)
    }

    test("hasCommand is memoised per tool") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("git")))
      val b = new LinuxBackend(r)
      assert(b.hasCommand("git"), b.hasCommand("git")) // present, queried twice
      assert(!b.hasCommand("nope"), !b.hasCommand("nope")) // absent, queried twice
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

    test("x11Forwarding binds the X11 socket and forwards DISPLAY on a socket backend") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(display = ":0"))
      val x = new LinuxBackend(r).x11Forwarding
      assert(x.env.get("DISPLAY").contains(":0"))
      assert(x.binds.map(_.source) == Seq("/tmp/.X11-unix"))
    }

    test("x11Forwarding is empty when the backend has no DISPLAY") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(display = ""))
      val x = new LinuxBackend(r).x11Forwarding
      assert(x.binds.isEmpty && x.env.isEmpty)
    }

    test("Lima x11Forwarding targets XQuartz over TCP (no socket bind)") {
      val r = new RecordingRunner(spec => ok(spec))
      val x = new LimaBackend(r, BackendConfig()).x11Forwarding
      assert(x.env.get("DISPLAY").contains("host.lima.internal:0"))
      assert(x.binds.isEmpty)
    }
  }
}
