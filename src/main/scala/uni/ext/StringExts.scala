package uni.ext

import uni.*
import java.nio.file.Path
import java.io.{File as JFile}

/** String Extension methods */
object stringExts {
  extension (str: String) {
    def path: Path = Paths.get(str)
    def asPath: Path = Paths.get(str)
    def toFile: JFile = Paths.get(str).toFile
    def posx: String = normalizePosix(str)
    def posix: String = posixAbs(str)

    def startsWithIgnoreCase(prefix: String): Boolean = startsWithUncased(str, prefix)
    def stripPrefixIgnoreCase(prefix: String): String = stripPrefixUncased(str, prefix)
    def stripPrefix(prefix: String): String =
      if str.startsWith(prefix) then str.substring(prefix.length)
      else str

    def local: String = {
      val forward = normalizePosix(str)
      val isPosix = forward.startsWith("/") //|| !forward.matches("(?i)^[a-z]:/.*")
      if (!isWin || !isPosix) {
        str
      } else {
        config.posix2win.collectFirst {
          case (posix, winSeq) if posix != "/" && str.startsWithIgnoreCase(posix) =>
            s"${winSeq.head}${str.stripPrefixIgnoreCase(posix)}"
        }.getOrElse {
          s"${config.msysRoot}$str"
        }
      }
    }
  }

  def startsWithUncased(str: String, prefix: String): Boolean = {
    str.regionMatches(true, 0, prefix, 0, prefix.length)
  }

  def stripPrefixUncased(str: String, prefix: String): String = {
    if startsWithUncased(str, prefix) then str.substring(prefix.length) else str
  }

}

