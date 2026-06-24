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
      // cache dir created, then a plain build into it (no mksquashfs args by default)
      assert(r.scripts.exists(_.contains("mkdir -p '/home/me/.scalapptainer/images'")))
      assert(
        r.calls.last.argv ==
          Seq(
            "/usr/bin/apptainer",
            "build",
            "/home/me/.scalapptainer/images/tools.sif",
            "def.def"
          )
      )
    }

    test("build passes no mksquashfs args by default, and the explicit value verbatim when given") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer"), home = "/home/me"))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      app.build("def.def", name = "plain")
      assert(!r.calls.last.argv.contains("--mksquashfs-args")) // nothing passed unless the caller asks
      app.build("def.def", name = "capped", mksquashfsArgs = Some("-processors 1"))
      assert(r.calls.last.argv.containsSlice(Seq("--mksquashfs-args", "-processors 1")))
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

    test("cleanEnv on the handle renders --cleanenv") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer")))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      app.image("/img.sif").cleanEnv().run()
      assert(r.calls.last.argv == Seq("/usr/bin/apptainer", "run", "--cleanenv", "/img.sif"))
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

    test("the user-mode install is refused with a clear error when the backend forbids user namespaces") {
      // No system apptainer and no prior managed install -> we'd do the unprivileged install, which is pointless
      // where unshare(CLONE_NEWUSER) is denied (e.g. a locked-down CI/playground container).
      val r = new RecordingRunner(
        RecordingRunner.linuxEnv(present = Set("bash", "base64"), home = "/home/me", usernsBlocked = true)
      )
      val app = Apptainer.forBackend(new LinuxBackend(r))
      val ex = assertThrows[UserNamespaceException](app.run("img.sif"))
      assert(ex.getMessage.contains("user namespaces"))
      // it failed fast: the installer was never fetched/run
      assert(!r.scripts.exists(_.contains("install-unprivileged.sh")))
    }

    test("an already-installed managed apptainer is reused without re-probing user namespaces") {
      // hasApptainer=true => the managed binary already exists on disk; resolve() returns it and install() — the only
      // place the userns check lives — is never reached, so a blocked sandbox does not matter here.
      val r = new RecordingRunner(
        RecordingRunner.linuxEnv(present = Set("bash", "base64"), home = "/home/me", hasApptainer = true, usernsBlocked = true)
      )
      val app = Apptainer.forBackend(new LinuxBackend(r))
      app.run("img.sif") // does not throw
      assert(!r.scripts.exists(_.startsWith("unshare"))) // the probe never ran
      val version = ApptainerInstaller.DefaultApptainerVersion
      assert(r.calls.last.argv == Seq(s"/home/me/.scalapptainer/$version/bin/apptainer", "run", "img.sif"))
    }

    test("a system apptainer (possibly setuid) is used without a user-namespace check") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "apptainer"), usernsBlocked = true))
      val app = Apptainer.forBackend(new LinuxBackend(r))
      app.run("img.sif") // does not throw
      assert(!r.scripts.exists(_.startsWith("unshare")))
      assert(r.calls.last.argv == Seq("/usr/bin/apptainer", "run", "img.sif"))
    }

    test("a system apptainer is version-probed so an outdated one can be flagged") {
      // When a system apptainer is found on PATH it is used as-is, but resolve() first runs `<bin> --version` to
      // compare it against the pinned version (and warn if older). The probe targets the resolved system binary.
      val base = RecordingRunner.linuxEnv(present = Set("bash", "apptainer"))
      val r = new RecordingRunner(spec => {
        val i = spec.argv.indexOf("-lc")
        val script = if (i >= 0 && i + 1 < spec.argv.length) spec.argv(i + 1) else ""
        // Have the system binary report an old version when probed via the shell.
        if (script == "'/usr/bin/apptainer' --version") ProcResult(0, "apptainer version 1.0.0", "", spec.argv)
        else base(spec)
      })
      val app = Apptainer.forBackend(new LinuxBackend(r))
      app.exec(Seq("--version"))
      assert(r.scripts.contains("'/usr/bin/apptainer' --version")) // the version probe ran against the system bin
      assert(r.calls.last.argv == Seq("/usr/bin/apptainer", "--version")) // and the system bin is still used as-is
    }

    test("ApptainerInstaller.parseVersion pulls the version token out of --version output") {
      assert(ApptainerInstaller.parseVersion("apptainer version 1.5.2") == Some("1.5.2"))
      assert(ApptainerInstaller.parseVersion("apptainer version 1.5.2-rc.1") == Some("1.5.2-rc.1"))
      assert(ApptainerInstaller.parseVersion("  1.4  ") == Some("1.4"))
      assert(ApptainerInstaller.parseVersion("no version here") == None)
    }

    test("ApptainerInstaller.compareVersions orders dotted versions numerically") {
      def cmp(a: String, b: String) = ApptainerInstaller.compareVersions(a, b)
      assert(cmp("1.5.1", "1.5.2") < 0)
      assert(cmp("1.5.2", "1.5.1") > 0)
      assert(cmp("1.5.2", "1.5.2") == 0)
      assert(cmp("1.5", "1.5.0") == 0) // missing trailing components count as 0
      assert(cmp("1.10.0", "1.9.9") > 0) // numeric, not lexical
      assert(cmp("1.5.2-rc.1", "1.5.2") < 0) // a pre-release sorts below the plain release
      assert(cmp("2.0.0", "1.9.9") > 0)
    }

    test("a run blocked by user namespaces at runtime is rethrown with actionable guidance") {
      // A system/managed apptainer skips the install-time probe, so a backend that only blocks the *mapping* step
      // (setgroups) surfaces it at run time; we recognise the signature and rethrow as UserNamespaceException.
      val base = RecordingRunner.linuxEnv(present = Set("bash", "apptainer"))
      val r = new RecordingRunner(spec =>
        if (spec.argv.contains("run"))
          ProcResult(
            255,
            "",
            "ERROR  : Could not write info to setgroups: Permission denied\n" +
              "ERROR  : Error while waiting event for user namespace mappings: no event received",
            spec.argv
          )
        else base(spec)
      )
      val app = Apptainer.forBackend(new LinuxBackend(r))
      val ex = assertThrows[UserNamespaceException](app.run("img.sif"))
      assert(ex.getMessage.contains("setgroups")) // echoes what Apptainer reported
      assert(ex.getMessage.contains("apparmor_restrict_unprivileged_userns")) // Linux-specific remedy
    }

    test("a non-userns command failure is left as-is (not misclassified)") {
      val base = RecordingRunner.linuxEnv(present = Set("bash", "apptainer"))
      val r = new RecordingRunner(spec =>
        if (spec.argv.contains("run")) ProcResult(1, "", "FATAL: no such image: img.sif", spec.argv)
        else base(spec)
      )
      val app = Apptainer.forBackend(new LinuxBackend(r))
      val res = app.run("img.sif") // exec returns the failed result; no UserNamespaceException
      assert(res.failed && res.err.contains("no such image"))
    }

    test("user-namespace remedy is tailored per backend") {
      val noop = new RecordingRunner(spec => ProcResult(0, "", "", spec.argv))
      val sig = "Could not write info to setgroups: Permission denied"

      val linux = UserNamespaceException.atRuntime(new LinuxBackend(noop), sig)
      assert(linux.getMessage.contains("apparmor_restrict_unprivileged_userns"))
      assert(linux.getMessage.contains("apptainer-suid")) // the no-sysctl setuid alternative

      val lima = UserNamespaceException.atRuntime(new LimaBackend(noop, BackendConfig()), sig)
      assert(lima.getMessage.contains("template:apptainer"))
      assert(lima.getMessage.contains("SCALAPPTAINER_LIMA_INSTANCE"))

      val wsl = UserNamespaceException.atRuntime(new Wsl2Backend(noop, BackendConfig()), sig)
      assert(wsl.getMessage.contains("WSL1"))
    }

    test("looksLikeUsernsFailure recognises the signature, not unrelated failures") {
      assert(UserNamespaceException.looksLikeUsernsFailure("Could not write info to setgroups: Permission denied"))
      assert(UserNamespaceException.looksLikeUsernsFailure("waiting event for user namespace mappings: no event received"))
      assert(UserNamespaceException.looksLikeUsernsFailure("unshare: Operation not permitted"))
      assert(!UserNamespaceException.looksLikeUsernsFailure("FATAL: no such image"))
      assert(!UserNamespaceException.looksLikeUsernsFailure("disk quota exceeded"))
    }
  }
}
