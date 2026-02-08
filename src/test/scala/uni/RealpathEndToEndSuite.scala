package uni

import munit.FunSuite
import java.nio.file.{Files, Path, Paths}

class RealpathEndToEndSuite extends FunSuite {

  test("realpath preserves non-existent tail and never injects CWD") {
    // Create a real temporary directory
    val tmp = Files.createTempDirectory("realpath-test")

    // Construct a path with a non-existent tail
    val p = tmp.resolve("foo").resolve("bar").resolve("baz")

    // Sanity: ensure only the prefix exists
    assert(Files.exists(tmp))
    assert(!Files.exists(tmp.resolve("foo")))

    // Run the function
    val result = p.realpath

    // 1. The prefix must be canonical
    val expectedPrefix = tmp.toRealPath()
    assertEquals(result.getRoot, expectedPrefix.getRoot)
    assert(result.startsWith(expectedPrefix))

    // 2. The tail must be preserved exactly
    val tail = result.subpath(expectedPrefix.getNameCount, result.getNameCount)
    assertEquals(tail.toString.replace('\\', '/'), "foo/bar/baz")

    // 3. Critically: result must NOT contain the JVM's working directory
    val cwd = Paths.get("").toAbsolutePath.normalize()
    assert(!result.startsWith(cwd), s"realpath incorrectly injected CWD: $cwd")

    // 4. And the final path must equal prefix + tail
    assertEquals(result, expectedPrefix.resolve("foo/bar/baz"))
  }
}
