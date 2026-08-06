package uni.apps

import uni.*

/**
 * Regenerates the cross-language parity fixture in `test-data/path-parity/` for
 * `uni.Paths.get` and its Rust port (`rust/src/upath/`).
 *
 * Consumed by two tests that must agree with each other:
 *   - uni.PathParitySuite            (Scala, src/test)
 *   - rust/tests/path_parity.rs      (Rust)
 *
 * Neither needs the other language installed, because both compare against the
 * committed reference rather than against each other.
 *
 * Every case is driven by an explicit synthetic mount table and a fake user, via
 * `withMountLines`, so nothing depends on which drives this machine has or which
 * ones it has visited. That is what makes the expectations portable.
 *
 * **The fixture is tagged with the platform that produced it.** `isWin` comes from
 * `os.name` and cannot be overridden, so a run on Windows records the Windows
 * rules and a run on Linux or macOS records the pass-through rules. The Rust side
 * has no such limit — it takes `is_windows` as data — so a single Rust test run
 * checks whichever platform's block is present, from any host. Regenerate on a
 * second platform to add its block.
 *
 * Run ONLY when the reference is meant to move — regenerating rewrites the very
 * values the tests check, so an unintended run masks a regression instead of
 * catching it. Review the diff before keeping it.
 *
 * Run:  sbt "runMain uni.apps.PathParityGen"
 */
