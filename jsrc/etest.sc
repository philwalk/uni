#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.13.2

import uni.*

// Pattern 1 — streaming per-line, log errors and continue
run("git", "ls-files") { pathstr =>
  run("stat", "-c", "%y", pathstr) { ts =>
    printf("%s : %s\n", ts.take(16), pathstr)
  } !! s"bad stat on [$pathstr]"
} !! "git ls-files failed"

// Pattern 2 — streaming per-line, abort on first error
failFast {
  run("git", "ls-files") { pathstr =>
    run("stat", "-c", "%y", pathstr) { ts =>
      printf("%s : %s\n", ts.take(16), pathstr)
    } orFail s"bad stat on [$pathstr]"
  } orFail "git ls-files failed"
}

// Pattern 3 — buffered capture; ProcResult carries both streams + cmd
val result = run("git", "log", "--oneline") !! "git log failed"
result.lines.take(5).foreach(println)

// Pattern 4 — happy path only (Option)
run("git", "rev-parse", "HEAD").toOption.foreach(println)

// Pattern 4b — cmd field: shows the post-routing command actually sent to the OS
// Useful for debugging (e.g. confirming .sh routing prepended bashExe).
val r = run("git", "rev-parse", "--abbrev-ref", "HEAD")
printf("ran: %s  →  %s\n", r.cmd.mkString(" "), r.text.trim)

// Pattern 4c — script routing
// On Linux/macOS the kernel handles shebangs; on Windows routeCmd prepends the interpreter.
// All three forms work identically on both platforms:
//
//   run("tools/setup.sh")          → [bashExe, tools/setup.sh]     (all platforms)
//   run("tools/analyse.py")        → [pythonExe, tools/analyse.py]  (Windows)
//                                   → [tools/analyse.py]             (Linux/macOS, shebang)
//   run("tools/transform.sc")      → [scala-cli, shebang, ...]      (Windows)
//                                   → [tools/transform.sc]           (Linux/macOS, shebang)
//
// The cmd field always shows what was actually sent to the OS:
val rsh = run("tools/list-files.sh")  // hypothetical; uncomment with a real path
// printf("routing: %s\n", rsh.cmd.mkString(" "))

// ─── More complex patterns ────────────────────────────────────────────────────

// Pattern 5 — explicit stderr separation
// Collect stdout and stderr independently without losing either stream.
val stdoutLines = collection.mutable.ListBuffer.empty[String]
val stderrLines = collection.mutable.ListBuffer.empty[String]
run("git", "log", "--oneline", "-5")(
  line    => stdoutLines += line,
  errLine => stderrLines += errLine,
)
printf("commits: %d, stderr lines: %d\n", stdoutLines.size, stderrLines.size)

// Pattern 6 — orElse with a custom handler (accumulate, don't abort)
val failures = collection.mutable.ListBuffer.empty[String]
run("git", "ls-files") { pathstr =>
  run("stat", pathstr) { _ => () } orElse { msg =>
    failures += s"$pathstr: $msg"
  }
} !! "git ls-files failed"
if failures.nonEmpty then
  eprintln(s"${failures.size} stat failures:")
  failures.foreach(f => eprintln(s"  $f"))

// Pattern 7 — compose two buffered results
// Diff the HEAD commit message against the previous one.
val headMsg = run("git", "log", "--format=%s", "-1")
val prevMsg = run("git", "log", "--format=%s", "-2", "--skip=1")
(headMsg.toOption, prevMsg.toOption) match
  case (Some(h), Some(p)) =>
    printf("HEAD:  %s\nPREV:  %s\n", h.trim, p.trim)
  case _ =>
    eprintln("could not retrieve commit messages")

// Pattern 8 — failFast across composed steps (abort on first failure)
failFast {
  val branch = run("git", "rev-parse", "--abbrev-ref", "HEAD") orFail "no branch"
  val sha    = run("git", "rev-parse", "--short", "HEAD")      orFail "no sha"
  printf("branch=%s sha=%s\n", branch.text.trim, sha.text.trim)
  0
}