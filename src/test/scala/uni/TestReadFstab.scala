package uni

import munit.FunSuite
import uni.fs.*
import TestUtils.{noisy}

class TestReadFstab extends FunSuite {

  // uname wrapper preserved exactly
  def uname(arg: String = "-a"): String =
    call("uname", arg).getOrElse("")

  test("fstab/hosts: should be readable depending on OS type") {
    val sysType = uname("-o")
    noisy(s"uname [$sysType]")

    sysType match {
      case "Msys" | "Linux" =>
        val p = Paths.get("/etc/fstab")
        assert(p.lines.nonEmpty, clues(s"/etc/fstab should not be empty: ${p.stdpath}"))

      case _ =>
        // on MacOS there is no /etc/fstab so we verify /etc/hosts instead
        val p = Paths.get("/etc/hosts")
        assert(p.lines.nonEmpty, clues(s"/etc/hosts should not be empty: ${p.stdpath}"))
    }
  }
}
