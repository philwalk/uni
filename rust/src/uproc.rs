//! Subprocesses — a port of Scala's `uni.Proc` (`run`, `proc`, `ProcResult`,
//! `ProcBuilder`, the `where`/`bashExe`/`uname` family) on `std::process`.
//!
//! # What is the same
//!
//! - **Routing by extension**, the reason the Scala API exists: `run(&["tools/setup.sh"])`
//!   prepends `bashExe` on every platform; on Windows `.py` gets `pythonExe`, `.sc` gets
//!   `scala-cli shebang`, `.bat`/`.cmd` get `cmd.exe /c`, `.ps1` gets `powershell.exe -File`,
//!   and any other program name gets `.exe` appended — so a script line written for Linux
//!   runs unchanged. [`route_cmd`] takes the platform as a parameter, so both tables are
//!   testable anywhere; [`run`] passes `cfg!(windows)`.
//! - **A missing program is a result, not a panic**: status −1, the OS message on `stderr`.
//! - [`ProcResult`]'s helpers — `text`, `lines`, `ok`, `toOption`, `orElse`, `headOnly`,
//!   `takeOnly` — and the chainable `orLog` (Scala's `!!`); `ProcBuilder`'s `cwd`, `env`,
//!   `stdin`, `timeout` (status −1 on expiry) and the buffered/streaming terminals.
//! - `bashExe`, `pythonExe`, `unameExe` resolved once (`OnceLock`), `uname`, `isWsl`,
//!   `osType`, `hostname`, `whereInPath`.
//!
//! # What is different, and why
//!
//! - Scala's `where` is spelled [`whereExe`] (`where` is a Rust keyword) and returns
//!   `Option<String>` rather than throwing; [`whereInPath`] is the same PATH scan as Scala's.
//! - Scala's `failFast { … orFail "msg" }` is `?` on [`ProcResult::orFail`] /
//!   [`StatusExt::orFail`], which return `Result<_, i32>` carrying the status.
//! - Scala's streaming overload `run(cmd)(out, err)` is [`runStream`]; the callbacks run on
//!   the calling thread (a channel carries the lines), so they need not be `Send`.
//! - `execLines` is a lazy iterator over stdout, as the Scala `LazyList` is.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name, so a script kept in both \
              languages needs no mental translation. Internal helpers stay snake_case, \
              so the case says whether a Scala counterpart exists."
)]

use std::io::BufRead;
use std::io::BufReader;
use std::io::Read;
use std::io::Write;
use std::path::Path;
use std::process::Child;
use std::process::Command;
use std::process::Stdio;
use std::sync::OnceLock;
use std::sync::mpsc;
use std::time::Duration;
use std::time::Instant;

/// What a buffered run returns — Scala's `ProcResult(status, stdout, stderr, cmd)`.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ProcResult {
    /// The exit status; −1 when the program could not be started or timed out.
    pub status: i32,
    pub stdout: Vec<String>,
    pub stderr: Vec<String>,
    /// The command actually sent to the OS, after routing.
    pub cmd: Vec<String>,
}

impl ProcResult {
    /// stdout lines joined by `\n`.
    #[must_use]
    pub fn text(&self) -> String {
        self.stdout.join("\n")
    }
    /// stdout lines.
    #[must_use]
    pub fn lines(&self) -> &[String] {
        &self.stdout
    }
    /// `status == 0`.
    #[must_use]
    pub fn ok(&self) -> bool {
        self.status == 0
    }
    /// `Some(text)` when ok and stdout is non-empty.
    #[must_use]
    pub fn toOption(&self) -> Option<String> {
        if self.ok() && !self.stdout.is_empty() {
            Some(self.text())
        } else {
            None
        }
    }
    /// `text` when ok and non-empty, else `default`.
    #[must_use]
    pub fn orElse(&self, default: &str) -> String {
        self.toOption().unwrap_or_else(|| default.to_owned())
    }
    /// The first stdout line (`""` when there is none).
    #[must_use]
    pub fn headOnly(&self) -> String {
        self.stdout.first().cloned().unwrap_or_default()
    }
    /// The first `n` stdout lines.
    #[must_use]
    pub fn takeOnly(&self, n: usize) -> Vec<String> {
        self.stdout.iter().take(n).cloned().collect()
    }
    /// Number of stdout lines (Scala: `IndexedSeq.length`).
    #[must_use]
    pub fn len(&self) -> usize {
        self.stdout.len()
    }
    #[must_use]
    pub fn is_empty(&self) -> bool {
        self.stdout.is_empty()
    }
    /// Scala's `!!`: log `msg [status]: cmd` to stderr when not ok; returns self.
    #[must_use]
    pub fn orLog(self, msg: &str) -> Self {
        if !self.ok() {
            log_failure(msg, self.status, &self.cmd);
        }
        self
    }
    /// Scala's `orFail` inside `failFast`: `Err(status)` when not ok, for `?`.
    ///
    /// # Errors
    /// The exit status, after logging `msg [status]: cmd`.
    pub fn orFail(self, msg: &str) -> Result<Self, i32> {
        if self.ok() {
            Ok(self)
        } else {
            log_failure(msg, self.status, &self.cmd);
            Err(self.status)
        }
    }
}

