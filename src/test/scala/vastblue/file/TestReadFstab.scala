//#!/usr/bin/env -S scala-cli shebang
package uni

//> using dep "org.vastblue::uni:0.4.4"

import uni.file.*
import org.scalatest.BeforeAndAfter
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

// TODO: instrument Paths.scala to allow injecting /etc/fstab settings.
// because the expectation for tests is a function of the mount table.
class TestReadFstab extends AnyFunSpec with Matchers with BeforeAndAfter {
  // display the native path and lines.size of /etc/fstab
  // mapped to "C:\msys64\etc\fstab" in default install for Windows MSYS2
  val sysType: String = uname("-o")
  println(s"uname [$sysType]")

  sysType match {
  case "Msys" | "Linux" =>
    val p = Paths.get("/etc/fstab")
    assert(p.lines.nonEmpty)
  case _ =>
    val p = Paths.get("/etc/hosts")
    assert(p.lines.nonEmpty)
  }

  def uname(arg: String = "-a"): String = {
    call("uname", arg).getOrElse("")
  }
}
