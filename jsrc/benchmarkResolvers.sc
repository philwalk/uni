#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.5.0

import uni.*
import uni.fs.*

// config is private[uni] ; need to locally publish a version with
// relaxed privacy to avoid compiler errors below.
object BenchmarkResolver {

  def main(args: Array[String]): Unit = {
    val testdirs = if (args.isEmpty){
      printf("usage: %s [@<testPathsFile>] | <path1> [<path2> ...]]\n", sys.props("scala.source.names"))
      sys.exit(1)
    } else if (args.length == 1 && args(0).startsWith("@")) {
      scala.io.Source.fromFile(args(0).tail).getLines.toList
    } else {
      args.toSeq
    }
    val loops: Int = 1e8.toInt / testdirs.length
    for (testdir <- testdirs) {
      val f1 = PrefixResolver.mountPrefix(testdir)
      val f2 = PrefixResolverRecursive.mountPrefix(testdir)
      assert(f1 == f2)
      if (f1.nonEmpty) {
        val p = Paths.get(f1.get)
        assert(p.exists)
        printf("%s, %s\n", testdir, p.posx)
      }
    }

    args.toSeq
    def snap: Long = System.currentTimeMillis
    def elapsedSecs(t0: Long): Double = (snap - t0).toDouble/1000.0
    printf("benchmark resolving of %d posix paths\n", loops * testdirs.size)

    val t0 = snap
    for (i <- 0 until loops) {
      for (testdir <- testdirs) {
        val _ = PrefixResolver.mountPrefix(testdir)
      }
    }
    printf("# non-recursive calls: elapsed: %s\n", elapsedSecs(t0))
    val t1 = snap
    for (i <- 0 until loops) {
      for (testdir <- testdirs) {
        val _ = PrefixResolverRecursive.mountPrefix(testdir)
      }
    }
    printf("# recursive calls:     elapsed: %s\n", elapsedSecs(t1))
  }

  object PrefixResolver {
    // Cache the candidate keys once, lowercased for comparison
    private lazy val candidates: List[String] =
      // config is private[uni] ; compiler error
      config.posix2win.keysIterator.map(_.toLowerCase).toList

    def mountPrefix(path: String): Option[String] = {
      val s = path.toLowerCase.stripSuffix("/")

      // Scan once, track longest match
      var best: String = null
      var bestLen = -1

      for (k <- candidates) {
        if (s.startsWith(k) &&
            (s.length == k.length || {
              val next = s.charAt(k.length)
              next == '/' || next == '\\' || next == ':'
            })) {
          if (k.length > bestLen) {
            best = k
            bestLen = k.length
          }
        }
      }

      if (best != null) Some(best) else None
    }
  }

  object PrefixResolverRecursive {
    // Cache candidates once, lazily
    private lazy val candidates: List[String] =
      // config is private[uni] ; compiler error
      config.posix2win.keysIterator.map(_.toLowerCase).toList

    def mountPrefix(path: String): Option[String] = {
      val s = path.toLowerCase.stripSuffix("/")

      import scala.annotation.tailrec
      @tailrec def loop(best: Option[String], rest: List[String]): Option[String] = rest match {
        case Nil => best
        case h :: t =>
          val nb =
            if (s.startsWith(h) &&
                (s.length == h.length || {
                  val next = s.charAt(h.length)
                  next == '/' || next == '\\' || next == ':'
                })) {
              best match {
                case Some(b) => if (h.length > b.length) Some(h) else best
                case None    => Some(h)
              }
            } else best
          loop(nb, t)
      }

      loop(None, candidates)
    }
  }
}
