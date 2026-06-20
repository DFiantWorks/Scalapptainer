package scalapptainer

import scalapptainer.commands.*
import utest.*

object ApptainerTests extends TestSuite {
  val tests = Tests {

    test("exec wraps apptainer with the resolved path (system install)") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer")))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      app.exec(Seq("--version"))
      assert(r.calls.last.argv == Seq("/usr/bin/apptainer", "--version"))
    }

    test("companion object is a default instance bound to the detected backend") {
      // Referencing the object initialises the auto-detected default. This only
      // runs Backend.detect() (no subprocess); the prerequisite check and install
      // stay lazy, so no real WSL2/Lima/apptainer is touched here.
      val app: Apptainer = Apptainer
      assert(app.backend.os == Platform.os)
    }

    test("repeated calls probe availability and resolve apptainer only once") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer")))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      app.exec(Seq("--version"))
      app.run(RunCommand("img.sif"))
      app.exec(Seq("inspect", "img.sif"))
      // backend availability probe (bash -lc true) ran once across three calls
      assert(r.scripts.count(_ == "true") == 1)
      // apptainer was resolved (command -v apptainer) once
      assert(r.scripts.count(_ == "command -v apptainer") == 1)
    }

    test("typed command dispatch builds the full apptainer argv") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer")))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      app.run(RunCommand("img.sif").withOptions(_.fakeroot().bind("/data", "/data")))
      // toArgs renders in a fixed, deterministic order (binds before flags),
      // independent of the fluent call order above.
      assert(
        r.calls.last.argv ==
          Seq("/usr/bin/apptainer", "run", "--bind", "/data:/data", "--fakeroot", "img.sif")
      )
    }

    test("build defaults the SIF into the image cache and returns a handle") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer"), home = "/home/me"))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      val img = app.build("def.def", name = "tools")
      assert(img.ref == "/home/me/.scalapptainer/images/tools.sif")
      // cache dir created, then a build into it carrying the default parallelism cap
      assert(r.scripts.exists(_.contains("mkdir -p '/home/me/.scalapptainer/images'")))
      assert(
        r.calls.last.argv ==
          Seq(
            "/usr/bin/apptainer",
            "build",
            "--mksquashfs-args",
            Apptainer.defaultMksquashfsArgs,
            "/home/me/.scalapptainer/images/tools.sif",
            "def.def"
          )
      )
    }

    test("build's default mksquashfs cap is -processors (cores/4, min 1) and is overridable") {
      assert(Apptainer.defaultMksquashfsArgs == s"-processors ${math.max(1, Runtime.getRuntime.availableProcessors() / 4)}")
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer"), home = "/home/me"))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      app.build("def.def", name = "tools", mksquashfsArgs = Some("-processors 16"))
      // the explicit value is used verbatim, and only once (the default is not also appended)
      assert(r.calls.last.argv.containsSlice(Seq("--mksquashfs-args", "-processors 16")))
      assert(r.calls.last.argv.count(_ == "--mksquashfs-args") == 1)
    }

    test("build derives the cache name from the source basename") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer"), home = "/home/me"))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      val img = app.build("/mnt/c/x/simio_min.def")
      assert(img.ref == "/home/me/.scalapptainer/images/simio_min.sif")
    }

    test("build(enableNonRootBuild=true) passes --ignore-subuid and any mksquashfs args") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer"), home = "/home/me"))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      app.build("def.def", name = "tools", enableNonRootBuild = true, mksquashfsArgs = Some("-processors 1"))
      assert(
        r.calls.last.argv ==
          Seq(
            "/usr/bin/apptainer",
            "build",
            "--ignore-subuid",
            "--mksquashfs-args",
            "-processors 1",
            "/home/me/.scalapptainer/images/tools.sif",
            "def.def"
          )
      )
    }

    test("build reuses the cached image when it already exists (no build invoked)") {
      val r = new RecordingRunner(
        RecordingRunner.linuxEnv(present = Set("bash", "apptainer"), home = "/home/me", imageExists = true)
      )
      val app = Apptainer.forBackend(new LinuxBackend(r))
      val img = app.build("def.def", name = "tools")
      assert(img.ref == "/home/me/.scalapptainer/images/tools.sif")
      assert(!r.calls.exists(_.argv.contains("build")))
    }

    test("build(force=true) rebuilds even when the image exists") {
      val r = new RecordingRunner(
        RecordingRunner.linuxEnv(present = Set("bash", "apptainer"), home = "/home/me", imageExists = true)
      )
      val app = Apptainer.forBackend(new LinuxBackend(r))
      app.build("def.def", name = "tools", force = true)
      assert(r.calls.last.argv.containsSlice(Seq("build", "--force")))
    }

    test("build(dest=...) bypasses the cache and uses the explicit path") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer")))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      val img = app.build("def.def", dest = Some("/elsewhere/out.sif"))
      assert(img.ref == "/elsewhere/out.sif")
      assert(
        r.calls.last.argv == Seq(
          "/usr/bin/apptainer",
          "build",
          "--mksquashfs-args",
          Apptainer.defaultMksquashfsArgs,
          "/elsewhere/out.sif",
          "def.def"
        )
      )
    }

    test("pull defaults into the cache, deriving the name from the URI") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer"), home = "/home/me"))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      val img = app.pull("docker://r0d0s/fpga_tools:latest")
      assert(img.ref == "/home/me/.scalapptainer/images/fpga_tools.sif")
      assert(
        r.calls.last.argv ==
          Seq(
            "/usr/bin/apptainer",
            "pull",
            "/home/me/.scalapptainer/images/fpga_tools.sif",
            "docker://r0d0s/fpga_tools:latest"
          )
      )
    }

    test("build resolves a bare-name source from the JVM classpath, materialising it into the backend") {
      // scalapptainer/test/resources/sample.def is on the test classpath.
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer"), home = "/home/me"))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      val img = app.build("sample.def", name = "fromres")
      assert(img.ref == "/home/me/.scalapptainer/images/fromres.sif")
      // resource bytes were written into the backend build dir via base64,
      val wrote = r.calls.exists(c =>
        r.scriptOf(c).exists(s => s.contains("base64 -d") && s.contains("/home/me/.scalapptainer/build/sample.def"))
      )
      assert(wrote)
      // and the build used that materialised backend path as its source.
      assert(
        r.calls.last.argv ==
          Seq(
            "/usr/bin/apptainer",
            "build",
            "--mksquashfs-args",
            Apptainer.defaultMksquashfsArgs,
            "/home/me/.scalapptainer/images/fromres.sif",
            "/home/me/.scalapptainer/build/sample.def"
          )
      )
    }

    test("build accepts inline def contents, materialising them into the backend") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer"), home = "/home/me"))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      val img = app.build("Bootstrap: docker\nFrom: busybox\n%post\n    echo hi\n", name = "inline")
      assert(img.ref == "/home/me/.scalapptainer/images/inline.sif")
      // contents written to <build>/inline.def via base64, then built from there
      assert(
        r.calls.exists(c =>
          r.scriptOf(c).exists(s => s.contains("base64 -d") && s.contains("/home/me/.scalapptainer/build/inline.def"))
        )
      )
      assert(
        r.calls.last.argv ==
          Seq(
            "/usr/bin/apptainer",
            "build",
            "--mksquashfs-args",
            Apptainer.defaultMksquashfsArgs,
            "/home/me/.scalapptainer/images/inline.sif",
            "/home/me/.scalapptainer/build/inline.def"
          )
      )
    }

    test("build inline def defaults the image name to 'image' when none given") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer"), home = "/home/me"))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      val img = app.build("Bootstrap: docker\nFrom: busybox\n")
      assert(img.ref == "/home/me/.scalapptainer/images/image.sif")
    }

    test("Apptainer.isInlineDef distinguishes def contents from references") {
      assert(Apptainer.isInlineDef("Bootstrap: docker\nFrom: busybox"))
      assert(Apptainer.isInlineDef("bootstrap: docker")) // single-line header
      assert(!Apptainer.isInlineDef("simio_min.def"))
      assert(!Apptainer.isInlineDef("simio_demo/simio_min.def"))
      assert(!Apptainer.isInlineDef("/path/to/x.def"))
      assert(!Apptainer.isInlineDef("docker://busybox"))
    }

    test("build falls back to the path when a bare-name source is not a classpath resource") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer"), home = "/home/me"))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      app.build("nonexistent.def", name = "x")
      assert(!r.scripts.exists(_.contains("base64 -d"))) // nothing materialised
      assert(
        r.calls.last.argv == Seq(
          "/usr/bin/apptainer",
          "build",
          "--mksquashfs-args",
          Apptainer.defaultMksquashfsArgs,
          "/home/me/.scalapptainer/images/x.sif",
          "nonexistent.def"
        )
      )
    }

    test("build with a ./ or absolute or URI source skips the classpath lookup") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer"), home = "/home/me"))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      // sample.def IS a resource, but the explicit ./ forces filesystem use.
      app.build("./sample.def", name = "x")
      assert(!r.scripts.exists(_.contains("base64 -d")))
      assert(r.calls.last.argv.last == "./sample.def")
    }

    test("ApptainerImage fluent bind/env feed exec and run, immutably") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer")))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      val base = app.image("/img.sif")
      val configured = base.bind("/data", "/data").env("X" -> "1")
      // base is unchanged (immutable)
      assert(base.options.binds.isEmpty && base.options.env.isEmpty)

      configured.exec("tool", "--v")
      assert(
        r.calls.last.argv ==
          Seq("/usr/bin/apptainer", "exec", "--bind", "/data:/data", "--env", "X=1", "/img.sif", "tool", "--v")
      )
      configured.run("app-arg")
      assert(
        r.calls.last.argv ==
          Seq("/usr/bin/apptainer", "run", "--bind", "/data:/data", "--env", "X=1", "/img.sif", "app-arg")
      )
    }

    test("withX11 binds the X11 socket and forwards DISPLAY (socket backend)") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer"), display = ":0"))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      app.image("/img.sif").withX11().exec("xeyes")
      assert(
        r.calls.last.argv ==
          Seq(
            "/usr/bin/apptainer",
            "exec",
            "--bind",
            "/tmp/.X11-unix:/tmp/.X11-unix",
            "--env",
            "DISPLAY=:0",
            "/img.sif",
            "xeyes"
          )
      )
    }

    test("withX11 is a no-op when the backend has no display") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer"), display = ""))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      app.image("/img.sif").withX11().exec("tool")
      assert(r.calls.last.argv == Seq("/usr/bin/apptainer", "exec", "/img.sif", "tool"))
    }

    test("Apptainer.deriveName strips scheme, segment, tag and extension") {
      assert(Apptainer.deriveName("docker://r0d0s/fpga_tools:latest") == "fpga_tools")
      assert(Apptainer.deriveName("/mnt/c/x/simio_min.def") == "simio_min")
      assert(Apptainer.deriveName("tools") == "tools")
    }

    test("WSL2 backend routes apptainer through wsl.exe") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer")))
      val app = Apptainer.forBackend(new Wsl2Backend(r, BackendConfig()))
      app.exec(Seq("--version"))
      assert(r.calls.last.argv == Seq("wsl.exe", "-e", "/usr/bin/apptainer", "--version"))
    }

    test("first use installs apptainer in user mode, vendoring the installer tools") {
      // A bare distro: only bash + base64. apptainer, curl, rpm2cpio, cpio are all absent.
      var installed = false
      val handler: ProcSpec => ProcResult = { spec =>
        def ok(out: String = "") = ProcResult(0, out, "", spec.argv)
        def fail() = ProcResult(1, "", "", spec.argv)
        val present = Set("bash", "base64")
        val i = spec.argv.indexOf("-lc")
        if (i < 0) ok()
        else {
          val s = spec.argv(i + 1).trim
          if (s == "true") ok()
          else if (s.contains("""printf %s "$HOME"""")) ok("/home/me")
          else if (s == "uname -m") ok("x86_64")
          else if (s.startsWith("command -v ")) {
            val tool = s
              .stripPrefix("command -v ")
              .trim
              .takeWhile(c => c != ' ' && c != '>')
              .stripPrefix("'")
              .stripSuffix("'")
            if (present.contains(tool)) ok(s"/usr/bin/$tool") else fail()
          } else if (s.contains("install-unprivileged.sh")) { installed = true; ok() }
          else if (s.startsWith("test -x")) {
            if (s.contains("/bin/apptainer") && installed) ok() else fail()
          } else ok() // mkdir, base64 materialisation
        }
      }
      val r = new RecordingRunner(handler)
      val app = Apptainer.forBackend(new LinuxBackend(r))
      app.exec(Seq("--version"))

      // the three installer-dependency tools were materialised via base64
      val base64Calls = r.calls.count(c => r.scriptOf(c).exists(_.contains("base64 -d")))
      assert(base64Calls == 3)

      // the unprivileged installer ran with the vendored-tools dir prepended to PATH,
      // pinning the Apptainer version via `-v`
      val installScript = r.scripts.find(_.contains("install-unprivileged.sh")).get
      val version = ApptainerInstaller.DefaultApptainerVersion
      assert(installScript.contains(s"bash -s - -v '$version'"))
      assert(installScript.contains("apptainer/main/tools/install-unprivileged.sh"))
      assert(installScript.contains("export PATH='/home/me/.scalapptainer/tools/bin'"))

      // a stale install dir is cleared (rm -rf) and recreated before the installer runs, so a partial install
      // left by an interrupted earlier run on a reused container (e.g. Scastie) self-heals — the installer
      // otherwise aborts with "<dir>/<arch> is not empty" and has no force flag.
      val rmIdx = installScript.indexOf(s"rm -rf '/home/me/.scalapptainer/$version'")
      val mkdirIdx = installScript.indexOf(s"mkdir -p '/home/me/.scalapptainer/$version'")
      val curlIdx = installScript.indexOf("install-unprivileged.sh")
      assert(rmIdx >= 0 && mkdirIdx > rmIdx && curlIdx > mkdirIdx)

      // and apptainer is then invoked from the per-version managed install location
      assert(
        r.calls.last.argv ==
          Seq(s"/home/me/.scalapptainer/$version/bin/apptainer", "--version")
      )
    }
  }
}
