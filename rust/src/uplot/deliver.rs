//! Where a rendered chart goes — `uni.plot.Deliver` and `uni.plot.Browser`: save under the
//! extension rule, or write a temp page and show it in a **standalone window** of the
//! user's default browser.
//!
//! The window: the OS's default HTML browser (Windows: the `https` URL association in the
//! registry; macOS: LaunchServices' handler for `http`, launched through the bundle's own
//! executable so a running browser still gets the flags; Linux: `xdg-settings get
//! default-web-browser`), launched in app mode when it is a Chromium-family browser
//! (`--app=<file-url>`, sized to the chart) or with `-new-window` when it is Firefox; when
//! the default cannot be read, the PATH (and Windows' `App Paths`) is probed — Chrome,
//! Chromium, Brave, Firefox, then Edge last. Overrides, the same names as the Scala side:
//! `UNI_PLOT_WINDOW=tab` (a tab via the OS opener), `UNI_PLOT_BROWSER=<name-or-path>`,
//! `UNI_PLOT_NO_OPEN` (print the path). Every failure falls through to the next route.

use std::path::Path;
use std::path::PathBuf;
use std::process::Command;
use std::process::Stdio;
use std::sync::atomic::AtomicU64;
use std::sync::atomic::Ordering;

use super::svg;
use crate::uproc;

/// Save when `save_to` is non-empty, else show. Never fails: when nothing can be started,
/// or `UNI_PLOT_NO_OPEN` is set, prints the page's path instead.
pub fn deliver(svg_text: &str, title: &str, save_to: &str, width: i32, height: i32) {
    if save_to.is_empty() {
        let tmp = temp_page_path();
        if std::fs::write(&tmp, svg::html(svg_text, title)).is_ok()
            && (std::env::var_os("UNI_PLOT_NO_OPEN").is_some() || !show(&tmp, width, height))
        {
            #[expect(
                clippy::print_stdout,
                reason = "the fallback for a headless run: tell the user where the page is"
            )]
            {
                println!("uni.plot: {}", tmp.display());
            }
        }
    } else {
        save(svg_text, title, save_to);
    }
}

/// Write `svg_text` to `save_to` — as is when it ends in `.svg`, wrapped in a page when
/// it ends in `.html`, otherwise to `save_to + ".svg"`; parent directories are created.
/// Returns the path written, or `None` when the write failed.
pub fn save(svg_text: &str, title: &str, save_to: &str) -> Option<PathBuf> {
    let target = if save_to.ends_with(".svg") || save_to.ends_with(".html") {
        save_to.to_owned()
    } else {
        format!("{save_to}.svg")
    };
    let path = PathBuf::from(&target);
    if let Some(parent) = path.parent()
        && !parent.as_os_str().is_empty()
        && std::fs::create_dir_all(parent).is_err()
    {
        return None;
    }
    let body = if target.ends_with(".html") {
        svg::html(svg_text, title)
    } else {
        svg_text.to_owned()
    };
    std::fs::write(&path, body).ok().map(|()| path)
}

static COUNTER: AtomicU64 = AtomicU64::new(0);

fn temp_page_path() -> PathBuf {
    let n = COUNTER.fetch_add(1, Ordering::Relaxed);
    std::env::temp_dir().join(format!("uni-plot-{}-{n}.html", std::process::id()))
}

// ── the browser ─────────────────────────────────────────────────────────────

/// A browser to launch: the command prefix and the family that decides its arguments.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Choice {
    pub cmd: Vec<String>,
    /// `"chromium"`, `"firefox"` or `"other"`.
    pub family: &'static str,
}

/// The family a browser name belongs to.
#[must_use]
pub fn family(name: &str) -> &'static str {
    let n = name.to_lowercase();
    if [
        "chrome", "chromium", "msedge", "edge", "brave", "vivaldi", "opera",
    ]
    .iter()
    .any(|k| n.contains(k))
    {
        "chromium"
    } else if ["firefox", "librewolf", "waterfox"]
        .iter()
        .any(|k| n.contains(k))
    {
        "firefox"
    } else {
        "other"
    }
}

fn quiet(cmd: &[&str]) -> Vec<String> {
    uproc::run(cmd).stdout
}

