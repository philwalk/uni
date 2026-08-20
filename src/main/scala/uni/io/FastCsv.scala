package uni.io

import java.nio.file.{Files, Path}
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.StandardOpenOption.READ

//export Delimiter.detect as autoDetectDelimiter

object FastCsv {
  /** Rows worth returning: at least one field with non-whitespace content.
   *
   *  Named once because the two readers used to filter differently — `rowsPulled`
   *  by this rule, `rowsAsync` by `_.size > 1` — and both carried the same comment
   *  claiming to "discard if empty or text-with-no-delimiter". Neither implemented
   *  that, and `_.size > 1` dropped *every* row of a single-column file. A blank
   *  row is dropped; a single-field row is kept, because one column is a legitimate
   *  CSV and losing it silently is worse than keeping a delimiter-less line.
   */
  private def isContentRow(row: Seq[String]): Boolean = row.exists(_.trim.nonEmpty)

  /** Pads every row out to the widest, so the result is rectangular. Opt-in.
   *
   *  The readers report rows as parsed; this is what a caller applies when it wants
   *  a rectangle. `FastCsv.rectangular(p.csvRows)` reproduces 0.16.0–0.18.x output.
   *  The matrix loaders (`loadSmart`, `loadCSV`) call it themselves, because blind
   *  `row(c)` indexing requires it.
   *
   *  Widest rather than first-row width on purpose: keying off the first row means
   *  truncating any row longer than it, which is data loss. The failure mode of the
   *  choice made here is the mirror image — one spuriously wide row inflates every
   *  other row to the outlier's width. `schema` shows which case a file is in.
   */
  def rectangular(rows: Seq[Seq[String]]): Seq[Seq[String]] =
    if rows.isEmpty then rows
    else
      val width = rows.map(_.length).max
      if rows.forall(_.length == width) then rows
      else rows.map(r => if r.length == width then r else r.padTo(width, ""))

  /** Shape report for parsed rows: arity histogram and modal arity (ties resolve
   *  wider, never losing fields). A report, never a transformation — when the modal
   *  call is wrong, the outvoted widths are visible in `widths` and no row was
   *  altered. */
  final case class CsvSchema(widths: Map[Int, Int], arity: Int)

  /** Folds width counts in constant memory, so an iterator is as good as a Seq —
   *  `schema(p.csvRowsStream)` never holds the file. It consumes what it is given:
   *  an iterator is spent afterwards, so read rows from a fresh one. */
  def schema(rows: IterableOnce[Seq[String]]): CsvSchema =
    val widths = rows.iterator.foldLeft(Map.empty[Int, Int]): (acc, row) =>
      acc.updatedWith(row.size)(n => Some(n.getOrElse(0) + 1))
    // Most rows wins; a tie goes to the wider arity, so a tie never loses fields.
    val arity = widths.maxByOption((w, n) => (n, w)).fold(0)(_._1)
    CsvSchema(widths, arity)

  final case class Config(
    delimiterChar: Option[Char] = None,
    quoteChar: Char = '"',
    charset: Charset = StandardCharsets.UTF_8,
    bufferSize: Int = 1 << 20, // 1 MiB
    initialFieldSize: Int = 128
  ) {
    val quote: Byte = quoteChar.toByte
    def delimiter: Option[Byte] = delimiterChar.map(_.toByte)
    override def toString = "delimiter: [%c]".format(delimiterChar.getOrElse('?'))
  }

  trait RowSink {
    def onRow(fields: Array[Array[Byte]]): Unit
  }

