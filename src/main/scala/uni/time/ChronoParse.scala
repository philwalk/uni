package uni.time

import uni.*
import java.time.LocalDateTime

object ChronoParse {
  private lazy val BadDate: LocalDateTime = yyyyMMddHHmmssToDate(List(1900,01,01))
  private lazy val now: LocalDateTime = LocalDateTime.now()
  private lazy val MonthNamesPattern = "(?i)(.*)(Jan[uary]*|Feb[ruary]*|Mar[ch]*|Apr[il]*|May|June?|July?|Aug[ust]*|Sep[tember]*|Oct[ober]*|Nov[ember]*|Dec[ember]*)(.*)".r
  var monthFirst = true // enforced convention for ambiguous month/day versus day/month

  def parseDateChrono(inpDateStr: String): LocalDateTime = {
    if (inpDateStr.trim.isEmpty) {
      BadDate
    } else {
      def isDigit(c: Char): Boolean = c >= '0' && c <= '9'
      val digitcount = inpDateStr.filter { (c: Char) => isDigit(c) }.size
      if (digitcount < 3 || digitcount > 19) {
        BadDate
      } else {
        val flds = uni.time.ChronoParse(inpDateStr) // might return BadDate!
        flds.dateTime 
      }
    }
  }

  /*
   * ChronoParse constructor.
   */
  def apply(datestr: String): ChronoParse = {
    val clean =
      datestr
        .replaceAll("\\s+", " ").trim
        // remove parenthesized timezone abbreviations anywhere
        .replaceAll("""\([A-Za-z]{2,5}\)""", "")

    val isIso8601 = datestr.matches("\\d{4}-\\d{2}-\\d{2}T.*")

    val normed = if isIso8601 then
      clean
        .replaceAll("""\.\d{1,9}""", "")          // strip fractional seconds
        // strip Z or numeric offset (with or without colon), allowing optional whitespace
        .replaceAll("""\s*(Z|[+-]\d{2}:?\d{2})$""", "")
        .replace('T', ' ') // convert ISO8601 T separator to space
        .trim
    else
      clean

    var valid = true
    var confident = true
    if (!isPossibleDateString(normed)) {
      BadChrono
    } else {
      val (datefields, timestring, timezone, _monthIndex, _yearIndex, pmFlag) = cleanPrep(normed)
      var (monthIndex: Int, yearIndex: Int) = (_monthIndex, _yearIndex)
      val clean = s"${datefields.mkString(" ")} ${timestring} $timezone".trim
      var _datenumstrings: IndexedSeq[String] = Nil.toIndexedSeq
      if (datefields.nonEmpty) {
        setDatenums(
          datefields.mkString(" ").replaceAll("\\D+", " ").trim.split(" +").toIndexedSeq
        )
      }

      def setDatenums(newval: Seq[String]): Unit = {
        if (newval.isEmpty) {
          hook += 1
        }
        val bad = newval.exists{ (s: String) =>
          s.trim.isEmpty || !s.matches("[0-9]+")
        }
        if (bad){
          hook += 1
        }
        _datenumstrings = newval.toIndexedSeq
      }
      def datenumstrings = _datenumstrings

      def swapDayAndMonth(dayIndex: Int, monthIndex: Int, monthStr: String, numstrings: IndexedSeq[String]): IndexedSeq[String] = {
        val maxIndex = numstrings.length - 1
        assert(dayIndex >= 0 && monthIndex >= 0 && dayIndex <= maxIndex && monthIndex <= maxIndex)
        val day = numstrings(dayIndex)
        var newnumstrings = numstrings.updated(monthIndex, day)
        newnumstrings = newnumstrings.updated(dayIndex, monthStr)
        newnumstrings
      }

      val widenums = datenumstrings.filter { _.length >= 4 }
      widenums.toList match {
      case Nil => // no wide num fields
      case year :: _ if year.length == 4 && year.toInt >= 1000 =>
        yearIndex = datenumstrings.indexOf(year)
        if (yearIndex > 3) {
          val (left, rite) = datenumstrings.splitAt(yearIndex)
          val newnumstrings = Seq(year) ++ left ++ rite.drop(1)
          setDatenums(newnumstrings.toIndexedSeq)
        }
        hook += 1
      case ymd :: _ =>
        hook += 1       // maybe 20240213 or similar
        var (y, m, d) = ("", "", "")
        if (ymd.toInt >= 1000 && ymd.length == 8) {
          // assume yyyy/mm/dd
          y = ymd.take(4)
          m = ymd.drop(4).take(2)
          d = ymd.drop(6)
        } else if (ymd.drop(4).matches("2[0-9]{3}") ){
          if (monthFirst) {
            // assume mm/dd/yyyy
            m = ymd.take(2)
            d = ymd.drop(2).take(2)
            y = ymd.drop(4)
          } else {
            // assume dd/mm/yyyy
            d = ymd.take(2)
            m = ymd.drop(2).take(2)
            y = ymd.drop(4)
          }
        }
        val newymd = Seq(y, m, d)
        val newnumstrings: Seq[String] = {
          val head: String = datenumstrings.head
          if (head == ymd) {
            val rite: Seq[String] = datenumstrings.tail
            val (mid: Seq[String], tail: Seq[String]) = rite.splitAt(1)
            val hrmin: String = mid.mkString
            //eprintf("hrmin[%s]\n", hrmin)
            if (hrmin.matches("[0-9]{3,4}")) {
              val (hr: String, min: String) = hrmin.splitAt(hrmin.length-2)
              val hour = if (hr.length == 1) {
                s"0$hr"
              } else {
                hr
              }
              //val lef: Seq[String] = newymd
              val mid: Seq[String] = Seq(hour, min)
              newymd ++ mid ++ tail
            } else {
              newymd ++ rite
            }
          } else {
            val i = datenumstrings.indexOf(ymd)
            val (left, rite) = datenumstrings.splitAt(i)
            val newhrmin = rite.drop(1)
            left ++ newymd ++ newhrmin
          }
        }
        setDatenums(newnumstrings.toIndexedSeq)
      }

      if (yearIndex > 2) {
        new ChronoParse(BadDate, clean, "", Nil, false)
      } else {
        if (monthIndex < 0) {
          // TODO : wrap this as a return value
          (yearIndex, monthFirst) match {
            case (-1, _) => // not enough info
            case (0,  true) => // y-m-d
              monthIndex = 1
            case (0, false) => // y-d-m
              monthIndex = 2
            case (2,  true) => // m-d-y
              monthIndex = 0
            case (2, false) => // d-m-y
              monthIndex = 1
            case (1,  true) => // m-y-d // ambiguous!
              confident = false
              monthIndex = 0
            case (1, false) => // d-y-m // ambiguous!
              confident = false
              monthIndex = 2
            case _ =>
              hook += 1 // TODO
          }
        }
        if verboseUni then printf("confident: %s\n", confident)
        def centuryPrefix(year: Int = now.getYear): String = {
          century(year).toString.take(2)
        }
        def century(y: Int): Int = {
          (y - y % 100)
        }
        (monthIndex, yearIndex) match {
          case (_, -1) =>
            datenumstrings.take(3) match {
            case Seq(m: String, d: String, y:String) if m.length <= 2 & d.length <= 2 && y.length <= 2 =>
              if (monthFirst) {
                val fullyear = s"${centuryPrefix()}$y"
                setDatenums(datenumstrings.updated(2, fullyear))
              }
            case _ =>
              // TODO verify this cannot happen (year index not initialized yet, so previous case is complete)
              hook += 1
            }

          case (-1, _) =>
            hook += 1
          case (0, 2) | (1, 0) => // m-d-y | y-m-d (month precedes day)
            val month = toNum(datenumstrings(monthIndex))
            if (!monthFirst && month <= 12) {
              val dayIndex = monthIndex + 1
              // swap month and day, if preferred and possible
              val day = datenumstrings(dayIndex)
              var newnums = datenumstrings.updated(monthIndex, day)
              newnums = newnums.updated(dayIndex, month.toString)
              setDatenums(newnums)
            }
          case (1, 2) => // d-m-y
            val month = toNum(datenumstrings(monthIndex))
            if (monthFirst && month <= 12) {
              // swap month and day, if preferred and possible
              val dayIndex = monthIndex - 1
              val swapped = swapDayAndMonth(dayIndex, monthIndex, month.toString, datenumstrings)
              setDatenums(swapped)
            }
          case (m, y) => // d-m-y
            hook += 1 // TODO
        }
        if (monthIndex >= 0) {
          val month = toNum(datenumstrings(monthIndex))
          if (!monthFirst && month <= 12) {
            // swap month and day, if preferred and possible
            val day = datenumstrings(monthIndex + 1)
            var newnums = datenumstrings.updated(monthIndex, day)
            newnums = newnums.updated(monthIndex+1, month.toString)
            setDatenums(newnums)
          }
        }
        //var nums: Array[Int] = datefields.map { (s: String) => toI(s) }
        var nums: Seq[Int] = datenumstrings.filter { _.trim.nonEmpty }.map { (numstr: String) =>
          if (!numstr.matches("[0-9]+")) {
            hook += 1
          }
          toNum(numstr)
        }

        val timeOnly: Boolean = datenumstrings.size <= 4 && clean.matches("[0-9]{2}:[0-9]{2}.*")
        if ( !timeOnly ) {
          def adjustYear(year: Int): Unit = {
            nums = nums.take(2) ++ Seq(year) ++ nums.drop(3)
            val newnums = nums.map { _.toString }
            setDatenums(newnums)
          }
          val dateFields = nums.take(3)
          dateFields match {
          case Seq(a, b, c) if a > 31 || b > 31 || c > 31 =>
            hook += 1 // the typical case where 4-digit year is provided
          case Seq(a, b) =>
            // the problem case; assume no year provided
            adjustYear(now.getYear) // no year provided, use current year
          case Seq(mOrD, dOrM, relyear) =>
            // the problem case; assume M/d/y or d/M/y format
            val y = now.getYear
            val century = y - y % 100
            adjustYear(century + relyear)
          case _ =>
            hook += 1 // huh?
          }
        }

        val fields: Seq[(String, Int)] = datenumstrings.zipWithIndex
        var (yval, mval, dval) = (0, 0, 0)
        val farr = fields.toArray
        var formats: Array[String] = farr.map { (s: String, i: Int) =>
          if (i < 3 && !timeOnly) {
            toNum(s) match {
            case y if y > 31 || s.length == 4 =>
              yval = y
              s.replaceAll(".", "y")
            case d if d > 12 && s.length <= 2 =>
              dval = d
              s.replaceAll(".", "d")
            case _ => // can't resolve month without more context
              s
            }
          } else {
            i match {
            case 3 => s.replaceAll(".", "H")
            case 4 => s.replaceAll(".", "m")
            case 5 => s.replaceAll(".", "s")
            case 6 => s.replaceAll(".", "Z")
            case _ =>
              s // not expecting any more numeric fields
            }
          }
        }
        def indexOf(s: String): Int = {
          formats.indexWhere((fld: String) =>
            fld.startsWith(s)
          )
        }
        def numIndex: Int = {
          formats.indexWhere((s: String) => s.matches("[0-9]+"))
        }
        def setFirstNum(s: String): Int = {
          val i = numIndex
          if (i < 0) {
            hook += 1
          }
          val numval = formats(i)
          val numfmt = numval.replaceAll("[0-9]", s)
          formats(i) = numfmt
          toNum(numval)
        }
        // if two yyyy-MM-dd fields already fixed, the third is implied
        formats.take(3).map { _.distinct }.sorted match {
          case Array(_, "M", "y") => dval = setFirstNum("d")
          case Array(_, "d", "y") => mval = setFirstNum("M")
          case Array(_, "M", "d") => yval = setFirstNum("y")
          case _arr =>
            hook += 1 // more than one numeric fields, so not ready to resolve
        }
        hook += 1
        //def is(s: String, v: String): Boolean = s.startsWith(v)

        val yidx = indexOf("y")
        val didx = indexOf("d")
        val midx = indexOf("M")
        def needsY = yidx < 0
        def needsM = midx < 0
        def needsD = didx < 0

        def replaceFirstNumericField(s: String): Unit = {
          val i = numIndex
          if (i < 0) {
            hook += 1 // no numerics found
          } else {
            assert(i >= 0 && i < 3, s"internal error: $clean [i: $i, s: $s]")
            s match {
              case "y" =>
                assert(yval == 0, s"yval: $yval")
                yval = toNum(formats(i))
              case "M" =>
                assert(mval == 0, s"mval: $mval")
                mval = toNum(formats(i))
              case "d" =>
                if (dval > 0) {
                  hook += 1
                }
                assert(dval == 0, s"dval: $dval")
                dval = toNum(formats(i))
              case _ =>
                sys.error(s"internal error: bad format indicator [$s]")
            }
            setFirstNum(s)
          }
        }

        //val needs = Seq(needsY, needsM, needsD)
        (needsY, needsM, needsD) match {
        case (false, false, true) =>
          replaceFirstNumericField("d")
        case (false, true, false) =>
          replaceFirstNumericField("M")
        case (true, false, false) =>
          replaceFirstNumericField("y")

        case (false, true,  true) =>
          // has year, needs month and day
          yidx match {
          case 1 =>
            // might as well support bizarre formats (M-y-d or d-M-y)
            if (monthFirst) {
              replaceFirstNumericField("M")
              replaceFirstNumericField("d")
            } else {
              replaceFirstNumericField("d")
              replaceFirstNumericField("M")
            }
          case 0 | 2 =>
            // y-M-d
            if (monthFirst) {
              replaceFirstNumericField("M")
              replaceFirstNumericField("d")
            } else {
              replaceFirstNumericField("d")
              replaceFirstNumericField("M")
            }

          }
        case (true,  true, false) =>
          // has day, needs month and year
          didx match {
          case 0 =>
            // d-M-y
            replaceFirstNumericField("M")
            replaceFirstNumericField("y")
          case 2 =>
            // y-M-d
            replaceFirstNumericField("y")
            replaceFirstNumericField("M")
          case 1 =>
            // AMBIGUOUS ...
            if (monthFirst) {
              // M-d-y
              replaceFirstNumericField("d")
              replaceFirstNumericField("M")
            } else {
              // d-M-y
              replaceFirstNumericField("M")
              replaceFirstNumericField("d")
            }
          }
        case (false, false, false) =>
          hook += 1 // done
        case (true, true, true) if timeOnly =>
          hook += 1 // done
        case (yy, mm, dd) =>
          formats.toList match {
          case a :: b :: Nil =>
            val (ta, tb) = (toNum(a), toNum(b))
            // interpret as missing day or missing year
            // missing day if either field is > 31
            if (monthFirst && ta <= 12) {
              mval = ta
              dval = tb
            } else {
              mval = tb
              dval = tb
            }
            if (mval > 31) {
              // assume day is missing
              yval = mval
              mval = dval
              dval = 1 // convention
            } else if (dval > 31) {
              // assume day is missing
              yval = dval
              dval = 1 // convention
            } else {
              if (mval > 12) {
                // the above swap might make this superfluous
                // swap month and day
                val temp = mval
                mval = dval
                dval = temp
              }
              yval = now.getYear // supply missing year
            }
            // TODO: reorder based on legal field values, if appropriate
            formats = Array("yyyy", "MM", "dd")
            setDatenums(IndexedSeq(yval, mval, dval).map { _.toString })
          case _ =>
            if (datenumstrings.nonEmpty) {
              sys.error(s"yy[$yy], mm[$mm], dd[$dd] datetime[$clean], formats[${formats.mkString("|")}]")
            }
          }
        }
        if (datenumstrings.endsWith("2019") ){
          hook += 1
        }

        val bareformats = formats.map { _.distinct }.toList
        nums = {
          val tdnums = (datenumstrings ++ timestring.split("[+: ]+"))
            tdnums.filter { _.trim.nonEmpty }.map { toNum(_) }
        }
        def ymd(iy: Int, im: Int, id: Int, tail: List[String]): LocalDateTime = {
          if (iy <0 || im <0 || id <0) {
            hook += 1
          } else if (nums.size < 3) {
            hook += 1
          }
          val standardOrder = List(nums(iy), nums(im), nums(id)) ++ nums.drop(3)
          yyyyMMddHHmmssToDate(standardOrder, pmFlag)
        }
        val dateTime: LocalDateTime = bareformats match {
          case "d" :: "M" :: "y" :: tail => ymd(2,1,0, tail)
          case "M" :: "d" :: "y" :: tail => ymd(2,0,1, tail)
          case "d" :: "y" :: "M" :: tail => ymd(1,2,0, tail)
          case "M" :: "y" :: "d" :: tail => ymd(1,0,2, tail)
          case "y" :: "d" :: "M" :: tail => ymd(0,2,1, tail)
          case "y" :: "M" :: "d" :: tail => ymd(0,1,2, tail)
          case other =>
            valid = false
            BadDate
        }
        new ChronoParse(dateTime, clean, timezone, formats.toSeq, valid)
      }
    }
  }
 