/// Scala's `Int` extensions on a streaming status: `!!` → `orLog`, `orElse`, `orFail`.
pub trait StatusExt {
    /// Log `msg [status]` when non-zero; returns the status.
    #[must_use]
    fn orLog(self, msg: &str) -> i32;
    /// Call `f("exit status: <n>")` when non-zero; returns the status.
    #[must_use]
    fn orElse(self, f: impl FnOnce(&str)) -> i32;
    /// `Err(status)` when non-zero, for `?`.
    ///
    /// # Errors
    /// The non-zero status, after logging `msg [status]`.
    fn orFail(self, msg: &str) -> Result<i32, i32>;
}

impl StatusExt for i32 {
    fn orLog(self, msg: &str) -> i32 {
        if self != 0 {
            log_status(msg, self);
        }
        self
    }
    fn orElse(self, f: impl FnOnce(&str)) -> i32 {
        if self != 0 {
            f(&format!("exit status: {self}"));
        }
        self
    }
    fn orFail(self, msg: &str) -> Result<i32, i32> {
        if self == 0 {
            Ok(0)
        } else {
            log_status(msg, self);
            Err(self)
        }
    }
}

#[expect(
    clippy::print_stderr,
    reason = "the documented behaviour of `!!`/`orFail`: a line on stderr"
)]
fn log_failure(msg: &str, status: i32, cmd: &[String]) {
    eprintln!("{msg} [{status}]: {}", cmd.join(" "));
}

#[expect(
    clippy::print_stderr,
    reason = "the documented behaviour of `!!`/`orFail`: a line on stderr"
)]
fn log_status(msg: &str, status: i32) {
    eprintln!("{msg} [{status}]");
}

#[expect(
    clippy::print_stderr,
    reason = "the streaming default `err` callback, as in Scala"
)]
fn eprint_line(line: &str) {
    eprintln!("{line}");
}

// ── routing ─────────────────────────────────────────────────────────────────

/// Route by file extension — the table Scala's `routeCmd` applies. `is_windows` is a
/// parameter so both tables are testable on either platform.
#[must_use]
pub fn route_cmd(cmd: &[&str], is_windows: bool) -> Vec<String> {
    let mut v: Vec<String> = cmd.iter().map(|s| (*s).to_owned()).collect();
    let Some(h) = cmd.first() else { return v };
    if h.ends_with(".sh") {
        v.insert(0, bashExe().to_owned());
    } else if is_windows && h.ends_with(".py") {
        v.insert(0, pythonExe().to_owned());
    } else if is_windows && h.ends_with(".sc") {
        v.insert(0, "shebang".to_owned());
        v.insert(0, "scala-cli".to_owned());
    } else if is_windows && (h.ends_with(".bat") || h.ends_with(".cmd")) {
        v.insert(0, "/c".to_owned());
        v.insert(0, "cmd.exe".to_owned());
    } else if is_windows && h.ends_with(".ps1") {
        v.insert(0, "-File".to_owned());
        v.insert(0, "powershell.exe".to_owned());
    } else if is_windows {
        v[0] = format!("{}.exe", h.strip_suffix(".exe").unwrap_or(h));
    }
    v
}

// ── running ─────────────────────────────────────────────────────────────────

enum Line {
    Out(String),
    Err(String),
    Done,
}

fn read_lines(reader: impl Read, tag: fn(String) -> Line, tx: &mpsc::Sender<Line>) {
    let mut br = BufReader::new(reader);
    let mut buf = Vec::new();
    loop {
        buf.clear();
        match br.read_until(b'\n', &mut buf) {
            Ok(0) | Err(_) => break,
            Ok(_) => {
                if buf.last() == Some(&b'\n') {
                    buf.pop();
                }
                if buf.last() == Some(&b'\r') {
                    buf.pop();
                }
                if tx
                    .send(tag(String::from_utf8_lossy(&buf).into_owned()))
                    .is_err()
                {
                    break;
                }
            }
        }
    }
    tx.send(Line::Done).ok();
}

