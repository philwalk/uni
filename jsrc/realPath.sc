#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.6.1
import uni.*
import uni.cli.*
import java.nio.file.Files

var paths = Vector.empty[Path]

eachArg(args, showUsage) {
  case fname if Paths.get(fname).exists =>
    paths :+= Paths.get(fname)
  case arg =>
    showUsage(s"unrecognized arg [$arg]")
}

paths.foreach {
  printf("%s\n", realpath(_))
}

def realpath(p: Path): String = {
  val p   = Paths.get(arg)

  // Find deepest existing parent
  val existing =
    Iterator.iterate(p)(_.getParent)
      .takeWhile(_ != null)
      .find(Files.exists(_))

  // Compute the remaining tail BEFORE canonicalizing the prefix
  val remaining =
    existing match
      case Some(prefix) =>
        val prefixCount = prefix.getNameCount
        val pCount      = p.getNameCount
        if prefixCount < pCount then
          p.subpath(prefixCount, pCount)
        else
          Paths.get("")
      case None =>
        Paths.get("") // nothing exists; whole path is "remaining"

  // Canonicalize the prefix
  val resolvedPrefix =
    existing.map(_.toRealPath()).getOrElse(p.toAbsolutePath())

  // Reattach and normalize
  val finalPath =
    resolvedPrefix.resolve(remaining).normalize()

  finalPath.toString.replace('\\', '/')
}