  /** Pull-style API: return Iterator[Seq[String]] */
  def rowsPulled(
    path: Path,
    cfg: Config = Config(),
    sampleRows: Int = 100
  ): Iterator[Seq[String]] = {
    new Iterator[Seq[String]] {
      private val delimiter: Byte = cfg.delimiter.getOrElse {
        val res = Delimiter.detect(path, sampleRows)
        res.delimiterChar.toByte
      }
      private val parser = new RowParser(cfg, delimiter)
      private val ch = Files.newByteChannel(path, READ).asInstanceOf[FileChannel]
      private val buf = ByteBuffer.allocateDirect(cfg.bufferSize)

      // Queue of decoded rows
      private val rowQueue = scala.collection.mutable.Queue.empty[Seq[String]]
      private var eof = false

      private def advance(): Unit = {
        while (rowQueue.isEmpty && !eof) {
          buf.clear()
          val read = ch.read(buf)
          if (read <= 0) {
            eof = true
            parser.eof().foreach(r => rowQueue.enqueue(decodeFields(r, cfg.charset).toSeq))
            ch.close()
          } else {
            buf.flip()
            while (buf.hasRemaining) {
              parser.feed(buf.get()) match {
                case Some(r) =>
                  rowQueue.enqueue(decodeFields(r, cfg.charset).toSeq)
                case None =>
              }
            }
          }
        }
      }

      override def hasNext: Boolean = {
        advance()
        rowQueue.nonEmpty
      }

      override def next(): Seq[String] = {
        if (hasNext) rowQueue.dequeue()
        else throw new NoSuchElementException("No more rows")
      }
    }.filter(isContentRow)
  }

  /** Queue filled by background thread: return Iterator[Seq[String]] */
  def rowsAsync(
    path: Path,
    cfg: Config = Config(),
    sampleRows: Int = 100,
    queueCapacity: Int = 1024
  ): Iterator[Seq[String]] = {
    import java.util.concurrent.LinkedBlockingQueue

    val delimiterChar: Char = cfg.delimiterChar.getOrElse {
      val res = Delimiter.detect(path, sampleRows)
      res.delimiterChar
    }
    val parser = new RowParser(cfg, delimiterChar.toByte)
    val ch = Files.newByteChannel(path, READ).asInstanceOf[FileChannel]
    val buf = ByteBuffer.allocateDirect(cfg.bufferSize)

    // Bounded queue for back-pressure
    val queue = new LinkedBlockingQueue[Option[Seq[String]]](queueCapacity)

    // A mid-read failure must not masquerade as end-of-stream: `rowsPulled` throws
    // in the same situation, and the readers must agree. The throwable is carried
    // across the thread boundary here and rethrown once the queue drains — the
    // volatile write happens before the end-of-stream marker is enqueued, so the
    // consumer that sees the marker sees the failure too.
    @volatile var failure: Throwable = null

    // Background thread fills the queue
    val producer = new Thread(() => {
      try {
        while (ch.read(buf) > 0) {
          buf.flip()
          while (buf.hasRemaining) {
            parser.feed(buf.get()) match {
              case Some(row) =>
                val decoded = decodeFields(row, cfg.charset).toSeq
                queue.put(Some(decoded)) // blocks if full
              case None =>
            }
          }
          buf.clear()
        }
        parser.eof().foreach { row =>
          val decoded = decodeFields(row, cfg.charset).toSeq
          queue.put(Some(decoded))
        }
      } catch {
        case e: Throwable => failure = e
      } finally {
        ch.close()
        queue.put(None) // end-of-stream marker
      }
    })
    producer.setDaemon(true)
    producer.start()

    // Foreground iterator consumes from queue
    new Iterator[Seq[String]] {
      private var nextRow: Option[Seq[String]] = None
      // The producer puts exactly one end-of-stream marker. Without this flag a
      // second `hasNext` after exhaustion takes from a queue nobody will ever
      // fill again and blocks forever -- `hasNext` must be idempotent.
      private var drained = false

      private def advance(): Unit = {
        if (nextRow.isEmpty && !drained) {
          nextRow = queue.take() // blocks until row or None
          if (nextRow.isEmpty) {
            drained = true
            if (failure != null) throw failure
          }
        }
      }

      override def hasNext: Boolean = {
        advance()
        nextRow.nonEmpty
      }

      override def next(): Seq[String] = {
        if (hasNext) {
          val r = nextRow.get
          nextRow = None
          r
        } else throw new NoSuchElementException("No more rows")
      }
    }.filter(isContentRow)
  }

