package uni.ext

import scala.math.BigDecimal
import scala.math.BigDecimal.RoundingMode

object helpers:

  def round(number: Double, scale: Int = 6): Double =
    BigDecimal(number).setScale(scale, RoundingMode.HALF_UP).toDouble

  def isValidWindowsPath(s: String): Boolean =
    val t = s.trim
    val driveAbs   = "^[A-Za-z]:[\\\\/].*".r
    val unc        = "^\\\\\\\\[^\\\\]+\\\\[^\\\\]+.*".r
    val device     = "^\\\\\\\\[.?]\\\\.*".r
    val winRel     = "^[^/]*\\\\.*".r
    val embedded   = ".*[A-Za-z]:[\\\\/].*".r

    t match
      case driveAbs()  => true
      case unc()       => true
      case device()    => true
      case winRel()    => true
      case embedded()  => true
      case _           => false

  def isValidMsysPath(s: String): Boolean =
    val t = s.trim
    val tildeUser = "^~[A-Za-z0-9._-]+/.*".r
    val msysDrive = "^/[A-Za-z]/.*".r
    val optValue  = """.*=[^=]*[/~].*""".r

    if t == "~" then true
    else if t.startsWith("~/") then true
    else if tildeUser.matches(t) then true
    else if t.startsWith("/") && !t.startsWith("//") then true
    else if msysDrive.matches(t) then true
    else if t.contains("/") && !isValidWindowsPath(t) then true
    else if optValue.matches(t) && !isValidWindowsPath(t) then true
    else false

//  def withFileWriter(p: Path, charsetName: String = "UTF-8", append: Boolean = false)
//      (func: PrintWriter => Any): Unit =
//    val jfile  = p.toFile
//    val lcname = jfile.getName.toLowerCase(Locale.ROOT)
//
//    if lcname != "stdout" then
//      Option(jfile.getParentFile) match
//        case Some(parent) =>
//          if !parent.exists then
//            throw new IllegalArgumentException(s"parent directory not found [$parent]")
//        case None =>
//          throw new IllegalArgumentException("no parent directory")
//
//    val writer =
//      if lcname == "stdout" then
//        new PrintWriter(new OutputStreamWriter(System.out, charsetName), true)
//      else
//        new PrintWriter(new FileWriter(jfile, append))
//
//    try func(writer)
//    finally
//      writer.flush()
//      if lcname != "stdout" then writer.close()
