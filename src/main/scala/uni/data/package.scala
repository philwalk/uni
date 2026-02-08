package uni.data

import uni.time.*

type CVD = String|Int|Long|uni.data.Big|java.time.LocalDateTime|Option[Int]

var verbose: Boolean = false

def v2s(b: Big): String = b match {
  case BadNum => "N/A"
  case b => b.toString
}

def toStr(x: CVD): String = {
  (x: @unchecked) match {
  case s: String        => s
  case n: (Int | Long)  => n.toString
  case BadNum           => "N/A"
  case b: Big           => b.toString
  case d: LocalDateTime => d.toString("yyyy-MM-dd")
  case Some(oi: Int)    => oi.toString
  case None             => ""
  }
}