  /** Synchronous blocking API -- parse and send rows to sink */
  def eachRow(
    path: Path,
    cfg: Config = Config(),
    sampleRows: Int = 100
  )(onRow: Seq[String] => Unit): Unit = {
    val delimiter: Byte = cfg.delimiter.getOrElse {
      val res = Delimiter.detect(path, sampleRows)
      res.delimiterChar.toByte
    }
    val parser = new RowParser(cfg, delimiter)
    val ch = Files.newByteChannel(path, READ).asInstanceOf[FileChannel]
    val buf = ByteBuffer.allocateDirect(cfg.bufferSize)

    // Same row rules as the iterator readers: `PathExts.csvRows` has a callback
    // overload backed by this method, and the two must not disagree about which
    // rows a file contains just because of how the caller asked for them.
    val emit: Seq[String] => Unit = row =>
      if isContentRow(row) then onRow(row)

    while (ch.read(buf) > 0) {
      buf.flip()
      while (buf.hasRemaining) {
        parser.feed(buf.get()) match {
          case Some(row) =>
            emit(decodeFields(row, cfg.charset).toSeq)
          case None =>
        }
      }
      buf.clear()
    }
    parser.eof().foreach(r => emit(decodeFields(r, cfg.charset).toSeq))
    ch.close()
  }

  /** Push-style API: parse file and send rows to sink */
  def parse(path: Path, sink: RowSink, cfg: Config = Config(), sampleRows: Int = 100): Unit = {
    val delimiter: Byte = cfg.delimiter.getOrElse {
      val res = Delimiter.detect(path, sampleRows)
      res.delimiterChar.toByte
    }
    val parser = new RowParser(cfg, delimiter)
    val ch = Files.newByteChannel(path, READ).asInstanceOf[FileChannel]
    val buf = ByteBuffer.allocateDirect(cfg.bufferSize)

    while (ch.read(buf) > 0) {
      buf.flip()
      while (buf.hasRemaining) {
        parser.feed(buf.get()) match {
          case Some(row) => sink.onRow(row)
          case None      =>
        }
      }
      buf.clear()
    }
    parser.eof().foreach(sink.onRow)
    ch.close()
  }

  def decodeFields(fields: Array[Array[Byte]], cs: Charset): Array[String] = {
    val out = new Array[String](fields.length)
    var i = 0
    while (i < fields.length) {
      out(i) = new String(fields(i), cs)
      i += 1
    }
    out
  }

  /** Core parser state machine, reusable by both push and pull APIs */
  final class RowParser(cfg: Config, delimiter: Byte) {
    private var field: Array[Byte] = new Array[Byte](cfg.initialFieldSize)
    private var fieldLen = 0
    private var fields: Array[Array[Byte]] = new Array[Array[Byte]](16)
    private var fieldCount = 0
    private var inQuotes = false
    private var pendingCR = false
    private var prevWasQuote = false
    private var fieldWasQuoted = false
    private var hasSeenQuoteInRow = false

    inline private def ensureFieldCapacity(n: Int): Unit = {
      if (n > field.length) {
        var cap = field.length
        while (cap < n) cap <<= 1
        val nf = new Array[Byte](cap)
        System.arraycopy(field, 0, nf, 0, fieldLen)
        field = nf
      }
    }
    inline private def append(b: Byte): Unit = {
      val next = fieldLen + 1
      ensureFieldCapacity(next)
      field(fieldLen) = b
      fieldLen = next
    }

    inline private def trimBytes(raw: Array[Byte]): Array[Byte] = {
      var start = 0
      var end = raw.length - 1

      // ASCII whitespace: space, tab, CR, LF
      while (start <= end && raw(start) <= ' ') start += 1
      while (end >= start && raw(end) <= ' ') end -= 1

      if (start == 0 && end == raw.length - 1) raw
      else java.util.Arrays.copyOfRange(raw, start, end + 1)
    }

