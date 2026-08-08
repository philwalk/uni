package uni.time

import munit.FunSuite

/** `UniDateTime.now()` must accept the parenthesised call form.
 *
 *  Scala 3 removed auto-application, so a bare `def now` cannot be invoked as `now()`
 *  -- and all nine call sites across the 166 scripts importing `uni.time` write
 *  `DateTime.now()`, because that is how `LocalDateTime.now()` reads. Declaring it
 *  without parens compiled fine here and would have failed in every one of them.
 */
class NowParensSuite extends FunSuite:
  test("now() is callable with parens, as the scripts write it") {
    val d: UniDateTime = UniDateTime.now()
    assert(d.year >= 2024, s"$d")
  }
