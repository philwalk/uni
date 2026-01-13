# uni.Paths

Write portable code that runs everywhere (Windows, Linux, Mac)

+ Extends `Paths.get(...)` with MSYS2/Cygwin filesystem view
  + alternative to `java.nio.file.Paths` sees MSYS2/cygwin mounts
  + `uni.Paths.get("/etc/hosts").lines.foreach(println)`
+ no 3rd party libraries, 100% scala.
+ extended path strings are supported if `mount.exe` is in the PATH

<img alt="uni image" width=200 src="images/uni.png">

Recognizes `posix` file paths in Windows, via customizable mount points in `C:/msys64/etc/fstab`.

* Supported Scala Versions
  * `scala 3.x`

* Tested Target environments
  * `Linux`
  * `Darwin/OSX`
  * `Windows`
    * `Cygwin64`
    * `Msys64`
    * `Mingw64`
    * `Git-bash`

### Usage

To use `uni` in an `SBT` project, add this dependency to `build.sbt`

```sbt
  "org.vastblue" % "uni_3" % "0.5.2"
  ```
For `scala 3.5+` or `scala-cli` scripts:
```sbt
  "//> using dep org.vastblue:uni_3:0.5.2"
```
## Simplicity and Portability
  * Script as though you're running in a Linux environment.
## Linux / MacOS Requirements
  * replace `import java.nio.file.Paths` with `import uni.Path`
  * or directly call `uni.Paths.get(...)`
  * no other changes needed (100% compatible drop in replacement for Paths.get)
## Windows Requirements
  ### a posix shell:
  * [MSYS64](https://msys2.org)
  * [CYGWIN64](https://www.cygwin.com)
  * [Git Bash](https://www.atlassian.com/git/tutorials/git-bash)
  ### `mount.exe` must be in the PATH

### Concept
  * `Paths.get` returns `java.nio.file.Path` objects
  * `Paths.get("/etc/fstab").toString` == `/etc/fstab` in most environments
  * `Paths.get("/etc/fstab").toString` == `C:\msys64\etc\fstab` (in MSYS64, for example)
  * `Paths.get("/etc/fstab").posx`     == `C:/msys64/etc/fstab`
  * `Paths.get("/etc/fstab").stdpath   == `/etc/fstab`

Examples below illustrate some of the capabilities.

### Background
Windows shell environments `cygwin64`, `msys64`, `Git-bash`, etc. provide posix-like file paths.
The `Windows` jvm can now use these filesystem abstractions.

This library provides:

  * in Windows: a universal `uni.Paths.get()` returning a usable `java.nio.file.Path` object
  * In all other environments `uni.Paths.get()` defers to `java.nio.file.Paths.get()`
  * extensions to `java.nio.file.Path` and `java.io.File` simplify writing portable code

### Example script: display the native path and the number of lines in `/etc/fstab`
The following example might surprise Windows developers, since JVM languages don't normally support posix file paths that aren't also legal Windows paths.

```scala
#!/usr/bin/env -S scala-cli shebang
//> using dep "org.vastblue::uni:0.5.2"

import uni.Paths
import uni.fs.{call, posx, lines}

// display the native path and lines.size of /etc/fstab
val p = Paths.get("/etc/fstab")
val sysType = call("uname", "-o").getOrElse("")
printf("env: %-10s| %-22s | %d lines\n", sysType, p.posx, p.lines.size)
```
### Output of the previous example script on various platforms:
```
Linux Mint # env: GNU/Linux | shellRoot: /           | /etc/fstab            | 21 lines
Darwin     # env: Darwin    | shellRoot: /           | /etc/fstab            | 0 lines
WSL Ubuntu # env: GNU/Linux | shellRoot: /           | /etc/fstab            | 6 lines
Cygwin64   # env: Cygwin    | shellRoot: C:/cygwin64 | C:/cygwin64/etc/fstab | 24 lines
Msys64     # env: Msys      | shellRoot: C:/msys64/  | C:/msys64/etc/fstab   | 22 lines
```
Note that on Darwin, there is no `/etc/fstab` file, so `Path#lines` is empty.

### Setup
  * `Windows`: install one of the following:
    * [MSYS64](https://msys2.org)
    * [CYGWIN64](https://www.cygwin.com)
    * [Git Bash](https://www.atlassian.com/git/tutorials/git-bash)
  * `Linux`: required packages:
    * `sudo apt install coreutils`
  * `Darwin/OSX`:
    * `brew install coreutils`

### Tips for Writing Portable Scala Scripts
Most portability issues concern the peculiaritites of the Windows jvm.
Things that maximize the odds of your script running everywhere:
  * prefer `scala 3`
  * specify `Windows` file paths with any `cygpath` equivalent form
    * but prefer forward slashes except when displaying path strings
  * avoid Windows syntax when specifying drive letters
    * specify paths with "/mnt/c" or "/c" rather than "C:/"
    * drive letter not needed for paths on the current working drive (e.g. C:)
    * drive-relative root is "/"
  * split text on newlines using OS-agnostic regex `"(\r)?\n"`
