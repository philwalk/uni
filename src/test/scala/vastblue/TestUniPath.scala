package uni

import uni.*
import org.scalatest.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class TestUniPath extends AnyFunSpec with Matchers with BeforeAndAfter {

  describe("uni") {
    it("should display discovered environment") {
      printf("hereDrive:   [%s]\n", hereDrive)
      for ((key, valu) <- win2posixMounts) {
        printf("mount %-22s -> %s\n", key, valu)
      }
      for ((key, valu) <- posix2winMounts) {
        printf("mount %-22s -> %s\n", key, valu)
      }
    }
  }
}