fn spawn_child(
    routed: &[String],
    cwd: Option<&Path>,
    env: &[(String, String)],
    stdin: bool,
) -> std::io::Result<Child> {
    let mut c = Command::new(&routed[0]);
    c.args(&routed[1..])
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());
    c.stdin(if stdin { Stdio::piped() } else { Stdio::null() });
    if let Some(d) = cwd {
        c.current_dir(d);
    }
    for (k, v) in env {
        c.env(k, v);
    }
    c.spawn()
}

/// Wait for the child, dispatching lines to `out`/`err` as they arrive; kills it when
/// `timeout` expires. Returns the status (−1 on timeout).
fn pump(
    mut child: Child,
    timeout: Option<Duration>,
    mut out: impl FnMut(&str),
    mut err: impl FnMut(&str),
) -> i32 {
    let (tx, rx) = mpsc::channel::<Line>();
    let mut readers = 0;
    if let Some(so) = child.stdout.take() {
        let tx = tx.clone();
        std::thread::spawn(move || read_lines(so, Line::Out, &tx));
        readers += 1;
    }
    if let Some(se) = child.stderr.take() {
        let tx = tx.clone();
        std::thread::spawn(move || read_lines(se, Line::Err, &tx));
        readers += 1;
    }
    drop(tx);
    let deadline = timeout.map(|t| Instant::now() + t);
    let mut done = 0;
    let mut timed_out = false;
    while done < readers {
        let msg = match deadline {
            Some(d) => {
                let now = Instant::now();
                if now >= d {
                    timed_out = true;
                    break;
                }
                match rx.recv_timeout(d - now) {
                    Ok(m) => m,
                    Err(mpsc::RecvTimeoutError::Timeout) => {
                        timed_out = true;
                        break;
                    }
                    Err(mpsc::RecvTimeoutError::Disconnected) => break,
                }
            }
            None => match rx.recv() {
                Ok(m) => m,
                Err(_) => break,
            },
        };
        match msg {
            Line::Out(l) => out(&l),
            Line::Err(l) => err(&l),
            Line::Done => done += 1,
        }
    }
    if timed_out {
        child.kill().ok();
        child.wait().ok();
        return -1;
    }
    match child.wait() {
        Ok(st) => st.code().unwrap_or(-1),
        Err(_) => -1,
    }
}

fn run_routed(
    routed: Vec<String>,
    cwd: Option<&Path>,
    env: &[(String, String)],
    stdin: Option<&str>,
    timeout: Option<Duration>,
) -> ProcResult {
    match spawn_child(&routed, cwd, env, stdin.is_some()) {
        Ok(mut child) => {
            if let (Some(text), Some(mut si)) = (stdin, child.stdin.take()) {
                let bytes = text.as_bytes().to_vec();
                std::thread::spawn(move || {
                    si.write_all(&bytes).ok();
                });
            }
            let mut out = Vec::new();
            let mut err = Vec::new();
            let status = pump(
                child,
                timeout,
                |l| out.push(l.to_owned()),
                |l| err.push(l.to_owned()),
            );
            ProcResult {
                status,
                stdout: out,
                stderr: err,
                cmd: routed,
            }
        }
        Err(e) => ProcResult {
            status: -1,
            stdout: Vec::new(),
            stderr: vec![e.to_string()],
            cmd: routed,
        },
    }
}

/// Buffered: captures stdout and stderr; a program that cannot start yields status −1
/// with the OS message on `stderr` rather than an error.
#[must_use]
pub fn run(cmd: &[&str]) -> ProcResult {
    run_routed(route_cmd(cmd, cfg!(windows)), None, &[], None, None)
}

/// Streaming — Scala's `run(cmd)(out, err)`: `out` per stdout line, `err` per stderr line
/// (both on the calling thread), returns the exit status; −1 when the program cannot start
/// (the message goes to `err`).
pub fn runStream(cmd: &[&str], out: impl FnMut(&str), err: impl FnMut(&str)) -> i32 {
    proc(cmd).stream(out, err)
}

/// Streaming with stdout only; stderr lines go to the process's stderr, as in Scala.
pub fn runLines(cmd: &[&str], out: impl FnMut(&str)) -> i32 {
    proc(cmd).stream(out, eprint_line)
}