object PathParityGen:
  def println(s: String = ""): Unit = print(s"$s\n")

  /** Fake user shared by every case. Matches `TestUtils.windowsTestUser` so the
   *  fixture lines up with the existing suites; the POSIX variant drops the drive,
   *  as `TestUtils.unixTestUser` does. */
  val user: UserInfo =
    if isWin then UserInfo("liam", "C:/Persons/liam", "C:/munit/test")
    else UserInfo("liam", "/Persons/liam", "/munit/test")

  /** Mount tables, each chosen to pin one thing the resolution has to get right. */
  val tables: Seq[(String, Seq[String])] = Seq(
    "root" -> Seq(
      "C:/msys64 on / type ntfs (binary)"),
    "drives" -> Seq(
      "C:/msys64 on / type ntfs (binary)",
      "C:/ on /c type ntfs (binary)",
      "D:/ on /d type ntfs (binary)"),
    "cygdrive" -> Seq(
      "none on /cygdrive type ntfs (binary)",
      "C:/cygwin64 on / type ntfs (binary)"),
    // The discriminator for longest-prefix matching: a first-segment lookup
    // resolves /opt/ue/src under /opt and never reaches D:/ue.
    "overlap" -> Seq(
      "C:/msys64 on / type ntfs (binary)",
      "C:/opt on /opt type ntfs (binary)",
      "D:/ue on /opt/ue type ntfs (binary)"),
    // One Windows directory reachable under two POSIX names; the reverse direction
    // must pick the first, so fstab order is load-bearing.
    "onetomany" -> Seq(
      "C:/msys64 on / type ntfs (binary)",
      "C:/Users on /Users type ntfs (binary)",
      "C:/Users on /home type ntfs (binary)"),
    // fstab column format, and no root entry — exercises the synthetic root.
    "fstab" -> Seq(
      "C:/opt /opt ntfs binary 0 0",
      "F:/weekly /weekly ntfs binary 0 0"),
    // The cygdrive marker NOT in first position. Derivation used to read only the
    // first entry, so the prefix silently became "/" and `/cygdrive/...` resolved
    // through the leftover `none` device, emitting the name literally.
    "markerlate" -> Seq(
      "C:/cygwin64/usr/bin on /usr/bin type ntfs (binary)",
      "none on /cygdrive type ntfs (binary)",
      "C:/cygwin64 on / type ntfs (binary)"),
    // Same, but the prefix is declared by a drive-root mount rather than `none`.
    "driverootlate" -> Seq(
      "C:/cygwin64/usr/bin on /usr/bin type ntfs (binary)",
      "C: on /cygdrive/c type ntfs (binary)",
      "C:/cygwin64 on / type ntfs (binary)"),
    // A verbatim slice of MSYS2's shipped /etc/fstab, comments included. The
    // commented-out example mounts have no space after the `#`, so an unfiltered
    // parse turns them into live entries whose Windows side starts with '#' —
    // which then makes JPaths.get throw on ordinary inputs like /c/Users.
    "fstabcomments" -> Seq(
      "# C:/msys64/etc/fstab",
      "# For a description of the file format, see the Users Guide",
      "# https://cygwin.com/cygwin-ug-net/using.html#mount-table",
      "",
      "#C:/cygwin64 / ntfs binary,noacl,auto",
      "#C:/cygwin64/usr/bin /bin ntfs binary,noacl,auto",
      "",
      "# DO NOT REMOVE NEXT LINE. It removes cygdrive prefix from path",
      "none /         cygdrive binary,posix=0,noacl,user 0 0",
      "#none /mnt      cygdrive binary,posix=0,noacl,user 0 0",
      "C:/opt /opt ntfs binary,user 0 0",
      "C:/tmp /tmp ntfs binary,user 0 0"),
  )

  /** Inputs spanning all seven path kinds plus the expansion cases. */
  val inputs: Seq[String] = Seq(
    "/",                                  // Root
    "/usr/bin/bash", "/usr/bin/",         // Posix, and a trailing slash
    "/c", "/c/", "/c/Users",              // drive mount
    "/d/data",
    "/opt", "/opt/ue", "/opt/ue/src",     // overlapping mounts
    "/Users/liam", "/home/liam",          // one-to-many reverse
    "/q/file",                            // unmapped drive → synthetic entry
    "/weekly/x",
    "/cygdrive/c/tmp",
    "C:/Windows", "c:/windows/system32",  // Absolute
    "C:", "F:", "F:config/bin",           // DriveRel, bare and with a suffix
    "//server/share",                     // UNC
    "file:///c/tmp",                      // Invalid
    ".", "..", "./x", "../x",             // dot expansion
    "~", "~/sub",                         // home expansion
    ".gitignore",                         // hidden file keeps its dot
    "bare.txt", "a/b",                    // bare name vs relative path
    "",                                   // empty → userdir
  )

  /** Drive letters to pin `driveCwd` against. */
  val drives: Seq[Char] = Seq('C', 'F', 'Z')

  private def attempt(f: => String): String =
    try
      val s = f
      if s.isEmpty then "!empty" else s
    catch case _: Throwable => "!error"

  private def esc(s: String): String = if s.isEmpty then "!empty" else s

  def main(args: Array[String]): Unit =
    val root = sys.props.getOrElse("user.dir", ".")
    val dir  = s"$root/test-data/path-parity"
    java.nio.file.Files.createDirectories(dir.asPath)

    val platform = if isWin then "windows" else "posix"
    val sb = StringBuilder()
    sb ++= header(platform)
    sb ++= s"platform | $platform\n"
    sb ++= s"user | ${user.name} | ${user.home} | ${user.dir}\n"

    for (id, lines) <- tables do
      for line <- lines do
        sb ++= s"table | $id | $line\n"

    for (id, lines) <- tables do
      withMountLines(lines, user)
      // Derived table facts first — a mismatch here explains every case below it.
      sb ++= s"derived | $id | cygdrive | ${esc(config.cygdrive)}\n"
      sb ++= s"derived | $id | msysroot | ${esc(config.msysRoot)}\n"
      for d <- drives do
        // Native form, no slash normalisation: `applyTildeAndDots` interpolates
        // this Path verbatim, so a bare `C:` input keeps Windows backslashes while
        // `resolveDriveRelPathstr` normalises separately. Recording the normalised
        // form here would hide that split.
        sb ++= s"case | $id | drivecwd | $d | ${attempt(config.driveCwd(d).toString)}\n"
      for in <- inputs do
        sb ++= s"case | $id | classify | ${esc(in)} | ${attempt(Resolver.classify(in).toString)}\n"
        sb ++= s"case | $id | win      | ${esc(in)} | ${attempt(Resolver.resolvePathstr(in))}\n"
        sb ++= s"case | $id | posixabs | ${esc(in)} | ${attempt(toPosixAbs(in))}\n"
      println(s"  $id: ${inputs.length} inputs × 3 fields + ${drives.length} drives")
    resetConfig()

    val out = s"$dir/scala-reference-$platform.txt"
    java.nio.file.Files.writeString(out.asPath, sb.toString)
    println(s"wrote $out")

  private def header(platform: String): String =
    s"""|# uni.Paths cross-language parity reference — platform: $platform
        |# Regenerate with: sbt "runMain uni.apps.PathParityGen"
        |#
        |# Checked by uni.PathParitySuite and rust/tests/path_parity.rs.
        |# Fields are ' | '-separated; paths never contain '|', mount lines contain spaces.
        |#   platform | windows|posix        -- which rule set this file records
        |#   user     | name | home | dir    -- the fake user every case resolves against
        |#   table    | <id> | <mount line>  -- in order; repeated per line
        |#   derived  | <id> | cygdrive|msysroot | <value>
        |#   case     | <id> | classify|win|posixabs | <input> | <expected>
        |#   case     | <id> | drivecwd | <drive> | <expected>
        |# '!error' means the call threw; '!empty' means an empty string.
        |#
        |# isWin is not overridable from Scala, so this file records one platform's
        |# rules. The Rust port takes is_windows as data and checks whichever blocks
        |# are present, from any host.
        |""".stripMargin
