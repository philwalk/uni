package streamtest

import java.nio.file.Files
import java.nio.charset.StandardCharsets
import munit.FunSuite

import uni.*

class StreamLinesSuite extends FunSuite:

  test("streamLines: UTF-8 line does not trigger fallback") {
    val p = Files.createTempFile("utf8-test", ".txt")
    Files.write(p, "hello".getBytes(StandardCharsets.UTF_8))

    val it = streamLines(p)
    val line = it.next()

    assertEquals(line, "hello")
  }

  test("streamLines: malformed UTF‑8 triggers ISO‑8859‑1 fallback") {
    val p = Files.createTempFile("fallback-test", ".txt")

    // Write a single invalid UTF‑8 byte: 0x80
    val bad = Array(0x80.toByte)
    Files.write(p, bad)

    val it = streamLines(p)
    val line = it.next()

    // Under ISO-8859-1, 0x80 maps to U+0080 (control char)
    // So the decoded string should be exactly one char with codepoint 0x80
    assertEquals(line.length, 1)
    assertEquals(line.charAt(0).toInt, 0x80)
  }
