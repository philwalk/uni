package uni

import org.scalatest.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class TestInvariants extends AnyFunSpec with Matchers with BeforeAndAfter {
  def hereDrive: String = {
    if (isWin) new java.io.File("/").getAbsolutePath.take(2).mkString else ""
  }

  describe("invariants") {
    // verify test invariants
    describe("working drive") {
      val hd = hereDrive
      printf("hd [%s]\n", hd.toString)
      it(" should be correct for os") {
        if (isWin) {
          assert(hereDrive.matches("[a-zA-Z]:"))
        } else {
          assert(hereDrive.isEmpty)
        }
      }
    }
  }
}
