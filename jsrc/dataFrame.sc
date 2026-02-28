#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.9.2

import uni.*
import uni.time.*
import uni.data.*

import java.time.LocalDateTime
import scala.util.Try

object CSV {
  
  enum ColType:
    case DateType, IntType, LongType, BigType, BooleanType, StringType, UnknownType
  
  sealed trait TypedValue
  case class DateVal(dt: LocalDateTime) extends TypedValue
  case class IntVal(n: Int) extends TypedValue
  case class LongVal(n: Long) extends TypedValue
  case class BigVal(n: Big) extends TypedValue
  case class BoolVal(b: Boolean) extends TypedValue
  case class StrVal(s: String) extends TypedValue
  case object BadDate extends TypedValue
  case object BigNaN extends TypedValue
  case object BadInt extends TypedValue
  case object BadLong extends TypedValue
  case object BadBool extends TypedValue
  case object EmptyVal extends TypedValue
  
  /** DataFrame-like structure - Pandas-inspired with sentinel values */
  case class DataFrame(
    private val data: Map[String, Seq[TypedValue]],
    private val colTypes: Map[String, ColType],
    private val originalNames: Map[String, String]
  ) {
    def columns: Seq[String] = data.keys.toSeq
    def size: Int = data.headOption.map(_._2.length).getOrElse(0)
    
    /** Access column by name (camelCase or original) */
    def apply(colName: String): Seq[TypedValue] = {
      data.getOrElse(colName, 
        data.getOrElse(originalNames.getOrElse(colName, ""), Seq.empty))
    }
    
    /** Access row by index as named tuple (Map) */
    def row(idx: Int): Map[String, TypedValue] = 
      data.map { case (name, values) => name -> values(idx) }
    
    /** Access specific cell */
    def at(row: Int, col: String): TypedValue = 
      apply(col).lift(row).getOrElse(EmptyVal)
    
    /** Filter rows based on predicate */
    def filter(pred: Map[String, TypedValue] => Boolean): DataFrame = {
      val indices = (0 until size).filter(i => pred(row(i)))
      val newData = data.map { case (name, values) =>
        name -> indices.map(values(_))
      }
      DataFrame(newData, colTypes, originalNames)
    }
    
    /** Select subset of columns */
    def select(colNames: String*): DataFrame = {
      val newData = colNames.flatMap(name => data.get(name).map(name -> _)).toMap
      val newTypes = colNames.flatMap(name => colTypes.get(name).map(name -> _)).toMap
      val newOriginal = colNames.flatMap(name => originalNames.get(name).map(name -> _)).toMap
      DataFrame(newData, newTypes, newOriginal)
    }
    
    /** Get column type */
    def colType(name: String): ColType = 
      colTypes.getOrElse(name, ColType.UnknownType)
    
    /** Convert to sequence of named tuples (Maps) */
    def toRows: Seq[Map[String, TypedValue]] = 
      (0 until size).map(row)
    
    /** Convert column to Int with BadInt sentinel */
    def getInts(colName: String): Seq[Int] = 
      apply(colName).map {
        case IntVal(n) => n
        case LongVal(n) if n >= Int.MinValue && n <= Int.MaxValue => n.toInt
        case BigVal(b) if b.isValidInt => b.toInt
        case _ => BadInt.asInstanceOf[Int]  // Sentinel value
      }
    
    /** Convert column to Long with BadLong sentinel */
    def getLongs(colName: String): Seq[Long] = 
      apply(colName).map {
        case IntVal(n) => n.toLong
        case LongVal(n) => n
        case BigVal(b) if b.isValidLong => b.toLong
        case _ => BadLong.asInstanceOf[Long]  // Sentinel value
      }
    
    /** Convert column to Big with BigNaN sentinel */
    def getBigs(colName: String): Seq[Big] = 
      apply(colName).map {
        case BigVal(b) => b
        case IntVal(n) => Big(n)
        case LongVal(n) => Big(n)
        case _ => BigNaN.asInstanceOf[Big]  // Sentinel value
      }
    
    /** Convert column to LocalDateTime with BadDate sentinel */
    def getDates(colName: String): Seq[LocalDateTime] = 
      apply(colName).map {
        case DateVal(dt) => dt
        case _ => BadDate.asInstanceOf[LocalDateTime]  // Sentinel value
      }
    
    /** Convert column to Boolean with BadBool sentinel */
    def getBools(colName: String): Seq[Boolean] = 
      apply(colName).map {
        case BoolVal(b) => b
        case _ => BadBool.asInstanceOf[Boolean]  // Sentinel value
      }
    
    /** Convert column to String (never fails) */
    def getStrings(colName: String): Seq[String] = 
      apply(colName).map {
        case StrVal(s) => s
        case IntVal(n) => n.toString
        case LongVal(n) => n.toString
        case BigVal(b) => b.toString
        case BoolVal(b) => b.toString
        case DateVal(dt) => dt.toString
        case BadDate => "BadDate"
        case BigNaN => "BigNaN"
        case BadInt => "BadInt"
        case BadLong => "BadLong"
        case BadBool => "BadBool"
        case EmptyVal => ""
      }
    
    /** Check if value is a sentinel/error */
    def isBad(value: TypedValue): Boolean = value match {
      case BadDate | BigNaN | BadInt | BadLong | BadBool | EmptyVal => true
      case _ => false
    }
    
    /** Count bad values in column */
    def countBad(colName: String): Int = 
      apply(colName).count(isBad)
    
    /** Pandas-like head/tail */
    def head(n: Int = 5): DataFrame = {
      val indices = 0 until Math.min(n, size)
      val newData = data.map { case (name, values) =>
        name -> indices.map(values(_))
      }
      DataFrame(newData, colTypes, originalNames)
    }
    
    def tail(n: Int = 5): DataFrame = {
      val start = Math.max(0, size - n)
      val indices = start until size
      val newData = data.map { case (name, values) =>
        name -> indices.map(values(_))
      }
      DataFrame(newData, colTypes, originalNames)
    }
    
    /** Pandas-like describe for numeric columns */
    def describe(): Map[String, Map[String, Double]] = {
      data.flatMap { case (name, values) =>
        colTypes.get(name) match {
          case Some(ColType.IntType | ColType.LongType | ColType.BigType) =>
            val nums = values.collect {
              case IntVal(n) => n.toDouble
              case LongVal(n) => n.toDouble
              case BigVal(b) => b.toDouble
            }
            if (nums.nonEmpty) {
              Some(name -> Map(
                "count" -> nums.length.toDouble,
                "mean" -> nums.sum / nums.length,
                "min" -> nums.min,
                "max" -> nums.max,
                "std" -> {
                  val mean = nums.sum / nums.length
                  val variance = nums.map(x => Math.pow(x - mean, 2)).sum / nums.length
                  Math.sqrt(variance)
                }
              ))
            } else None
          case _ => None
        }
      }
    }
    
    /** Show summary with bad value counts */
    def info(): String = {
      val sb = new StringBuilder
      sb.append(s"DataFrame with ${size} rows and ${columns.length} columns\n")
      sb.append("Columns:\n")
      columns.foreach { col =>
        val typ = colTypes.getOrElse(col, ColType.UnknownType)
        val orig = originalNames.getOrElse(col, col)
        val badCount = countBad(col)
        sb.append(s"  $col ($orig): $typ, bad values: $badCount\n")
      }
      sb.toString
    }
  }
  
