# uni — Subprocess API

`import uni.*` provides two `run` overloads and a set of error-handling extensions for running
subprocesses. The design goals are:

- **One entry point** — no `call` / `spawn` / `shellExec` surface to choose between
- **Injection-safe** — each argument is passed as a separate OS token; no shell string is interpolated
- **Implicit shell routing** — `.sh`, `.bat`/`.cmd`, and `.ps1` scripts are dispatched to the correct
  interpreter automatically
- **Composable error handling** — `!!` (log and continue), `orElse` (custom handler), and `orFail`
  (structured abort via `failFast`) chain without nesting

---

## Running scripts on Windows — it works

Windows JVM users often avoid calling `.sh`, `.py`, and `.sc` scripts directly because the
Windows kernel does not understand shebangs. The typical workaround is to hardcode the
interpreter path for the local environment:

```scala
// Common Windows workaround — fragile and platform-specific:
run("C:/path/to/bash.exe", "tools/setup.sh")
run("C:/path/to/python3.exe", "tools/analyse.py")
```

With `uni`, this is unnecessary. `run` applies implicit routing based on the file extension,
so the same call works on Linux, macOS, and any Windows POSIX environment:

```scala
run("tools/setup.sh")        // works on all platforms
run("tools/analyse.py")      // works on all platforms
run("tools/transform.sc")    // works on all platforms
```

On Windows, `uni` resolves the interpreter from the environment at startup — whichever
POSIX layer is active (MSYS2, Cygwin, Git-for-Windows / Git Bash, or any environment
that provides `bash.exe`, `python3.exe`, and `mount.exe` / `cygpath.exe` on `PATH`).
On Linux and macOS, the OS kernel handles shebangs directly and no prepending is needed.

The `cmd` field in the returned `ProcResult` shows exactly what was sent to the OS, so there
is no mystery about what happened:

```scala
val r = run("tools/analyse.py", "--verbose")
// On Windows:  r.cmd == Seq("/resolved/path/to/python3.exe", "tools/analyse.py", "--verbose")
// On Linux:    r.cmd == Seq("tools/analyse.py", "--verbose")
```

---

## The two `run` overloads

### Buffered — captures stdout and stderr, returns `ProcResult`

```scala
run("git", "log", "--oneline")           // ProcResult
run("git", "log").text                   // String  (all stdout lines joined by "\n")
run("git", "log").lines                  // Seq[String]
run("git", "log").stderr                 // Seq[String]
run("git", "log").toOption               // Option[String]  (None if failed or empty)
run("git", "log").ok                     // Boolean  (true iff exit status == 0)
run("git", "log").cmd                    // Seq[String]  (post-routing command sent to OS)
```

### Streaming — calls a callback per stdout line, returns exit status `Int`

```scala
run("git", "ls-files") { line => println(line) }            // Int
run("git", "ls-files") { line => ... } { err => ... }       // Int  (explicit stderr callback)
```

---

## `proc` builder

When you need to set a working directory, inject environment variables, pipe stdin, or impose a
timeout, use `proc(cmd*)` instead of `run(cmd*)`. It returns a `ProcBuilder` that you configure
with a fluent chain before calling `.run()` or `.stream()`:

```scala
proc("mvn", "package").cwd("/home/user/myproject").run()

proc("sh", "-c", "echo $SECRET").env(Map("SECRET" -> "s3cr3t")).run()

proc("cat").stdin("hello\nworld\n").run().lines   // Seq("hello", "world")

proc(bashExe, "-c", "sleep 10").timeout(500L).run().ok  // false — timed out → status -1
```

### `ProcBuilder` API

```scala
proc(cmd: String*): ProcBuilder          // factory

// configuration (chainable):
.cwd(p: Path): ProcBuilder               // working directory
.cwd(s: String): ProcBuilder
.env(m: Map[String, String]): ProcBuilder  // additional env vars (merged into child env)
.stdin(s: String): ProcBuilder           // text piped to child's stdin
.timeout(ms: Long): ProcBuilder          // max wait in milliseconds; status -1 on expiry

// terminal:
.run(): ProcResult                       // buffered — same result type as run(cmd*)
.stream(out: String => Unit,             // streaming — same as streaming run overload
        err: String => Unit = eprintln): Int
```

`ProcBuilder.run()` and `.stream()` accept the same error-handling extensions as the
top-level `run` overloads (`!!`, `orFail`, `orElse`).

---

## `ProcResult`

```scala
case class ProcResult(status: Int, stdout: Seq[String], stderr: Seq[String], cmd: Seq[String])
    extends IndexedSeq[String]:
  def text: String                         // stdout lines joined by "\n"
  def lines: Seq[String]                   // stdout lines
  def ok: Boolean                          // status == 0
  def toOption: Option[String]             // Some(text) if ok && nonEmpty, else None
  def orElse(default: => String): String   // text if ok && nonEmpty, else default
  def headOnly: String                     // first stdout line; drains rest in background
  def takeOnly(n: Int): Seq[String]        // first n stdout lines; drains rest in background
  def apply(i: Int): String                // IndexedSeq — r(0) is first stdout line
  def length: Int                          // number of stdout lines
```

