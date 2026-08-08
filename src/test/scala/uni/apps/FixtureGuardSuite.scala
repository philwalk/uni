package uni.apps

import munit.FunSuite
import uni.*

/**
 * Checks that `FixtureGuard` reads git's exclusion correctly.
 *
 * The inversion is the thing worth pinning: `git check-ignore` exits **0 when the path IS
 * ignored** and 1 when it is not, which is backwards from every other exit-code convention
 * and would silently invert the whole guard if misread. A guard that answers "fine" for an
 * ignored fixture is worse than no guard, because it reads as verification.
 *
 * These tests are skipped rather than failed where git is unavailable or the tree is not a
 * repository — the guard is designed to stay silent in exactly that case, and asserting
 * otherwise would fail in source tarballs and bare containers for no benefit.
 */
class FixtureGuardSuite extends FunSuite:

  /** Whether git can answer at all here; `isIgnored` returns `None` when it cannot. */
  lazy val gitAvailable: Boolean =
    FixtureGuard.isIgnored(Paths.get("build.sbt")).isDefined

  test("a committed parity fixture is reported as NOT ignored") {
    assume(gitAvailable, "git unavailable or not a repository — the guard stays silent there")
    for dir <- Seq("csv-parity", "hash-parity", "date-parity", "path-parity", "tprf3-parity") do
      val p = Paths.get(s"test-data/$dir")
      assume(p.exists, s"$dir not generated in this tree")
      assertEquals(FixtureGuard.isIgnored(p), Some(false),
        s"[test-data/$dir] must be committable; check the .gitignore negation")
  }

  test("a path git does exclude is reported as ignored") {
    assume(gitAvailable, "git unavailable or not a repository")
    // `target/` is ignored in every Scala project and needs no fixture of its own.
    val p = Paths.get("target")
    assume(p.exists, "no target/ directory in this tree")
    assertEquals(FixtureGuard.isIgnored(p), Some(true),
      "target/ should be ignored; if this fails the exit-code reading is inverted")
  }

  test("the warning names the path, the consequence and the fix") {
    // Closes the loop on the output, not just the decision: a guard whose message does not say
    // what to do leaves the next person to rediscover the .gitignore pattern from scratch.
    assume(gitAvailable, "git unavailable or not a repository")
    val bad = Paths.get("test-data/guard-probe-not-a-fixture")
    val captured = new java.io.ByteArrayOutputStream()
    val saved = System.err
    try
      java.nio.file.Files.createDirectories(bad)
      System.setErr(new java.io.PrintStream(captured, true, "UTF-8"))
      FixtureGuard.warnIfIgnored(bad)
    finally
      System.setErr(saved)
      java.nio.file.Files.deleteIfExists(bad)
    val text = captured.toString("UTF-8")
    assert(text.contains("guard-probe-not-a-fixture"), s"should name the path: [$text]")
    assert(text.contains(".gitignore"), s"should name the cause: [$text]")
    assert(text.contains("fail for everyone else"), s"should name the consequence: [$text]")
    assert(text.contains("-parity"), s"should name the fix: [$text]")
  }

  test("a tracked fixture never warns, whatever the patterns say") {
    // `check-ignore` consults the index, so this holds even if the negation were removed. It
    // is also why the guard cannot be exercised against an already-committed fixture.
    assume(gitAvailable, "git unavailable or not a repository")
    val tracked = Paths.get("build.sbt")
    val captured = new java.io.ByteArrayOutputStream()
    val saved = System.err
    try
      System.setErr(new java.io.PrintStream(captured, true, "UTF-8"))
      FixtureGuard.warnIfIgnored(tracked)
    finally System.setErr(saved)
    assertEquals(captured.toString("UTF-8"), "", "a tracked path must produce no output")
  }

  test("a fixture directory not matching the pattern would be caught") {
    // The residual hole the guard exists for: `.gitignore` admits `test-data/*-parity/` by
    // pattern, so a directory named anything else is silently excluded.
    //
    // The directories have to be created, not merely named. The negation carries a trailing
    // slash, so it matches only directories -- and git cannot tell that a *non-existent* path
    // would be one, so it reports the bare `test-data/*` exclusion instead. That is harmless
    // for the guard, which runs after the generator has created the directory, but it makes
    // the difference between a real test and one that passes for the wrong reason.
    assume(gitAvailable, "git unavailable or not a repository")
    val bad = Paths.get("test-data/guard-probe-not-a-fixture")
    val good = Paths.get("test-data/guard-probe-parity")
    try
      java.nio.file.Files.createDirectories(bad)
      java.nio.file.Files.createDirectories(good)
      assertEquals(FixtureGuard.isIgnored(bad), Some(true),
        "a non-conforming name under test-data must be reported as ignored")
      assertEquals(FixtureGuard.isIgnored(good), Some(false),
        "a `*-parity` name must be admitted without editing .gitignore")
    finally
      java.nio.file.Files.deleteIfExists(bad)
      java.nio.file.Files.deleteIfExists(good)
  }
