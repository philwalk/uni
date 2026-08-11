//! CLI argument parsing — a port of `uni.cli.ArgsParser` (`eachArg`, `showUsage`
//! and the cursor helpers).
//!
//! # The shape, and why it differs slightly from Scala's
//!
//! Scala's `eachArg` takes a partial function and routes unmatched arguments to
//! the usage handler; the cursor helpers (`thisArg`, `consumeNext`, `nextInt`,
//! ...) reach the current context through a `DynamicVariable`. Rust has neither
//! partial functions nor dynamic scoping, so the context is passed to the
//! closure explicitly and a `match` plays the partial function — with the
//! difference that a Rust `match` is total, so "unknown argument" is the
//! script's own `_ =>` arm rather than something this module can supply:
//!
//! ```ignore
//! let usage = |m: &str| showUsage(m, &["-v          ; verbose",
//!                                      "-n <count>  ; how many",
//!                                      "<file> ..."]);
//! eachArg(&args, &usage, |ctx, arg| match arg {
//!     "-v" => verbose = true,
//!     "-n" => count = ctx.nextInt(),
//!     f if !f.starts_with('-') => files.push(f.to_owned()),
//!     other => ctx.usage(&format!("unknown argument [{other}]")),
//! });
//! ```
//!
//! # Program-name derivation
//!
//! Scala's `showUsage` names the *source file* (`treestat.sc`), derived through
//! scala-cli properties or a caller-site macro, so a usage message never
//! hardcodes its own filename. The Rust mirror is `#[track_caller]` +
//! `Location::caller().file()`: the caller's source file, captured at compile
//! time — `treestat.rs` in perfect parallel. (`argv[0]` would name the
//! *executable*, platform-suffixed, which is not the convention.)

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name, so a script kept in both \
              languages needs no mental translation. Internal helpers stay snake_case, \
              so the case says whether a Scala counterpart exists."
)]

/// Prints `msg` (when non-empty), a `usage: <prog> <options>` line naming the
/// caller's source file, and the given lines (empty ones are skipped, as in
/// Scala), then exits with status 1. `ArgsParser.showUsage`.
#[track_caller]
#[expect(
    clippy::print_stderr,
    reason = "printing the usage message to stderr is this function's entire purpose, \
              exactly as the Scala original does"
)]
pub fn showUsage(msg: &str, lines: &[&str]) -> ! {
    let file = std::panic::Location::caller().file();
    let prog = prog_name(file);
    eprint!("{}", usage_text(&prog, msg, lines));
    std::process::exit(1);
}

/// The caller's source file name, basename only, slashes of either kind.
fn prog_name(file: &str) -> String {
    file.rsplit(['/', '\\']).next().unwrap_or(file).to_owned()
}

/// The full usage message — separated from [`showUsage`] so the formatting is
/// testable without exiting the process.
fn usage_text(prog: &str, msg: &str, lines: &[&str]) -> String {
    let mut out = String::new();
    if !msg.is_empty() {
        out.push_str(msg);
        out.push('\n');
    }
    out.push_str(&format!("usage: {prog} <options>\n"));
    for l in lines.iter().filter(|l| !l.is_empty()) {
        out.push_str(l);
        out.push('\n');
    }
    out
}

/// The argument cursor handed to the [`eachArg`] closure — the counterpart of
/// Scala's dynamically-scoped `thisArg`/`consumeNext`/`next*` helpers.
pub struct ArgCtx<'a> {
    args: &'a [String],
    i: usize,
    usage: &'a dyn Fn(&str),
}

impl ArgCtx<'_> {
    /// The argument currently being dispatched. `ArgsParser.thisArg`.
    #[must_use]
    pub fn thisArg(&self) -> &str {
        &self.args[self.i]
    }

    /// Consumes and returns the next argument, or routes to the usage handler
    /// when there is none. `ArgsParser.consumeNext`.
    pub fn consumeNext(&mut self) -> &str {
        if self.i + 1 < self.args.len() {
            self.i += 1;
            &self.args[self.i]
        } else {
            self.fail(&format!("missing argument after [{}]", self.thisArg()))
        }
    }

    /// The next argument without consuming it; `""` when there is none —
    /// indistinguishable from a genuine empty argument by design, exactly as in
    /// Scala: peeking is for lookahead decisions, and a caller that needs the
    /// distinction uses [`Self::consumeNext`], which errors instead.
    /// `ArgsParser.peekNext`.
    #[must_use]
    pub fn peekNext(&self) -> &str {
        self.args.get(self.i + 1).map_or("", String::as_str)
    }

    /// `ArgsParser.nextInt`.
    pub fn nextInt(&mut self) -> i32 {
        let this = self.thisArg().to_owned();
        match self.consumeNext().parse() {
            Ok(v) => v,
            Err(_) => self.fail(&format!("expected Int after [{this}]")),
        }
    }

    /// `ArgsParser.nextLong`.
    pub fn nextLong(&mut self) -> i64 {
        let this = self.thisArg().to_owned();
        match self.consumeNext().parse() {
            Ok(v) => v,
            Err(_) => self.fail(&format!("expected Long after [{this}]")),
        }
    }

    /// `ArgsParser.nextDouble`.
    pub fn nextDouble(&mut self) -> f64 {
        let this = self.thisArg().to_owned();
        match self.consumeNext().parse() {
            Ok(v) => v,
            Err(_) => self.fail(&format!("expected Double after [{this}]")),
        }
    }

    /// Routes to the usage handler — the escape hatch the closure's `_ =>` arm
    /// uses for unknown arguments (Scala's partial function got this for free).
    pub fn usage(&self, msg: &str) -> ! {
        self.fail(msg)
    }

    fn fail(&self, msg: &str) -> ! {
        (self.usage)(msg);
        // The handler's contract is to diverge (print usage and exit); Scala
        // encodes that as `String => Nothing`, which Rust's stable trait bounds
        // cannot express. If a handler returns anyway, dying loudly here beats
        // parsing on in a corrupt state.
        unreachable!("usage handler returned instead of exiting")
    }
}