  /** Convert column name to camelCase legal Scala identifier */
  def toCamelCase(name: String): String = {
    val words = name
      .replaceAll("[^a-zA-Z0-9]", " ")
      .trim
      .split("\\s+")
      .filter(_.nonEmpty)
    
    val camel = if (words.isEmpty) {
      "column"
    } else {
      words.head.toLowerCase + words.tail.map(_.capitalize).mkString
    }
    
    val keywords = Set(
      "abstract", "case", "catch", "class", "def", "do", "else", 
      "extends", "false", "final", "finally", "for", "forSome", 
      "if", "implicit", "import", "lazy", "match", "new", "null", 
      "object", "override", "package", "private", "protected", 
      "sealed", "super", "this", "throw", "trait", "try", "true", 
      "type", "val", "var", "while", "with", "yield",
      "enum", "export", "given", "then", "using"
    )
    
    if (keywords.contains(camel)) s"`$camel`" else camel
  }
  
  private def tryParseBoolean(s: String): Option[Boolean] = {
    if (s.trim.isEmpty) None
    else {
      val lower = s.trim.toLowerCase
      lower match {
        case "true" | "yes" | "1" | "t" | "y" => Some(true)
        case "false" | "no" | "0" | "f" | "n" => Some(false)
        case _ => None
      }
    }
  }
  
  private def tryParseInt(s: String): Option[Int] = 
    if (s.trim.isEmpty) None else Try(s.trim.toInt).toOption
  
  private def tryParseLong(s: String): Option[Long] = 
    if (s.trim.isEmpty) None 
    else Try(s.trim.toLong).toOption.filter(l => l < Int.MinValue || l > Int.MaxValue)
  