    inline private def emitField(): Unit = {
      val raw = java.util.Arrays.copyOf(field, fieldLen)
      val cleaned =
        if fieldWasQuoted then raw
        else trimBytes(raw)
      if (fieldCount == fields.length) {
        val nf = new Array[Array[Byte]](fields.length << 1)
        System.arraycopy(fields, 0, nf, 0, fields.length)
        fields = nf
      }
      fields(fieldCount) = cleaned
      fieldCount += 1
      fieldLen = 0
      fieldWasQuoted = false
    }
    inline private def emitRow(): Array[Array[Byte]] = {
      val row = new Array[Array[Byte]](fieldCount)
      System.arraycopy(fields, 0, row, 0, fieldCount)
      fieldCount = 0
      row
    }

    /** Feed one byte; return Some(row) when a row completes */
    def feed(b: Byte): Option[Array[Array[Byte]]] = {
      if (pendingCR) {
        pendingCR = false
        if (b == '\n') {
          None
        } else {
          feedCore(b)
        }
      } else {
        feedCore(b)
      }
    }

    inline private def feedCore(b: Byte): Option[Array[Array[Byte]]] = {
      // Fast path: no quotes seen yet, not currently in quotes
      if (!inQuotes && !hasSeenQuoteInRow) {
        if (b == delimiter) {
          emitField()
          None
        } else if (b == '\n') {
          emitField()
          hasSeenQuoteInRow = false
          Some(emitRow())
        } else if (b == '\r') {
          emitField()
          val r = emitRow()
          pendingCR = true
          hasSeenQuoteInRow = false
          Some(r)
        } else if (b == cfg.quote) {
          inQuotes = true
          prevWasQuote = false
          fieldWasQuoted = true
          hasSeenQuoteInRow = true
          None
        } else {
          append(b)
          None
        }

      } else if (inQuotes) {

        if (b == cfg.quote) {
          // Either start of escaped quote or potential closing quote
          if (prevWasQuote) {
            // Escaped quote: "" → "
            append(cfg.quote)
            prevWasQuote = false
          } else {
            // Possible closing quote; need next byte to decide
            prevWasQuote = true
          }
          None

        } else {
          if (prevWasQuote) {
            // We just saw a quote; decide if it was closing or literal
            if (b == delimiter || b == '\n' || b == '\r') {
              // Real closing quote
              inQuotes = false
              prevWasQuote = false

              if (b == delimiter) {
                emitField()
                None
              } else if (b == '\n') {
                emitField()
                hasSeenQuoteInRow = false
                Some(emitRow())
              } else { // '\r'
                emitField()
                val r = emitRow()
                pendingCR = true
                hasSeenQuoteInRow = false
                Some(r)
              }

            } else {
              // Not a valid closing quote → treat previous quote as literal
              append(cfg.quote)
              append(b)
              prevWasQuote = false
              None
            }

          } else {
            // Normal character inside quotes
            append(b)
            None
          }
        }

      } else {
        // Not inQuotes, but we *have* seen a quote earlier in this row
        if (b == delimiter) {
          emitField()
          None
        } else if (b == '\n') {
          emitField()
          hasSeenQuoteInRow = false
          Some(emitRow())
        } else if (b == '\r') {
          emitField()
          val r = emitRow()
          pendingCR = true
          hasSeenQuoteInRow = false
          Some(r)
        } else if (b == cfg.quote) {
          inQuotes = true
          prevWasQuote = false
          fieldWasQuoted = true
          None
        } else {
          append(b)
          None
        }
      }
    }

    /** Flush final row at EOF if needed */
    def eof(): Option[Array[Array[Byte]]] = {
      if (fieldLen > 0 || fieldCount > 0) {
        emitField()
        Some(emitRow())
      } else None
    }
  }


  def parseCsvLine(line: String, cfg: Config = Config()): Seq[String] = {
    // default to comma (the byte literal for a comma: 44.toByte or ',')
    val delimiter = cfg.delimiter.getOrElse(','.toByte)
    val parser = new RowParser(cfg, delimiter)
    val bytes = line.getBytes(cfg.charset)
    
    // Feed all bytes from the string
    bytes.foreach(parser.feed)
    
    // Use eof() to trigger the emission of the final fields
    parser.eof() match {
      case Some(row) => decodeFields(row, cfg.charset).toSeq
      case None      => Seq.empty
    }
  }
}
