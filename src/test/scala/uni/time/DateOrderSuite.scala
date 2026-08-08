package uni.time

import munit.FunSuite

/**
 * Day/month order: lenient by default, enforceable on request, inferable per column.
 *
 * The problem being solved: `monthFirst` only decides the *ambiguous* case, so an
 * unambiguous date overrides it. Under the default `monthFirst = true` a consistent
 * European column parses as a mixture -- `04/12/1992` as April 12, `13/12/1992` as
 * December 13 -- and the rows that flip are exactly the ones with a day of 12 or less,
 * so nothing looks wrong.
 */
class DateOrderSuite extends FunSuite:

  /** A European column: every date is dd/MM, three of five ambiguous. */
  private val european = Seq("04/12/1992", "13/12/1992", "05/01/2020", "25/01/2020", "11/10/2021")

  test("the default is lenient, and that is why a consistent column comes out mixed") {
    // Not an aspiration -- the current behaviour, pinned so a change is deliberate.
    assertEquals(parseDateSmart("04/12/1992").toString, "1992-04-12T00:00") // read as MM/dd
    assertEquals(parseDateSmart("13/12/1992").toString, "1992-12-13T00:00") // read as dd/MM
  }

  test("an enforced order holds to one convention") {
    withDateOrder(DateOrder.DayFirst) {
      assertEquals(parseDateSmart("04/12/1992").toString, "1992-12-04T00:00")
      assertEquals(parseDateSmart("13/12/1992").toString, "1992-12-13T00:00")
    }
    withDateOrder(DateOrder.MonthFirst) {
      assertEquals(parseDateSmart("04/12/1992").toString, "1992-04-12T00:00")
      // Only readable as day-first, so it contradicts the declared order.
      assertEquals(parseDateSmart("13/12/1992"), BadDate)
    }
  }

  test("an enforced order makes a whole column consistent") {
    withDateOrder(DateOrder.DayFirst) {
      assertEquals(
        european.map(parseDateSmart(_).toLocalDate.toString),
        List("1992-12-04", "1992-12-13", "2020-01-05", "2020-01-25", "2021-10-11"))
    }
  }

  test("a column's convention can be inferred rather than declared") {
    // The ease-of-use route to enforcement: no one has to know the file's convention.
    assertEquals(inferDateOrder(european), Some(DateOrder.DayFirst))
    assertEquals(inferDateOrder(Seq("12/25/2020", "01/13/2021")), Some(DateOrder.MonthFirst))
  }

  test("inference reports Auto when nothing is decisive") {
    // Every date fits both readings, so there is nothing to enforce.
    assertEquals(inferDateOrder(Seq("01/02/2020", "03/04/2021")), Some(DateOrder.Auto))
    assertEquals(inferDateOrder(Seq.empty[String]), Some(DateOrder.Auto))
    // ISO dates say nothing about day/month order either.
    assertEquals(inferDateOrder(Seq("2020-01-02", "2021-03-04")), Some(DateOrder.Auto))
  }

  test("a genuinely mixed column is reported as a conflict, not resolved by guesswork") {
    // Both conventions proved present: no single order can be right, and inventing one
    // would hide the problem the caller most needs to see.
    assertEquals(inferDateOrder(Seq("13/12/1992", "12/25/2020")), None)
  }

  test("infer then enforce is the intended combination") {
    val order = inferDateOrder(european).getOrElse(DateOrder.Auto)
    withDateOrder(order) {
      val parsed = european.map(parseDateSmart)
      assert(parsed.forall(_ != BadDate), "an inferred order must read its own column")
      assertEquals(parsed.map(_.getDayOfMonth), List(4, 13, 5, 25, 11))
    }
  }

  test("enforcement does not disturb ISO or month-name forms") {
    withDateOrder(DateOrder.DayFirst) {
      assertEquals(parseDateSmart("2020-01-02").toString, "2020-01-02T00:00")
      assertEquals(parseDateSmart("Jan 2 2020").toString, "2020-01-02T00:00")
      assertEquals(parseDateSmart("2 Jan 2020").toString, "2020-01-02T00:00")
    }
  }

  // ---------------------------------------------------------------------------
  // Scope
  // ---------------------------------------------------------------------------

  test("the order is dynamically scoped and restored on exit") {
    val d = "04/12/1992"
    assertEquals(parseDateSmart(d).toString, "1992-04-12T00:00")
    withDateOrder(DateOrder.DayFirst) {
      assertEquals(parseDateSmart(d).toString, "1992-12-04T00:00")
      withDateOrder(DateOrder.MonthFirst) {
        assertEquals(parseDateSmart(d).toString, "1992-04-12T00:00", "inner wins")
      }
      assertEquals(parseDateSmart(d).toString, "1992-12-04T00:00", "outer restored")
    }
    assertEquals(parseDateSmart(d).toString, "1992-04-12T00:00", "restored on exit")
  }

  test("Auto keeps an enclosing monthFirst instead of resetting it") {
    // It used to build a fresh TimeConfig, discarding the outer setting -- so asking
    // for Auto inside a day-first block silently switched to month-first.
    withTimeConfig(TimeConfig(monthFirst = false)) {
      withDateOrder(DateOrder.Auto) {
        assertEquals(timeConfig.monthFirst, false)
        assertEquals(parseDateSmart("04/12/1992").toString, "1992-12-04T00:00")
      }
    }
  }

  test("a strict order also pins the ambiguous case") {
    // Under enforcement the ambiguous dates must follow the declared order too,
    // otherwise the column is still parsed two ways.
    withDateOrder(DateOrder.DayFirst) {
      assertEquals(timeConfig.monthFirst, false)
      assertEquals(parseDateSmart("04/12/1992").toString, "1992-12-04T00:00")
    }
  }

  test("a thread created inside the block inherits the order") {
    // DynamicVariable is an InheritableThreadLocal: a child thread started inside the
    // block sees it. A thread that already existed -- a warm pool -- does not, so
    // parsing on a pre-existing executor is the one place this does not reach.
    withDateOrder(DateOrder.DayFirst) {
      var seen = ""
      val t = new Thread(() => seen = parseDateSmart("04/12/1992").toString)
      t.start(); t.join()
      assertEquals(seen, "1992-12-04T00:00")
    }
  }
