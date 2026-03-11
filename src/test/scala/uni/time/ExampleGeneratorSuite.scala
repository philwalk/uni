package uni.time

import munit.FunSuite
import uni.time.SmartParse.{Pattern, Shape}

class ExampleGeneratorSuite extends FunSuite:

  override def beforeEach(context: BeforeEach): Unit =
    ExampleGenerator.seed(42)

  // Helper: generate and assert the result is classifiable
  private def check(p: Pattern): Unit =
    val (ex, shape) = ExampleGenerator.exampleForPattern(p.pattern)
    assertNotEquals(shape, Shape.Unknown,
      s"${p.pattern} produced unclassifiable example: [$ex]")
    assert(ex.nonEmpty, s"${p.pattern} produced empty example")

  // ============================================================================
  // All 8 Pattern cases
  // ============================================================================

  test("YMD: generates a classifiable date string") {
    check(Pattern.YMD)
  }

  test("MDY: generates a classifiable date string") {
    check(Pattern.MDY)
  }

  test("DMY: generates a classifiable date string") {
    check(Pattern.DMY)
  }

  test("MonthDayYear: generates a classifiable date string") {
    check(Pattern.MonthDayYear)
  }

  test("MonthDayYearWithTime: generates a classifiable date string") {
    check(Pattern.MonthDayYearWithTime)
  }

  test("MDYWithTime: generates a classifiable date string") {
    check(Pattern.MDYWithTime)
  }

  test("DayMonthYear: generates a classifiable date string") {
    check(Pattern.DayMonthYear)
  }

  test("ISO8601Strict: generates a classifiable date string") {
    check(Pattern.ISO8601Strict)
  }

  // ============================================================================
  // Error path
  // ============================================================================

  test("unknown pattern string throws RuntimeException") {
    intercept[RuntimeException] {
      ExampleGenerator.exampleForPattern("not-a-real-pattern")
    }
  }

  // ============================================================================
  // Seed determinism
  // ============================================================================

  test("seed: same seed produces identical output") {
    ExampleGenerator.seed(7)
    val (ex1, _) = ExampleGenerator.exampleForPattern(Pattern.YMD.pattern)
    ExampleGenerator.seed(7)
    val (ex2, _) = ExampleGenerator.exampleForPattern(Pattern.YMD.pattern)
    assertEquals(ex1, ex2)
  }

  test("seed: generated example is non-empty regardless of seed") {
    for s <- Seq(0, 1, 99, Int.MaxValue) do
      ExampleGenerator.seed(s)
      val (ex, _) = ExampleGenerator.exampleForPattern(Pattern.MDY.pattern)
      assert(ex.nonEmpty, s"seed=$s produced empty example")
  }
