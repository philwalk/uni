package uni.io

import java.nio.channels.{Channels}
import java.nio.channels.FileChannel.MapMode.READ_ONLY
//import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{Charset, CodingErrorAction}
import scala.jdk.CollectionConverters.*
import java.io.{BufferedWriter, OutputStreamWriter}
import java.io.{RandomAccessFile, BufferedReader, InputStreamReader, ByteArrayInputStream}
import scala.collection.mutable.ArrayBuffer
import uni.*

object LinesIterator {
  export java.io.PrintWriter

  /**
   * @param sourceOpt: Some(inputPath), or None for stdin
   * @param lineProcessor: process each line to buffered stdout
   */
  def iterateLines(sourceOpt: Option[Path])(lineProcessor: (String, PrintWriter) => Unit): Unit = {
    val stdout = new PrintWriter(new BufferedWriter(new OutputStreamWriter(System.out)))
    val iter = sourceOpt match {
      case Some(path) => FileChannelIterator(path)
      case None       => StdinIterator()
    }
    try {
      iter.foreach { line =>
        lineProcessor(line, stdout)
      }
    } finally {
      iter.close() // stdin not closed
    }
    stdout.flush()
  }

  /**
   * @param path: input filename
   * @param lineProcessor: process each line
   */
  def iterateLines(path: String)(lineProcessor: String => Unit): Unit = {
    val iter = FileChannelIterator(path)
    try {
      iter.foreach { line =>
        lineProcessor(line)
      }
    } finally {
      iter.close()
    }
  }

  def normalize(line: String): String =
    line.replaceAll("\r\n?", "\n").stripTrailing()

  trait ClosableIterator extends Iterator[String] {
    def close(): Unit
  }
  
  private lazy val maxChunkSize = 4 * 1024 * 1024 // 4 MiB
  private lazy val baseChunkSize = 64 * 1024 // 64 KiB
  class FileChannelIterator(path: Path) extends ClosableIterator {
    private val fileSize = path.toFile.length
    private val chunkSize = Math.min(maxChunkSize, Math.max(baseChunkSize, (fileSize / 64).toInt))
    private val file = new RandomAccessFile(path.posx, "r")
    private val channel = file.getChannel
    //private val byteBuffer = ByteBuffer.allocate(chunkSize)
    //private val charBuffer = CharBuffer.allocate(chunkSize)
    private val reader = Channels.newReader(channel, decoderUtf8, -1)
    private val bufferedReader = new BufferedReader(reader)
    private var currentIterator: Iterator[String] = Iterator.empty

    private def refill(): Unit = {
      val lines = new ArrayBuffer[String]()
      var bytesRead = 0
      while (bytesRead < chunkSize) {
        val line = bufferedReader.readLine()
        if (line == null) {
          bytesRead = chunkSize // force exit
        } else {
          lines += line
          bytesRead += line.getBytes(decoderUtf8.charset()).length
        }
      }
      currentIterator = lines.iterator
    }

    override def hasNext: Boolean = {
      if (currentIterator.hasNext) true
      else {
        refill()
        currentIterator.hasNext
      }
    }

    override def next(): String = {
      if (!hasNext) throw new NoSuchElementException("End of stream")
      currentIterator.next()
    }

    def close(): Unit = {
      bufferedReader.close()
      channel.close()
      file.close()
    }
  }

  object FileChannelIterator {
    def apply(pathstr: String): FileChannelIterator = {
      new FileChannelIterator(Paths.get(pathstr))
    }
    def apply(path: Path): FileChannelIterator = {
      new FileChannelIterator(path)
    }
  }

  class FileChannelIterate(path: Path) extends ClosableIterator {
    private val file = new RandomAccessFile(path.posx, "r")
    private val channel = file.getChannel
    private val buffer = channel.map(READ_ONLY, 0, channel.size)
    val bytes = new Array[Byte](buffer.remaining())
    buffer.get(bytes)
    val reader = new BufferedReader(new InputStreamReader(new ByteArrayInputStream(bytes), decoderUtf8))
    private val lines = reader.lines().iterator().asScala

    override def hasNext: Boolean = lines.hasNext
    override def next(): String = lines.next()

    def close(): Unit = {
      reader.close()
      channel.close()
      file.close()
    }
  }

  object FileChannelIterate {
    def apply(pathstr: String): FileChannelIterate = {
      new FileChannelIterate(Paths.get(pathstr))
    }
    def apply(path: Path): FileChannelIterate = {
      new FileChannelIterate(path)
    }
  }

  class StdinIterator() extends ClosableIterator {
    val reader = new BufferedReader(new InputStreamReader(System.in, decoderUtf8))
    val stdin = Iterator.continually(reader.readLine()).takeWhile(_ != null)
    override def hasNext: Boolean = stdin.hasNext
    override def next(): String = stdin.next()
    def close(): Unit = { }
  }

  lazy val decoderUtf8 = Charset.forName("UTF-8")
    .newDecoder()
    .onMalformedInput(CodingErrorAction.REPLACE)
    .onUnmappableCharacter(CodingErrorAction.REPLACE)
}