fn reg_sz(lines: &[String]) -> Option<String> {
    lines
        .iter()
        .find(|l| l.contains("REG_SZ"))
        .and_then(|l| l.find("REG_SZ").map(|i| l[i + 6..].trim().to_owned()))
        .filter(|s| !s.is_empty())
}

fn default_windows() -> Option<Choice> {
    let lines = quiet(&[
        "reg",
        "query",
        r"HKCU\Software\Microsoft\Windows\Shell\Associations\UrlAssociations\https\UserChoice",
        "/v",
        "ProgId",
    ]);
    let prog_id = lines
        .iter()
        .map(|l| l.trim())
        .find(|l| l.starts_with("ProgId"))
        .and_then(|l| l.split_whitespace().last())?
        .to_owned();
    let command = reg_sz(&quiet(&[
        "reg",
        "query",
        &format!(r"HKCR\{prog_id}\shell\open\command"),
        "/ve",
    ]))?;
    // `"C:\...\chrome.exe" --single-argument %1` — the exe is the first quoted token
    let exe = if let Some(rest) = command.strip_prefix('"') {
        rest.split('"').next().unwrap_or("").to_owned()
    } else {
        command.split(' ').next().unwrap_or("").to_owned()
    };
    if exe.is_empty() {
        None
    } else {
        Some(Choice {
            family: family(&exe),
            cmd: vec![exe],
        })
    }
}

fn default_mac() -> Option<Choice> {
    let home = std::env::var("HOME").ok()?;
    let plist = format!(
        "{home}/Library/Preferences/com.apple.LaunchServices/com.apple.launchservices.secure.plist"
    );
    let json = quiet(&["plutil", "-convert", "json", "-o", "-", &plist]).join("");
    let bundle = json
        .split("},")
        .find(|seg| {
            seg.contains("\"LSHandlerURLScheme\":\"http\"")
                || seg.contains("\"LSHandlerURLScheme\":\"https\"")
        })
        .and_then(|seg| {
            let key = "\"LSHandlerRoleAll\":\"";
            let i = seg.find(key)? + key.len();
            seg[i..].split('"').next().map(str::to_owned)
        })?;
    // The bundle's own executable, so `--app`/`-new-window` reach a browser that is already
    // running (`open -b … --args` passes arguments only to a new instance).
    let binary = quiet(&[
        "osascript",
        "-e",
        &format!("POSIX path of (path to application id \"{bundle}\")"),
    ])
    .into_iter()
    .next()
    .map(|s| s.trim().trim_end_matches('/').to_owned())
    .filter(|s| !s.is_empty())
    .and_then(|app| {
        let name = quiet(&[
            "defaults",
            "read",
            &format!("{app}/Contents/Info"),
            "CFBundleExecutable",
        ])
        .into_iter()
        .next()
        .map(|s| s.trim().to_owned())
        .filter(|s| !s.is_empty())?;
        let bin = format!("{app}/Contents/MacOS/{name}");
        if Path::new(&bin).is_file() {
            Some(bin)
        } else {
            None
        }
    });
    Some(Choice {
        family: family(&bundle),
        cmd: binary.map_or_else(
            || vec!["open".into(), "-b".into(), bundle.clone(), "--args".into()],
            |b| vec![b],
        ),
    })
}

fn default_linux() -> Option<Choice> {
    let desktop = quiet(&["xdg-settings", "get", "default-web-browser"])
        .into_iter()
        .next()?
        .trim()
        .to_owned();
    if desktop.is_empty() {
        return None;
    }
    let name = desktop
        .strip_suffix(".desktop")
        .unwrap_or(&desktop)
        .to_owned();
    let home = std::env::var("HOME").unwrap_or_default();
    let dirs = [
        format!("{home}/.local/share/applications"),
        "/usr/local/share/applications".to_owned(),
        "/usr/share/applications".to_owned(),
    ];
    let exec = dirs
        .iter()
        .map(|d| Path::new(d).join(&desktop))
        .find(|p| p.is_file())
        .and_then(|p| {
            std::fs::read_to_string(p)
                .ok()?
                .lines()
                .find(|l| l.starts_with("Exec="))
                .and_then(|l| {
                    l.trim_start_matches("Exec=")
                        .split_whitespace()
                        .next()
                        .map(str::to_owned)
                })
        });
    Some(Choice {
        family: family(&name),
        cmd: vec![exec.unwrap_or(name)],
    })
}