  // If month name present, convert to numeric equivalent.
  // month-day order is also implied and must be captured.
  // Return array of numbers plus month index (can be -1).
  private def numerifyNames(_cleanFields: Array[String]): (Int, Seq[String]) = {
    val cleanFields = _cleanFields.filter { _.trim.nonEmpty }
    val clean = cleanFields.mkString(" ")

    val monthIndex = clean match {
    case MonthNamesPattern(pre, monthName, post) =>
      val month: Int = monthAbbrev2Number(monthName)
      val midx = cleanFields.indexWhere( _.contains(monthName) )
      if (midx < 0) {
        sys.error(s"internal error: failed to find index of month[$monthName] in [$clean]")
      }
      cleanFields(midx) = month.toString
      midx
    case _ =>
      -1
    }
    (monthIndex, cleanFields.toIndexedSeq)
  }

  private def cleanPrep(rawdatetime: String): (Seq[String], String, String, Int, Int, Boolean) = {
    var monthIndex: Int = -1
    var yearIndex: Int = -1
    var pmFlag = false
    val (datefields, timestring, timezone) = {
      // toss weekday name, convert month name to number
      val (cleandates: Array[String], cleantimes: Array[String], timezone: String) = {
        val cleaned = rawdatetime.
          replaceAll("([0-9])-", "$1/"). // remove all hyphens except minus signs
          replaceAll("([a-zA-Z])([0-9])", "$1 $2"). // separate immediately adjacent numeric and alpha fields
          replaceAll("([0-9])([a-zA-Z])", "$1 $2"). // ditto
          replaceAll("([-])([AP]M)\\b", "$1 $2"). // separate hyphens from AM/PM fields (e.g., AM- or -AM)
          replaceAll("(\\b[[AP]M])([-])", "$1 $2"). // ditto
          replaceAll("([0-9])T([0-9])", "$1 $2"). // remove T separating date and time
          // discard day-of-week
          replaceAll("(?i)(Sun[day]*|Mon[day]*|Tue[sday]*|Wed[nesday]*|Thu[rsday]*|Fri[day]*|Sat[urday]*),? *", "")

        val splitRegex = if (cleaned.contains(":")) {
          "[/,\\s]+"
        } else {
          "[-/,\\s]+" // also split on hyphens
        }
        val (dts, tms) = {
          val ff = cleaned.
            split(splitRegex).
            filter {
              case "-AM" | "AM" =>
                false
              case "-PM" | "PM" =>
                pmFlag = true
                true
              case _ => true
            }
          ff.partition {
            case s if s.contains(":") =>
              false // HH mm or ss
            case s if s.matches("(?i)([AP]M)?[-+][0-9]{4}") =>
              false // time zone
            case s if s.matches("[.][0-9]+") =>
              false // decimal time field
            case s if timeZoneCodes.contains(s) =>
              false
            case _ =>
              true // date
          }
        }
        val (times, zones) = tms.partition { (s: String) =>
          s.contains(":") // || s.matches("(?i)([AP]M)?[-+][0-9]{4}")
        }
        (dts, times, zones.mkString(" "))
      }

      val (_monthIndex, cleanFields) = numerifyNames(cleandates ++ cleantimes)
      monthIndex = _monthIndex

      // separate into time and date, then handle each separately
      val (datefields, timefields) = {
        cleanFields.indexWhere(_.contains(":")) match {
          case -1 => // no time fields
            (cleanFields, Nil)
          case num =>
            // TODO: date fields (e.g., year) can sometimes appear after time fields
            var (dates, times) = cleanFields.splitAt(num)
            val widefields: Seq[String] = times.filter { (s: String) => !s.startsWith("0") && !s.contains(":") && s.length == 4 }
            if (widefields.nonEmpty) {
              val yi: Int = widefields.indexWhere(_.startsWith("2"))
              if (yi < 0) {
                hook += 1 // unexpected?
              }
              val yy = widefields(yi)
              if (yy.length == 4) {
                // move year from times to dates
                val yi = times.indexOf(yy)
                dates = Seq(yy) ++ dates
                yearIndex = 0
                times = {
                  val (a, b) = times.splitAt(yi)
                  a ++ b.drop(1)
                }
              }
            }
            (dates, times)
        }
      }
      val timestring: String = timefields.mkString(" ")

      (datefields, timestring, timezone)
    }
    (datefields, timestring, timezone, monthIndex, yearIndex, pmFlag)
  }

