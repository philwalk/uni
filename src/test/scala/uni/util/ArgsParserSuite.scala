package uni.cli

import munit.FunSuite

class ArgsParserSuite extends FunSuite {

  override def beforeAll(): Unit = uni.resetConfig()

  private def failUsage(msg: String): Nothing =
    throw new RuntimeException(msg)

  test("consumeNext returns the next argument and advances the cursor") {
    var seen: String = ""

    eachArg(Seq("-x", "hello"), failUsage) {
      case "-x" => {
        seen = consumeNext
      }
    }

    assertEquals(seen, "hello")
  }

  test("peekNext returns the next argument without advancing the cursor") {
    var peeked: String = ""
    var consumed: String = ""

    eachArg(Seq("-x", "hello"), failUsage) {
      case "-x" => {
        peeked = peekNext
        consumed = consumeNext
      }
    }

    assertEquals(peeked, "hello")
    assertEquals(consumed, "hello")
  }

  test("peekNext does NOT call usage if there is no next argument") {
    var peeked: String = ""

    eachArg(Seq("-x"), failUsage) {
      case "-x" => {
        peeked = peekNext
      }
    }
    assertEquals(peeked, "")
  }

  test("nextInt parses an integer") {
    var value = 0

    eachArg(Seq("-n", "42"), failUsage) {
      case "-n" => {
        value = nextInt
      }
    }

    assertEquals(value, 42)
  }

  test("nextLong parses a long") {
    var value = 0L

    eachArg(Seq("-n", "1234567890123"), failUsage) {
      case "-n" => {
        value = nextLong
      }
    }

    assertEquals(value, 1234567890123L)
  }

  test("nextDouble parses a double") {
    var value = 0.0

    eachArg(Seq("-n", "3.14"), failUsage) {
      case "-n" => {
        value = nextDouble
      }
    }

    assertEqualsDouble(value, 3.14, 0.0001)
  }

  test("missing argument triggers usage error") {
    intercept[RuntimeException] {
      eachArg(Seq("-x"), failUsage) {
        case "-x" => {
          consumeNext
        }
      }
    }
  }

  test("unknown flag triggers usage error") {
    intercept[RuntimeException] {
      eachArg(Seq("-unknown"), failUsage) {
        case "-x" => ()
        case "-y" => ()
      }
    }
  }

  test("helpers cannot be used outside eachArg") {
    intercept[IllegalStateException] {
      consumeNext
    }
  }

  test("multiple eachArg invocations do not leak state") {
    var first = ""
    var second = ""

    eachArg(Seq("-x", "one"), failUsage) {
      case "-x" => {
        first = consumeNext
      }
    }

    eachArg(Seq("-x", "two"), failUsage) {
      case "-x" => {
        second = consumeNext
      }
    }

    assertEquals(first, "one")
    assertEquals(second, "two")
  }

  test("cursor advances correctly across multiple flags") {
    var a = ""
    var b = ""

    eachArg(Seq("-a", "foo", "-b", "bar"), failUsage) {
      case "-a" => { a = consumeNext }
      case "-b" => { b = consumeNext }
    }

    assertEquals(a, "foo")
    assertEquals(b, "bar")
  }

  test("peekNext does not advance cursor even when used repeatedly") {
    var p1 = ""
    var p2 = ""
    var consumed = ""

    eachArg(Seq("-x", "hello"), failUsage) {
      case "-x" => {
        p1 = peekNext
        p2 = peekNext
        consumed = consumeNext
      }
    }

    assertEquals(p1, "hello")
    assertEquals(p2, "hello")
    assertEquals(consumed, "hello")
  }
}
