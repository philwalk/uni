#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.12.3
import uni.*

object GenTestPaths {
  def main(args: Array[String]): Unit = {
    printf("%-32s, %-32s, %-32s\n", colnames*)
    umount()
    runTest
    mount()
    runTest
  }

  lazy val mountBase = "C:/Users"
  lazy val mountName = "/homedir"
  lazy val colnames = Seq("input", "expect", "isMapped")
  // usersMap: Boolean, denotes whether "C:/homedir" is mapped to "/home"
  lazy val windowsPaths = Seq(
    "C:/Users/liam/file.txt",
    "C:/Users/liam/AppData/Local",
    "/Users/liam/file.txt",
    "/Users/liam/AppData/Local",
    "/homedir/liam/file.txt",
    "/homedir/liam/AppData/Local",
    "../AppData/Local",
    "~/AppData",
  )

  def umount(name: String = mountName): Unit =
    run("umount.exe", name)
  def mount(name: String = mountName): Unit =
    run("mount.exe", mountBase, name)
  def isMapped(name: String = mountName): Boolean =
    run("mount.exe").text.split("\r?\n").exists(_.contains(name))
  def fixup(str: String): String =
    str.replaceAll(username, "liam").replaceAll(userhome, "C:/Users/liam")
  def cygpath(path: String, flag: String): String =
    fixup(run("cygpath.exe", flag, path).toOption.getOrElse("").trim)
  lazy val username = sys.props("user.name").replace('\\', '/')
  lazy val userhome = sys.props("user.home").replace('\\', '/')
  lazy val userdir = fixup(sys.props("user.dir"))

  def runTest: Unit = {
    val homeDir = isMapped()
    windowsPaths.foreach( fname =>
      val cyg = cygpath("-u", fname)
      printf("%-32s, %-32s, %-32s\n", fname, cyg, homeDir)
    )
  }

}