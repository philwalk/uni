package uni.time

import scala.util.Random

object ExampleGenerator:
  import SmarterParse.{Shape, Pattern}

  private var rng: Random = new Random(0)
  def seed(n: Int): Unit =
    rng = new Random(n)

  // Delimiters for numeric patterns
  private val delimiters = Array("/", "-", ".", " ")

  def exampleForPattern(pattern: String): (String, Shape) =
    var ex = ""
    var inferred: Shape = Shape.Unknown

    // Keep generating until classifier accepts it
    while inferred == Shape.Unknown do
      ex = generateRawExample(pattern)
      inferred = SmarterParse.classify(ex)
      if uni.verboseUni && inferred == Shape.Unknown then
        System.err.printf(s"rejected pattern: [$ex]\n")
        //sys.error(s"rejected pattern [$ex]")

    (ex, inferred)

  private def generateRawExample(pattern: String): String =
    pattern match
      case Pattern.YMD.pattern          => expandYMD()
      case Pattern.MDY.pattern          => expandMDY()
      case Pattern.DMY.pattern          => expandDMY()
      case Pattern.MonthDayYear.pattern => expandMonthDayYear()
      case Pattern.ISO8601Strict.pattern => expandISO8601Strict()
      case other =>
        throw new RuntimeException(s"Unknown pattern: $other")

  // -----------------------
  // Pattern expansions
  // -----------------------

  private def delim: String =
    delimiters(rng.nextInt(delimiters.length))

  private def expandYMD(): String =
    val y = 2000 + rng.nextInt(30)
    val m = 1 + rng.nextInt(12)
    val d = 1 + rng.nextInt(28)
    val d1 = delim
    s"$y$d1$m$d1$d"

  private def expandMDY(): String =
    val m = 1 + rng.nextInt(12)
    val d = 1 + rng.nextInt(28)
    val y = 2000 + rng.nextInt(30)
    val d1 = delim
    s"$m$d1$d$d1$y"

  private def expandDMY(): String =
    val d = 1 + rng.nextInt(28)
    val m = 1 + rng.nextInt(12)
    val y = 2000 + rng.nextInt(30)
    val d1 = delim
    s"$d$d1$m$d1$y"

  private def expandMonthDayYear(): String =
    val months = Array("Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec")
    val month = months(rng.nextInt(12))
    val d = 1 + rng.nextInt(28)
    val y = 2000 + rng.nextInt(30)

    // 80% classic "Aug 11, 2020", 20% delimiter variants
    if rng.nextDouble() < 0.8 then
      s"$month $d, $y"
    else
      val d1 = delim
      s"$month$d1$d$d1$y"

  private def expandISO8601Strict(): String =
    val y = 2000 + rng.nextInt(30)
    val m = 1 + rng.nextInt(12)
    val d = 1 + rng.nextInt(28)
    val hh = rng.nextInt(24)
    val mm = rng.nextInt(60)
    val ss = rng.nextInt(60)
    f"$y%04d-$m%02d-$d%02dT$hh%02d:$mm%02d:$ss%02d"
