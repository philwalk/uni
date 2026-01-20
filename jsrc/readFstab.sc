#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation

//> using dep org.vastblue:uni_3:0.6.1

import uni.*
import uni.fs.* // useful extensions

// display the native path and lines.size of /etc/fstab
// mapped to "C:\msys64\etc\fstab" in default install for Windows MSYS2

// display the native path and lines.size of /etc/fstab
// mapped to "C:\msys64\etc\fstab" in Windows msys2

val sysType = uname("-o")

println(s"`uname -o` => [$sysType]")

for (fname <- Seq("/etc/hosts", "~/.bashrc"))
  val p = fname.path
  assert(p.exists, s"${p.posx}")

for (fname <- Seq("/etc/fstab", "/etc/hosts", "~/.bashrc", "/opt/no-such-file")) {
  val p = Paths.get(fname)
  val size = if p.exists then p.lines.size else 0
    printf("%-32s| %4d lines\n", p.posx, size)
}

def uname(arg: String): String = {
  call("uname", arg).getOrElse("")
}
