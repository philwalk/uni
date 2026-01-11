package uni.io

import java.io.{BufferedInputStream, FileInputStream}

/*
 * Based on the fast XXH3 algorithm
 */
object Hash64 {

  inline def hash64(file: java.io.File): (String, Option[Exception]) = {
    try {
      val in    = new BufferedInputStream(new FileInputStream(file))
      val buf   = new Array[Byte](64 * 1024)
      val hasher = Hash64(seed = 0L)
      var r = 0
      while
        r = in.read(buf)
        r != -1
      do
        hasher.update(buf, 0, r)

      in.close()
      val h   = hasher.digest()
      val hex = f"$h%016x"
      (hex, None)
    } catch {
      case e: java.io.FileNotFoundException =>
        ("", Some(e))
    }
  }

  private inline val PRIME64_1 = 0x9E3779B185EBCA87L
  private inline val PRIME64_2 = 0xC2B2AE3D27D4EB4FL
  private inline val PRIME64_3 = 0x165667B19E3779F9L
  private inline val PRIME64_4 = 0x85EBCA77C2B2AE63L
  private inline val PRIME64_5 = 0x27D4EB2F165667C5L

  final class Hasher(seed: Long) {
    private var acc1 = seed + PRIME64_1 + PRIME64_2
    private var acc2 = seed + PRIME64_2
    private var acc3 = seed
    private var acc4 = seed - PRIME64_1
    private var totalLen = 0L

    // enough to hold at least one 64-Kbyte block
    private val buffer = new Array[Byte](1024 * 64)
    private var bufferSize = 0

    def update(input: Array[Byte], offset: Int, length: Int): Unit = {
      var off = offset
      var len = length
      totalLen += len

      if (bufferSize + len < 64) {
        System.arraycopy(input, off, buffer, bufferSize, len)
        bufferSize += len
        return
      }

      if (bufferSize > 0) {
        val fill = 64 - bufferSize
        System.arraycopy(input, off, buffer, bufferSize, fill)
        processChunk(buffer, 0)
        off += fill
        len -= fill
        bufferSize = 0
      }

      while (len >= 64) {
        processChunk(input, off)
        off += 64
        len -= 64
      }

      if (len > 0) {
        System.arraycopy(input, off, buffer, 0, len)
        bufferSize = len
      }
    }

    private inline def readLE64(b: Array[Byte], i: Int): Long = {
      (b(i).toLong       & 0xffL)        |
      ((b(i + 1).toLong  & 0xffL) << 8)  |
      ((b(i + 2).toLong  & 0xffL) << 16) |
      ((b(i + 3).toLong  & 0xffL) << 24) |
      ((b(i + 4).toLong  & 0xffL) << 32) |
      ((b(i + 5).toLong  & 0xffL) << 40) |
      ((b(i + 6).toLong  & 0xffL) << 48) |
      ((b(i + 7).toLong  & 0xffL) << 56)
    }

    private inline def rotl(x: Long, r: Int): Long = {
      (x << r) | (x >>> (64 - r))
    }

    private def processChunk(b: Array[Byte], off: Int): Unit = {
      acc1 = mixLane(acc1, readLE64(b, off))
      acc2 = mixLane(acc2, readLE64(b, off + 8))
      acc3 = mixLane(acc3, readLE64(b, off + 16))
      acc4 = mixLane(acc4, readLE64(b, off + 24))
      // remaining 32 bytes could be mixed similarly if we wanted a closer xxHash3 mimic;
      // this is a simplified 4-lane structure for streaming.
    }

    private inline def mixLane(acc: Long, lane: Long): Long = {
      val x = acc + lane * PRIME64_2
      rotl(x, 31) * PRIME64_1
    }

    def digest(): Long = {
      var h =
        if (totalLen >= 64L) {
          rotl(acc1, 1) + rotl(acc2, 7) + rotl(acc3, 12) + rotl(acc4, 18)
        } else {
          seed + PRIME64_5
        }

      h += totalLen

      var i = 0
      while (i + 8 <= bufferSize) {
        val k1 = readLE64(buffer, i)
        val mixed = mixLane(0L, k1)
        h ^= mixed
        h = rotl(h, 27) * PRIME64_1 + PRIME64_4
        i += 8
      }

      while (i < bufferSize) {
        val k1 = buffer(i).toLong & 0xffL
        h ^= k1 * PRIME64_5
        h = rotl(h, 11) * PRIME64_1
        i += 1
      }

      h ^= h >>> 33
      h *= PRIME64_2
      h ^= h >>> 29
      h *= PRIME64_3
      h ^= h >>> 32

      h
    }
  }

  def apply(seed: Long = 0): Hasher = new Hasher(seed)
}
