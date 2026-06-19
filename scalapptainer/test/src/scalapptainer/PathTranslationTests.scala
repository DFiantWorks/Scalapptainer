package scalapptainer

import utest.*

object PathTranslationTests extends TestSuite {

  private val noop = new RecordingRunner(s => ProcResult(0, "", "", s.argv))

  val tests = Tests {

    test("WSL2 maps Windows drive paths to /mnt") {
      val b = new Wsl2Backend(noop, BackendConfig())
      assert(b.translatePath("""C:\Users\me\img.sif""") == "/mnt/c/Users/me/img.sif")
      assert(b.translatePath("D:/data/x") == "/mnt/d/data/x")
    }

    test("WSL2 passes through POSIX-looking paths (normalising slashes)") {
      val b = new Wsl2Backend(noop, BackendConfig())
      assert(b.translatePath("/already/posix") == "/already/posix")
      assert(b.translatePath("""rel\path""") == "rel/path")
    }

    test("Linux translation is identity") {
      val b = new LinuxBackend(noop)
      assert(b.translatePath("""/any/path""") == "/any/path")
    }

    test("Lima translation is identity (home mounted at same path)") {
      val b = new LimaBackend(noop, BackendConfig())
      assert(b.translatePath("/Users/me/img.sif") == "/Users/me/img.sif")
    }
  }
}
