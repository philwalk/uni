package uni.io

import uni.*

import java.nio.file.Files

def cksum(path: Path): (Long, Long) = {
  var crc: Long = 0L
  var length: Long = 0L
  
  val in = Files.newInputStream(path)
  try {
    val buffer = new Array[Byte](8192)
    var n = in.read(buffer)
    
    while n > 0 do
      var i = 0
      while i < n do
        crc = (crc << 8) ^ crcTable(((crc >> 24) ^ (buffer(i) & 0xFF)).toInt & 0xFF)
        i += 1
      length += n
      n = in.read(buffer)
  } finally {
    in.close()
  }
  
  // Append length as bytes
  var len = length
  while len > 0 do
    crc = (crc << 8) ^ crcTable(((crc >> 24) ^ (len & 0xFF)).toInt & 0xFF)
    len >>= 8
  
  crc = ~crc & 0xFFFFFFFFL
  (crc, length)
}

// CRC-32 table (POSIX standard)
private lazy val crcTable: Array[Long] = {
  val table = new Array[Long](256)
  for i <- 0 until 256 do
    var c = i.toLong << 24
    for _ <- 0 until 8 do
      c = if (c & 0x80000000L) != 0 then (c << 1) ^ 0x04C11DB7L else c << 1
    table(i) = c & 0xFFFFFFFFL
  table
}