/// Lazy stdout lines of `cmd` — Scala's `execLines` `LazyList`. stderr is inherited; a
/// program that cannot start yields no lines.
pub fn execLines(cmd: &[&str]) -> impl Iterator<Item = String> {
    let routed = route_cmd(cmd, cfg!(windows));
    let child = Command::new(&routed[0])
        .args(&routed[1..])
        .stdout(Stdio::piped())
        .stdin(Stdio::null())
        .spawn()
        .ok();
    let reader = child.and_then(|mut c| c.stdout.take()).map(BufReader::new);
    let mut lines = reader.map(BufRead::lines);
    std::iter::from_fn(move || lines.as_mut()?.next()?.ok())
}

/// A configurable run — Scala's `ProcBuilder`: `cwd`, `env`, `stdin`, `timeout`, then
/// `run()` (buffered) or `stream(out, err)`.
pub struct ProcBuilder {
    cmd: Vec<String>,
    cwd: Option<std::path::PathBuf>,
    env: Vec<(String, String)>,
    stdin: Option<String>,
    timeout: Option<Duration>,
}

/// Start a builder for `cmd`.
#[must_use]
pub fn proc(cmd: &[&str]) -> ProcBuilder {
    ProcBuilder {
        cmd: cmd.iter().map(|s| (*s).to_owned()).collect(),
        cwd: None,
        env: Vec::new(),
        stdin: None,
        timeout: None,
    }
}

impl ProcBuilder {
    /// Working directory of the child.
    #[must_use]
    pub fn cwd(mut self, p: impl AsRef<Path>) -> Self {
        self.cwd = Some(p.as_ref().to_path_buf());
        self
    }
    /// Additional environment variables, merged into the child's environment.
    #[must_use]
    pub fn env(mut self, vars: &[(&str, &str)]) -> Self {
        self.env = vars
            .iter()
            .map(|(k, v)| ((*k).to_owned(), (*v).to_owned()))
            .collect();
        self
    }
    /// Text piped to the child's stdin.
    #[must_use]
    pub fn stdin(mut self, s: &str) -> Self {
        self.stdin = Some(s.to_owned());
        self
    }
    /// Maximum wait in milliseconds; status −1 on expiry (the child is killed).
    #[must_use]
    pub fn timeout(mut self, ms: u64) -> Self {
        self.timeout = Some(Duration::from_millis(ms));
        self
    }
    /// Buffered — the same result type as [`run`].
    #[must_use]
    pub fn run(&self) -> ProcResult {
        let cmd: Vec<&str> = self.cmd.iter().map(String::as_str).collect();
        run_routed(
            route_cmd(&cmd, cfg!(windows)),
            self.cwd.as_deref(),
            &self.env,
            self.stdin.as_deref(),
            self.timeout,
        )
    }
    /// Streaming — `out` per stdout line, `err` per stderr line; the exit status.
    pub fn stream(&self, out: impl FnMut(&str), mut err: impl FnMut(&str)) -> i32 {
        let cmd: Vec<&str> = self.cmd.iter().map(String::as_str).collect();
        let routed = route_cmd(&cmd, cfg!(windows));
        match spawn_child(
            &routed,
            self.cwd.as_deref(),
            &self.env,
            self.stdin.is_some(),
        ) {
            Ok(mut child) => {
                if let (Some(text), Some(mut si)) = (self.stdin.as_deref(), child.stdin.take()) {
                    let bytes = text.as_bytes().to_vec();
                    std::thread::spawn(move || {
                        si.write_all(&bytes).ok();
                    });
                }
                pump(child, self.timeout, out, err)
            }
            Err(e) => {
                err(&e.to_string());
                -1
            }
        }
    }
}

// ── the tool family ─────────────────────────────────────────────────────────

/// Direct two-argument call, no routing — what `bashExe`/`pythonExe` use before routing
/// could depend on them. First stdout line when status 0 and non-empty.
fn call_quiet(cmd: &str, arg: &str) -> Option<String> {
    let out = Command::new(cmd)
        .arg(arg)
        .stdin(Stdio::null())
        .stderr(Stdio::null())
        .output()
        .ok()?;
    if !out.status.success() {
        return None;
    }
    let text = String::from_utf8_lossy(&out.stdout);
    text.lines()
        .map(str::trim)
        .find(|l| !l.is_empty())
        .map(str::to_owned)
}

