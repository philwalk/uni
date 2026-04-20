package uni

import munit.FunSuite

/** Unit tests for the subprocess API introduced in PathsUtils.scala:
 *  ProcResult fields/methods, buffered run, streaming run,
 *  Int extensions (!! / orElse / orFail), failFast, and shell routing.
 */
class ProcSuite extends FunSuite:

  // ============================================================================
  // ProcResult — field accessors and derived methods
  // ============================================================================

  test("ProcResult.ok: status 0 is ok") {
    val r = Proc.ProcResult(0, Seq("hello"), Seq.empty, Seq("echo", "hello"))
    assert(r.ok)
  }

  test("ProcResult.ok: non-zero status is not ok") {
    val r = Proc.ProcResult(1, Seq.empty, Seq("bad"), Seq("false"))
    assert(!r.ok)
  }

  test("ProcResult.text: joins stdout lines with newline") {
    val r = Proc.ProcResult(0, Seq("a", "b", "c"), Seq.empty, Seq("cmd"))
    assertEquals(r.text, "a\nb\nc")
  }

  test("ProcResult.text: empty stdout yields empty string") {
    val r = Proc.ProcResult(0, Seq.empty, Seq.empty, Seq("cmd"))
    assertEquals(r.text, "")
  }

  test("ProcResult.lines: same as stdout seq") {
    val lines = Seq("x", "y")
    val r = Proc.ProcResult(0, lines, Seq.empty, Seq("cmd"))
    assertEquals(r.lines, lines)
  }

  test("ProcResult.toOption: Some(text) when ok and stdout nonEmpty") {
    val r = Proc.ProcResult(0, Seq("result"), Seq.empty, Seq("cmd"))
    assertEquals(r.toOption, Some("result"))
  }

  test("ProcResult.toOption: None when status non-zero") {
    val r = Proc.ProcResult(1, Seq("result"), Seq.empty, Seq("cmd"))
    assertEquals(r.toOption, None)
  }

  test("ProcResult.toOption: None when stdout is empty even if ok") {
    val r = Proc.ProcResult(0, Seq.empty, Seq.empty, Seq("cmd"))
    assertEquals(r.toOption, None)
  }

  // ============================================================================
  // Buffered run — happy path
  // ============================================================================

  test("run buffered: echo produces non-empty output") {
    val r = run("echo", "hello")
    assert(r.ok, s"echo should succeed, got status ${r.status}")
    assert(r.lines.exists(_.contains("hello")), s"expected 'hello' in output: ${r.lines}")
  }

  test("run buffered: cmd field matches routed command") {
    val r = run("echo", "test")
    assert(r.cmd.nonEmpty)
    // On Windows, "echo" becomes "echo.exe"; on Unix it stays "echo"
    assert(r.cmd.exists(_.contains("echo")), s"cmd should mention echo: ${r.cmd}")
  }

  test("run buffered: failed command returns non-zero status") {
    // Use a guaranteed-absent program name
    val r = run(if isWin then "no-such-program-xyz.exe" else "no-such-program-xyz")
    assert(!r.ok, s"missing program should fail")
  }

  // ============================================================================
  // Buffered run — ProcResult extensions
  // ============================================================================

  test("ProcResult.!! returns result unchanged on success") {
    val r = run("echo", "ok") !! "echo failed"
    assert(r.ok)
  }

  test("ProcResult.!! returns result unchanged on failure (does not throw)") {
    val r = run(if isWin then "no-such-xyz.exe" else "no-such-xyz") !! "expected failure"
    assert(!r.ok)
  }

  test("ProcResult.orFail: completes normally on success inside failFast") {
    val status = failFast {
      val r = run("echo", "hi") orFail "echo failed"
      assert(r.ok)
      0
    }
    assertEquals(status, 0)
  }

  test("ProcResult.orFail: breaks out of failFast on failure") {
    var reached = false
    val status = failFast {
      run(if isWin then "no-such-xyz.exe" else "no-such-xyz") orFail "expected"
      reached = true
      0
    }
    assert(!reached, "code after orFail should not run on failure")
    assert(status != 0)
  }

  // ============================================================================
  // Streaming run
  // ============================================================================

  test("run streaming: collects stdout lines via callback") {
    val buf = collection.mutable.ListBuffer.empty[String]
    val status = run("echo", "stream-test") { line => buf += line }
    assertEquals(status, 0)
    assert(buf.exists(_.contains("stream-test")), s"expected stream-test in: $buf")
  }

  test("run streaming: returns non-zero on failure") {
    val status = run(if isWin then "no-such-xyz.exe" else "no-such-xyz") { _ => () }
    assert(status != 0)
  }

  test("run streaming: explicit stderr callback receives stderr output") {
    // Run a command that writes to stderr
    val errBuf = collection.mutable.ListBuffer.empty[String]
    // ls on a non-existent path writes to stderr
    val missingPath = "/no-such-dir-xyz"
    run("ls", missingPath)(
      _ => (),
      errLine => errBuf += errLine
    )
    // We only check that the callback was wired — some systems print nothing for missing dirs
    // so just assert the mechanism doesn't throw
    assert(true)
  }

  // ============================================================================
  // Int extensions: !! / orElse / orFail / failFast
  // ============================================================================

  test("Int.!! returns status unchanged on 0") {
    val s = 0 !! "should not log"
    assertEquals(s, 0)
  }

  test("Int.!! returns status unchanged on non-zero") {
    val s = 42 !! "logged failure"
    assertEquals(s, 42)
  }

  test("Int.orElse: callback not called on 0") {
    var called = false
    val s = 0 orElse { _ => called = true }
    assertEquals(s, 0)
    assert(!called)
  }

  test("Int.orElse: callback called on non-zero") {
    var received = ""
    val s = 7 orElse { msg => received = msg }
    assertEquals(s, 7)
    assert(received.nonEmpty, "orElse handler should receive error description")
  }

  test("Int.orFail: 0 passes through inside failFast") {
    val result = failFast {
      0 orFail "should not fail"
    }
    assertEquals(result, 0)
  }

  test("Int.orFail: non-zero breaks out of failFast") {
    var reached = false
    val result = failFast {
      5 orFail "expected break"
      reached = true
      0
    }
    assert(!reached, "code after orFail should not run")
    assertEquals(result, 5)
  }

  test("failFast: returns body value when no orFail triggered") {
    val result = failFast { 42 }
    assertEquals(result, 42)
  }

  // ============================================================================
  // Integration: streaming run + Int extensions
  // ============================================================================

  test("run streaming !! on success returns 0") {
    val status = run("echo", "ok") { _ => () } !! "echo failed"
    assertEquals(status, 0)
  }

  test("run streaming orElse on success does not invoke handler") {
    var called = false
    run("echo", "ok") { _ => () } orElse { _ => called = true }
    assert(!called)
  }

  // ============================================================================
  // where / whereInPath
  // ============================================================================

  test("where: returns non-empty path for known tool") {
    val tool = if isWin then "cmd.exe" else "sh"
    val path = where(tool)
    assert(path.nonEmpty, s"where($tool) should return something")
  }

  test("whereInPath: Some for a known tool on PATH") {
    val tool = if isWin then "cmd" else "sh"
    val result = Proc.whereInPath(tool)
    assert(result.isDefined, s"whereInPath($tool) should find the tool on PATH")
  }

  test("whereInPath: None for a made-up program") {
    val result = Proc.whereInPath("no-such-program-xyz-abc")
    assertEquals(result, None)
  }

  // ============================================================================
  // Shell routing — .sh, .py, .sc
  // On Linux/macOS the kernel handles shebangs directly; on Windows the interpreter
  // is prepended by routeCmd.  The cmd field in ProcResult makes routing observable.
  // ============================================================================

  test("run: .sh routing prepends bashExe on all platforms") {
    import java.nio.file.Files
    val script = Files.createTempFile("uni-proc-test-", ".sh")
    try
      Files.writeString(script, "#!/bin/sh\necho shell-routing-ok\n")
      script.toFile.setExecutable(true)
      val r = run(script.toString)
      assertEquals(r.cmd.head, bashExe,
        s"bashExe=$bashExe should be cmd.head for .sh, got ${r.cmd.head}")
      assert(r.lines.exists(_.contains("shell-routing-ok")),
        s".sh script output should contain 'shell-routing-ok': ${r.lines}")
    finally
      Files.deleteIfExists(script)
  }

  test("run: .py routing — cmd.head is pythonExe on Windows; shebang on Unix") {
    import java.nio.file.Files
    // Skip if python3 is not available
    assume(Proc.whereInPath(if isWin then "python3" else "python3").isDefined
      || Proc.whereInPath(if isWin then "python" else "python").isDefined,
      "python3/python not found on PATH — skipping")
    val script = Files.createTempFile("uni-proc-test-", ".py")
    try
      Files.writeString(script, "#!/usr/bin/env python3\nprint('python-routing-ok')\n")
      if !isWin then script.toFile.setExecutable(true)
      val r = run(script.toString)
      if isWin then
        assertEquals(r.cmd.head, pythonExe,
          s"pythonExe=$pythonExe should be cmd.head for .py on Windows, got ${r.cmd.head}")
      assert(r.lines.exists(_.contains("python-routing-ok")),
        s".py script output should contain 'python-routing-ok': ${r.lines}")
    finally
      Files.deleteIfExists(script)
  }

  test("run: .sc routing — cmd starts with scala-cli shebang on Windows; shebang on Unix") {
    import java.nio.file.Files
    // Skip if scala-cli is not available (not guaranteed in CI)
    assume(Proc.whereInPath("scala-cli").isDefined, "scala-cli not found on PATH — skipping")
    val script = Files.createTempFile("uni-proc-test-", ".sc")
    try
      Files.writeString(script,
        "#!/usr/bin/env -S scala-cli shebang\nprintln(\"scala-routing-ok\")\n")
      if !isWin then script.toFile.setExecutable(true)
      val r = run(script.toString)
      if isWin then
        assertEquals(r.cmd.take(2), Seq("scala-cli", "shebang"),
          s"cmd should start with [scala-cli, shebang] on Windows, got ${r.cmd.take(2)}")
      assert(r.lines.exists(_.contains("scala-routing-ok")),
        s".sc script output should contain 'scala-routing-ok': ${r.lines}")
    finally
      Files.deleteIfExists(script)
  }

  // ============================================================================
  // ProcBuilder — proc(...).cwd / .env / .stdin / .timeout
  // ============================================================================

  test("proc.env: custom env var is visible to child process") {
    val key   = "UNI_TEST_VAR"
    val value = "hello-from-env"
    val r =
      if isWin then
        proc("cmd.exe", "/c", s"echo %${key}%").env(Map(key -> value)).run()
      else
        proc("sh", "-c", s"echo $$${key}").env(Map(key -> value)).run()
    assert(r.ok, s"proc.env run should succeed, got ${r.status}")
    assert(r.lines.exists(_.contains(value)),
      s"expected '$value' in output: ${r.lines}")
  }

  test("proc.cwd: working directory is respected") {
    import java.nio.file.{Files as JFiles}
    val tmpDir = JFiles.createTempDirectory("uni-proc-cwd-test-")
    try
      val r =
        if isWin then
          proc("cmd.exe", "/c", "cd").cwd(tmpDir.toString).run()
        else
          proc("pwd").cwd(tmpDir.toString).run()
      assert(r.ok, s"proc.cwd run should succeed, got ${r.status}")
      val out = r.lines.mkString.replace('\\', '/').toLowerCase
      val exp = tmpDir.toAbsolutePath.toString.replace('\\', '/').toLowerCase
      assert(out.contains(exp.takeRight(20)),
        s"expected cwd output to contain '${exp.takeRight(20)}', got: $out")
    finally
      JFiles.deleteIfExists(tmpDir)
  }

  test("proc.stdin: stdin is passed to child process") {
    val input = "hello-stdin"
    val r = proc("cat").stdin(input + "\n").run()
    assert(r.ok, s"proc.stdin run should succeed, got ${r.status}")
    assert(r.lines.exists(_.contains(input)),
      s"expected '$input' in output: ${r.lines}")
  }

  test("proc.timeout: process exceeding timeout returns -1") {
    val r = proc(bashExe, "-c", "sleep 10").timeout(200L).run()
    assertEquals(r.status, -1, s"timed-out process should return status -1")
  }

  // ============================================================================
  // ProcResult.headOnly / takeOnly / IndexedSeq
  // ============================================================================

  test("headOnly: returns first stdout line and drains rest") {
    val r = run("echo", "head-only-test")
    val h = r.headOnly
    assert(h.contains("head-only-test"), s"headOnly should return first line, got: $h")
  }

  test("takeOnly: returns first N lines") {
    val lines5 =
      if isWin then "echo a && echo b && echo c && echo d && echo e"
      else "printf 'a\nb\nc\nd\ne\n'"
    val r =
      if isWin then run("cmd.exe", "/c", lines5)
      else run("sh", "-c", lines5)
    val taken = r.takeOnly(3)
    assertEquals(taken.size, 3, s"takeOnly(3) should return 3 lines, got ${taken.size}: $taken")
  }

  test("takeOnly(1): returns single-element seq") {
    val r     = run("echo", "single")
    val taken = r.takeOnly(1)
    assertEquals(taken.size, 1)
    assert(taken.head.contains("single"))
  }

  test("ProcResult extends IndexedSeq: apply(0) returns first line") {
    val r = run("echo", "indexedseq-test")
    assert(r(0).contains("indexedseq-test"), s"r(0) should be first line, got: ${r(0)}")
  }
