package uni.io

import java.nio.file.Path
import java.io.{FileWriter, OutputStreamWriter, PrintWriter}
import java.util.Locale
import scala.reflect.ClassTag
import uni.*
import uni.data.*
import uni.data.Big.Big
import uni.data.Mat.*

export FileOps.*

object FileOps {
  def withFileWriter(p: Path, charsetName: String = "UTF-8", append: Boolean = false)
      (func: PrintWriter => Any): Unit =
    val jfile  = p.toFile
    val lcname = jfile.getName.toLowerCase(Locale.ROOT)

    if lcname != "stdout" then
      Option(jfile.getParentFile) match
        case Some(parent) =>
          if !parent.exists then
            throw new IllegalArgumentException(s"parent directory not found [$parent]")
        case None =>
          throw new IllegalArgumentException("no parent directory")

    val writer =
      if lcname == "stdout" then
        new PrintWriter(new OutputStreamWriter(System.out, charsetName), true)
      else
        new PrintWriter(new FileWriter(jfile, append))

    try func(writer)
    finally
      writer.flush()
      if lcname != "stdout" then writer.close()

  /** * Loads a CSV into a Mat[T]. 
   * @param path The file path
   * @param skipHeader Whether to skip the first row
   * @param map Function to convert String columns to T (e.g., _.toDouble or Big(_))
   */
  def loadCSV[T: ClassTag](
    pathString: String, 
    skipHeader: Boolean = true, 
    map: String => T
  ): Mat[T] = {
    val path: Path = uni.Paths.get(pathString)
    val rows = path.csvRows.toVector
    val dataRows = if (skipHeader && rows.nonEmpty) rows.tail else rows
    
    if dataRows.isEmpty then
      create(Array.empty[T], 0, 0)
    else
      val numRows = dataRows.length
      val numCols = dataRows.head.length
      val flatData = new Array[T](numRows * numCols)
      
      var r = 0
      while (r < numRows) {
        val row = dataRows(r)
        var c = 0
        while (c < numCols) {
          flatData(r * numCols + c) = map(row(c))
          c += 1
        }
        r += 1
      }
      create(flatData, numRows, numCols)
  }

  case class MatResult[T](headers: Vector[String], mat: Mat[T])

  /** * The identity version: returns MatResult[Big]
   * Perfect for financial data where you want to stay in Big.
   */
  def loadSmart(p: Path): MatResult[Big] = 
    loadSmart(p, identity)

  /** * The generic version: returns MatResult[T]
   * Used when you want to transform to Double, Int, etc.
   */
  def loadSmart[T: ClassTag](p: Path, map: Big => T): MatResult[T] = {
    // 1. Get ALL rows from the CSV
    val allRows = p.csvRows.toVector
    if allRows.isEmpty then
      MatResult(Vector.empty, create(Array.empty[T], 0, 0))
    else
      val width = allRows.head.length
      val alignedRows = allRows.filter(_.length == width)

      // 2. HEADER DETECTION
      // We only consider a header if we have at least TWO rows
      // AND the first row looks like text labels while the second looks like numbers.
      var headerOffset = 0
      if (alignedRows.size > 1) {
        val row0 = alignedRows(0)
        val row1 = alignedRows(1)
        
        // Logic: Is row 0 non-numeric and row 1 numeric?
        val row0IsText = row0.forall(s => big(s).isNaN)
        val row1IsData = row1.exists(s => !big(s).isNaN)
        
        if (row0IsText && row1IsData) {
          headerOffset = 1
        }
      }

      // 3. SLICE DATA
      val headers = if (headerOffset == 1) alignedRows(0).map(_.trim).toVector else Vector.empty[String]
      val dataRows = alignedRows.drop(headerOffset)

      // 4. REIFICATION
      val numRows = dataRows.length
      if (numRows == 0) return MatResult(headers, create(Array.empty[T], 0, width))

      val flatData = new Array[T](numRows * width)
      var r = 0
      while (r < numRows) {
        var c = 0
        while (c < width) {
          // Direct indexing to avoid any row-skipping "magic"
          flatData(r * width + c) = map(big(dataRows(r)(c)))
          c += 1
        }
        r += 1
      }

      MatResult(headers, create(flatData, numRows, width))
  }
}
