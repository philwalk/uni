#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.8.2

import uni.*
import java.nio.file.Files

var paths = Vector.empty[Path]

def usage(m: String = ""): Nothing = {
  showUsage(m, "",
    "<filename>    ; input",
  )
}

eachArg(args.toSeq, usage) {
  case fname if Paths.get(fname).exists =>
    paths :+= Paths.get(fname)
  case arg =>
    showUsage(s"unrecognized arg [$arg]")
}

paths.foreach { (arg: Path) =>
  printf("%s\n", realPath(arg))
}

def realPath(p: Path): String = {
  // Find deepest existing parent
  val existing =
    Iterator.iterate(p)(_.getParent)
      .takeWhile(_ != null)
      .find(Files.exists(_))

  // Compute the remaining tail BEFORE canonicalizing the prefix
  val remaining: Option[Path] =
    existing match
      case Some(prefix) =>
        val prefixCount = prefix.getNameCount
        val pCount      = p.getNameCount
        if prefixCount < pCount then
          Some(p.subpath(prefixCount, pCount))
        else
          None
      case None =>
        None

  // Canonicalize the prefix
  val resolvedPrefix =
    existing.map(_.toRealPath()).getOrElse(p.toAbsolutePath())

  // Reattach and normalize
  val finalPath =
    resolvedPrefix.resolve(remaining.mkString("/")).normalize()

  finalPath.toString.replace('\\', '/')
}
