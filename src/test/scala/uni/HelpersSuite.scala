package uni

import munit.FunSuite

/** Tests for `object helpers` — `isValidMsysPath` was 0% covered. */
class HelpersSuite extends FunSuite:

  // ============================================================================
  // helpers.round
  // ============================================================================

  test("round: default scale 6") {
    assertEquals(helpers.round(1.23456789), 1.234568)
  }

  test("round: explicit scale 2") {
    assertEquals(helpers.round(3.145, 2), 3.15)
  }

  test("round: zero rounds to zero") {
    assertEquals(helpers.round(0.0, 4), 0.0)
  }

  // ============================================================================
  // helpers.isValidMsysPath — exercises every branch
  // ============================================================================

  test("isValidMsysPath: bare '~' → true") {
    assert(helpers.isValidMsysPath("~"))
  }

  test("isValidMsysPath: '~/' prefix → true") {
    assert(helpers.isValidMsysPath("~/Documents/notes.txt"))
  }

  test("isValidMsysPath: tildeUser pattern (~user/path) → true") {
    assert(helpers.isValidMsysPath("~alice/repo/file"))
  }

  test("isValidMsysPath: POSIX absolute (starts with /) → true") {
    assert(helpers.isValidMsysPath("/usr/bin/bash"))
  }

  test("isValidMsysPath: POSIX root '/' alone → true") {
    assert(helpers.isValidMsysPath("/"))
  }

  test("isValidMsysPath: UNC-like '//' path → true (contains '/', is not a Windows path)") {
    // starts with "//" so the startsWith("/") && !startsWith("//") branch is false,
    // but it falls through to the t.contains("/") && !isValidWindowsPath(t) branch → true
    assert(helpers.isValidMsysPath("//server/share"))
  }

  test("isValidMsysPath: '/c/Users/liam' → true (hits startsWith('/') branch, not msysDrive)") {
    // startsWith("/") && !startsWith("//") is true, so returns true before msysDrive regex runs
    assert(helpers.isValidMsysPath("/c/Users/liam"))
  }

  test("isValidMsysPath: path with forward slash, not Windows → true") {
    assert(helpers.isValidMsysPath("src/main/scala"))
  }

  test("isValidMsysPath: Windows absolute path → false") {
    assert(!helpers.isValidMsysPath("C:/Windows/System32"))
  }

  test("isValidMsysPath: UNC Windows path → false") {
    assert(!helpers.isValidMsysPath("""\\server\share\file"""))
  }

  test("isValidMsysPath: plain word with no slash → false") {
    assert(!helpers.isValidMsysPath("plainword"))
  }

  test("isValidMsysPath: optValue pattern (contains = with / or ~) → true when not Windows") {
    // e.g. PATH=/usr/bin matches .*=[^=]*[/~].*
    assert(helpers.isValidMsysPath("PATH=/usr/bin"))
  }

  test("isValidMsysPath: optValue with Windows path on right → false") {
    // the right-hand side is a Windows path, so isValidWindowsPath returns true
    assert(!helpers.isValidMsysPath("PATH=C:/Windows/System32"))
  }

  test("isValidMsysPath: leading/trailing whitespace is trimmed") {
    assert(helpers.isValidMsysPath("  ~/dotfiles  "))
  }
