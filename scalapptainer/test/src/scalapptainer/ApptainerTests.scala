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

      // and apptainer is then invoked from the per-version managed install location
      assert(
        r.calls.last.argv ==
          Seq(s"/home/me/.scalapptainer/$version/bin/apptainer", "--version")
      )
    }
  }
}
