//! Where a rendered chart goes — `uni.plot.Deliver`: save under the extension rule, or
//! write a temp page and open the default browser.

use std::path::Path;
use std::path::PathBuf;
use std::process::Command;
use std::process::Stdio;
use std::sync::atomic::AtomicU64;
use std::sync::atomic::Ordering;

use super::svg;

/// Save when `save_to` is non-empty, else show. Never fails: a browser that cannot be
/// started, or `UNI_PLOT_NO_OPEN` in the environment, prints the page's path instead.
pub fn deliver(svg_text: &str, title: &str, save_to: &str) {
    if save_to.is_empty() {
        let tmp = temp_page_path();
        if std::fs::write(&tmp, svg::html(svg_text, title)).is_ok()
            && (std::env::var_os("UNI_PLOT_NO_OPEN").is_some() || !openInBrowser(&tmp))
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
        let _ = save(svg_text, title, save_to);
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

/// Start the platform's default opener on `path`; `false` when it could not be started.
pub fn openInBrowser(path: &Path) -> bool {
    let p = std::path::absolute(path).unwrap_or_else(|_| path.to_path_buf());
    let mut cmd = if cfg!(target_os = "windows") {
        let mut c = Command::new("rundll32");
        c.arg("url.dll,FileProtocolHandler").arg(&p);
        c
    } else if cfg!(target_os = "macos") {
        let mut c = Command::new("open");
        c.arg(&p);
        c
    } else {
        let mut c = Command::new("xdg-open");
        c.arg(&p);
        c
    };
    cmd.stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    cmd.spawn().is_ok()
}
