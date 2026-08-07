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
  def userFor(isWindows: Boolean): UserInfo =
    if isWindows then UserInfo("liam", "C:/Persons/liam", "C:/munit/test")
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
    // Interior separators and dot segments. `Paths.get` normalises some of these
    // and leaves others alone, and which is which is exactly what the Rust port
    // has to reproduce — it has no `Paths.get` to lean on.
    "a//b", "a/./b", "a/../b",
    "/usr//bin", "/usr/./bin", "/usr/../bin",
    "/usr/bin/.", "/usr/bin/..",
  )

  /** Drive letters to pin `driveCwd` against. */
  val drives: Seq[Char] = Seq('C', 'F', 'Z')

  /** The `Path` extension methods, keyed by fixture field name.
   *
   *  Each goes through `Paths.get` first, so these pin the whole chain — mount
   *  resolution *and* the `java.nio.file.Path` normalisation layered on top. The
   *  Rust port has no `Paths.get` doing that second part, so it has to reproduce
   *  it explicitly; without these fields nothing would catch it failing to. */
  val extFields: Seq[(String, String => String)] = Seq(
    "posx"      -> (s => Paths.get(s).posx),
    "local"     -> (s => Paths.get(s).local),
    "localpath" -> (s => Paths.get(s).localpath),
    "dospath"   -> (s => Paths.get(s).dospath),
    "nodrive"   -> (s => Paths.get(s).noDrive),
    "last"      -> (s => Paths.get(s).last),
    "basename"  -> (s => Paths.get(s).baseName),
    "ext"       -> (s => Paths.get(s).ext),
    "dotsuffix" -> (s => Paths.get(s).dotsuffix),
    "revpath"   -> (s => Paths.get(s).reversePath),
    "segments"  -> (s => Paths.get(s).segments.mkString(",")),
  )

  /* Three methods are deliberately absent, all for the same reason: their result
   * depends on the machine that generated the fixture, so pinning them here would
   * commit this checkout's paths and fail everywhere else.
   *
   *   abs      branches on `Files.exists(p)`.
   *   stdpath  calls `p.toAbsolutePath`, which resolves against the JVM's real
   *            `user.dir` — Java knows nothing about the injected config — so any
   *            relative input picks up the real working directory.
   *   relpath  is `standardizePath(relativePathToCwd(p))`, and those two disagree
   *            about which directory is current: the first uses `config.userdir`,
   *            the second the real one. Visible in a single pair of values —
   *            `stdpath "."` gave the injected `/c/munit/test` while `relpath "."`
   *            gave the real `/c/Users/.../uni`.
   *
   * The Rust ports are covered by unit tests using absolute inputs, where no
   * working directory is consulted and the answer is deterministic. */

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
    // Both blocks from one run, on any host: `config.isWindows` is injected rather
    // than read from `os.name`, so the Windows rule set is reachable from Linux and
    // macOS. This used to emit only the host's own block, which left Linux and macOS
    // with no fixture at all and `PathParitySuite` failing on a missing file.
    for (platform, isWindows) <- Seq("windows" -> true, "posix" -> false) do
      generate(dir, platform, isWindows)
    resetConfig()

  private def generate(dir: String, platform: String, isWindows: Boolean): Unit =
    val user = userFor(isWindows)
    val sb = StringBuilder()
    sb ++= header(platform)
    sb ++= s"platform | $platform\n"
    sb ++= s"user | ${user.name} | ${user.home} | ${user.dir}\n"

    for (id, lines) <- tables do
      for line <- lines do
        sb ++= s"table | $id | $line\n"

    for (id, lines) <- tables do
      withMountLines(lines, user, isWindows)
      // Derived table facts first — a mismatch here explains every case below it.
      sb ++= s"derived | $id | cygdrive | ${esc(config.cygdrive)}\n"
      sb ++= s"derived | $id | msysroot | ${esc(config.msysRoot)}\n"
      // `drivecwd` is recorded for the Windows block only, and it is the one field
      // that still depends on the generating host. `PathsConfig.driveCwd` returns a
      // `java.nio.file.Path`, which renders through the host's own FileSystem -- so
      // `Paths.get("F:/")` is the root `F:\` on Windows but the relative name `F:` on
      // Linux, and no rule injection changes that. Under POSIX rules the concept is
      // meaningless anyway: there are no drives.
      if isWindows && isWindows == isWin then
        for d <- drives do
          sb ++= s"case | $id | drivecwd | $d | ${attempt(config.driveCwd(d).toString)}\n"
      for in <- inputs do
        sb ++= s"case | $id | classify | ${esc(in)} | ${attempt(Resolver.classify(in).toString)}\n"
        sb ++= s"case | $id | win      | ${esc(in)} | ${attempt(Resolver.resolvePathstr(in))}\n"
        sb ++= s"case | $id | posixabs | ${esc(in)} | ${attempt(toPosixAbs(in))}\n"
        // Extension methods go through `Paths.get`, so they render through the
        // *host's* java.nio FileSystem -- `Paths.get("/munit/test").toString` is
        // `\\munit\\test` on Windows whatever rule set is injected, and
        // `Paths.get("C:").getFileName` is null there but "C:" on Linux. Injecting
        // `isWindows` cannot reach that, so these rows are recorded only for the block
        // matching the generating host. classify/win/posixabs above are pure string
        // work and are recorded for both.
        if isWindows == isWin then
                  for (field, f) <- extFields do
            sb ++= s"case | $id | $field | ${esc(in)} | ${attempt(f(in))}\n"
      println(s"  $id: ${inputs.length} inputs × 3 fields + ${drives.length} drives")

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
