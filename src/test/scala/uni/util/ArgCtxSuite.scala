package uni

import munit.*
import java.io.{ByteArrayOutputStream, PrintStream}
import uni.cli.*

class ArgCtxSuite extends FunSuite {
  override def beforeAll(): Unit = uni.resetConfig()

  // uncomment the next 2 lines to disable timeout
  import scala.concurrent.duration.*
  override def munitTimeout: Duration = Duration.Inf

  // Test: showUsage respects an explicitly provided contextual programName
  test("showUsage uses explicitly provided programName contextual argument") {
    val (out, code) = captureStderr {
      showUsage("oops", "--x")
    }

    assertEquals(code, 1)
    assert(out.contains("oops"))
  }
  
  // Test: showUsage uses defaultProgramName when no contextual given is provided
  test("showUsage uses defaultProgramName and prints usage") {
    val (out, code) = captureStderr {
      showUsage("bad arg", "--foo", "--bar")
    }

    assertEquals(code, 1)
    assert(out.contains("bad arg"))
    assert(out.contains("usage:"))
    assert(out.contains("--foo"))
    assert(out.contains("--bar"))
  }

  // Test: eachArg dispatches correctly
  test("eachArg dispatches matching arguments in order") {
    val seen = scala.collection.mutable.ArrayBuffer.empty[String]

    eachArg(Seq("--a", "--b"), _ => fail("usage called unexpectedly")) {
      case "--a" => seen += "A"
      case "--b" => seen += "B"
    }

    assertEquals(seen.toList, List("A", "B"))
  }

  // Test: eachArg calls usage on unknown argument
  test("eachArg calls usage on unknown argument") {
    val (out, code) = captureStderr {
      eachArg(Seq("--good", "--bad"), msg => showUsage(msg)) {
        case "--good" => ()
      }
    }

    assertEquals(code, 1)
    assert(out.contains("unknown argument [--bad]"))
  }

  // Test: nextInt / nextLong / nextDouble work through exported API
  test("nextInt / nextLong / nextDouble parse correctly") {
    var ints = List.empty[Int]
    var longs = List.empty[Long]
    var doubles = List.empty[Double]

    eachArg(Seq("--i", "42", "--l", "99", "--d", "3.14"), msg => fail(msg)) {
      case "--i" => ints ::= nextInt
      case "--l" => longs ::= nextLong
      case "--d" => doubles ::= nextDouble
    }

    assertEquals(ints.reverse, List(42))
    assertEquals(longs.reverse, List(99L))
    assertEquals(doubles.reverse, List(3.14))
  }

  // Test: missing argument after flag triggers usage
  test("consumeNext triggers usage on missing argument") {
    val (out, code) = captureStderr {
      eachArg(Seq("--i"), msg => showUsage(msg)) {
        case "--i" => nextInt
      }
    }

    assertEquals(code, 1)
    assert(out.contains("missing argument after [--i]"))
  }

  case class ExitCalled(code: Int) extends Throwable

  // Utility: capture stdout and intercept sys.exit
  def captureStderr[A](body: => A): (String, Int) =
    val originalErr = System.err
    val baos = new ByteArrayOutputStream()
    val ps   = new PrintStream(baos, true)
    System.setErr(ps)

    val originalExit = ArgCtx.exitFn
    ArgCtx.exitFn = code => throw ExitCalled(code)

    try
      body
      fail("sys.exit was not called")
    catch
      case ExitCalled(code) =>
        ps.flush()
        (baos.toString("UTF-8"), code)
    finally
      System.setErr(originalErr)
      ArgCtx.exitFn = originalExit
}