  def inferColumnType(values: Seq[String], pct: Int = 10): ColType = {
    if (values.isEmpty) ColType.UnknownType
    else {
      val nonEmpty = values.filter(_.trim.nonEmpty)
      if (nonEmpty.isEmpty) ColType.StringType
      else {
        val threshold = (nonEmpty.length * pct) / 100.0
        
        // Priority: Boolean, Int, Long, Big, Date, String
        val boolCount = nonEmpty.count(s => tryParseBoolean(s).isDefined)
        if (boolCount >= threshold) ColType.BooleanType
        else {
          val intCount = nonEmpty.count(s => tryParseInt(s).isDefined)
          if (intCount >= threshold) ColType.IntType
          else {
            val longCount = nonEmpty.count(s => tryParseLong(s).isDefined)
            if (longCount >= threshold) ColType.LongType
            else {
              val bigCount = nonEmpty.count(s => Big(s) != BigNaN)
              if (bigCount >= threshold) ColType.BigType
              else {
                val dateCount = nonEmpty.count(s => parseDate(s) != BadDate)
                if (dateCount >= threshold) ColType.DateType
                else ColType.StringType
              }
            }
          }
        }
      }
    }
  }
  
  def convertValue(s: String, colType: ColType): TypedValue = {
    if (s.trim.isEmpty) EmptyVal
    else colType match {
      case ColType.BooleanType =>
        tryParseBoolean(s).map(BoolVal(_)).getOrElse(BadBool)
        
      case ColType.IntType =>
        tryParseInt(s).map(IntVal(_)).getOrElse(BadInt)
        
      case ColType.LongType =>
        Try(s.trim.toLong).toOption.map(LongVal(_)).getOrElse(BadLong)
        
      case ColType.BigType =>
        val b = Big(s)
        if (b == BigNaN) BigNaN else BigVal(b)
        
      case ColType.DateType =>
        val d = parseDate(s)
        if (d == BadDate) BadDate else DateVal(d)
        
      case ColType.StringType | ColType.UnknownType =>
        StrVal(s)
    }
  }
  
  /** Read CSV into DataFrame */
  def readDataFrame(p: Path, pct: Int = 10): DataFrame = {
    val allRows = p.csvRows.toSeq
    if (allRows.isEmpty) {
      DataFrame(Map.empty, Map.empty, Map.empty)
    } else {
      val header = allRows.head
      val colCount = header.takeWhile(_.trim.nonEmpty).length
      val originalColNames = header.take(colCount)
      val camelColNames = originalColNames.map(toCamelCase)
      
      val dataRows = allRows.tail
        .filter(_.size >= colCount)
        .map(_.take(colCount))
        .filterNot(_ == originalColNames)
      
      if (dataRows.isEmpty) {
        val emptyData = camelColNames.map(_ -> Seq.empty[TypedValue]).toMap
        val emptyTypes = camelColNames.map(_ -> ColType.StringType).toMap
        val nameMap = camelColNames.zip(originalColNames).toMap
        DataFrame(emptyData, emptyTypes, nameMap)
      } else {
        val columnData = (0 until colCount).map(colIdx => dataRows.map(_(colIdx)))
        
        val typedData = camelColNames.zip(columnData).map { case (name, values) =>
          val colType = inferColumnType(values, pct)
          val typedValues = values.map(v => convertValue(v, colType))
          (name, typedValues, colType)
        }
        
        val data = typedData.map { case (name, values, _) => name -> values }.toMap
        val types = typedData.map { case (name, _, typ) => name -> typ }.toMap
        val nameMap = camelColNames.zip(originalColNames).toMap
        
        DataFrame(data, types, nameMap)
      }
    }
  }
}

// Usage examples with sentinels
object DataFrameExamples {
  def example(p: Path): Unit = {
    val df = CSV.readDataFrame(p, pct = 15)
    
    println(df.info())  // Shows bad value counts
    
    // Get typed columns - sentinels instead of Options
    val dates = df.getDates("transactionDate")
    val amounts = df.getBigs("amount")
    
    // Filter using sentinels
    val valid = df.filter { row =>
      row("amount") match {
        case CSV.BigVal(b) if b != BigNaN => b > Big(100)
        case _ => false
      }
    }
    
    // Work with sentinels like NaN
    val processed = amounts.map { amt =>
      if (amt == BigNaN) Big(0)  // Replace bad values
      else amt * 1.1
    }
  }
  
  // Extract to typed tuples with sentinels
  def toTypedRows(df: CSV.DataFrame): Seq[(String, Int, LocalDateTime, Big)] = {
    val names = df.getStrings("name")
    val ids = df.getInts("id")
    val dates = df.getDates("createdAt")
    val amounts = df.getBigs("amount")
    
    (0 until df.size).map { i =>
      (names(i), ids(i), dates(i), amounts(i))
      // BadDate, BadInt, BigNaN are sentinels in the tuple
    }
  }
}