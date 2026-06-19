package scalapptainer

import utest.*

object VendoredToolsTests extends TestSuite {
  val tests = Tests {

    test("Arch.parse maps common uname/os.arch tokens") {
      assert(Arch.parse("x86_64") == Arch.X86_64)
      assert(Arch.parse("amd64") == Arch.X86_64)
      assert(Arch.parse("aarch64") == Arch.Aarch64)
      assert(Arch.parse("arm64") == Arch.Aarch64)
      assert(Arch.parse("riscv64") == Arch.Other)
    }

    test("resourceName tokens match the vendored directory layout") {
      assert(Arch.X86_64.resourceName == "x86_64")
      assert(Arch.Aarch64.resourceName == "aarch64")
    }

    test("vendored tool binaries are actually bundled for both arches") {
      for {
        arch <- Seq("x86_64", "aarch64")
        tool <- Seq("rpm2cpio", "curl", "cpio")
      } {
        val path = s"/scalapptainer/tools/linux-$arch/$tool"
        val in = getClass.getResourceAsStream(path)
        assert(in != null)
        in.close()
      }
    }

    test("rpm2cpio is a shell script") {
      val in = getClass.getResourceAsStream("/scalapptainer/tools/linux-x86_64/rpm2cpio")
      val head = new String(in.readNBytes(2))
      in.close()
      assert(head == "#!")
    }

    test("ensure() is a no-op when all tools are already present") {
      val r = new RecordingRunner(RecordingRunner.linuxEnv()) // all present by default
      val result = VendoredTools.ensure(new LinuxBackend(r))
      assert(result.isEmpty)
      // no mkdir / base64 materialisation happened
      assert(!r.scripts.exists(_.contains("base64 -d")))
    }

    test("ensure() materialises only the missing tools via base64 pipe") {
      // Only base64 is present; curl/rpm2cpio/cpio are missing and must be vendored.
      val r = new RecordingRunner(RecordingRunner.linuxEnv(present = Set("bash", "base64"), home = "/home/me"))
      val result = VendoredTools.ensure(new LinuxBackend(r))
      assert(result.contains("/home/me/.scalapptainer/tools/bin"))

      val base64Calls = r.calls.filter(c => r.scriptOf(c).exists(_.contains("base64 -d")))
      assert(base64Calls.length == 3) // curl, rpm2cpio, cpio
      assert(base64Calls.forall(_.stdin.isDefined)) // payload fed via stdin
    }
  }
}
