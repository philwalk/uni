# uni — Scripting Utilities

> **TODO:** Many features of the `uni` package are not yet documented here, including
> `uni.io`, `uni.cli`, CSV processing, process execution (`Proc`), and others.
> This document covers the most commonly used utilities; contributions welcome.

The `uni` package provides practical tools for Scala scripts and applications: portable path handling across Windows POSIX environments, flexible date parsing, command-line argument handling, and inline data embedding. These utilities are available alongside the main focus of the library, [uni.data.Mat](../README.md).

A single import makes all of them available:

```scala
import uni.*
```

---

## Portable Shell Scripts

For Unix-like environments (Linux, macOS, WSL, MSYS2, Cygwin, Git-Bash), the recommended `scala-cli` shebang is:

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
```

This ensures that the script runs with compiler flags for code quality.

---

## Portable Path Handling

`java.nio.file.Paths.get("/etc/fstab")` throws an exception on Windows because the JVM has no knowledge of MSYS2 or Cygwin mount tables. `uni.Paths` is a drop-in replacement that works correctly on all platforms with the same code.

On Linux and macOS, `uni.Paths` delegates to `java.nio.file.Paths` unchanged. On Windows, it reads the MSYS2 or Cygwin `fstab` file at startup and, if `mount.exe` is in `PATH`, also processes its output. The resulting mount table is used to translate posix paths to their correct Windows equivalents.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.2

import uni.*

val p = Paths.get("/etc/fstab")
val sysType = Proc.call("uname", "-o").getOrElse("")
printf("env: %-10s| %-22s | %d lines\n", sysType, p.posx, p.lines.size)
```

Output across platforms:
```
env: GNU/Linux  | /etc/fstab            | 21 lines
env: Darwin     | /etc/fstab            |  0 lines
env: Msys       | C:/msys64/etc/fstab   | 22 lines
env: Cygwin     | C:/cygwin64/etc/fstab | 24 lines
```

### Tested environments

| Platform | Behaviour |
| :--- | :--- |
| Linux | delegates to `java.nio.file.Paths` |
| macOS / Darwin | delegates to `java.nio.file.Paths` |
| WSL | delegates to `java.nio.file.Paths` |
| Windows + MSYS2 | reads `fstab` and `mount.exe` output |
| Windows + Cygwin64 | reads `fstab` and `mount.exe` output |
| Windows + Git-bash | reads `mount.exe` output |

---

## Robustness and Portability

`uni` methods are designed for scripting: they never return `null` and avoid throwing exceptions for common file-system issues. 

- **Defensive:** Methods like `.lines`, `.csvRows`, or `.csvRowsStream` return an empty `Seq` or `Iterator` (rather than crashing) if the file does not exist, or if it is a directory.
- **Encoding:** Reading methods default to UTF-8 but automatically fall back to Latin-1 (`ISO-8859-1`) if encoding errors occur, ensuring that "corrupt" or mixed-encoding data can still be processed.
- **Portability:** All `Path` extension methods are also available for `java.io.File` (aliased as `JFile`), ensuring consistent behavior regardless of the underlying API used.

---

For a complete list of all `Path`, `JFile`, and `String` extension methods added by `import uni.*`, see the [Path & I/O Reference](PathIOReference.md).

---

## Platform Conveniences

`import uni.*` exports several values useful for platform-aware code:

```scala
isWin     // Boolean: true on a Windows JVM
shellRoot // String:  e.g. "/", "C:/msys64", "C:/cygwin64"
```

`showUsage()` prints a usage message that includes the correct program name regardless of how it was launched — as an `sbt` task, a `scala-cli` script, or a compiled application run from IntelliJ or the command line. The program name is derived automatically; no hardcoded string is needed.

### Tips for portable scripts

- Split lines with `"(\r)?\n"` to handle both Windows and Unix line endings
- Prefer forward slashes in path literals; avoid `C:\` style except when displaying to users
- Use `/mnt/c` or `/c` rather than `C:/` in posix shell contexts

---

## Command-Line Argument Parsing

`eachArg` provides pattern-match based argument parsing. Each argument is matched against a partial function; unrecognised arguments are forwarded to a `usage` handler automatically. `showUsage()` in the handler derives the program name from the runtime environment, so no usage string needs to be hardcoded.

The following script hashes all files under a directory and demonstrates argument parsing, `showUsage`, directory traversal, and `hash64` together:

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.2

import uni.*

def usage(m: String = ""): Nothing = {
  showUsage(m, "",
    "<dirname>    ; hash all files below directory",
  )
}

var verbose = false
var dirname = ""
eachArg(args.toSeq, usage) {
  case "-v" => verbose = true
  case dir if dir.asPath.isDirectory =>
    dirname = dir
  case arg =>
    usage(s"unrecognized arg [$arg]")
}
if dirname.isEmpty then
  usage()

dirname.asPath.paths.foreach { p =>
  if p.isFile then
    println(s"${p.hash64} ${p.posx}")
}
```

Running with an unrecognised argument produces:
```
unrecognized arg [--help]
usage: hash64demo.sc <options>
<dirname>    ; hash all files below directory
```

The program name (`hash64demo.sc`) is derived automatically from the runtime environment.

---

## Smart Date Parsing

`uni.time` parses date and timestamp strings to `java.time.LocalDateTime` without a format string. It recognises a wide range of formats automatically, which makes it particularly useful when reading CSV files whose date columns use an unknown or inconsistent format. Strings that cannot be parsed return the `BadDate` sentinel value rather than throwing an exception.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.2

import uni.time.*

println(parseDate("2024-03-15 14:23:01"))               // ISO 8601
println(parseDate("March 15, 2024"))                    // natural language
println(parseDate("15/03/2024 14:23"))                  // European
println(parseDate("1710505381"))                        // Unix timestamp
println(parseDate("2024-03-15").toString("yyyy-MM-dd")) // formatted output
```

Supported formats include ISO 8601, US, European, natural language, Unix timestamps, and many mixed-separator variants.

---

## Inline Data (`__DATA__`)

A source file can include a block of raw data after the code, enclosed in a `/* __DATA__ ... */` comment. `uni.data.HereDoc.DATA` returns that content as an `Iterator[String]` at runtime. Because the data is read from the file rather than stored as a String literal, there is no 64KB size restriction.

```scala
#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.11.2

import uni.data.HereDoc

val data: Iterator[String] = HereDoc.DATA

for line <- data do
  println(line)

/* __DATA__
alice,30,engineer
bob,25,designer
carol,35,manager
*/
```

---

## File I/O Utilities (`uni.io`)