/// Absolute path to bash — `where.exe bash.exe` on Windows (msys/cygwin/git), `/bin/bash`
/// elsewhere; resolved once.
pub fn bashExe() -> &'static str {
    static V: OnceLock<String> = OnceLock::new();
    V.get_or_init(|| {
        if cfg!(windows) {
            call_quiet("where.exe", "bash.exe").unwrap_or_else(|| "bash.exe".to_owned())
        } else {
            "/bin/bash".to_owned()
        }
    })
}

/// Absolute path to python3 (or python); resolved once.
pub fn pythonExe() -> &'static str {
    static V: OnceLock<String> = OnceLock::new();
    V.get_or_init(|| {
        if cfg!(windows) {
            call_quiet("where.exe", "python3.exe")
                .or_else(|| call_quiet("where.exe", "python.exe"))
                .unwrap_or_else(|| "python3.exe".to_owned())
        } else {
            call_quiet("which", "python3")
                .or_else(|| call_quiet("which", "python"))
                .unwrap_or_else(|| "python3".to_owned())
        }
    })
}

/// Absolute path to uname when one is on the PATH, else the bare name — a Windows machine
/// without msys legitimately has none, and this must not fail on first reference.
pub fn unameExe() -> &'static str {
    static V: OnceLock<String> = OnceLock::new();
    V.get_or_init(|| {
        whereInPath("uname").unwrap_or_else(|| {
            if cfg!(windows) {
                "uname.exe".to_owned()
            } else {
                "uname".to_owned()
            }
        })
    })
}

/// `uname <arg>` (default `-a`); `""` when it fails or is absent.
#[must_use]
pub fn uname(arg: &str) -> String {
    let exe = if cfg!(windows) { "uname.exe" } else { "uname" };
    run(&[exe, arg]).toOption().unwrap_or_default()
}

/// True inside WSL (`uname -r` mentions it).
#[must_use]
pub fn isWsl() -> bool {
    uname("-r").contains("WSL")
}

/// `"windows"`, `"linux"` or `"darwin"` — the OS the binary was built for.
#[must_use]
pub fn osType() -> &'static str {
    if cfg!(windows) {
        "windows"
    } else if cfg!(target_os = "macos") {
        "darwin"
    } else {
        "linux"
    }
}

/// The machine's host name: `uname -n`, else `COMPUTERNAME`/`HOSTNAME`, else `""`.
#[must_use]
pub fn hostname() -> String {
    let n = uname("-n");
    if !n.is_empty() {
        return n;
    }
    std::env::var("COMPUTERNAME")
        .or_else(|_| std::env::var("HOSTNAME"))
        .unwrap_or_default()
}

/// Scala's `where(prog)` (a Rust keyword, hence the name): the system resolver's first
/// match — `where.exe` on Windows, `which` elsewhere; `None` when not on the PATH (Scala
/// throws there).
#[must_use]
pub fn whereExe(prog: &str) -> Option<String> {
    if cfg!(windows) {
        let name = format!("{}.exe", prog.strip_suffix(".exe").unwrap_or(prog));
        call_quiet("where.exe", &name)
    } else {
        call_quiet("which", prog)
    }
}

/// An in-process PATH scan: the first entry holding `prog` (`.exe` appended on Windows;
/// executable bit required elsewhere); `None` when not found.
#[must_use]
pub fn whereInPath(prog: &str) -> Option<String> {
    let name = if cfg!(windows) && !prog.ends_with(".exe") {
        format!("{prog}.exe")
    } else {
        prog.to_owned()
    };
    let path = std::env::var_os("PATH")?;
    for dir in std::env::split_paths(&path) {
        let candidate = dir.join(&name);
        let hit = if cfg!(windows) {
            candidate.exists()
        } else {
            is_executable(&candidate)
        };
        if hit {
            return Some(candidate.to_string_lossy().into_owned());
        }
    }
    None
}

#[cfg(unix)]
fn is_executable(p: &Path) -> bool {
    use std::os::unix::fs::PermissionsExt;
    p.metadata()
        .map(|m| m.is_file() && m.permissions().mode() & 0o111 != 0)
        .unwrap_or(false)
}

