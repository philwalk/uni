package uni

import munit.FunSuite
import TestUtils.noisy
import uni.ext.*

class TestReadFstab extends FunSuite {
  override def beforeAll(): Unit = uni.resetConfig()

  // uname wrapper preserved exactly
  def uname(arg: String = "-a"): String = {
    val exe = if isWin then ".exe" else ""
    call(s"uname$exe", arg).getOrElse("")
  }

  test("/etc/hosts should be readable if uname in Path for any OS type") {
    Proc.whereInPath("uname") match {
      case None =>
      // need best effort examination of other evidence (sys.props? sys.env?)

      case Some(pathstr) =>
        val sysType = Proc.call(pathstr, "-o").getOrElse("")
        noisy(s"uname [$sysType]")

        sysType match {
          case "Msys" | "Linux" =>
            val p = Paths.get("/etc/hosts")
            assert(p.lines.nonEmpty, clues(s"/etc/hosts should not be empty: ${p.stdpath}"))

          case _ =>
            // on MacOS there is no /etc/fstab so we verify /etc/hosts instead
            val p = Paths.get("/etc/hosts")
            assert(p.lines.nonEmpty, clues(s"/etc/hosts should not be empty: ${p.stdpath}"))
        }
    }
  }
}
