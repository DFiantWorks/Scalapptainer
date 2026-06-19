package scalapptainer

import utest.*

object ProcResultTests extends TestSuite {
  val tests = Tests {

    test("succeeded / failed") {
      val ok = ProcResult(0, "out", "", Seq("x"))
      val bad = ProcResult(2, "", "boom", Seq("x"))
      assert(ok.succeeded, !ok.failed, bad.failed, !bad.succeeded)
    }

    test("out / err are trimmed") {
      val r = ProcResult(0, "  hi\n", "\nwarn  ", Seq("x"))
      assert(r.out == "hi", r.err == "warn")
    }

    test("throwIfFailed returns self when ok") {
      val r = ProcResult(0, "", "", Seq("x"))
      assert(r.throwIfFailed() eq r)
    }

    test("throwIfFailed throws with diagnostics when failed") {
      val r = ProcResult(3, "", "the error", Seq("apptainer", "run"))
      val ex = intercept[ApptainerCommandException](r.throwIfFailed())
      assert(ex.result eq r)
      assert(ex.getMessage.contains("exit code 3"))
      assert(ex.getMessage.contains("the error"))
      assert(ex.getMessage.contains("apptainer run"))
    }
  }
}