  private lazy val BadChrono = new ChronoParse(BadDate, "", "", Nil, false)

  private def toNum(str: String): Int = {
    str match {
    case n if n.matches("0\\d+") =>
      n.replaceAll("0+(.)", "$1").toInt
    case n if (n.matches("\\d+")) =>
      n.toInt
    case n if n.contains(".") =>
      Math.round(n.toDouble).toInt
    case "-0" => 0
    case other =>
      sys.error(s"internal error A: toI($str)")
    }
  }

  private def validYear(y: Int): Boolean = y > 0 && y < 2500
  private def validMonth(m: Int): Boolean = m > 0 && m <= 12 
  private def validDay(d: Int): Boolean = d > 0 && d <= 31 

  private def validYmd(ymd: Seq[Int]): Boolean = {
    val Seq(y: Int, m: Int, d: Int) = ymd
    validYear(y) && validMonth(m) && validDay(d)
  }

  private def yyyyMMddHHmmssToDate(
    fields: List[Int],
    pm: Boolean = false
  ): LocalDateTime = {
    def valid24(hr: Int, mn: Int, sc: Int): Boolean =
      hr >= 0 && hr <= 23 &&
      mn >= 0 && mn <= 59 &&
      sc >= 0 && sc <= 59
    def valid12(hr: Int, mn: Int, sc: Int): Boolean =
      hr >= 1 && hr <= 12 &&
      mn >= 0 && mn <= 59 &&
      sc >= 0 && sc <= 59

    // Extract YMD
    val (yr, mo, dy) = fields match
      case y :: m :: d :: _ => (y, m, d)
      case _                => return BadDate

    if !validYmd(List(yr, mo, dy)) then return BadDate

    // Extract time components (defaulting missing ones)
    val (hr0, mn0, sc0, nano0) = fields.drop(3) match
      case h :: m :: s :: n :: _ => (h, m, s, n)
      case h :: m :: s :: Nil    => (h, m, s, 0)
      case h :: m :: Nil         => (h, m, 0, 0)
      case h :: Nil              => (h, 0, 0, 0)
      case Nil                   => (0, 0, 0, 0)

    // Validate time according to AM/PM or 24-hour rules
    val valid =
      if pm then valid12(hr0, mn0, sc0)
      else valid24(hr0, mn0, sc0)

    if !valid then return BadDate

    // Apply PM conversion
    val hr = if pm then
      if hr0 == 12 then 12 else hr0 + 12
    else
      hr0

    LocalDateTime.of(yr, mo, dy, hr, mn0, sc0, nano0)
  }