#[cfg(not(unix))]
fn is_executable(p: &Path) -> bool {
    p.is_file()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn shell(script: &str) -> Vec<&str> {
        if cfg!(windows) {
            vec!["cmd.exe", "/c", script]
        } else {
            vec!["sh", "-c", script]
        }
    }

    #[test]
    fn routing_tables_both_platforms() {
        assert_eq!(route_cmd(&["git", "log"], false), vec!["git", "log"]);
        assert_eq!(route_cmd(&["git", "log"], true), vec!["git.exe", "log"]);
        assert_eq!(route_cmd(&["tool.exe"], true), vec!["tool.exe"]);
        assert_eq!(
            route_cmd(&["tools/analyse.py", "-v"], false),
            vec!["tools/analyse.py", "-v"]
        );
        assert_eq!(
            route_cmd(&["tools/analyse.py", "-v"], true),
            vec![pythonExe(), "tools/analyse.py", "-v"]
        );
        assert_eq!(
            route_cmd(&["x.sc"], true),
            vec!["scala-cli", "shebang", "x.sc"]
        );
        assert_eq!(route_cmd(&["x.bat"], true), vec!["cmd.exe", "/c", "x.bat"]);
        assert_eq!(
            route_cmd(&["x.ps1"], true),
            vec!["powershell.exe", "-File", "x.ps1"]
        );
        assert_eq!(route_cmd(&["setup.sh"], false), vec![bashExe(), "setup.sh"]);
        assert_eq!(route_cmd(&["setup.sh"], true), vec![bashExe(), "setup.sh"]);
        assert!(route_cmd(&[], true).is_empty());
    }

    #[test]
    fn run_captures_stdout_and_status() {
        let r = run(&shell("echo hello"));
        assert!(r.ok(), "{r:?}");
        assert_eq!(r.headOnly(), "hello");
        assert_eq!(r.text(), "hello");
        assert_eq!(r.toOption().as_deref(), Some("hello"));
        assert_eq!(r.len(), 1);
        let bad = run(&shell("exit 3"));
        assert_eq!(bad.status, 3);
        assert_eq!(bad.toOption(), None);
        assert_eq!(bad.orElse("dflt"), "dflt");
        assert!(bad.clone().orFail("expected").is_err());
        assert_eq!(bad.orLog("expected").status, 3);
    }

    #[test]
    fn missing_program_is_a_result() {
        let r = run(&["no-such-program-xyz-uni"]);
        assert_eq!(r.status, -1);
        assert!(r.stdout.is_empty());
        assert_eq!(r.stderr.len(), 1);
    }

    #[test]
    fn streaming_delivers_lines_in_order() {
        let mut got = Vec::new();
        let script = if cfg!(windows) {
            "echo a& echo b"
        } else {
            "echo a; echo b"
        };
        let st = runStream(&shell(script), |l| got.push(l.trim().to_owned()), |_| {});
        assert_eq!(st, 0);
        assert_eq!(got, vec!["a", "b"]);
        assert_eq!(st.orLog("x"), 0);
        assert_eq!(3.orElse(|m| assert_eq!(m, "exit status: 3")), 3);
        assert_eq!(0.orFail("x"), Ok(0));
        assert_eq!(2.orFail("x"), Err(2));
    }

    #[test]
    fn builder_stdin_env_timeout() {
        // System32's sort.exe reads piped stdin as UTF-16, so Windows filters with findstr
        let (filter, want): (Vec<&str>, Vec<&str>) = if cfg!(windows) {
            (vec!["findstr", "a"], vec!["a"])
        } else {
            (vec!["sort"], vec!["a", "b"])
        };
        let r = proc(&filter).stdin("b\na\n").run();
        assert!(r.ok(), "{r:?}");
        assert_eq!(r.lines(), want);
        let env_script = if cfg!(windows) {
            "echo %UNI_PROC_T%"
        } else {
            "echo $UNI_PROC_T"
        };
        let r = proc(&shell(env_script))
            .env(&[("UNI_PROC_T", "s3cr3t")])
            .run();
        assert_eq!(r.headOnly(), "s3cr3t");
        let slow = if cfg!(windows) {
            "ping -n 6 127.0.0.1 >NUL"
        } else {
            "sleep 5"
        };
        let started = Instant::now();
        let r = proc(&shell(slow)).timeout(300).run();
        assert_eq!(r.status, -1);
        assert!(started.elapsed() < Duration::from_secs(4));
    }

    #[test]
    fn exec_lines_is_lazy_and_where_in_path_finds_the_shell() {
        let lines: Vec<String> = execLines(&shell("echo one")).collect();
        assert_eq!(lines, vec!["one"]);
        let shell_name = if cfg!(windows) { "cmd" } else { "sh" };
        assert!(whereInPath(shell_name).is_some());
        assert!(whereInPath("no-such-program-xyz-uni").is_none());
        assert!(!osType().is_empty());
    }
}
