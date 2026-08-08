package uni.apps

import uni.*

/**
 * Warns when a freshly written parity fixture will not be committed.
 *
 * # The failure this closes
 *
 * Every parity fixture is read by BOTH a Scala suite and a Rust test, and the point is that
 * neither needs the other language installed. `.gitignore` excludes everything under
 * `test-data` by wildcard, with a negation for fixture directories, so a directory that does
 * not match the pattern is silently ignored — and that fails in the worst available way. The
 * file exists locally, so every test passes for whoever generated it; the fixture is simply
 * absent for everyone else, and their suite fails on a missing file with no hint as to why.
 *
 * Nothing else in the pipeline can catch it. The suites already fail loudly when a fixture is
 * *missing*, which is exactly the case that does not arise locally. Generation is the only
 * moment where the omission is both present and detectable, which is why the check lives
 * here rather than in a test.
 *
 * # Why it never fails the run
 *
 * A generator has already done its work by the time this is called, and rewriting a fixture
 * is the expensive, reviewed step. Aborting would discard it over a `.gitignore` line. So
 * this warns on stderr and returns; the operator sees it next to the "wrote N rows" line.
 *
 * Absent or unusable `git` is also not a failure: fixtures are legitimately regenerated
 * inside a source tarball or a container without git, and a warning there would be noise
 * rather than signal. The check reports only what it can establish.
 */
object FixtureGuard:
  private def eprintln(s: String): Unit = System.err.print(s"$s\n")

  /**
   * Warns if `path` is excluded by `.gitignore`.
   *
   * Pass the fixture *directory* rather than a single file: a generator that writes several
   * (`Tprf3ParityGen` writes CSV operands beside its reference, `CsvParityGen` an `inputs/`
   * tree) would otherwise check one and miss the rest, and it is the directory that the
   * negation pattern actually admits or excludes.
   *
   * Call it *after* the directory exists, which is naturally where a generator ends up. The
   * negation carries a trailing slash and so matches directories only, and git cannot tell
   * that a path which does not exist yet would be one — it would report the blanket exclusion
   * and warn about a fixture that is in fact fine.
   */
  def warnIfIgnored(path: java.nio.file.Path): Unit =
    if isIgnored(path).contains(true) then
      eprintln("")
      eprintln(s"WARNING: [${path.posx}] is excluded by .gitignore, so this fixture will NOT")
      eprintln("  be committed. It will pass here and fail for everyone else, on a missing file.")
      eprintln("  Fix: name the directory `<name>-parity`, which .gitignore admits by pattern,")
      eprintln("  or add an explicit `!` exception. Verify with:")
      eprintln(s"    git check-ignore -v ${path.posx}")
      eprintln("  which prints nothing once the path will be committed.")

  /**
   * Whether git excludes `path`: `Some(true)` ignored, `Some(false)` not, `None` when it
   * cannot be established.
   *
   * Split out from the warning so the decision is testable without capturing stderr — the
   * printing is incidental, this is the part that can be wrong.
   *
   * `git check-ignore` exits **0 when the path IS ignored** and 1 when it is not, which is
   * inverted from the usual convention and easy to get backwards; 128 means git errored.
   * `None` covers a missing git, a non-repository, and that error case alike: fixtures are
   * legitimately regenerated inside a source tarball or a container without git, where a
   * warning would be noise rather than signal.
   *
   * Note `check-ignore` consults the index, so a path that is already **tracked** is reported
   * as not ignored whatever the patterns say. That is the right answer — a tracked fixture
   * will be distributed regardless — and it means this can only ever fire for a fixture that
   * has not been committed yet, which is precisely the case it exists for.
   */
  def isIgnored(path: java.nio.file.Path): Option[Boolean] =
    scala.util.Try(Proc.run("git", "check-ignore", "-q", path.posx)).toOption
      .map(_.status)
      .collect { case 0 => true; case 1 => false }
