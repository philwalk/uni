#!/usr/bin/env -S scala-cli shebang -Wunused:imports -Wunused:locals -deprecation
//> using dep org.vastblue:uni_3:0.18.0

// One half of the cross-language pair probe; `rust/examples/pair_probe.rs` is the other.
// Both build the same tree with the same fixed mtimes, run the same ops, and print
// `op<TAB>key<TAB>value` with the temp prefix normalised to `<T>`, so the outputs diff directly:
//
//   scala-cli run jsrc/pairProbe.sc          > scala.out
//   cargo run --example pair_probe           > rust.out     (from rust/)
//   diff scala.out rust.out
//
// The outputs are byte-identical: since `copyTo` returns Option[Path] in both languages
// (0.16.0), a refused overwrite prints None on both sides and no line differs by design.
//
// This exists because parity fixtures pin methods one at a time against recorded values, so a
// method with no fixture is pinned by nothing. On its first run this caught canRead/canExecute
// answering false for every directory on Windows, and mkdirs throwing on one side.
object PairProbe {
  def println(s: String = ""): Unit = print(s"$s\n")

  import java.nio.file.Files
  import java.nio.file.attribute.FileTime
  import uni.*

  def main(args: Array[String]): Unit = {
    val t = Files.createTempDirectory("pairprobe")
    val T = t.posx
    def rel(s: String): String = s.replace(T, "<T>")

    val a = t.resolve("a.txt");     Files.writeString(a, "alpha\nmidline\n")
    val b = t.resolve("b.txt");     Files.writeString(b, "bb")
    val e = t.resolve("empty.txt"); Files.writeString(e, "")
    val d = Files.createDirectory(t.resolve("subdir"))
    val c = d.resolve("c.txt");     Files.writeString(c, "ccc")
    Files.setLastModifiedTime(a, FileTime.fromMillis(1715524200000L)) // 2024-05-12T14:30:00Z, a Sunday
    Files.setLastModifiedTime(b, FileTime.fromMillis(1715524260000L)) // one minute later
    Files.setLastModifiedTime(e, FileTime.fromMillis(946684800000L))  // 2000-01-01T00:00:00Z
    val missing = t.resolve("no-such.txt")

    def emit(op: String, key: String, v: Any): Unit = println(s"$op\t$key\t${rel(v.toString)}")

    emit("exists", "file", a.exists);      emit("exists", "dir", d.exists);      emit("exists", "missing", missing.exists)
    emit("isFile", "file", a.isFile);      emit("isFile", "dir", d.isFile);      emit("isFile", "missing", missing.isFile)
    emit("isDirectory", "file", a.isDirectory); emit("isDirectory", "dir", d.isDirectory)
    emit("length", "file", a.length);      emit("length", "empty", e.length);    emit("length", "missing", missing.length)
    emit("isEmpty", "file", a.isEmpty);    emit("isEmpty", "empty", e.isEmpty);  emit("isEmpty", "missing", missing.isEmpty)
    emit("nonEmpty", "file", a.nonEmpty);  emit("nonEmpty", "empty", e.nonEmpty)
    emit("canRead", "file", a.canRead);    emit("canRead", "dir", d.canRead);    emit("canRead", "missing", missing.canRead)
    emit("canExecute", "dir", d.canExecute)
    emit("canExecute", "file", a.canExecute)
    emit("canExecute", "missing", missing.canExecute)
    emit("isSymbolicLink", "file", a.isSymbolicLink)
    emit("lastModified", "a", a.lastModified)
    emit("lastModifiedYMD", "a", a.lastModifiedYMD)
    emit("lastModifiedYMD", "e", e.lastModifiedYMD)
    emit("lastModifiedTime", "a", a.lastModifiedTime)
    emit("weekDay", "a", a.weekDay)
    emit("weekDayName", "a", a.weekDayName)
    emit("newerThan", "a-vs-b", a.newerThan(b)); emit("newerThan", "b-vs-a", b.newerThan(a))
    emit("olderThan", "a-vs-b", a.olderThan(b)); emit("olderThan", "dir-vs-a", d.olderThan(a))
    emit("epoch2DateTime", "0", a.epoch2DateTime(0L))
    emit("epoch2DateTime", "1715524200000", a.epoch2DateTime(1715524200000L))
    emit("realPath", "existing", rel(c.realPath.posx))
    emit("realPath", "missing-child", rel(d.resolve("ghost.txt").realPath.posx))
    emit("firstLine", "file", a.firstLine); emit("firstLine", "missing", missing.firstLine)
    emit("contentAsString", "b", b.contentAsString)

    // mutation semantics: collision handling and status codes
    val cp = t.resolve("copy1.txt")
    def optStr(o: Option[java.nio.file.Path]): String = o.map(_.posx).getOrElse("None")
    emit("copyTo", "fresh", rel(optStr(a.copyTo(cp, overwrite = false))))
    emit("copyTo-collision", "no-overwrite", optStr(a.copyTo(cp, overwrite = false)))
    emit("renameToOpt", "collision-no-overwrite", b.renameToOpt(cp, overwrite = false))
    emit("renameTo", "missing-source", missing.renameTo(t.resolve("x.txt"), overwrite = false))
    emit("renameViaCopy", "missing-source", missing.renameViaCopy(t.resolve("y.txt"), overwrite = false))
    val rvc = t.resolve("moved.txt")
    emit("renameViaCopy", "ok", e.renameViaCopy(rvc, overwrite = false))
    emit("renameViaCopy", "source-gone", e.exists)
    emit("mkdirs", "over-file", a.mkdirs)
    emit("mkdirs", "existing-dir", d.mkdirs)
    emit("delete", "missing", missing.delete())
    emit("delete", "file", rvc.delete())
    val w = t.resolve("written.txt")
    w.write("one\ntwo")
    emit("write-read", "roundtrip", w.lines.mkString("|"))
    w.writeLines(Seq("x", "y"))
    emit("writeLines-read", "roundtrip", w.lines.mkString("|"))
  }
}