  private lazy val timeZoneCodes = Set(
    "ACDT",  // Australian Central Daylight Saving Time	UTC+10:30
    "ACST",  // Australian Central Standard Time	UTC+09:30
    "ACT",   // Acre Time	UTC−05
    "ACT",   // ASEAN Common Time (proposed)	UTC+08:00
    "ACWST", // Australian Central Western Standard Time (unofficial)	UTC+08:45
    "ADT",   // Atlantic Daylight Time	UTC−03
    "AEDT",  // Australian Eastern Daylight Saving Time	UTC+11
    "AEST",  // Australian Eastern Standard Time	UTC+10
    "AET",   // Australian Eastern Time	UTC+10 / UTC+11
    "AEST",  // Australian Eastern Time	UTC+10 / UTC+11,
    "AEDT",  // Australian Eastern Time	UTC+10 / UTC+11
    "AFT",   // Afghanistan Time	UTC+04:30
    "AKDT",  // Alaska Daylight Time	UTC−08
    "AKST",  // Alaska Standard Time	UTC−09
    "ALMT",  // Alma-Ata Time[1]	UTC+06
    "AMST",  // Amazon Summer Time (Brazil)[2]	UTC−03
    "AMT",   // Amazon Time (Brazil)[3]	UTC−04
    "AMT",   // Armenia Time	UTC+04
    "ANAT",  // Anadyr Time[4]	UTC+12
    "AQTT",  // Aqtobe Time[5]	UTC+05
    "ART",   // Argentina Time	UTC−03
    "AST",   // Arabia Standard Time	UTC+03
    "AST",   // Atlantic Standard Time	UTC−04
    "AWST",  // Australian Western Standard Time	UTC+08
    "AZOST", // Azores Summer Time	UTC±00
    "AZOT",  // Azores Standard Time	UTC−01
    "AZT",   // Azerbaijan Time	UTC+04
    "BNT",   // Brunei Time	UTC+08
    "BIOT",  // British Indian Ocean Time	UTC+06
    "BIT",   // Baker Island Time	UTC−12
    "BOT",   // Bolivia Time	UTC−04
    "BRST",  // Brasília Summer Time	UTC−02
    "BRT",   // Brasília Time	UTC−03
    "BST",   // Bangladesh Standard Time	UTC+06
    "BST",   // Bougainville Standard Time[6]	UTC+11
    "BST",   // British Summer Time (British Standard Time from Mar 1968 to Oct 1971)	UTC+01
    "BTT",   // Bhutan Time	UTC+06
    "CAT",   // Central Africa Time	UTC+02
    "CCT",   // Cocos Islands Time	UTC+06:30
    "CDT",   // Central Daylight Time (North America)	UTC−05
    "CDT",   // Cuba Daylight Time[7]	UTC−04
    "CEST",  // Central European Summer Time	UTC+02
    "CET",   // Central European Time	UTC+01
    "CHADT", // Chatham Daylight Time	UTC+13:45
    "CHAST", // Chatham Standard Time	UTC+12:45
    "CHOT",  // Choibalsan Standard Time	UTC+08
    "CHOST", // Choibalsan Summer Time	UTC+09
    "CHST",  // Chamorro Standard Time	UTC+10
    "CHUT",  // Chuuk Time	UTC+10
    "CIST",  // Clipperton Island Standard Time	UTC−08
    "CKT",   // Cook Island Time	UTC−10
    "CLST",  // Chile Summer Time	UTC−03
    "CLT",   // Chile Standard Time	UTC−04
    "COST",  // Colombia Summer Time	UTC−04
    "COT",   // Colombia Time	UTC−05
    "CST",   // Central Standard Time (North America)	UTC−06
    "CST",   // China Standard Time	UTC+08
    "CST",   // Cuba Standard Time	UTC−05
    "CT",
    "CST",
    "CDT",   // Central Time	UTC−06 / UTC−05
    "CVT",   // Cape Verde Time	UTC−01
    "CWST",  // Central Western Standard Time (Australia) unofficial	UTC+08:45
    "CXT",   // Christmas Island Time	UTC+07
    "DAVT",  // Davis Time	UTC+07
    "DDUT",  // Dumont d'Urville Time	UTC+10
    "DFT",   // AIX-specific equivalent of Central European Time[NB 1]	UTC+01
    "EASST", // Easter Island Summer Time	UTC−05
    "EAST",  // Easter Island Standard Time	UTC−06
    "EAT",   // East Africa Time	UTC+03
    "ECT",   // Eastern Caribbean Time (does not recognise DST)	UTC−04
    "ECT",   // Ecuador Time	UTC−05
    "EDT",   // Eastern Daylight Time (North America)	UTC−04
    "EEST",  // Eastern European Summer Time	UTC+03
    "EET",   // Eastern European Time	UTC+02
    "EGST",  // Eastern Greenland Summer Time	UTC±00
    "EGT",   // Eastern Greenland Time	UTC−01
    "EST",   // Eastern Standard Time (North America)	UTC−05
    "ET",
    "EST",
    "EDT",   // Eastern Time (North America)	UTC−05 / UTC−04
    "FET",   // Further-eastern European Time	UTC+03
    "FJT",   // Fiji Time	UTC+12
    "FKST",  // Falkland Islands Summer Time	UTC−03
    "FKT",   // Falkland Islands Time	UTC−04
    "FNT",   // Fernando de Noronha Time	UTC−02
    "GALT",  // Galápagos Time	UTC−06
    "GAMT",  // Gambier Islands Time	UTC−09
    "GET",   // Georgia Standard Time	UTC+04
    "GFT",   // French Guiana Time	UTC−03
    "GILT",  // Gilbert Island Time	UTC+12
    "GIT",   // Gambier Island Time	UTC−09
    "GMT",   // Greenwich Mean Time	UTC±00
    "GST",   // South Georgia and the South Sandwich Islands Time	UTC−02
    "GST",   // Gulf Standard Time	UTC+04
    "GYT",   // Guyana Time	UTC−04
    "HDT",   // Hawaii–Aleutian Daylight Time	UTC−09
    "HAEC",  // Heure Avancée d'Europe Centrale French-language name for CEST	UTC+02
    "HST",   // Hawaii–Aleutian Standard Time	UTC−10
    "HKT",   // Hong Kong Time	UTC+08
    "HMT",   // Heard and McDonald Islands Time	UTC+05
    "HOVST", // Hovd Summer Time (not used from 2017-present)	UTC+08
    "HOVT",  // Hovd Time	UTC+07
    "ICT",   // Indochina Time	UTC+07
    "IDLW",  // International Date Line West time zone	UTC−12
    "IDT",   // Israel Daylight Time	UTC+03
    "IOT",   // Indian Ocean Time	UTC+03
    "IRDT",  // Iran Daylight Time	UTC+04:30
    "IRKT",  // Irkutsk Time	UTC+08
    "IRST",  // Iran Standard Time	UTC+03:30
    "IST",   // Indian Standard Time	UTC+05:30
    "IST",   // Irish Standard Time[8]	UTC+01
    "IST",   // Israel Standard Time	UTC+02
    "JST",   // Japan Standard Time	UTC+09
    "KALT",  // Kaliningrad Time	UTC+02
    "KGT",   // Kyrgyzstan Time	UTC+06
    "KOST",  // Kosrae Time	UTC+11
    "KRAT",  // Krasnoyarsk Time	UTC+07
    "KST",   // Korea Standard Time	UTC+09
    "LHST",  // Lord Howe Standard Time	UTC+10:30
    "LHST",  // Lord Howe Summer Time	UTC+11
    "LINT",  // Line Islands Time	UTC+14
    "MAGT",  // Magadan Time	UTC+12
    "MART",  // Marquesas Islands Time	UTC−09:30
    "MAWT",  // Mawson Station Time	UTC+05
    "MDT",   // Mountain Daylight Time (North America)	UTC−06
    "MET",   // Middle European Time (same zone as CET)	UTC+01
    "MEST",  // Middle European Summer Time (same zone as CEST)	UTC+02
    "MHT",   // Marshall Islands Time	UTC+12
    "MIST",  // Macquarie Island Station Time	UTC+11
    "MIT",   // Marquesas Islands Time	UTC−09:30
    "MMT",   // Myanmar Standard Time	UTC+06:30
    "MSK",   // Moscow Time	UTC+03
    "MST",   // Malaysia Standard Time	UTC+08
    "MST",   // Mountain Standard Time (North America)	UTC−07
    "MUT",   // Mauritius Time	UTC+04
    "MVT",   // Maldives Time	UTC+05
    "MYT",   // Malaysia Time	UTC+08
    "NCT",   // New Caledonia Time	UTC+11
    "NDT",   // Newfoundland Daylight Time	UTC−02:30
    "NFT",   // Norfolk Island Time	UTC+11
    "NOVT",  // Novosibirsk Time [9]	UTC+07
    "NPT",   // Nepal Time	UTC+05:45
    "NST",   // Newfoundland Standard Time	UTC−03:30
    "NT",    // Newfoundland Time	UTC−03:30
    "NUT",   // Niue Time	UTC−11
    "NZDT",  // New Zealand Daylight Time	UTC+13
    "NZST",  // New Zealand Standard Time	UTC+12
    "OMST",  // Omsk Time	UTC+06
    "ORAT",  // Oral Time	UTC+05
    "PDT",   // Pacific Daylight Time (North America)	UTC−07
    "PET",   // Peru Time	UTC−05
    "PETT",  // Kamchatka Time	UTC+12
    "PGT",   // Papua New Guinea Time	UTC+10
    "PHOT",  // Phoenix Island Time	UTC+13
    "PHT",   // Philippine Time	UTC+08
    "PHST",  // Philippine Standard Time	UTC+08
    "PKT",   // Pakistan Standard Time	UTC+05
    "PMDT",  // Saint Pierre and Miquelon Daylight Time	UTC−02
    "PMST",  // Saint Pierre and Miquelon Standard Time	UTC−03
    "PONT",  // Pohnpei Standard Time	UTC+11
    "PST",   // Pacific Standard Time (North America)	UTC−08
    "PWT",   // Palau Time[10]	UTC+09
    "PYST",  // Paraguay Summer Time[11]	UTC−03
    "PYT",   // Paraguay Time[12]	UTC−04
    "RET",   // Réunion Time	UTC+04
    "ROTT",  // Rothera Research Station Time	UTC−03
    "SAKT",  // Sakhalin Island Time	UTC+11
    "SAMT",  // Samara Time	UTC+04
    "SAST",  // South African Standard Time	UTC+02
    "SBT",   // Solomon Islands Time	UTC+11
    "SCT",   // Seychelles Time	UTC+04
    "SDT",   // Samoa Daylight Time	UTC−10
    "SGT",   // Singapore Time	UTC+08
    "SLST",  // Sri Lanka Standard Time	UTC+05:30
    "SRET",  // Srednekolymsk Time	UTC+11
    "SRT",   // Suriname Time	UTC−03
    "SST",   // Samoa Standard Time	UTC−11
    "SST",   // Singapore Standard Time	UTC+08
    "SYOT",  // Showa Station Time	UTC+03
    "TAHT",  // Tahiti Time	UTC−10
    "THA",   // Thailand Standard Time	UTC+07
    "TFT",   // French Southern and Antarctic Time[13]	UTC+05
    "TJT",   // Tajikistan Time	UTC+05
    "TKT",   // Tokelau Time	UTC+13
    "TLT",   // Timor Leste Time	UTC+09
    "TMT",   // Turkmenistan Time	UTC+05
    "TRT",   // Turkey Time	UTC+03
    "TOT",   // Tonga Time	UTC+13
    "TST",   // Taiwan Standard Time	UTC+08
    "TVT",   // Tuvalu Time	UTC+12
    "ULAST", // Ulaanbaatar Summer Time	UTC+09
    "ULAT",  // Ulaanbaatar Standard Time	UTC+08
    "UTC",   // Coordinated Universal Time	UTC±00
    "UYST",  // Uruguay Summer Time	UTC−02
    "UYT",   // Uruguay Standard Time	UTC−03
    "UZT",   // Uzbekistan Time	UTC+05
    "VET",   // Venezuelan Standard Time	UTC−04
    "VLAT",  // Vladivostok Time	UTC+10
    "VOLT",  // Volgograd Time	UTC+03
    "VOST",  // Vostok Station Time	UTC+06
    "VUT",   // Vanuatu Time	UTC+11
    "WAKT",  // Wake Island Time	UTC+12
    "WAST",  // West Africa Summer Time	UTC+02
    "WAT",   // West Africa Time	UTC+01
    "WEST",  // Western European Summer Time	UTC+01
    "WET",   // Western European Time	UTC±00
    "WIB",   // Western Indonesian Time	UTC+07
    "WIT",   // Eastern Indonesian Time	UTC+09
    "WITA",  // Central Indonesia Time	UTC+08
    "WGST",  // West Greenland Summer Time[14]	UTC−02
    "WGT",   // West Greenland Time[15]	UTC−03
    "WST",   // Western Standard Time	UTC+08
    "YAKT",  // Yakutsk Time	UTC+09
    "YEKT",  // Yekaterinburg Time 
  )
  private lazy val NumberPattern = "[+-]?([0-9]+([.][0-9]*)?|[.][0-9]+)".r

