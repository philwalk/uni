package uni.io

import java.io.FileInputStream
import java.security.MessageDigest
import uni.*

def md5(path: Path): String = {
  val md = MessageDigest.getInstance("MD5")
  val fis = new FileInputStream(path.toFile)
  try {
    val buffer = new Array[Byte](8192)
    var bytesRead = fis.read(buffer)
    while (bytesRead != -1) {
      md.update(buffer, 0, bytesRead)
      bytesRead = fis.read(buffer)
    }
    md.digest().map("%02x".format(_)).mkString
  } finally {
    fis.close()
  }
}
