package uni

import uni.*
import java.nio.file.Path
import java.io.{File as JFile}

/** String Extension methods */
object stringExts {
  extension (str: String) {
    def path: Path   = Paths.get(str)
    def asPath: Path = Paths.get(str)
    @deprecated("Use `path` or `asPath`", "uni") def toPath: Path = Paths.get(str)
    def toFile: JFile = Paths.get(str).toFile
    def absPath: Path = Paths.get(str).toAbsolutePath.normalize
    def posx: String  = normalizePosix(str)
    def posix: String = posixAbs(str)

    def lc: String = str.toLowerCase
    def uc: String = str.toUpperCase

    /** Drop the last `.ext` from a filename string. Hidden files (dot-first) are returned unchanged. */
    def dropSuffix: String = {
      val i = str.lastIndexOf('.')
      if i <= 0 then str else str.substring(0, i)
    }

    def startsWithIgnoreCase(prefix: String): Boolean = startsWithUncased(str, prefix)
    private def stripPrefixIgnoreCase(prefix: String): String = stripPrefixUncased(str, prefix)
    def stripPrefix(prefix: String): String =
      if str.startsWith(prefix) then str.substring(prefix.length)
      else str

    def local: String = {
      val forward = normalizePosix(str)
      val isPosix = forward.startsWith("/")
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

  private def startsWithUncased(str: String, prefix: String): Boolean = {
    str.regionMatches(true, 0, prefix, 0, prefix.length)
  }

  private def stripPrefixUncased(str: String, prefix: String): String = {
    if startsWithUncased(str, prefix) then str.substring(prefix.length) else str
  }

}
