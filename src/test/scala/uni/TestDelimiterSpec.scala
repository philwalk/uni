package uni.io

import org.scalatest.funsuite.AnyFunSuite
import java.nio.file.{Files, Paths}
import java.nio.charset.StandardCharsets

class TestDelimiterSpec extends AnyFunSuite {

  private def writeTempFile(name: String, lines: Seq[String]): java.nio.file.Path = {
    val path = Paths.get("target", s"$name.csv")
    Files.createDirectories(path.getParent)
    Files.write(path, lines.mkString("\n").getBytes(StandardCharsets.UTF_8))
    path
  }

  test("detects comma delimiter") {
    val path = writeTempFile("comma", Seq(
      "a,b,c",
      "1,2,3",
      "x,y,z"
    ))
    val res = Delimiter.detect(path, sampleRows = 3)
    assert(res.delimiter == ',')
    assert(res.modeColumns == 3)
    assert(res.consistency == 1.0)
  }

  test("detects tab delimiter") {
    val path = writeTempFile("tab", Seq(
      "a\tb\tc\t",
      "1\t2\t3\t",
      "x\t y\t z\t"
    ))
    val res = Delimiter.detect(path, sampleRows = 3)
    assert(res.delimiter == '\t')
    assert(res.modeColumns == 4)
  }

  test("detects semicolon delimiter with quotes") {
    val path = writeTempFile("semicolon", Seq(
      "\"a;1\";b;c",
      "d;e;f",
      "g;h;i"
    ))
    val res = Delimiter.detect(path, sampleRows = 3)
    assert(res.delimiter == ';')
    assert(res.modeColumns == 3)
  }

  test("ambiguous but decidable: comma vs semicolon") {
    val path = writeTempFile("ambiguous", Seq(
      "a,b;c",
      "1,2;3;4",
      "x,y;z"
    ))
    val res = Delimiter.detect(path, sampleRows = 3)
    // Expect comma to win: consistent 2 columns across all rows
    assert(res.delimiter == ',')
    assert(res.modeColumns == 2)
    assert(res.consistency == 1.0)
  }

  test("ambiguous but decidable: tab vs comma with quoted commas") {
    val path = writeTempFile("ambiguousTabVsComma", Seq(
      "\"foo,bar\"\t123\tabc",
      "\"baz,qux\"\t456\tdef",
      "\"quux,corge\"\t789\tghi"
    ))
    val res = Delimiter.detect(path, sampleRows = 3)
    // Expect tab to win: consistent 3 columns across all rows
    assert(res.delimiter == '\t')
    assert(res.modeColumns == 3)
    assert(res.consistency == 1.0)
  }
}