`cmd` carries the post-routing command — what the OS actually received. For example,
`run("deploy.sh")` stores `[/usr/bin/bash, deploy.sh]` in `cmd`. The `!!` and `orFail`
extensions on `ProcResult` use `cmd` to self-describe errors without the caller repeating the name.

`headOnly` and `takeOnly(n)` are for cases where you only need the first line(s) of a
potentially long output. They consume the requested lines eagerly, then drain and discard
the rest in a background daemon thread — preventing reader threads from blocking:

```scala
val branch = run("git", "rev-parse", "--abbrev-ref", "HEAD").headOnly
val top3   = run("git", "log", "--oneline").takeOnly(3)
```

`orElse` provides a default without the intermediate `Option`:

```scala
val version = run("git", "describe", "--tags").orElse("unknown")
```

---

## Error-handling extensions

### On `ProcResult`

```scala
run("git", "log") !! "git log failed"           // ProcResult; logs to stderr on failure
run("git", "log") orFail "git log failed"        // ProcResult; breaks out of failFast on failure
```

### On `Int` (streaming run exit status)

```scala
run("git", "ls-files") { ... } !! "ls-files failed"         // Int; logs on failure
run("git", "ls-files") { ... } orElse { msg => ... }        // Int; custom handler on failure
run("git", "ls-files") { ... } orFail "ls-files failed"     // Int; breaks out of failFast
```

### `failFast`

Wraps a block so that any `.orFail` call inside short-circuits execution on the first failure,
returning the failing exit status:

```scala
failFast {
  run("git", "fetch")                    orFail "fetch failed"
  run("git", "rebase", "origin/main")    orFail "rebase failed"
  run("git", "push")                     orFail "push failed"
  0
}
```

---

## Shell routing

The leading argument is examined and the command is rewritten before hitting the OS.

On **Linux and macOS**, the kernel handles shebangs directly — an executable script file
(`#!/usr/bin/env python3`, `#!/usr/bin/env -S scala-cli shebang`, etc.) can be passed
as the first argument and the OS dispatches to the correct interpreter without any help
from `routeCmd`. On **Windows**, the kernel has no shebang support, so `routeCmd` prepends
the interpreter explicitly.

| Extension | Linux / macOS | Windows |
|-----------|--------------|---------|
| `.sh` | `bashExe` prepended (handles non-executable scripts) | `bashExe` prepended |
| `.py` | passed through (shebang) | `pythonExe` prepended |
| `.sc` | passed through (shebang, if executable) | `scala-cli shebang` prepended |
| `.bat` / `.cmd` | passed through | `cmd.exe /c` prepended |
| `.ps1` | passed through | `powershell.exe -File` prepended |
| any other name | passed through | `.exe` suffix ensured |

All three script types work with the same call on both platforms:

```scala
run("tools/setup.sh")       // → [bashExe, tools/setup.sh]
run("tools/analyse.py")     // → [pythonExe, analyse.py]  on Windows
                              // → [tools/analyse.py]        on Linux/macOS
run("tools/transform.sc")   // → [scala-cli, shebang, ...]  on Windows
                              // → [tools/transform.sc]        on Linux/macOS
```

The `cmd` field in `ProcResult` always shows the post-routing command that was actually
sent to the OS, making routing observable for debugging:

```scala
val r = run("tools/analyse.py", "--verbose")
printf("routing: %s\n", r.cmd.mkString(" "))
// Linux/macOS: tools/analyse.py --verbose
// Windows:     /resolved/path/to/python3.exe tools/analyse.py --verbose
```

`pythonExe` is exported from `import uni.*` alongside `bashExe`, so callers can refer to
it explicitly when needed:

```scala
run(pythonExe, "-c", "import sys; print(sys.version)")
```

When shell features (pipes, globbing, redirects) are genuinely needed, pass `-c`
explicitly to make the intent visible:

```scala
run(bashExe, "-c", "git log --oneline | head -20").lines
```

---

## Usage patterns

### Pattern 1 — streaming, log errors and continue

```scala
run("git", "ls-files") { pathstr =>
  run("stat", "-c", "%y", pathstr) { ts =>
    printf("%s : %s\n", ts.take(16), pathstr)
  } !! s"bad stat on [$pathstr]"
} !! "git ls-files failed"
```

### Pattern 2 — streaming, abort on first error

```scala
failFast {
  run("git", "ls-files") { pathstr =>
    run("stat", "-c", "%y", pathstr) { ts =>
      printf("%s : %s\n", ts.take(16), pathstr)
    } orFail s"bad stat on [$pathstr]"
  } orFail "git ls-files failed"
}
```

### Pattern 3 — buffered capture

```scala
val result = run("git", "log", "--oneline") !! "git log failed"
result.lines.take(5).foreach(println)
```

### Pattern 4 — happy path only

