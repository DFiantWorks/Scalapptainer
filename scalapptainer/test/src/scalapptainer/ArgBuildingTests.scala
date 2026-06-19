package scalapptainer

import scalapptainer.commands.*
import utest.*

object ArgBuildingTests extends TestSuite {
  val tests = Tests {

    test("BindMount specs") {
      assert(BindMount("/data").spec == "/data")
      assert(BindMount("/h", "/c").spec == "/h:/c")
      assert(BindMount("/h", "/c", readOnly = true).spec == "/h:/c:ro")
    }

    test("ExecOptions render in deterministic order") {
      val opts = ExecOptions()
        .bind("/data", "/data")
        .bind(BindMount("/ro", Some("/ro"), readOnly = true))
        .env("B" -> "2", "A" -> "1")
        .fakeroot()
        .nv()
        .workdir("/tmp/work")
        .arg("--no-mount", "tmp")
      assert(
        opts.toArgs == Seq(
          "--bind",
          "/data:/data",
          "--bind",
          "/ro:/ro:ro",
          "--env",
          "A=1", // env sorted by key for determinism
          "--env",
          "B=2",
          "--fakeroot",
          "--nv",
          "--workdir",
          "/tmp/work",
          "--no-mount",
          "tmp"
        )
      )
    }

    test("RunCommand: run [opts] image args") {
      val c = RunCommand("img.sif", Seq("--flag", "x")).withOptions(_.fakeroot())
      assert(c.args == Seq("run", "--fakeroot", "img.sif", "--flag", "x"))
    }

    test("ExecCommand: exec [opts] image command") {
      val c = ExecCommand("img.sif", Seq("echo", "hi")).withOptions(_.cleanEnv())
      assert(c.args == Seq("exec", "--cleanenv", "img.sif", "echo", "hi"))
    }

    test("ShellCommand: shell [opts] image") {
      val c = ShellCommand("img.sif", ExecOptions().contain())
      assert(c.args == Seq("shell", "--contain", "img.sif"))
    }

    test("PullCommand") {
      assert(PullCommand("docker://alpine").args == Seq("pull", "docker://alpine"))
      assert(
        PullCommand("docker://alpine", dest = Some("a.sif"), force = true, dir = Some("/imgs")).args ==
          Seq("pull", "--force", "--dir", "/imgs", "a.sif", "docker://alpine")
      )
    }

    test("BuildCommand") {
      assert(
        BuildCommand("out.sif", "def.def", sandbox = true, force = true, fakeroot = true).args ==
          Seq("build", "--sandbox", "--force", "--fakeroot", "out.sif", "def.def")
      )
    }

    test("InspectCommand") {
      assert(
        InspectCommand("img.sif", labels = true, json = true).args ==
          Seq("inspect", "--labels", "--json", "img.sif")
      )
    }

    test("InstanceCommand family") {
      assert(
        InstanceCommand.Start("img.sif", "web").withOptions(_.bind("/d", "/d")).args ==
          Seq("instance", "start", "--bind", "/d:/d", "img.sif", "web")
      )
      assert(InstanceCommand.Stop("web").args == Seq("instance", "stop", "web"))
      assert(
        InstanceCommand.Stop(all = true, force = true).args ==
          Seq("instance", "stop", "--force", "--all")
      )
      assert(InstanceCommand.ListInstances(json = true).args == Seq("instance", "list", "--json"))
    }
  }
}