/// Dispatches each argument to `f` in order, with an [`ArgCtx`] cursor whose
/// `consumeNext`/`next*` helpers advance past option values. `ArgsParser.eachArg`.
pub fn eachArg(args: &[String], usage: &dyn Fn(&str), mut f: impl FnMut(&mut ArgCtx, &str)) {
    let mut ctx = ArgCtx { args, i: 0, usage };
    while ctx.i < args.len() {
        let arg = &args[ctx.i];
        f(&mut ctx, arg);
        ctx.i += 1;
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn strings(xs: &[&str]) -> Vec<String> {
        xs.iter().map(|s| (*s).to_owned()).collect()
    }

    #[test]
    fn dispatches_in_order_with_values_consumed() {
        let args = strings(&["-v", "-n", "7", "file.txt"]);
        let (mut verbose, mut n, mut files) = (false, 0, Vec::new());
        eachArg(
            &args,
            &|m| panic!("unexpected usage: {m}"),
            |ctx, arg| match arg {
                "-v" => verbose = true,
                "-n" => n = ctx.nextInt(),
                f => files.push(f.to_owned()),
            },
        );
        assert!(verbose);
        assert_eq!(n, 7);
        assert_eq!(files, vec!["file.txt"]);
    }

    #[test]
    fn next_long_and_double_parse() {
        let args = strings(&["-l", "9876543210", "-d", "2.5"]);
        let (mut l, mut d) = (0i64, 0f64);
        eachArg(
            &args,
            &|m| panic!("unexpected usage: {m}"),
            |ctx, arg| match arg {
                "-l" => l = ctx.nextLong(),
                "-d" => d = ctx.nextDouble(),
                other => panic!("unexpected arg {other}"),
            },
        );
        assert_eq!(l, 9_876_543_210);
        assert!((d - 2.5).abs() < f64::EPSILON);
    }

    #[test]
    fn peek_does_not_consume_and_answers_empty_at_the_end() {
        let args = strings(&["-a", "next"]);
        let mut seen = Vec::new();
        eachArg(&args, &|m| panic!("unexpected usage: {m}"), |ctx, arg| {
            seen.push(format!("{arg}:{}", ctx.peekNext()));
        });
        // peek left "next" unconsumed, so it was dispatched too, peeking ""
        assert_eq!(seen, vec!["-a:next", "next:"]);
    }

    #[test]
    #[should_panic(expected = "missing argument after [-n]")]
    fn missing_value_routes_to_usage() {
        let args = strings(&["-n"]);
        eachArg(&args, &|m| panic!("{m}"), |ctx, arg| match arg {
            "-n" => {
                ctx.nextInt();
            }
            other => panic!("unexpected arg {other}"),
        });
    }

    #[test]
    #[should_panic(expected = "expected Int after [-n]")]
    fn unparseable_value_routes_to_usage() {
        let args = strings(&["-n", "seven"]);
        eachArg(&args, &|m| panic!("{m}"), |ctx, arg| match arg {
            "-n" => {
                ctx.nextInt();
            }
            other => panic!("unexpected arg {other}"),
        });
    }

    #[test]
    fn usage_text_matches_the_scala_layout() {
        let text = usage_text("treestat.rs", "bad news", &["-v ; verbose", "", "<file>"]);
        assert_eq!(
            text,
            "bad news\nusage: treestat.rs <options>\n-v ; verbose\n<file>\n"
        );
        // empty message: no leading blank line
        let bare = usage_text("x.rs", "", &[]);
        assert_eq!(bare, "usage: x.rs <options>\n");
    }

    #[test]
    fn prog_name_takes_the_basename_of_either_slash_kind() {
        assert_eq!(prog_name("examples/treestat.rs"), "treestat.rs");
        assert_eq!(prog_name("examples\\treestat.rs"), "treestat.rs");
        assert_eq!(prog_name("treestat.rs"), "treestat.rs");
    }
}
