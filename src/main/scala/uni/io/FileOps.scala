package uni.io

import java.nio.file.Path
import java.io.{FileWriter, OutputStreamWriter, PrintWriter}
import java.util.Locale

export uni.io.FileOps.*

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
}
