package uni.io

import java.nio.file.{Files, Path}
import java.nio.channels.FileChannel
import java.nio.ByteBuffer
import java.nio.charset.{Charset, StandardCharsets}
import java.nio.file.StandardOpenOption.READ

object FastCsv {

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
  ): Iterator[Seq[String]] = new Iterator[Seq[String]] {

    private val delimiter: Byte = cfg.delimiter.getOrElse {
      val res = Delimiter.detect(path, sampleRows)
      res.delimiter.toByte
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
  }

  /** Queue filled by background thread: return Iterator[Seq[String]] */
  def rowsAsync(
    path: Path,
    cfg: Config = Config(),
    sampleRows: Int = 100,
    queueCapacity: Int = 1024
  ): Iterator[Seq[String]] = {

    import java.util.concurrent.LinkedBlockingQueue

    val delimiter: Byte = cfg.delimiter.getOrElse {
      val res = Delimiter.detect(path, sampleRows)
      res.delimiter.toByte
    }
    val parser = new RowParser(cfg, delimiter)
    val ch = Files.newByteChannel(path, READ).asInstanceOf[FileChannel]
    val buf = ByteBuffer.allocateDirect(cfg.bufferSize)

    // Bounded queue for back-pressure
    val queue = new LinkedBlockingQueue[Option[Seq[String]]](queueCapacity)

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

      private def advance(): Unit = {
        if (nextRow.isEmpty) {
          nextRow = queue.take() // blocks until row or None
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
    }
  }

  /** Synchronous blocking API -- parse and send rows to sink */
  def eachRow(
    path: Path,
    cfg: Config = Config(),
    sampleRows: Int = 100
  )(onRow: Seq[String] => Unit): Unit = {
    val delimiter: Byte = cfg.delimiter.getOrElse {
      val res = Delimiter.detect(path, sampleRows)
      res.delimiter.toByte
    }
    val parser = new RowParser(cfg, delimiter)
    val ch = Files.newByteChannel(path, READ).asInstanceOf[FileChannel]
    val buf = ByteBuffer.allocateDirect(cfg.bufferSize)

    while (ch.read(buf) > 0) {
      buf.flip()
      while (buf.hasRemaining) {
        parser.feed(buf.get()) match {
          case Some(row) =>
            onRow(decodeFields(row, cfg.charset).toSeq) // call the function directly
          case None =>
        }
      }
      buf.clear()
    }
    parser.eof().foreach(r => onRow(decodeFields(r, cfg.charset).toSeq))
    ch.close()
  }

  /** Push-style API: parse file and send rows to sink */
  def parse(path: Path, sink: RowSink, cfg: Config = Config(), sampleRows: Int = 100): Unit = {
    val delimiter: Byte = cfg.delimiter.getOrElse {
      val res = Delimiter.detect(path, sampleRows)
      res.delimiter.toByte
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

    @inline private def ensureFieldCapacity(n: Int): Unit = {
      if (n > field.length) {
        var cap = field.length
        while (cap < n) cap <<= 1
        val nf = new Array[Byte](cap)
        System.arraycopy(field, 0, nf, 0, fieldLen)
        field = nf
      }
    }
    @inline private def append(b: Byte): Unit = {
      val next = fieldLen + 1
      ensureFieldCapacity(next)
      field(fieldLen) = b
      fieldLen = next
    }
    @inline private def emitField(): Unit = {
      val out = java.util.Arrays.copyOf(field, fieldLen)
      if (fieldCount == fields.length) {
        val nf = new Array[Array[Byte]](fields.length << 1)
        System.arraycopy(fields, 0, nf, 0, fields.length)
        fields = nf
      }
      fields(fieldCount) = out
      fieldCount += 1
      fieldLen = 0
    }
    @inline private def emitRow(): Array[Array[Byte]] = {
      val row = new Array[Array[Byte]](fieldCount)
      System.arraycopy(fields, 0, row, 0, fieldCount)
      fieldCount = 0
      row
    }

    /** Feed one byte; return Some(row) when a row completes */
    def feed(b: Byte): Option[Array[Array[Byte]]] = {
      if (pendingCR) {
        pendingCR = false
        if (b == '\n') return None
      }
      if (inQuotes) {
        if (b == cfg.quote) {
          if (prevWasQuote) {
            append(cfg.quote)
            prevWasQuote = false
          } else {
            prevWasQuote = true
          }
        } else {
          if (prevWasQuote) {
            inQuotes = false
            prevWasQuote = false
            if (b == delimiter) { emitField(); return None }
            else if (b == '\n') { emitField(); return Some(emitRow()) }
            else if (b == '\r') { emitField(); val r = emitRow(); pendingCR = true; return Some(r) }
            else append(b)
          } else append(b)
        }
      } else {
        if (b == delimiter) emitField()
        else if (b == '\n') { emitField(); return Some(emitRow()) }
        else if (b == '\r') { emitField(); val r = emitRow(); pendingCR = true; return Some(r) }
        else if (b == cfg.quote) { inQuotes = true; prevWasQuote = false }
        else append(b)
      }
      None
    }

    /** Flush final row at EOF if needed */
    def eof(): Option[Array[Array[Byte]]] = {
      if (fieldLen > 0 || fieldCount > 0) {
        emitField()
        Some(emitRow())
      } else None
    }
  }
}
