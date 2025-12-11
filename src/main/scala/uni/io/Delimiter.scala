package uni.io

import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.Path
import scala.io.Source
import scala.util.Using

object Delimiter {
  // Final configuration fields
  final val candidates: Array[Char] = Array(',', '\t', ';', '|')
  final val escape: Char = '\\'
  final val quote: Char = '"'

  final case class DelimState(
    delimiter: Char,
    fieldsCount: Int = 0,              // current row-in-progress
    score: Int = 0,                    // raw delimiter hits
    inQuotes: Boolean = false,
    escaped: Boolean = false,
    rowCounts: Vector[Int] = Vector.empty,
    partialCounts: Vector[Int] = Vector.empty // NEW: widths from truncated rows
  ) {
    def rowsExamined = rowCounts.size
    def partialRows: Int = partialCounts.size   // <-- add this

    /** Flush the current row into rowCounts (complete or truncated). */
    def recordRow(partial: Boolean = false): DelimState = {
      val width = fieldsCount + 1
      if (partial)
        copy(partialCounts = partialCounts :+ width, fieldsCount = 0)
      else
        copy(rowCounts = rowCounts :+ width, fieldsCount = 0)
    }

    /** Most common width among completed rows. */
    def modeColumns: Int =
      if (rowCounts.isEmpty) 0
      else rowCounts.groupBy(identity).view.mapValues(_.size).maxBy(_._2)._1

    /** Number of distinct widths observed in completed rows. */
    def widthDistinct: Int = rowCounts.distinct.size

    /** How many rows match the mode width (higher is better). */
    def modeSupport: Int =
      if (rowCounts.isEmpty) 0
      else rowCounts.count(_ == modeColumns)

    /** Best-effort width from truncated rows, if no full rows recorded. */
    def partialMode: Int =
      if (partialCounts.isEmpty) 0
      else partialCounts.groupBy(identity).view.mapValues(_.size).maxBy(_._2)._1

    def bestWidth: Int = if (modeColumns > 0) modeColumns else partialMode

    override def toString: String =
      f"fields: $fieldsCount%5d, score: $score%3d, " +
      f"mode: $modeColumns%4d (rows=$rowsExamined%3d), " +
      f"partialMode: $partialMode%4d (partials=$partialRows), " +
      f"bestWidth: $bestWidth%4d, delimiter == [$delimiter]"
  }

  // Detect winning delimiter state
    def detect(
      path: Path,
      maxRows: Int,
      dominanceFactor: Int = 2,
      checkInterval: Int = 100,
      maxCharsPerRow: Int = 8000
    ): DelimState = {

      var states = candidates.map(d => DelimState(d)).toVector
      val rows = charStreamWithFallback(path, maxRows).take(maxRows)

      var winnerDelim: Option[Char] = None

      for (row <- rows if winnerDelim.isEmpty) {
        var idx = 0
        val it = row
        var truncated = false

        while (it.hasNext && winnerDelim.isEmpty && idx < maxCharsPerRow) {
          val c = it.next()
          states = states.map { s =>
            var st = s
            if (st.escaped) st = st.copy(escaped = false)
            else if (c == escape) st = st.copy(escaped = true)
            else if (c == quote) st = st.copy(inQuotes = !st.inQuotes)
            else if (c == st.delimiter && !st.inQuotes)
              st = st.copy(fieldsCount = st.fieldsCount + 1, score = st.score + 1)
            st
          }

          if (idx % checkInterval == 0) {
            dominantState(states, dominanceFactor).foreach { best =>
              winnerDelim = Some(best.delimiter) // note: only store the delimiter
            }
          }
          idx += 1
        }

        // if we didn’t consume the whole row, it’s truncated (either maxCharsPerRow or winner mid-row)
        if (it.hasNext || winnerDelim.nonEmpty) truncated = true

        // flush as complete or partial for ALL candidates
        states = states.map(_.recordRow(partial = truncated))

        // if no winner yet, re-check after we have rowCounts/partialCounts
        if (winnerDelim.isEmpty) {
          dominantState(states, dominanceFactor).foreach { best =>
            winnerDelim = Some(best.delimiter)
          }
        }
      }

      // Final selection: return the up-to-date state object for the winner delimiter
      winnerDelim
        .flatMap(d => states.find(_.delimiter == d))
        .getOrElse(states.maxBy(s => (s.score, -s.rowCounts.distinct.size)))
    }

