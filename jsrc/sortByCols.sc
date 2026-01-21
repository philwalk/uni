#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.6.2

import uni.*
//import uni.fs.*
import uni.io.*

// sort a csv file by a specified list of columns.
object ColumnN {
  def usage(m: String = ""): Nothing = {
    if (m.nonEmpty) printf("%s\n", m)
    printf("usage: %s <sortColumns> <csvFile>\n", progName(this))
    printf("<colA> [<colB> ...]   ; zero-based column indexes")
    printf("use a negative index to denote descending order.\n")
    sys.exit(1)
  }

  var (verbose, sortSpecs, fullstack, inputFile: Option[Path])  = (false, List.empty[(Int, Boolean)], false, None)

  def main(args: Array[String]): Unit = {
    try {
      parseArgs(args)

      if (inputFile.isEmpty || sortSpecs.isEmpty) {
        usage()
      }

      val rows = FastCsv.rowsAsync(inputFile.get).dropWhile(_.size < 2).toList
      if (verbose) {
        eprintf("%s x %s\n", rows.size, rows.head.size)
      }

      import Key.given
      rows.sortBy(row => rowKey(row, sortSpecs))
        .foreach { row =>
          printf("%s\n", row.mkString("|"))
        }
    } catch {
    case e: Exception =>
      if (fullstack) {
        throw e
      } else {
        showLimitedStack(e)
        sys.exit(1)
      }
    }
  }

  def parseArgs(args: Array[String]): Unit = {
    args.foreach { arg =>
      arg match {
      case "-fullstack" =>
        fullstack = true
      case "-v" => verbose = true
      case str if str.matches("-?[0-9]+(,-?[0-9]+)*") =>
      sortSpecs = str.split(",").toList.map { token =>
          if token.startsWith("-") then
            (token.drop(1).toInt, false)   // descending
          else
            (token.toInt, true)            // ascending
        }
      case fname if fname.path.isFile =>
        if inputFile.nonEmpty then
          usage(s"2nd filename [$fname] but already specified [${inputFile.get}]")
        val p = fname.path
        if (!p.isFile) {
          usage(s"not found [${p.posx}]")
        }
        inputFile = Some(p)
      case _ =>
        usage(s"unrecognized arg [$arg]")
      }
    }
  }

  lazy val numericThreshold = 0.3
  def inferColumn(col: Seq[String]): Seq[Double] | Seq[String] =
    val numericCount = col.count(_.toDoubleOption.isDefined)
    val ratio = numericCount.toDouble / col.size

    if ratio >= numericThreshold then
      // numeric column
      col.map(_.toDoubleOption.getOrElse(Double.NaN))
    else
      // string column
      col

  // to avoid "String | Double" type
  def unify(col: Seq[String]): Seq[Double] =
    col.map(_.toDoubleOption.getOrElse(Double.NaN))

  // common indicators of missing data
  lazy val missing = Set(
    "", " ", "N/A", "NA", "NaN", "nan", "NULL", "null", "--"
  )

  def isMissing(s: String): Boolean =
    missing.contains(s.trim)

  def isNumericOrMissing(s: String): Boolean =
    isMissing(s) || s.toDoubleOption.isDefined

  def isNumeric(col: Seq[String], threshold: Double = numericThreshold): Boolean =
    val ok = col.count(isNumericOrMissing)
    ok.toDouble / col.size >= threshold

  def toNumeric(col: Seq[String]): Seq[Double] =
    col.map(_.toDoubleOption.getOrElse(Double.NaN))

  def toNumeric(s: String): Double =
    if isMissing(s) then Double.NaN
    else s.toDoubleOption.getOrElse(Double.NaN)

}

sealed trait Key derives CanEqual
case class NumKey(v: Double) extends Key
case class StrKey(v: String) extends Key
case class Desc(k: Key) extends Key

def cellKey(s: String): Key =
  s.toDoubleOption match
    case Some(d) => NumKey(d)
    case None    => StrKey(s)

def showCell(raw: String): String =
  raw.toDoubleOption match
    case Some(d) if d.isNaN => "N/A"
    case Some(d)            => d.toString
    case None               => raw

def rowKey(row: Seq[String], specs: List[(Int, Boolean)]): List[Key] =
  specs.map { case (col, asc) =>
    val base = cellKey(row(col))
    if asc then base else Desc(base)
  }

object Key:
  given Ordering[Key] with
    def compare(a: Key, b: Key): Int =
      (a, b) match
        case (Desc(x), Desc(y)) => summon[Ordering[Key]].compare(y, x) // reversed
        case (Desc(x), y)       => summon[Ordering[Key]].compare(y, x)
        case (x, Desc(y))       => summon[Ordering[Key]].compare(x, y)
        case (NumKey(x), NumKey(y)) => java.lang.Double.compare(x, y)
        case (StrKey(x), StrKey(y)) => x.compareTo(y)
        case (NumKey(_), StrKey(_)) => -1
        case (StrKey(_), NumKey(_)) => 1

  given Ordering[List[Key]] with
    def compare(xs: List[Key], ys: List[Key]): Int =
      val it1 = xs.iterator
      val it2 = ys.iterator
      while it1.hasNext && it2.hasNext do
        val c = summon[Ordering[Key]].compare(it1.next(), it2.next())
        if c != 0 then return c
      java.lang.Integer.compare(xs.size, ys.size)