/// The OS's default HTML browser, when it can be read.
#[must_use]
pub fn defaultBrowser() -> Option<Choice> {
    if cfg!(windows) {
        default_windows()
    } else if cfg!(target_os = "macos") {
        default_mac()
    } else {
        default_linux()
    }
}

/// Windows registers installed browsers under `App Paths` even when not on the PATH.
fn app_path(name: &str) -> Option<String> {
    if !cfg!(windows) {
        return None;
    }
    let key = format!(
        r"HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\App Paths\{}.exe",
        name.strip_suffix(".exe").unwrap_or(name)
    );
    reg_sz(&quiet(&["reg", "query", &key, "/ve"]))
        .map(|s| s.trim_matches('"').to_owned())
        .filter(|s| !s.is_empty())
}

fn resolve(name: &str) -> Option<String> {
    if name.contains('/') || name.contains(std::path::MAIN_SEPARATOR) {
        Some(name.to_owned())
    } else {
        uproc::whereInPath(name).or_else(|| app_path(name))
    }
}

/// Probe when the OS default cannot be read — Edge deliberately last.
const CANDIDATES: [&str; 8] = [
    "google-chrome",
    "chrome",
    "chromium",
    "chromium-browser",
    "brave-browser",
    "brave",
    "firefox",
    "msedge",
];

fn probe() -> Option<Choice> {
    CANDIDATES.iter().find_map(|n| {
        resolve(n).map(|p| Choice {
            family: family(n),
            cmd: vec![p],
        })
    })
}

fn from_env() -> Option<Choice> {
    let b = std::env::var("UNI_PLOT_BROWSER").ok()?;
    let b = b.trim();
    if b.is_empty() {
        return None;
    }
    Some(Choice {
        family: family(b),
        cmd: vec![resolve(b).unwrap_or_else(|| b.to_owned())],
    })
}

fn start(cmd: &[String]) -> bool {
    let Some(prog) = cmd.first() else {
        return false;
    };
    Command::new(prog)
        .args(&cmd[1..])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .is_ok()
}

/// The OS opener: a tab in the default browser.
pub fn openTab(path: &Path) -> bool {
    let p = std::path::absolute(path)
        .unwrap_or_else(|_| path.to_path_buf())
        .to_string_lossy()
        .into_owned();
    if cfg!(windows) {
        start(&[
            "rundll32".to_owned(),
            "url.dll,FileProtocolHandler".to_owned(),
            p,
        ])
    } else if cfg!(target_os = "macos") {
        start(&["open".to_owned(), p])
    } else {
        start(&["xdg-open".to_owned(), p.clone()]) || start(&["wslview".to_owned(), p])
    }
}

fn file_url(path: &Path) -> String {
    let abs = std::path::absolute(path).unwrap_or_else(|_| path.to_path_buf());
    let s = abs.to_string_lossy().replace('\\', "/");
    if s.starts_with('/') {
        format!("file://{s}")
    } else {
        format!("file:///{s}")
    }
}

/// A standalone window sized to the chart, per the module rules; `false` when nothing
/// could be started (the caller then prints the path).
pub fn show(path: &Path, width: i32, height: i32) -> bool {
    if std::env::var("UNI_PLOT_WINDOW").is_ok_and(|v| v.trim().eq_ignore_ascii_case("tab")) {
        return openTab(path);
    }
    let url = file_url(path);
    let choice = from_env().or_else(defaultBrowser).or_else(probe);
    let launched = choice.is_some_and(|c| match c.family {
        "chromium" => {
            let mut cmd = c.cmd;
            cmd.push(format!("--app={url}"));
            cmd.push(format!("--window-size={},{}", width + 16, height + 40));
            start(&cmd)
        }
        "firefox" => {
            let mut cmd = c.cmd;
            cmd.push("-new-window".to_owned());
            cmd.push(url.clone());
            start(&cmd)
        }
        _ => false,
    });
    launched || openTab(path)
}
