package uni.time

import munit.FunSuite
//import java.time.LocalDateTime
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Await
import scala.concurrent.duration.*

class TestTimeConfig extends FunSuite {

  test("SmartParse respects MDY (default) for ambiguous dates") {
    val dateStr = "02/11/2009" // Feb 11 (MDY) or Nov 2 (DMY)
    val result = SmartParse.parseDateSmart(dateStr)
    assertEquals(result.getMonthValue, 2)
    assertEquals(result.getDayOfMonth, 11)
  }

  test("SmartParse respects withDMY scope") {
    withDMY {
      val dateStr = "02/11/2009" 
      val result = SmartParse.parseDateSmart(dateStr)
      assertEquals(result.getMonthValue, 11)
      assertEquals(result.getDayOfMonth, 2)
    }
    // Outside scope, back to MDY
    val result = SmartParse.parseDateSmart("02/11/2009")
    assertEquals(result.getMonthValue, 2)
  }

  test("Config is thread-safe and isolated via DynamicVariable") {
    val f1 = Future {
      withMDY {
        Thread.sleep(50)
        val d = TimeUtils.parseDate("02/11/2009")
        d.getMonthValue // Should be 2
      }
    }
    val f2 = Future {
      withDMY {
        val d = TimeUtils.parseDate("02/11/2009")
        d.getMonthValue // Should be 11
      }
    }

    val res1 = Await.result(f1, 2.seconds)
    val res2 = Await.result(f2, 2.seconds)

    assertEquals(res1, 2, "Thread 1 should be MDY")
    assertEquals(res2, 11, "Thread 2 should be DMY")
  }

  test("Nested scopes work correctly") {
    withMDY {
      assertEquals(TimeUtils.parseDate("02/11/2009").getMonthValue, 2)
      withDMY {
        assertEquals(TimeUtils.parseDate("02/11/2009").getMonthValue, 11)
      }
      assertEquals(TimeUtils.parseDate("02/11/2009").getMonthValue, 2)
    }
  }
}
