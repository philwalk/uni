#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.8.2

import uni.*

  printf("%-32s: %s\n", "scriptPath",         scriptPath)           // defined by scala-cli, undefined in non-script scala programs
  printf("%-32s: %s\n", "sourcePathRelative", sourcePathRelative)   // based on a property defined by scala-cli 
  printf("%-32s: %s\n", "progName",           progName)             // if sourceName is defined, else mainName
  printf("%-32s: %s\n", "progPath",           progPath)             // full path
  printf("%-32s: %s\n", "sourceName",         sourceName)           // a property defined by scala-cli 
  printf("%-32s: %s\n", "sourcePath",         sourcePath)           // a property defined by scala-cli 
  printf("%-32s: %s\n", "mainName",           mainName)             // main object name
  printf("%-32s: %s\n", "progCmnd",           progCmnd)             // based on sys.prop `sun.java.command`

def sourceName = Option(sys.props("scala.source.names")).getOrElse("?")
def sourcePath = Option(sys.props("scala.sources")).getOrElse("?")
def sourcePathRelative = relativePath(sourcePath)

def mainName = this.getClass.getName
  .replaceAll(".*[.]", "")
  .replaceAll("[$].*", "")

def relativePath(pathstr: String): String = {
  if (pathstr == "?") then pathstr else
    val p = Paths.get(pathstr)
    userDir.relativize(p.toAbsolutePath.normalize).toString.replace('\\', '/')
}
def userDir: Path = Paths.get(sys.props("user.dir")).toAbsolutePath.normalize

def progCmnd: String = sys.props("sun.java.command").split("\\s+")(0).replaceAll(".*[.]", "").replace("_sc", ".sc")

def showLimitedStack: Unit = {
  val elems: Array[StackTraceElement] =
    Thread.currentThread.getStackTrace.filter(_.toString.toLowerCase.contains(progName.toLowerCase))
  val t = new Throwable()
  t.setStackTrace(elems)
  t.printStackTrace()        // prints in the exact JVM format
}