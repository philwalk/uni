#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.4.5

import uni.*
import uni.file.*

object BenchmarkResolver {

  def main(args: Array[String]): Unit = {
    val testdirs = if (args.isEmpty){
      testData
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
  def testData = Seq(
    "/c/Drivers",
    "/c/Apps",
    "/c/OneDriveTemp",
    "/c/Dell",
    "/c/$Windows.~WS",
    "/c/ESD",
    "/c/temp",
    "/c/Android",
    "/c/HP",
    "/c/Config.Msi",
    "/c/MATS",
    "/c/test",
    "/c/ff",
    "/c/System Repair",
    "/c/NVIDIA",
    "/c/jdk-test",
    "/c/hostedtoolcache",
    "/c/$Recycle.Bin",
    "/c/PerfLogs",
    "/c/ZIR",
    "/c/hadoop",
    "/c/texlive",
    "/c/Recovery",
    "/c/Log",
    "/c/inetpub",
    "/c/OpenBLAS",
    "/c/Users",
    "/c/rtools45",
    "/c/cygwin64",
    "/c/Program Files (x86)",
    "/c/.bsp",
    "/c/scan",
    "/c/msys64",
    "/c/f",
    "/c/Intel",
    "/c/data",
    "/c/Program Files",
    "/c/opt",
    "/c/ProgramData",
    "/c/Windows",
    "/c/",
    "/c/System Volume Information",
    "/c/tmp",
    "/Drivers",
    "/Apps",
    "/OneDriveTemp",
    "/Dell",
    "/$Windows.~WS",
    "/ESD",
    "/temp",
    "/Android",
    "/HP",
    "/Config.Msi",
    "/MATS",
    "/test",
    "/ff",
    "/System Repair",
    "/NVIDIA",
    "/jdk-test",
    "/hostedtoolcache",
    "/$Recycle.Bin",
    "/PerfLogs",
    "/ZIR",
    "/hadoop",
    "/texlive",
    "/Recovery",
    "/Log",
    "/inetpub",
    "/OpenBLAS",
    "/Users",
    "/rtools45",
    "/cygwin64",
    "/Program Files (x86)",
    "/.bsp",
    "/scan",
    "/msys64",
    "/f",
    "/Intel",
    "/data",
    "/Program Files",
    "/opt",
    "/ProgramData",
    "/Windows",
    "/",
    "/System Volume Information",
    "/tmp",
  )
}