  // quick heuristic rejection of non-date strings 
  private def isPossibleDateString(s: String): Boolean = {

    def isDecimalNum: Boolean = NumberPattern.matches(s) && s.replaceAll("[^.]+", "").length == 1

    if (s.length < 4 || s.length > 35 || isDecimalNum ) {
      false
    } else {
      val lc = s.toLowerCase.
        replaceAll("[jfmasond][aerpuco][nbrylgptvc][uaryrchilestmbo]{0,6}", "1").
        replaceAll("[mtwfs][ouehra][neduit][daysneru]{0,4}", "2")

      var digits = 0
      var nondigits = 0
      var bogus = 0
      val validchars = lc.filter { (c: Char) =>
        c match {
        case c if c >= '0' && c <= '9' =>
          digits += 1
          true
        case ':' | '-' | '.' | '/' =>
          nondigits += 1
          true
        case _ =>
          bogus += 1
          false
        }
      }
      if verboseUni then printf("digits: %d, nondigits: %d, bogus: %d\n", digits, nondigits, bogus)
      val density = 100.0 * validchars.size.toDouble / s.length.toDouble
      val proportion = 100.0 * nondigits.toDouble / (digits+1.0)
      digits >= 3 && digits <= 19 && density >= 30.0 && proportion < 35.0
    }
  }

  def monthAbbrev2Number(name: String): Int = {
    name.toLowerCase.substring(0, 3) match {
    case "jan" => 1
    case "feb" => 2
    case "mar" => 3
    case "apr" => 4
    case "may" => 5
    case "jun" => 6
    case "jul" => 7
    case "aug" => 8
    case "sep" => 9
    case "oct" => 10
    case "nov" => 11
    case "dec" => 12
    case _ =>
      hook += 1
      -1
    }
  }
}

// TODO: use timezone info, including CST, etc
case class ChronoParse(dateTime: LocalDateTime, clean: String, timezone: String, formats: Seq[String], valid: Boolean) {
  override def toString: String = dateTime.toString("yyyy-MM-dd HH:mm:ss")
}
