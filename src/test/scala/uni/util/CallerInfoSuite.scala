package uni.cli

import munit.FunSuite

/** Exercises the CallerInfo macro and case class (previously 0% covered). */
class CallerInfoSuite extends FunSuite:

  test("currentCaller: returns non-empty source file path") {
    val caller = currentCaller
    assert(caller.nonEmpty, "currentCaller should return a non-empty path")
  }

  test("currentCaller: path ends with a .scala file extension") {
    val caller = currentCaller
    assert(caller.endsWith(".scala"), s"expected .scala extension: $caller")
  }

  test("currentCallerInfo: line number is positive") {
    val info = currentCallerInfo
    assert(info.line > 0, s"line should be > 0, got ${info.line}")
  }

  test("currentCallerInfo: column is non-negative") {
    val info = currentCallerInfo
    assert(info.column >= 0, s"column should be >= 0, got ${info.column}")
  }

  test("currentCallerInfo: owner is non-empty") {
    val info = currentCallerInfo
    assert(info.owner.nonEmpty, "owner should be non-empty")
  }

  test("currentCallerInfo: path matches currentCaller") {
    val info   = currentCallerInfo
    val caller = currentCaller
    assertEquals(info.path, caller)
  }

  test("CallerInfo case class: fields are accessible and equal under copy") {
    val a = CallerInfo("file.scala", 10, 4, "MyObject")
    val b = a.copy(line = 20)
    assertEquals(a.path, "file.scala")
    assertEquals(b.line, 20)
    assertEquals(a.owner, b.owner)
  }
