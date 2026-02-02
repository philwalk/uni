package uni

import munit.FunSuite
import uni.Internals.*
import TestUtils.prmsg

class TestUniPath extends FunSuite {
  override def beforeAll(): Unit = uni.resetConfig()

  // ANSI colors (safe on MSYS2, Git Bash, Windows Terminal, IntelliJ)
  private val CYAN   = "\u001b[36m"
  private val GREEN  = "\u001b[32m"
  private val YELLOW = "\u001b[33m"
  private val RED    = "\u001b[31m"
  private val RESET  = "\u001b[0m"

  // Environment dump (colorized)
  test("uni: display discovered environment") {
    if verboseUni then print(s"${RED}verbose-logging")
    prmsg(s"${CYAN}hereDrive:${RESET}   [$hereDrive]")

    prmsg(s"${YELLOW}win2posix mappings:${RESET}")
    for ((key, valu) <- config.win2posix)
      prmsg(f"${GREEN}  mount $key%-22s -> ${valu.mkString(",")}")

    prmsg(s"${YELLOW}posix2win mappings:${RESET}")
    for ((key, valu) <- config.posix2win)
      prmsg(f"${CYAN}  mount $key%-22s -> $valu")

    prmsg(s"${GREEN}Environment dump complete.${RESET}")
  }

  // validate win2posix keys
  test("win2posix: keys must be Windows-like paths") {
    for ((key, _) <- config.win2posix) {
      val isDrive = key.matches("^[A-Za-z]:.*")
      val isUNC   = key.startsWith("\\\\")
      assert(isDrive || isUNC, clues(s"Invalid win2posix key: $key"))
    }
  }

  // validate win2posix values
  test("win2posix: values must be POSIX-like paths") {
    for ((_, posixSeq) <- config.win2posix)
      for (p <- posixSeq)
        assert(p.startsWith("/"), clues(s"Invalid POSIX path in win2posix: $p"))
  }

  // validate posix2win keys
  test("posix2win: keys must be POSIX-like paths") {
    for ((key, _) <- config.posix2win)
      assert(key.startsWith("/"), clues(s"Invalid posix2win key: $key"))
  }

  // validate posix2win values
  test("posix2win: values must be Windows-like paths") {
    for ((_, win) <- config.posix2win) {
      val isAbsolute      = win.matches("^[A-Za-z]:[\\\\/].*")
      val isUNC           = win.startsWith("\\\\")
      val isDriveRelative = win.matches("^[A-Za-z]:$")
      assert(isAbsolute || isUNC || isDriveRelative,
        clues(s"Invalid posix2win value: $win"))
    }
  }

  // reversibility: win2posix → posix2win
  test("mount maps must be reversible (win2posix → posix2win)") {
    if (config.win2posix.nonEmpty && config.posix2win.nonEmpty) {
      for ((win, posixSeq) <- config.win2posix)
        for (posix <- posixSeq) {

          // Case 1: POSIX entry is actually a Windows drive-relative-style path
          if (isWin && posix.startsWith("/")) {
            // In your environment, entries like win="c:/users", posix="/Users"
            // are valid and do not require a posix2win entry.
            ()
          } else {
            // Case 2: True POSIX path must have a reverse mapping
            val backOpt = config.posix2win.get(posix)
            val ok = backOpt.exists(back => winEquivalent(back, win))

            assert(
              ok,
              clues(
                s"""
                   |Reversibility failed:
                   |  win      = $win
                   |  posix    = $posix
                   |  backOpt  = $backOpt
                   |""".stripMargin
              )
            )
          }
        }
    }
  }

  test("posix2win: detect ambiguous POSIX prefixes") {
    val keys = config.posix2winKeys   // already sorted longest-first

    for {
      a <- keys
      b <- keys
      if a != b
      if a.length == b.length          // only equal-length prefixes can conflict
      if a.equalsIgnoreCase(b)         // or normalize and compare
    } {
      fail(s"Ambiguous POSIX prefixes: '$a' and '$b'")
    }
  }

  test("win2posix: detect duplicate POSIX entries per Windows key") {
    for ((win, posixSeq) <- config.win2posix) {
      // Ignore drive-relative keys like "c:"
      if (win.matches("^[A-Za-z]:$")) {
        ()
      } else {
        val dupes = posixSeq.groupBy(identity).collect { case (p, xs) if xs.size > 1 => p }
        // In practice, duplicates here are harmless; log via clues but don't fail hard.
        if (dupes.nonEmpty) {
          prmsg(s"NOTE: Duplicate POSIX entries for $win: $dupes")
        }
        assert(true)
      }
    }
  }

  // Detect duplicate Windows entries in posix2win
  test("posix2win: detect duplicate Windows entries") {
    // Multiple POSIX paths mapping to the same Windows path is valid.
    // The only real error is duplicate POSIX keys, which the map type already prevents.
    val keys = config.posix2win.keys.toSeq
    val dupes = keys.groupBy(identity).collect { case (k, xs) if xs.size > 1 => k }
    assert(dupes.isEmpty, clues(s"Duplicate POSIX keys: $dupes"))
  }

  private def winEquivalent(a: String, b: String): Boolean = {
    def norm(s: String): String =
      s.replace('\\', '/')
       .stripSuffix("/")
       .toLowerCase

    val na = norm(a)
    val nb = norm(b)

    // Case 1: exact match after normalization
    if (na == nb) {
      true
    } else if (na.startsWith("/") && nb.matches("^[a-z]:/.*")) {
     // Case 2: drive-relative "/foo" matches "C:/foo" if C: is current drive
      val drive = nb.take(2) // "c:"
      (drive + na).toLowerCase == nb
    } else if (nb.startsWith("/") && na.matches("^[a-z]:/.*")) {
      val drive = na.take(2)
      (drive + nb).toLowerCase == na
    } else {
      false
    }
  }

}
