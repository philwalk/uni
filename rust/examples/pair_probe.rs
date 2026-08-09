//! One half of the cross-language pair probe; `jsrc/pairProbe.sc` is the other. The outputs are
//! byte-identical -- `copyTo` returns an Option in both languages, so no line differs by design.
//! Both build the same tree with the same fixed mtimes, run the same ops, and print
//! `op<TAB>key<TAB>value` with the temp prefix normalised to `<T>`, so the outputs diff directly.

#![allow(non_snake_case, reason = "keys mirror the Scala probe line for line")]
#![allow(
    clippy::print_stdout,
    clippy::too_many_lines,
    clippy::absolute_paths,
    reason = "a probe is one long main that prints; that is its job"
)]
#![allow(clippy::unwrap_used, clippy::expect_used, reason = "a probe should die loudly")]

use std::fs;
use std::sync::Arc;
use std::time::{Duration, UNIX_EPOCH};

use uni::upath::{PathContext, UPath, UserInfo};

fn set_mtime(p: &UPath, millis: u64) {
    let f = fs::OpenOptions::new().write(true).open(p.as_std_path()).expect("open");
    f.set_modified(UNIX_EPOCH + Duration::from_millis(millis)).expect("set_modified");
}

fn main() {
    let t = tempfile::tempdir().expect("tmp");
    let troot = t.path().to_string_lossy().replace('\\', "/");
    let ctx = Arc::new(PathContext::synthetic(
        &[],
        UserInfo::new("tester", &troot, &troot),
        cfg!(windows),
    ));
    let at = |s: &str| UPath::resolve(&ctx, &format!("{troot}/{s}")).expect("resolve");
    let posx_of = |p: &UPath| p.posx().to_owned();
    let rel = |s: String| s.replace(&troot, "<T>");
    let emit = |op: &str, key: &str, v: String| println!("{op}\t{key}\t{}", rel(v));

    let a = at("a.txt");     fs::write(a.as_std_path(), "alpha\nmidline\n").unwrap();
    let b = at("b.txt");     fs::write(b.as_std_path(), "bb").unwrap();
    let e = at("empty.txt"); fs::write(e.as_std_path(), "").unwrap();
    let d = at("subdir");    fs::create_dir(d.as_std_path()).unwrap();
    let c = at("subdir/c.txt"); fs::write(c.as_std_path(), "ccc").unwrap();
    set_mtime(&a, 1_715_524_200_000);
    set_mtime(&b, 1_715_524_260_000);
    set_mtime(&e, 946_684_800_000);
    let missing = at("no-such.txt");

    emit("exists", "file", a.exists().to_string());       emit("exists", "dir", d.exists().to_string());   emit("exists", "missing", missing.exists().to_string());
    emit("isFile", "file", a.isFile().to_string());       emit("isFile", "dir", d.isFile().to_string());   emit("isFile", "missing", missing.isFile().to_string());
    emit("isDirectory", "file", a.isDirectory().to_string()); emit("isDirectory", "dir", d.isDirectory().to_string());
    emit("length", "file", a.length().to_string());       emit("length", "empty", e.length().to_string()); emit("length", "missing", missing.length().to_string());
    emit("isEmpty", "file", a.isEmpty().to_string());     emit("isEmpty", "empty", e.isEmpty().to_string()); emit("isEmpty", "missing", missing.isEmpty().to_string());
    emit("nonEmpty", "file", a.nonEmpty().to_string());   emit("nonEmpty", "empty", e.nonEmpty().to_string());
    emit("canRead", "file", a.canRead().to_string());     emit("canRead", "dir", d.canRead().to_string()); emit("canRead", "missing", missing.canRead().to_string());
    emit("canExecute", "dir", d.canExecute().to_string());
    emit("canExecute", "file", a.canExecute().to_string());
    emit("canExecute", "missing", missing.canExecute().to_string());
    emit("isSymbolicLink", "file", a.isSymbolicLink().to_string());
    emit("lastModified", "a", a.lastModified().to_string());
    emit("lastModifiedYMD", "a", a.lastModifiedYMD());
    emit("lastModifiedYMD", "e", e.lastModifiedYMD());
    emit("lastModifiedTime", "a", a.lastModifiedTime().to_string());
    emit("weekDay", "a", a.weekDay().to_string());
    emit("weekDayName", "a", a.weekDayName().to_string());
    emit("newerThan", "a-vs-b", a.newerThan(&b).to_string()); emit("newerThan", "b-vs-a", b.newerThan(&a).to_string());
    emit("olderThan", "a-vs-b", a.olderThan(&b).to_string()); emit("olderThan", "dir-vs-a", d.olderThan(&a).to_string());
    emit("epoch2DateTime", "0", uni::upath::times::epoch2DateTime(0, 0).to_string());
    emit("epoch2DateTime", "1715524200000", uni::upath::times::epoch2DateTime(1_715_524_200_000, 0).to_string());
    emit("realPath", "existing", rel(posx_of(&c.realPath())));
    emit("realPath", "missing-child", rel(posx_of(&at("subdir/ghost.txt").realPath())));
    emit("firstLine", "file", a.firstLine()); emit("firstLine", "missing", missing.firstLine());
    emit("contentAsString", "b", b.contentAsString());

    let cp = at("copy1.txt");
    emit("copyTo", "fresh", rel(a.copyTo(&cp, false, false).map(|p| posx_of(&p)).unwrap_or_else(|| "None".into())));
    emit("copyTo-collision", "no-overwrite", a.copyTo(&cp, false, false).map(|p| posx_of(&p)).unwrap_or_else(|| "None".into()));
    emit("renameToOpt", "collision-no-overwrite", b.renameToOpt(&cp, false).map(|p| posx_of(&p)).unwrap_or_else(|| "None".into()));
    emit("renameTo", "missing-source", missing.renameTo(&at("x.txt"), false).to_string());
    emit("renameViaCopy", "missing-source", missing.renameViaCopy(&at("y.txt"), false).to_string());
    let rvc = at("moved.txt");
    emit("renameViaCopy", "ok", e.renameViaCopy(&rvc, false).to_string());
    emit("renameViaCopy", "source-gone", e.exists().to_string());
    emit("mkdirs", "over-file", a.mkdirs().to_string());
    emit("mkdirs", "existing-dir", d.mkdirs().to_string());
    emit("delete", "missing", missing.delete().to_string());
    emit("delete", "file", rvc.delete().to_string());
    let w = at("written.txt");
    w.write("one\ntwo");
    emit("write-read", "roundtrip", w.lines().join("|"));
    w.writeLines(&["x", "y"]);
    emit("writeLines-read", "roundtrip", w.lines().join("|"));
}
