package uni

import uni.*
import org.scalatest.BeforeAndAfter
import org.scalatest.funspec.AnyFunSpec
import org.scalatest.matchers.should.Matchers

class BenchmarkPrefix extends AnyFunSpec with Matchers with BeforeAndAfter {
  // current working directory is fixed at JVM startup time
  val workingDir = Paths.get(".").toAbsolutePath
  val cwdDrive   = workingDir.getRoot.toString.take(2)

  describe("Find-prefix recursive") {
    val testdirs = Seq("/opt", "/OPT", "/$RECYCLE.BIN", "/Program Files", "/etc")

    for (testdir <- testdirs) {
      it(s"should correctly resolve Windows rootRelative path [$testdir]") {
        val result1 = PrefixResolver.findPrefix(testdir)
        val result2 = PrefixResolverRecursive.findPrefix(testdir)
        assert(result1 == result2)
      }
    }
  }

  object PrefixResolverRecursive {
    // Cache candidates once, lazily
    private lazy val candidates: List[String] =
      config.posix2win.keysIterator.map(_.toLowerCase).toList

    def findPrefix(path: String): Option[String] = {
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
  object PrefixResolver {
    // Cache the candidate keys once, lowercased for comparison
    private lazy val candidates: List[String] =
      config.posix2win.keysIterator.map(_.toLowerCase).toList

    def findPrefix(path: String): Option[String] = {
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
}