```scala
run("git", "rev-parse", "HEAD").toOption.foreach(println)
```

### Pattern 4b — inspect `cmd` for debugging

`cmd` shows the post-routing command actually sent to the OS, which is useful when diagnosing
routing issues (e.g. confirming that `.sh` routing prepended `bashExe`):

```scala
val r = run("git", "rev-parse", "--abbrev-ref", "HEAD")
printf("ran: %s  →  %s\n", r.cmd.mkString(" "), r.text.trim)
```

### Pattern 5 — explicit stderr separation

```scala
val stdoutLines = collection.mutable.ListBuffer.empty[String]
val stderrLines = collection.mutable.ListBuffer.empty[String]
run("git", "log", "--oneline", "-5")(
  line    => stdoutLines += line,
  errLine => stderrLines += errLine,
)
printf("commits: %d, stderr lines: %d\n", stdoutLines.size, stderrLines.size)
```

### Pattern 6 — `orElse` with a custom accumulating handler

Unlike `!!` (fixed stderr log) and `orFail` (abort), `orElse` lets the caller supply any handler —
logging to a file, accumulating for a summary report, or suppressing:

```scala
val failures = collection.mutable.ListBuffer.empty[String]
run("git", "ls-files") { pathstr =>
  run("stat", pathstr) { _ => () } orElse { msg =>
    failures += s"$pathstr: $msg"
  }
} !! "git ls-files failed"
if failures.nonEmpty then
  eprintln(s"${failures.size} stat failures:")
  failures.foreach(f => eprintln(s"  $f"))
```

### Pattern 7 — compose two buffered results

```scala
val headMsg = run("git", "log", "--format=%s", "-1")
val prevMsg = run("git", "log", "--format=%s", "-2", "--skip=1")
(headMsg.toOption, prevMsg.toOption) match
  case (Some(h), Some(p)) => printf("HEAD:  %s\nPREV:  %s\n", h.trim, p.trim)
  case _                  => eprintln("could not retrieve commit messages")
```

### Pattern 8 — `failFast` across composed dependent steps

```scala
failFast {
  val branch = run("git", "rev-parse", "--abbrev-ref", "HEAD") orFail "no branch"
  val sha    = run("git", "rev-parse", "--short", "HEAD")      orFail "no sha"
  printf("branch=%s sha=%s\n", branch.text.trim, sha.text.trim)
  0
}
```

---

## Environment utilities

These values and functions are exported by `import uni.*` alongside `run` and `proc`.

### Interpreter paths

```scala
bashExe: String      // absolute path to bash — resolved once at startup via `where.exe`/`which`
pythonExe: String    // absolute path to python3 (or python) — resolved at startup
unameExe: String     // absolute path to uname
```

Use them when you need to pass the interpreter explicitly rather than relying on implicit routing:

```scala
run(bashExe, "-c", "git log --oneline | head -20").lines
run(pythonExe, "-c", "import sys; print(sys.version)").headOnly
```

### `where` / `whereInPath`

```scala
where(prog: String): String              // resolves full path via where.exe / which; returns prog unchanged on failure
whereInPath(prog: String): Option[String]  // searches PATH entries directly; None if not found
```

`where` calls the system resolver and is appropriate when you need the canonical path
that the OS would actually use. `whereInPath` does an in-process `PATH` scan and is useful
for existence checks without spawning a subprocess:

```scala
val bash = where("bash")                          // "/usr/bin/bash" or "C:/msys64/usr/bin/bash.exe"
val hasPython = Proc.whereInPath("python3").isDefined
```

### Other exported utilities

```scala
uname(arg: String = "-a"): String   // runs uname, returns stdout or "" on failure
isWsl: Boolean                      // true when running inside WSL
osType: String                      // "windows" | "linux" | "darwin"
hostname: String                    // java.net.InetAddress.getLocalHost.getHostName
```

---

## Migration from the old API

| Old call | New equivalent | Notes |
|----------|---------------|-------|
| `exec("git", "log")` | `run("git", "log").text` | old package-level `exec` returned `String` |
| `call("git", "rev-parse", "HEAD")` | `run("git", "rev-parse", "HEAD").toOption` | |
| `call("where.exe", "bash.exe")` | `run("where.exe", "bash.exe").toOption` | |
| `spawn("git", "log")` | `run("git", "log")` | `ProcResult` has same fields |
| `spawn("git", "log").stdout` | `run("git", "log").lines` | |
| `spawn("git", "log").stderr` | `run("git", "log").stderr` | |
| `spawn("git", "log").status` | `run("git", "log").status` | |
| `shellExec("ls -la")` | `run(bashExe, "-c", "ls -la").lines` | explicit `-c` makes shell use visible |
| `shellExecProc("ls -la")` | `run(bashExe, "-c", "ls -la")` | returns `ProcResult` |
| `exec("f.sh") { l => }` | `run("f.sh") { l => }` | shell routing now implicit |
| `ProcStatus(status, out, err)` | `ProcResult(status, out, err, cmd)` | `cmd` field added |
