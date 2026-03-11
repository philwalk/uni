package uni.cli

import munit.FunSuite
import java.io.{ByteArrayOutputStream, PrintStream}
import ArgsParser.{consumeInt, consumeLong}

/** Covers ArgsParser branches not exercised by ArgCtxSuite or ArgsParserSuite. */
class ArgsParserCoverageSuite extends FunSuite {

  override def beforeAll(): Unit = uni.resetConfig()

  // Intercept sys.exit by swapping out exitFn
  case class ExitCalled(code: Int) extends Throwable

  private def captureStderr[A](body: => A): (String, Int) =
    val origErr  = System.err
    val baos     = new ByteArrayOutputStream()
    System.setErr(new PrintStream(baos, true))
    val origExit = ArgsParser.exitFn
    ArgsParser.exitFn = code => throw ExitCalled(code)
    try
      body
      fail("exitFn was not called")
    catch
      case ExitCalled(code) =>
        (baos.toString("UTF-8"), code)
    finally
      System.setErr(origErr)
      ArgsParser.exitFn = origExit

  // ============================================================================
  // showUsage — empty-message branch (m.nonEmpty is false → no message printed)
  // ============================================================================

  test("showUsage: empty message omits message line, still prints usage:") {
    val (out, code) = captureStderr { showUsage() }
    assertEquals(code, 1)
    assert(!out.contains("\n\n"), "should not print blank line for empty message")
    assert(out.contains("usage:"), s"expected 'usage:' in: $out")
  }

  test("showUsage: list with empty strings filters them out") {
    val (out, code) = captureStderr { showUsage("err", "", "--foo", "") }
    assertEquals(code, 1)
    assert(out.contains("--foo"))
    // empty strings should not appear as standalone lines
    assertEquals(out.linesIterator.count(_.isBlank), 0)
  }

  // ============================================================================
  // nextInt / nextLong / nextDouble — invalid input triggers usage
  // ============================================================================

  test("nextInt: non-integer value triggers usage") {
    intercept[RuntimeException] {
      eachArg(Seq("-n", "abc"), msg => throw new RuntimeException(msg)) {
        case "-n" => nextInt
      }
    }
  }

  test("nextLong: non-long value triggers usage") {
    intercept[RuntimeException] {
      eachArg(Seq("-n", "xyz"), msg => throw new RuntimeException(msg)) {
        case "-n" => nextLong
      }
    }
  }

  test("nextDouble: non-double value triggers usage") {
    intercept[RuntimeException] {
      eachArg(Seq("-n", "notanumber"), msg => throw new RuntimeException(msg)) {
        case "-n" => nextDouble
      }
    }
  }

  // ============================================================================
  // consumeInt / consumeLong — aliases for nextInt / nextLong
  // ============================================================================

  test("consumeInt: parses integer correctly") {
    var v = 0
    eachArg(Seq("-n", "7"), _ => fail("unexpected usage")) {
      case "-n" => v = consumeInt
    }
    assertEquals(v, 7)
  }

  test("consumeLong: parses long correctly") {
    var v = 0L
    eachArg(Seq("-n", "9876543210"), _ => fail("unexpected usage")) {
      case "-n" => v = consumeLong
    }
    assertEquals(v, 9876543210L)
  }

  // ============================================================================
  // thisArg — accessed within eachArg
  // ============================================================================

  test("thisArg: returns the current argument being processed") {
    var seen = ""
    eachArg(Seq("--flag"), _ => fail("unexpected usage")) {
      case "--flag" => seen = thisArg
    }
    assertEquals(seen, "--flag")
  }

  // ============================================================================
  // helpers outside eachArg → IllegalStateException
  // ============================================================================

  test("thisArg outside eachArg throws IllegalStateException") {
    intercept[IllegalStateException] { thisArg }
  }

  test("peekNext outside eachArg throws IllegalStateException") {
    intercept[IllegalStateException] { peekNext }
  }

  test("nextInt outside eachArg throws IllegalStateException") {
    intercept[IllegalStateException] { nextInt }
  }

  test("nextLong outside eachArg throws IllegalStateException") {
    intercept[IllegalStateException] { nextLong }
  }

  test("nextDouble outside eachArg throws IllegalStateException") {
    intercept[IllegalStateException] { nextDouble }
  }

  // ============================================================================
  // progNameFromClassname — String branch and AnyRef branch
  // ============================================================================

  test("progNameFromClassname(String): strips package and suffix from string") {
    val name = ArgsParser.progNameFromClassname("com.example.MyApp$")
    // should strip "com.example." and "$" suffix
    assert(!name.contains("."), s"expected no dots, got: $name")
    assert(!name.contains("$"), s"expected no dollar, got: $name")
  }

  test("progNameFromClassname(AnyRef): uses class name, strips package") {
    val name = ArgsParser.progNameFromClassname(this)
    // Class name is something like "uni.cli.ArgsParserCoverageSuite"
    // After processing should be just the simple name
    assert(!name.contains("."), s"expected no dots, got: $name")
    assert(name.nonEmpty)
  }

  // ============================================================================
  // empty args list — withArgs / eachArg runs with no iterations
  // ============================================================================

  test("eachArg: empty args list completes without calling pf or usage") {
    eachArg(Seq.empty, _ => fail("usage called unexpectedly")) { case x => fail(s"pf called with $x") }
  }
}
