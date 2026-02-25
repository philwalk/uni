#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.0

import java.nio.file.Path

object strExts:
  extension(s: String)
    def posx: String = s.replace('\\', '/')

object pathExts:
  extension (p: Path)
    def posx: String = p.toString.replace('\\', '/')

object fs {
  export strExts.*
  export pathExts.*
} 

object Ov:
  extension (s: String)
    def stringPosx: String = "your override here"

import uni.*              // import both

extension (s: String) def posx = "your override here"

println("abc".posix)

val files: Seq[java.io.File] = java.nio.file.Paths.get(".").toFile.listFiles.toList

for f <- files.filter(_.isFile).take(5) do
  val p: Path = f.toPath
  if f.isFile then printf("%s\n", p.posix)