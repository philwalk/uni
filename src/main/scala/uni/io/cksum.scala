package uni.io

import uni.*
import java.nio.file.{Files, Path}
import java.io.BufferedInputStream

export Cksum.cksum

object Cksum {

  def cksum(bytes: Array[Byte]): (Long, Long) = {
    val size = bytes.length
    var crc = 0L
    val table = crcTable
    
    var i = 0
    while (i < size) {
      // Ensure we treat the byte as an unsigned long before the XOR
      val b = bytes(i).toLong & 0xFFL
      crc = (crc << 8) ^ table(((crc >> 24) ^ b).toInt & 0xFF)
      i += 1
    }

    (computeFinal(crc, size.toLong), size.toLong)
  }

  def cksum(bytes: Iterator[Byte]): (Long, Long) = {
    var crc = 0L
    var length = 0L
    val table = crcTable
    
    while (bytes.hasNext) {
      val b = bytes.next().toLong & 0xFFL
      crc = (crc << 8) ^ table(((crc >> 24) ^ b).toInt & 0xFF)
      length += 1
    }

    (computeFinal(crc, length), length)
  }

  def cksum(path: Path): (Long, Long) = {
    val in = new BufferedInputStream(Files.newInputStream(path))
    try {
      var crc = 0L
      var length = 0L
      val buffer = new Array[Byte](1024 * 64)
      val table = crcTable
      var bytesRead = in.read(buffer)

      while (bytesRead != -1) {
        var i = 0
        while (i < bytesRead) {
          val b = buffer(i).toLong & 0xFFL
          crc = (crc << 8) ^ table(((crc >> 24) ^ b).toInt & 0xFF)
          i += 1
        }
        length += bytesRead
        bytesRead = in.read(buffer)
      }
      (computeFinal(crc, length), length)
    } finally {
      in.close()
    }
  }

  // Renamed to avoid confusion with java.lang.Object.finalize
  private def computeFinal(initialCrc: Long, length: Long): Long = {
    var crc = initialCrc
    var len = length
    val table = crcTable
    while (len > 0) {
      crc = (crc << 8) ^ table(((crc >> 24) ^ (len & 0xFF)).toInt & 0xFF)
      len >>= 8
    }
    ~crc & 0xFFFFFFFFL
  }

  private lazy val crcTable: Array[Long] = {
    val table = new Array[Long](256)
    for (i <- 0 until 256) {
      var c = i.toLong << 24
      for (_ <- 0 until 8) {
        c = if ((c & 0x80000000L) != 0) (c << 1) ^ 0x04C11DB7L else c << 1
      }
      table(i) = c & 0xFFFFFFFFL
    }
    table
  }
}
