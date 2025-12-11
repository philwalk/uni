package uni

import uni.Internals.*
import org.scalatest.*
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class TestUniPath extends AnyFunSpec with Matchers with BeforeAndAfter {

  describe("uni") {
    it("should display discovered environment") {
      printf("hereDrive:   [%s]\n", hereDrive)
      for ((key, valu) <- config.win2posix) {
        printf("mount %-22s -> %s\n", key, valu)
      }
      for ((key, valu) <- config.posix2win) {
        printf("mount %-22s -> %s\n", key, valu)
      }
    }
  }
}