  // Split a single row lazily into fields
  def splitRow(row: Iterator[Char], delim: Char): Iterator[String] = new Iterator[String] {
    private val buf = new StringBuilder
    private var inQuotes = false
    private var escaped = false
    private var nextField: Option[String] = None

    private def advance(): Unit = {
      while (row.hasNext && nextField.isEmpty) {
        val c = row.next()
        if (escaped) {
          buf.append(c); escaped = false
        } else if (c == escape) {
          escaped = true
        } else if (c == quote) {
          inQuotes = !inQuotes
        } else if (c == delim && !inQuotes) {
          nextField = Some(buf.result()); buf.clear()
        } else {
          buf.append(c)
        }
      }
      if (!row.hasNext && buf.nonEmpty) {
        nextField = Some(buf.result()); buf.clear()
      }
    }

    def hasNext: Boolean = { if (nextField.isEmpty) advance(); nextField.nonEmpty }
    def next(): String = { if (hasNext) { val f = nextField.get; nextField = None; f } else Iterator.empty.next() }
  }

  // Full splitter: detect delimiter, then stream fields row by row
  def lazySplitter(path: Path, sampleRows: Int): Iterator[Iterator[String]] = {
    val winnerState = detect(path, sampleRows)
    val rows = charStreamWithFallback(path, Int.MaxValue) // reopen full file
    rows.map(row => splitRow(row, winnerState.delimiter))
  }

  // Dominance check
  def dominantState(states: Iterable[DelimState], factor: Int = 2): Option[DelimState] = {
    if (states.isEmpty) None
    else {
      // Rank: consistency first, then mode support, then raw score
      def rank(s: DelimState) = (-s.widthDistinct, s.modeSupport, s.score)
      val best = states.maxBy(rank)

      // Dominance condition that respects consistency:
      // best must be at least as consistent as others, and if equally consistent,
      // it should have >= mode support and a clearly higher score.
      val dominance = states.forall { s =>
        s.delimiter == best.delimiter ||
        best.widthDistinct < s.widthDistinct ||
        (best.widthDistinct == s.widthDistinct &&
          (best.modeSupport > s.modeSupport ||
           (best.modeSupport == s.modeSupport && best.score > s.score * factor)))
      }

      if (dominance) Some(best) else None
    }
  }

  // Charset fallback reader: yields rows as Iterator[Char]
  private def charStreamWithFallback(path: Path, maxRows: Int): Iterator[Iterator[Char]] = {
    val charsets = Seq(
      StandardCharsets.UTF_8,
      Charset.forName("windows-1252"),
      StandardCharsets.ISO_8859_1,
      StandardCharsets.US_ASCII,
      Charset.forName("UTF-16"),
      Charset.forName("UTF-16LE"),
      Charset.forName("UTF-16BE"),
      Charset.forName("UTF-32"),
      Charset.forName("ISO-8859-15"),
      Charset.forName("MacRoman"),
      Charset.forName("Shift_JIS"),
      Charset.forName("GB18030")
    )

    charsets.iterator
      .collectFirst {
        case cs =>
          try {
            Using.resource(Source.fromFile(path.toFile, cs.name)) { src =>
              src.getLines()
                .take(maxRows)
                .map(_.iterator)
                .toList
                .iterator
            }
          } catch {
            case _: java.nio.charset.MalformedInputException => null
          }
      }
      .filter(_ != null)
      .getOrElse(Iterator.empty)
  }

}

object TestDiagnostics {
  def summarize(res: Delimiter.DelimState): String =
    s"winner=${res.delimiter}, score=${res.score}, mode=${res.modeColumns}"

  def candidatesSummary(path: java.nio.file.Path, maxRows: Int): String = {
    // Re-run detection but capture the internal states snapshot at the end
    var states = Delimiter.candidates.map(d => Delimiter.DelimState(d)).toVector
    val rows = {
      val it = Delimiter
        .getClass
        .getDeclaredMethod("charStreamWithFallback", classOf[java.nio.file.Path], classOf[Int])
      it.setAccessible(true)
      it.invoke(Delimiter, path, Int.box(maxRows)).asInstanceOf[Iterator[Iterator[Char]]]
    }

    for (row <- rows) {
      var inQuotes = false
      var escaped = false
      val fields = Array.fill(Delimiter.candidates.length)(0)
      val it = row
      while (it.hasNext) {
        val c = it.next()
        if (escaped) escaped = false
        else if (c == Delimiter.escape) escaped = true
        else if (c == Delimiter.quote) inQuotes = !inQuotes
        else if (!inQuotes) {
          for (i <- Delimiter.candidates.indices) {
            if (c == Delimiter.candidates(i)) fields(i) += 1
          }
        }
      }
      states = states.zip(fields).map { case (s, f) =>
        s.copy(fieldsCount = f, score = s.score + f).recordRow()
      }
    }

    states
      .sortBy(s => (-s.rowCounts.distinct.size, -s.modeSupport, -s.score))
      .map(s => s"delim=${s.delimiter} widths=${s.rowCounts.mkString("[", ",", "]")} mode=${s.modeColumns} score=${s.score}")
      .mkString("\n")
  }
}
