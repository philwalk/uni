//! The pure core: a matrix and its options in, SVG text out — `uni.plot.Svg`
//! (`src/main/scala/uni/plot/PlotSvg.scala`) function for function, to the byte. The
//! `pl.` rows of `test-data/mat-parity/scala-reference.txt` are FNV digests of these
//! strings, computed on the JVM and checked here.
//!
//! The determinism rules are the Scala file's: coordinates through [`num`]
//! (`floor(x·100+0.5)` then decimal text), tick labels through [`fmt_tick`] (integer
//! arithmetic, hand-rolled scientific form), decades by repeated ×10/÷10, no font metrics,
//! constant palette and margins, non-finite values dropped first, and the log axis goes
//! through [`log10`] — this file's own, the same operations as the Scala's — so the
//! renderer makes no libm call at all.

use super::style::Color;
use super::style::Font;
use super::style::PlotStyle;
use crate::udata::MatD;

// ── theme constants ──────────────────────────────────────────────────────────

pub(crate) const PALETTE: [&str; 10] = [
    "#1f77b4", "#ff7f0e", "#2ca02c", "#d62728", "#9467bd", "#8c564b", "#e377c2", "#7f7f7f",
    "#bcbd22", "#17becf",
];
pub(crate) const HEAT_STOPS: [&str; 3] = ["#2166ac", "#f7f7f7", "#b2182b"];
const CHART_BG: &str = "#ffffff";
const PLOT_BG: &str = "#ebebeb";
const GRID_LINE: &str = "#ffffff";
const TEXT_FILL: &str = "#333333";
const AXIS_LINE: &str = "#999999";
const TITLE_SIZE: i32 = 16;
const LABEL_SIZE: i32 = 13;
const TICK_SIZE: i32 = 11;

// ── text primitives ──────────────────────────────────────────────────────────

/// A coordinate: half-up to 2 decimals, trailing zeros dropped, `-0` never produced.
#[must_use]
pub fn num(x: f64) -> String {
    if !x.is_finite() {
        return "0".to_owned();
    }
    let v = (x * 100.0 + 0.5).floor();
    let neg = v < 0.0;
    #[expect(
        clippy::cast_possible_truncation,
        reason = "|v| < 2^53 by construction"
    )]
    let a = v.abs() as i64;
    let ip = a / 100;
    let fp = a % 100;
    let s = if fp == 0 {
        ip.to_string()
    } else if fp % 10 == 0 {
        format!("{ip}.{}", fp / 10)
    } else if fp < 10 {
        format!("{ip}.0{fp}")
    } else {
        format!("{ip}.{fp}")
    };
    if neg && a != 0 { format!("-{s}") } else { s }
}

/// Escape the five XML specials.
#[must_use]
pub fn esc(s: &str) -> String {
    let mut out = String::with_capacity(s.len());
    for c in s.chars() {
        match c {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&apos;"),
            _ => out.push(c),
        }
    }
    out
}

/// `#rrggbb`.
#[must_use]
pub fn hex(c: Color) -> String {
    format!("#{:02x}{:02x}{:02x}", c.r, c.g, c.b)
}

/// ` fill-opacity="0.31"` when translucent, else empty.
#[must_use]
pub fn opacity_attr(alpha: i32, attr: &str) -> String {
    if alpha >= 255 {
        String::new()
    } else {
        format!(" {attr}=\"{}\"", num(f64::from(alpha) / 255.0))
    }
}

/// 10^k by repeated multiplication.
#[must_use]
pub fn pow10(k: i32) -> f64 {
    let mut v = 1.0;
    if k >= 0 {
        for _ in 0..k {
            v *= 10.0;
        }
    } else {
        for _ in 0..(-k) {
            v /= 10.0;
        }
    }
    v
}

// ln 2 split Cody–Waite style (fdlibm's pair): e·LN2_HI is exact for |e| ≤ 1074; ln 10 is
// the correctly rounded value. Bit patterns, so the Scala and Rust constants cannot drift.
const LN2_HI: f64 = f64::from_bits(0x3fe6_2e42_fee0_0000);
const LN2_LO: f64 = f64::from_bits(0x3dea_39ef_3579_3c76);
const LN10: f64 = f64::from_bits(0x4002_6bb1_bbb5_5516); // == std::f64::consts::LN_10

/// `Svg.log10`, operation for operation: `x = m·2^e` split from the bits, `m` folded into
/// [√½, √2), `ln m = 2·(s + s³/3 + … + s²¹/21)` with `s = (m−1)/(m+1)`, then
/// `((e·ln2ʰⁱ + ln m) + e·ln2ˡᵒ)/ln10`; a result within an ulp of an integer `k` with
/// `pow10(k) == x` snaps to `k`. NaN for x ≤ 0 or NaN, +∞ for +∞.
#[must_use]
pub fn log10(x: f64) -> f64 {
    if x.is_nan() || x <= 0.0 {
        return f64::NAN;
    }
    if x == f64::INFINITY {
        return f64::INFINITY;
    }
    let mut v = x;
    let mut e: i32 = 0;
    if v < 2.225_073_858_507_201_4e-308 {
        v *= 18_014_398_509_481_984.0; // subnormal: ×2^54
        e -= 54;
    }
    let bits = v.to_bits();
    let ef = i32::try_from((bits >> 52) & 0x7ff).unwrap_or(0); // 11-bit field, always fits
    e += ef - 1023;
    let mut m = f64::from_bits((bits & 0x000f_ffff_ffff_ffff) | 0x3ff0_0000_0000_0000); // [1,2)
    if m > std::f64::consts::SQRT_2 {
        m *= 0.5;
        e += 1;
    }
    let s = (m - 1.0) / (m + 1.0);
    let s2 = s * s;
    let mut term = s;
    let mut sum = s;
    let mut k = 3;
    while k <= 21 {
        term *= s2;
        sum += term / f64::from(k);
        k += 2;
    }
    let ef64 = f64::from(e);
    let r = ((ef64 * LN2_HI + 2.0 * sum) + ef64 * LN2_LO) / LN10;
    let kk = (r + 0.5).floor();
    #[expect(clippy::cast_possible_truncation, reason = "|kk| ≤ 308 by the guard")]
    if (r - kk).abs() < 1e-12 && (-308.0..=308.0).contains(&kk) && pow10(kk as i32) == x {
        kk
    } else {
        r
    }
}

/// Tick label: up to 3 decimals for 1e-3 ≤ |v| < 1e7, else `1.50e6`-style.
#[must_use]
pub fn fmt_tick(v: f64) -> String {
    if !v.is_finite() || v == 0.0 {
        return "0".to_owned();
    }
    let a = v.abs();
    let sign = if v < 0.0 { "-" } else { "" };
    if (1e-3..1e7).contains(&a) {
        #[expect(clippy::cast_possible_truncation, reason = "a·1000 < 1e10")]
        let n = (a * 1000.0 + 0.5).floor() as i64;
        let ip = n / 1000;
        let fp = n % 1000;
        if fp == 0 {
            format!("{sign}{ip}")
        } else {
            let mut digits = format!("{fp:03}");
            while digits.ends_with('0') {
                digits.pop();
            }
            format!("{sign}{ip}.{digits}")
        }
    } else {
        let mut m = a;
        let mut e = 0i32;
        while m >= 10.0 {
            m /= 10.0;
            e += 1;
        }
        while m < 1.0 {
            m *= 10.0;
            e -= 1;
        }
        #[expect(clippy::cast_possible_truncation, reason = "100 ≤ m·100 ≤ 1000")]
        let mut mant = (m * 100.0 + 0.5).floor() as i64;
        if mant >= 1000 {
            mant = 100;
            e += 1;
        }
        let ip = mant / 100;
        let fp = mant % 100;
        let fps = if fp < 10 {
            format!("0{fp}")
        } else {
            fp.to_string()
        };
        format!("{sign}{ip}.{fps}e{e}")
    }
}

/// Tick positions: a 1/2/5 step nearest to `(mx-mn)/n`, first multiple at or above `mn`
/// to the last at or below `mx`.
#[must_use]
pub fn nice_ticks(mn: f64, mx: f64, n: i32) -> Vec<f64> {
    if mx.partial_cmp(&mn) != Some(std::cmp::Ordering::Greater) {
        return vec![mn];
    }
    let step0 = (mx - mn) / f64::from(n);
    let mut mag = 1.0;
    while mag * 10.0 <= step0 {
        mag *= 10.0;
    }
    while mag > step0 {
        mag /= 10.0;
    }
    let cands = [1.0 * mag, 2.0 * mag, 5.0 * mag, 10.0 * mag];
    let mut step = cands[0];
    let mut best = (cands[0] - step0).abs();
    for &c in &cands[1..] {
        let d = (c - step0).abs();
        if d < best {
            best = d;
            step = c;
        }
    }
    let start = (mn / step).ceil() * step;
    if start > mx {
        return Vec::new();
    }
    let mut out = Vec::new();
    let mut t = start;
    let mut k = 0i32;
    let limit = mx + step * 0.01;
    while t <= limit && k < n + 3 {
        out.push(t);
        k += 1;
        t = start + f64::from(k) * step;
    }
    out
}

// ── scales ───────────────────────────────────────────────────────────────────

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Domain {
    pub lo: f64,
    pub hi: f64,
}

/// Data range padded 5 % each side; a point widens by 0.5; no data → [0,1].
#[must_use]
pub fn domain(values: &[f64]) -> Domain {
    let mut lo = f64::INFINITY;
    let mut hi = f64::NEG_INFINITY;
    for &v in values {
        if v.is_finite() {
            if v < lo {
                lo = v;
            }
            if v > hi {
                hi = v;
            }
        }
    }
    if lo > hi {
        Domain { lo: 0.0, hi: 1.0 }
    } else if lo == hi {
        Domain {
            lo: lo - 0.5,
            hi: hi + 0.5,
        }
    } else {
        let pad = (hi - lo) * 0.05;
        Domain {
            lo: lo - pad,
            hi: hi + pad,
        }
    }
}

/// [`domain`] of `log10` over the positive finite values.
#[must_use]
pub fn log_domain(values: &[f64]) -> Domain {
    let logs: Vec<f64> = values
        .iter()
        .copied()
        .filter(|v| v.is_finite() && *v > 0.0)
        .map(log10)
        .collect();
    domain(&logs)
}

/// A linear or log axis mapped onto `[p0, p0+len]`; y axes flip.
#[derive(Clone, Copy, Debug)]
pub struct Axis {
    pub dom: Domain,
    pub log: bool,
    pub p0: f64,
    pub len: f64,
    pub flip: bool,
}

impl Axis {
    #[must_use]
    pub fn pos(&self, v: f64) -> f64 {
        let t0 = if self.log { log10(v) } else { v };
        let t = (t0 - self.dom.lo) / (self.dom.hi - self.dom.lo);
        if self.flip {
            self.p0 + self.len - t * self.len
        } else {
            self.p0 + t * self.len
        }
    }

    /// Tick values in data units (powers of ten on a log axis).
    #[must_use]
    pub fn ticks(&self) -> Vec<f64> {
        if self.log {
            #[expect(
                clippy::cast_possible_truncation,
                reason = "decades are small integers"
            )]
            let k0 = self.dom.lo.ceil() as i32;
            #[expect(
                clippy::cast_possible_truncation,
                reason = "decades are small integers"
            )]
            let k1 = self.dom.hi.floor() as i32;
            (k0..=k1).map(pow10).collect()
        } else {
            nice_ticks(self.dom.lo, self.dom.hi, 5)
        }
    }

    #[must_use]
    pub fn contains(&self, v: f64) -> bool {
        let t = if self.log { log10(v) } else { v };
        t >= self.dom.lo && t <= self.dom.hi
    }
}

// ── layout ───────────────────────────────────────────────────────────────────

#[derive(Clone, Copy, Debug)]
pub struct Frame {
    pub w: i32,
    pub h: i32,
    pub px0: i32,
    pub py0: i32,
    pub pw: i32,
    pub ph: i32,
}

#[must_use]
pub fn frame(style: &PlotStyle, title: &str, extra_right: i32) -> Frame {
    let left = 64;
    let top = if title.is_empty() { 20 } else { 44 };
    let right = 20 + extra_right;
    let bottom = if style.xLabel.is_empty() { 42 } else { 58 };
    Frame {
        w: style.width,
        h: style.height,
        px0: left,
        py0: top,
        pw: (style.width - left - right).max(1),
        ph: (style.height - top - bottom).max(1),
    }
}

pub struct Theme {
    pub chart_bg: String,
    pub plot_bg: String,
    pub text: String,
    pub palette: Vec<String>,
}

impl Theme {
    #[must_use]
    pub fn color(&self, i: usize) -> &str {
        &self.palette[i % self.palette.len()]
    }
}

#[must_use]
pub fn theme(style: &PlotStyle) -> Theme {
    Theme {
        chart_bg: style.background.map_or_else(|| CHART_BG.to_owned(), hex),
        plot_bg: style.plotBackground.map_or_else(|| PLOT_BG.to_owned(), hex),
        text: style.foreground.map_or_else(|| TEXT_FILL.to_owned(), hex),
        palette: if style.seriesColors.is_empty() {
            PALETTE.iter().map(|s| (*s).to_owned()).collect()
        } else {
            style.seriesColors.iter().copied().map(hex).collect()
        },
    }
}

// ── SVG building blocks ──────────────────────────────────────────────────────

/// Line-oriented SVG accumulator; every element is one line, as in Scala.
#[derive(Default)]
pub struct Out {
    buf: String,
}

impl Out {
    pub fn line(&mut self, s: &str) {
        self.buf.push_str(s);
        self.buf.push('\n');
    }
    #[must_use]
    pub fn finish(self) -> String {
        self.buf
    }
}

fn open(out: &mut Out, w: i32, h: i32, chart_bg: &str) {
    out.line(&format!(
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"{w}\" height=\"{h}\" viewBox=\"0 0 {w} {h}\" font-family=\"sans-serif\">"
    ));
    out.line(&format!(
        "<rect x=\"0\" y=\"0\" width=\"{w}\" height=\"{h}\" fill=\"{chart_bg}\"/>"
    ));
}

fn close(out: &mut Out) {
    out.line("</svg>");
}

#[expect(
    clippy::too_many_arguments,
    reason = "the SVG text element's attributes, in the order Scala passes them"
)]
fn text(out: &mut Out, x: f64, y: f64, s: &str, size: i32, anchor: &str, fill: &str, extra: &str) {
    out.line(&format!(
        "<text x=\"{}\" y=\"{}\" font-size=\"{size}\" text-anchor=\"{anchor}\" fill=\"{fill}\"{extra}>{}</text>",
        num(x),
        num(y),
        esc(s)
    ));
}

fn title(out: &mut Out, f: &Frame, t: &str, th: &Theme) {
    if !t.is_empty() {
        text(
            out,
            f64::from(f.w) / 2.0,
            28.0,
            t,
            TITLE_SIZE,
            "middle",
            &th.text,
            "",
        );
    }
}

fn axis_labels(out: &mut Out, f: &Frame, style: &PlotStyle, th: &Theme) {
    if !style.xLabel.is_empty() {
        text(
            out,
            f64::from(f.px0) + f64::from(f.pw) / 2.0,
            f64::from(f.h) - 10.0,
            &style.xLabel,
            LABEL_SIZE,
            "middle",
            &th.text,
            "",
        );
    }
    if !style.yLabel.is_empty() {
        let cx = 16.0;
        let cy = f64::from(f.py0) + f64::from(f.ph) / 2.0;
        let extra = format!(" transform=\"rotate(-90 {} {})\"", num(cx), num(cy));
        text(
            out,
            cx,
            cy,
            &style.yLabel,
            LABEL_SIZE,
            "middle",
            &th.text,
            &extra,
        );
    }
}

fn plot_area(out: &mut Out, f: &Frame, th: &Theme) {
    out.line(&format!(
        "<rect x=\"{}\" y=\"{}\" width=\"{}\" height=\"{}\" fill=\"{}\"/>",
        f.px0, f.py0, f.pw, f.ph, th.plot_bg
    ));
}

fn x_axis(out: &mut Out, f: &Frame, ax: &Axis, th: &Theme) {
    for t in ax.ticks() {
        if !ax.contains(t) {
            continue;
        }
        let x = num(ax.pos(t));
        out.line(&format!(
            "<line x1=\"{x}\" y1=\"{}\" x2=\"{x}\" y2=\"{}\" stroke=\"{GRID_LINE}\" stroke-width=\"1\"/>",
            f.py0,
            f.py0 + f.ph
        ));
        out.line(&format!(
            "<line x1=\"{x}\" y1=\"{}\" x2=\"{x}\" y2=\"{}\" stroke=\"{AXIS_LINE}\" stroke-width=\"1\"/>",
            f.py0 + f.ph,
            f.py0 + f.ph + 4
        ));
        text(
            out,
            ax.pos(t),
            f64::from(f.py0 + f.ph) + 16.0,
            &fmt_tick(t),
            TICK_SIZE,
            "middle",
            &th.text,
            "",
        );
    }
}

fn y_axis(out: &mut Out, f: &Frame, ax: &Axis, th: &Theme) {
    for t in ax.ticks() {
        if !ax.contains(t) {
            continue;
        }
        let y = num(ax.pos(t));
        out.line(&format!(
            "<line x1=\"{}\" y1=\"{y}\" x2=\"{}\" y2=\"{y}\" stroke=\"{GRID_LINE}\" stroke-width=\"1\"/>",
            f.px0,
            f.px0 + f.pw
        ));
        out.line(&format!(
            "<line x1=\"{}\" y1=\"{y}\" x2=\"{}\" y2=\"{y}\" stroke=\"{AXIS_LINE}\" stroke-width=\"1\"/>",
            f.px0 - 4,
            f.px0
        ));
        text(
            out,
            f64::from(f.px0) - 7.0,
            ax.pos(t) + 4.0,
            &fmt_tick(t),
            TICK_SIZE,
            "end",
            &th.text,
            "",
        );
    }
}

pub struct LegendEntry {
    pub label: String,
    pub color: String,
    /// `"line"`, `"dot"` or `"rect"`.
    pub kind: &'static str,
}

fn legend(out: &mut Out, f: &Frame, entries: &[LegendEntry], th: &Theme) {
    if entries.is_empty() {
        return;
    }
    let max_len = entries
        .iter()
        .map(|e| e.label.chars().count())
        .max()
        .unwrap_or(0);
    #[expect(
        clippy::cast_possible_truncation,
        clippy::cast_possible_wrap,
        reason = "label lengths are small"
    )]
    let bw = 30 + (max_len as i32) * 7;
    #[expect(
        clippy::cast_possible_truncation,
        clippy::cast_possible_wrap,
        reason = "entry counts are small"
    )]
    let bh = (entries.len() as i32) * 18 + 8;
    let bx = f.px0 + f.pw - 8 - bw;
    let by = f.py0 + 8;
    out.line(&format!(
        "<rect x=\"{bx}\" y=\"{by}\" width=\"{bw}\" height=\"{bh}\" fill=\"#ffffff\" fill-opacity=\"0.85\" stroke=\"#cccccc\"/>"
    ));
    for (i, e) in entries.iter().enumerate() {
        #[expect(
            clippy::cast_possible_truncation,
            clippy::cast_possible_wrap,
            reason = "entry counts are small"
        )]
        let cy = by + 4 + (i as i32) * 18 + 9;
        match e.kind {
            "line" => out.line(&format!(
                "<line x1=\"{}\" y1=\"{cy}\" x2=\"{}\" y2=\"{cy}\" stroke=\"{}\" stroke-width=\"2\"/>",
                bx + 6,
                bx + 20,
                e.color
            )),
            "dot" => out.line(&format!(
                "<circle cx=\"{}\" cy=\"{cy}\" r=\"3.5\" fill=\"{}\"/>",
                bx + 13,
                e.color
            )),
            _ => out.line(&format!(
                "<rect x=\"{}\" y=\"{}\" width=\"14\" height=\"12\" fill=\"{}\"/>",
                bx + 6,
                cy - 6,
                e.color
            )),
        }
        text(
            out,
            f64::from(bx) + 26.0,
            f64::from(cy) + 4.0,
            &e.label,
            TICK_SIZE,
            "start",
            &th.text,
            "",
        );
    }
}

fn column(m: &MatD, j: usize) -> Vec<f64> {
    (0..m.rows()).map(|i| m.at(i, j)).collect()
}

fn all_values(m: &MatD) -> Vec<f64> {
    let cols = m.cols();
    (0..m.rows() * cols)
        .map(|k| m.at(k / cols, k % cols))
        .collect()
}

fn label(labels: &[String], k: usize) -> String {
    labels.get(k).cloned().unwrap_or_else(|| format!("col{k}"))
}

fn finite_min_max(data: &[f64]) -> (f64, f64) {
    if data.is_empty() {
        return (0.0, 1.0);
    }
    let mut lo = data[0];
    let mut hi = data[0];
    for &v in data {
        if v < lo {
            lo = v;
        }
        if v > hi {
            hi = v;
        }
    }
    if lo == hi {
        (lo - 0.5, hi + 0.5)
    } else {
        (lo, hi)
    }
}

// ── charts ───────────────────────────────────────────────────────────────────

/// Line plot — one polyline per column, x = row index.
#[must_use]
pub fn plot(m: &MatD, title_s: &str, labels: &[String], style: &PlotStyle) -> String {
    let th = theme(style);
    let f = frame(style, title_s, 0);
    let mut out = Out::default();
    open(&mut out, f.w, f.h, &th.chart_bg);
    title(&mut out, &f, title_s, &th);
    plot_area(&mut out, &f, &th);
    #[expect(clippy::cast_precision_loss, reason = "row indices")]
    let xs: Vec<f64> = (0..m.rows()).map(|i| i as f64).collect();
    let xdom = if style.xLog {
        log_domain(&xs)
    } else {
        domain(&xs)
    };
    let all = all_values(m);
    let ydom = if style.yLog {
        log_domain(&all)
    } else {
        domain(&all)
    };
    let ax = Axis {
        dom: xdom,
        log: style.xLog,
        p0: f64::from(f.px0),
        len: f64::from(f.pw),
        flip: false,
    };
    let ay = Axis {
        dom: ydom,
        log: style.yLog,
        p0: f64::from(f.py0),
        len: f64::from(f.ph),
        flip: true,
    };
    x_axis(&mut out, &f, &ax, &th);
    y_axis(&mut out, &f, &ay, &th);
    for j in 0..m.cols() {
        let mut pts = String::new();
        for (i, &x) in xs.iter().enumerate() {
            let y = m.at(i, j);
            let ok = y.is_finite() && (!style.yLog || y > 0.0) && (!style.xLog || x > 0.0);
            if ok {
                if !pts.is_empty() {
                    pts.push(' ');
                }
                pts.push_str(&num(ax.pos(x)));
                pts.push(',');
                pts.push_str(&num(ay.pos(y)));
            }
        }
        out.line(&format!(
            "<polyline points=\"{pts}\" fill=\"none\" stroke=\"{}\" stroke-width=\"1.5\"/>",
            th.color(j)
        ));
    }
    if m.cols() > 1 || !labels.is_empty() {
        let entries: Vec<LegendEntry> = (0..m.cols())
            .map(|j| LegendEntry {
                label: label(labels, j),
                color: th.color(j).to_owned(),
                kind: "line",
            })
            .collect();
        legend(&mut out, &f, &entries, &th);
    }
    axis_labels(&mut out, &f, style, &th);
    close(&mut out);
    out.finish()
}

/// Scatter of two columns; `group_col >= 0` colours by the distinct values of that column.
#[must_use]
pub fn scatter(
    m: &MatD,
    x_col: usize,
    y_col: usize,
    group_col: i64,
    title_s: &str,
    style: &PlotStyle,
) -> String {
    let th = theme(style);
    let f = frame(style, title_s, 0);
    let mut out = Out::default();
    open(&mut out, f.w, f.h, &th.chart_bg);
    title(&mut out, &f, title_s, &th);
    plot_area(&mut out, &f, &th);
    let xs = column(m, x_col);
    let ys = column(m, y_col);
    let ax = Axis {
        dom: if style.xLog {
            log_domain(&xs)
        } else {
            domain(&xs)
        },
        log: style.xLog,
        p0: f64::from(f.px0),
        len: f64::from(f.pw),
        flip: false,
    };
    let ay = Axis {
        dom: if style.yLog {
            log_domain(&ys)
        } else {
            domain(&ys)
        },
        log: style.yLog,
        p0: f64::from(f.py0),
        len: f64::from(f.ph),
        flip: true,
    };
    x_axis(&mut out, &f, &ax, &th);
    y_axis(&mut out, &f, &ay, &th);
    let dots = |out: &mut Out, rows: &[usize], color: &str| {
        for &i in rows {
            let x = xs[i];
            let y = ys[i];
            let ok = x.is_finite()
                && y.is_finite()
                && (!style.xLog || x > 0.0)
                && (!style.yLog || y > 0.0);
            if ok {
                out.line(&format!(
                    "<circle cx=\"{}\" cy=\"{}\" r=\"3\" fill=\"{color}\" fill-opacity=\"0.8\"/>",
                    num(ax.pos(x)),
                    num(ay.pos(y))
                ));
            }
        }
    };
    if group_col < 0 {
        let rows: Vec<usize> = (0..m.rows()).collect();
        dots(&mut out, &rows, th.color(0));
    } else {
        #[expect(clippy::cast_sign_loss, reason = "checked non-negative")]
        let gs = column(m, group_col as usize);
        let mut groups: Vec<f64> = gs.iter().copied().filter(|v| v.is_finite()).collect();
        groups.sort_by(f64::total_cmp);
        groups.dedup();
        for (gi, &g) in groups.iter().enumerate() {
            let rows: Vec<usize> = (0..m.rows()).filter(|&i| gs[i] == g).collect();
            dots(&mut out, &rows, th.color(gi));
        }
        let entries: Vec<LegendEntry> = groups
            .iter()
            .enumerate()
            .map(|(gi, &g)| LegendEntry {
                label: fmt_tick(g),
                color: th.color(gi).to_owned(),
                kind: "dot",
            })
            .collect();
        legend(&mut out, &f, &entries, &th);
    }
    axis_labels(&mut out, &f, style, &th);
    close(&mut out);
    out.finish()
}

/// Histogram of every finite value, `bins` equal-width bins over [min, max].
#[must_use]
pub fn hist(m: &MatD, bins: i32, title_s: &str, style: &PlotStyle) -> String {
    let th = theme(style);
    let f = frame(style, title_s, 0);
    let mut out = Out::default();
    open(&mut out, f.w, f.h, &th.chart_bg);
    title(&mut out, &f, title_s, &th);
    plot_area(&mut out, &f, &th);
    let data: Vec<f64> = all_values(m)
        .into_iter()
        .filter(|v| v.is_finite())
        .collect();
    let nb = bins.max(1);
    let (mn, mx) = finite_min_max(&data);
    let bin_size = (mx - mn) / f64::from(nb);
    #[expect(clippy::cast_sign_loss, reason = "nb ≥ 1")]
    let mut counts = vec![0i64; nb as usize];
    for &v in &data {
        #[expect(clippy::cast_possible_truncation, reason = "bin index")]
        let b = (((v - mn) / bin_size) as i64).min(i64::from(nb) - 1);
        #[expect(clippy::cast_sign_loss, reason = "b ≥ 0 since v ≥ mn")]
        {
            counts[b as usize] += 1;
        }
    }
    let ax = Axis {
        dom: domain(&[mn, mx]),
        log: false,
        p0: f64::from(f.px0),
        len: f64::from(f.pw),
        flip: false,
    };
    #[expect(clippy::cast_precision_loss, reason = "counts")]
    let max_count = counts.iter().copied().max().unwrap_or(0) as f64;
    let ay = Axis {
        dom: domain(&[0.0, max_count]),
        log: false,
        p0: f64::from(f.py0),
        len: f64::from(f.ph),
        flip: true,
    };
    x_axis(&mut out, &f, &ax, &th);
    y_axis(&mut out, &f, &ay, &th);
    let y0 = ay.pos(0.0);
    for (b, &c) in counts.iter().enumerate() {
        #[expect(clippy::cast_precision_loss, reason = "bin index")]
        let bf = b as f64;
        let x0 = ax.pos(mn + bf * bin_size);
        let x1 = ax.pos(mn + (bf + 1.0) * bin_size);
        #[expect(clippy::cast_precision_loss, reason = "counts")]
        let yt = ay.pos(c as f64);
        out.line(&format!(
            "<rect x=\"{}\" y=\"{}\" width=\"{}\" height=\"{}\" fill=\"{}\" stroke=\"{GRID_LINE}\" stroke-width=\"1\"/>",
            num(x0),
            num(yt),
            num(x1 - x0),
            num(y0 - yt),
            th.color(0)
        ));
    }
    axis_labels(&mut out, &f, style, &th);
    close(&mut out);
    out.finish()
}

/// Bar chart — one bar per row of `col`; labels from `label_col` or the row index.
#[must_use]
pub fn bar(m: &MatD, col: usize, label_col: i64, title_s: &str, style: &PlotStyle) -> String {
    let th = theme(style);
    let f = frame(style, title_s, 0);
    let mut out = Out::default();
    open(&mut out, f.w, f.h, &th.chart_bg);
    title(&mut out, &f, title_s, &th);
    plot_area(&mut out, &f, &th);
    let ys = column(m, col);
    let mut with_zero = ys.clone();
    with_zero.push(0.0);
    let ay = Axis {
        dom: domain(&with_zero),
        log: false,
        p0: f64::from(f.py0),
        len: f64::from(f.ph),
        flip: true,
    };
    y_axis(&mut out, &f, &ay, &th);
    let n = m.rows().max(1);
    #[expect(clippy::cast_precision_loss, reason = "row count")]
    let band = f64::from(f.pw) / n as f64;
    let y0 = ay.pos(0.0);
    for (i, &v) in ys.iter().enumerate() {
        #[expect(clippy::cast_precision_loss, reason = "row index")]
        let fi = i as f64;
        if v.is_finite() {
            let yv = ay.pos(v);
            let top = y0.min(yv);
            let hgt = (y0 - yv).abs();
            out.line(&format!(
                "<rect x=\"{}\" y=\"{}\" width=\"{}\" height=\"{}\" fill=\"{}\"/>",
                num(f64::from(f.px0) + fi * band + band * 0.1),
                num(top),
                num(band * 0.8),
                num(hgt),
                th.color(0)
            ));
        }
        let lbl = if label_col >= 0 {
            #[expect(clippy::cast_sign_loss, reason = "checked non-negative")]
            let lc = label_col as usize;
            fmt_tick(m.at(i, lc))
        } else {
            i.to_string()
        };
        text(
            &mut out,
            f64::from(f.px0) + fi * band + band / 2.0,
            f64::from(f.py0 + f.ph) + 16.0,
            &lbl,
            TICK_SIZE,
            "middle",
            &th.text,
            "",
        );
    }
    out.line(&format!(
        "<line x1=\"{}\" y1=\"{}\" x2=\"{}\" y2=\"{}\" stroke=\"{AXIS_LINE}\" stroke-width=\"1\"/>",
        f.px0,
        num(y0),
        f.px0 + f.pw,
        num(y0)
    ));
    axis_labels(&mut out, &f, style, &th);
    close(&mut out);
    out.finish()
}

fn channel(s: &str, k: usize) -> i64 {
    i64::from_str_radix(&s[1 + 2 * k..3 + 2 * k], 16).unwrap_or(0)
}

/// Linear interpolation between gradient stops, `t` in [0,1]; channels half-up.
#[must_use]
pub fn gradient(stops: &[String], t: f64) -> String {
    let tt = t.clamp(0.0, 1.0);
    #[expect(clippy::cast_precision_loss, reason = "stop counts are tiny")]
    let seg = tt * (stops.len() - 1) as f64;
    #[expect(
        clippy::cast_possible_truncation,
        clippy::cast_sign_loss,
        reason = "0 ≤ seg < stops"
    )]
    let i0 = (seg.floor() as usize).min(stops.len() - 2);
    #[expect(clippy::cast_precision_loss, reason = "stop index")]
    let u = seg - i0 as f64;
    let a = &stops[i0];
    let b = &stops[i0 + 1];
    #[expect(
        clippy::cast_precision_loss,
        clippy::cast_possible_truncation,
        reason = "0..255"
    )]
    let mix = |k: usize| -> i64 {
        (channel(a, k) as f64 + (channel(b, k) - channel(a, k)) as f64 * u + 0.5).floor() as i64
    };
    format!("#{:02x}{:02x}{:02x}", mix(0), mix(1), mix(2))
}

fn heat_stops(style: &PlotStyle) -> Vec<String> {
    if style.seriesColors.len() >= 2 {
        style.seriesColors.iter().copied().map(hex).collect()
    } else {
        HEAT_STOPS.iter().map(|s| (*s).to_owned()).collect()
    }
}

#[expect(
    clippy::too_many_arguments,
    reason = "one cell loop, split out of heatmap for length"
)]
fn heat_cells(out: &mut Out, m: &MatD, f: &Frame, th: &Theme, stops: &[String], mn: f64, mx: f64) {
    #[expect(clippy::cast_precision_loss, reason = "shape")]
    let cw = f64::from(f.pw) / m.cols().max(1) as f64;
    #[expect(clippy::cast_precision_loss, reason = "shape")]
    let chh = f64::from(f.ph) / m.rows().max(1) as f64;
    for i in 0..m.rows() {
        for j in 0..m.cols() {
            let v = m.at(i, j);
            #[expect(clippy::cast_precision_loss, reason = "shape")]
            let x = f64::from(f.px0) + j as f64 * cw;
            #[expect(clippy::cast_precision_loss, reason = "shape")]
            let y = f64::from(f.py0) + i as f64 * chh;
            if v.is_finite() {
                let t = (v - mn) / (mx - mn);
                out.line(&format!(
                    "<rect x=\"{}\" y=\"{}\" width=\"{}\" height=\"{}\" fill=\"{}\" stroke=\"{GRID_LINE}\" stroke-width=\"1\"/>",
                    num(x),
                    num(y),
                    num(cw),
                    num(chh),
                    gradient(stops, t)
                ));
                if cw >= 36.0 && chh >= 16.0 {
                    let fill = if (0.25..=0.75).contains(&t) {
                        "#000000"
                    } else {
                        "#ffffff"
                    };
                    text(
                        out,
                        x + cw / 2.0,
                        y + chh / 2.0 + 4.0,
                        &fmt_tick(v),
                        TICK_SIZE,
                        "middle",
                        fill,
                        "",
                    );
                }
            } else {
                out.line(&format!(
                    "<rect x=\"{}\" y=\"{}\" width=\"{}\" height=\"{}\" fill=\"{}\" stroke=\"{GRID_LINE}\" stroke-width=\"1\"/>",
                    num(x),
                    num(y),
                    num(cw),
                    num(chh),
                    th.plot_bg
                ));
            }
        }
    }
}

/// Heatmap — a colour cell per element, value text when the cell is big enough, colour
/// bar at the right.
#[must_use]
pub fn heatmap(
    m: &MatD,
    title_s: &str,
    row_labels: &[String],
    col_labels: &[String],
    style: &PlotStyle,
) -> String {
    let th = theme(style);
    let stops = heat_stops(style);
    let f = frame(style, title_s, 60);
    let mut out = Out::default();
    open(&mut out, f.w, f.h, &th.chart_bg);
    title(&mut out, &f, title_s, &th);
    let all: Vec<f64> = all_values(m)
        .into_iter()
        .filter(|v| v.is_finite())
        .collect();
    let (mn, mx) = finite_min_max(&all);
    heat_cells(&mut out, m, &f, &th, &stops, mn, mx);
    #[expect(clippy::cast_precision_loss, reason = "shape")]
    let cw = f64::from(f.pw) / m.cols().max(1) as f64;
    #[expect(clippy::cast_precision_loss, reason = "shape")]
    let chh = f64::from(f.ph) / m.rows().max(1) as f64;
    for j in 0..m.cols() {
        let lbl = col_labels.get(j).cloned().unwrap_or_else(|| j.to_string());
        #[expect(clippy::cast_precision_loss, reason = "shape")]
        let x = f64::from(f.px0) + j as f64 * cw + cw / 2.0;
        text(
            &mut out,
            x,
            f64::from(f.py0 + f.ph) + 16.0,
            &lbl,
            TICK_SIZE,
            "middle",
            &th.text,
            "",
        );
    }
    for i in 0..m.rows() {
        let lbl = row_labels.get(i).cloned().unwrap_or_else(|| i.to_string());
        #[expect(clippy::cast_precision_loss, reason = "shape")]
        let y = f64::from(f.py0) + i as f64 * chh + chh / 2.0 + 4.0;
        text(
            &mut out,
            f64::from(f.px0) - 7.0,
            y,
            &lbl,
            TICK_SIZE,
            "end",
            &th.text,
            "",
        );
    }
    let bx = f.px0 + f.pw + 16;
    let bwid = 14;
    let bands = 20;
    let band_h = f64::from(f.ph) / f64::from(bands);
    for k in 0..bands {
        let t = 1.0 - (f64::from(k) + 0.5) / f64::from(bands);
        out.line(&format!(
            "<rect x=\"{bx}\" y=\"{}\" width=\"{bwid}\" height=\"{}\" fill=\"{}\"/>",
            num(f64::from(f.py0) + f64::from(k) * band_h),
            num(band_h),
            gradient(&stops, t)
        ));
    }
    text(
        &mut out,
        f64::from(bx + bwid) + 4.0,
        f64::from(f.py0) + 10.0,
        &fmt_tick(mx),
        TICK_SIZE,
        "start",
        &th.text,
        "",
    );
    text(
        &mut out,
        f64::from(bx + bwid) + 4.0,
        f64::from(f.py0 + f.ph),
        &fmt_tick(mn),
        TICK_SIZE,
        "start",
        &th.text,
        "",
    );
    axis_labels(&mut out, &f, style, &th);
    close(&mut out);
    out.finish()
}

/// NumPy's linear-interpolation quantile on a sorted slice.
#[must_use]
pub fn quantile(sorted: &[f64], p: f64) -> f64 {
    let n = sorted.len();
    if n == 1 {
        return sorted[0];
    }
    #[expect(clippy::cast_precision_loss, reason = "length")]
    let idx = p * (n - 1) as f64;
    #[expect(
        clippy::cast_possible_truncation,
        clippy::cast_sign_loss,
        reason = "0 ≤ idx < n"
    )]
    let lo = idx as usize;
    let hi = (lo + 1).min(n - 1);
    #[expect(clippy::cast_precision_loss, reason = "index")]
    let fr = idx - lo as f64;
    sorted[lo] + fr * (sorted[hi] - sorted[lo])
}

fn box_one(out: &mut Out, s: &[f64], cx: f64, band: f64, ay: &Axis, color: &str) {
    let q1 = quantile(s, 0.25);
    let q2 = quantile(s, 0.5);
    let q3 = quantile(s, 0.75);
    let iqr = q3 - q1;
    let lo_fence = q1 - 1.5 * iqr;
    let hi_fence = q3 + 1.5 * iqr;
    let inside: Vec<f64> = s
        .iter()
        .copied()
        .filter(|v| *v >= lo_fence && *v <= hi_fence)
        .collect();
    let (wlo, whi) = if inside.is_empty() {
        (q1, q3)
    } else {
        let mut lo = inside[0];
        let mut hi = inside[0];
        for &v in &inside {
            if v < lo {
                lo = v;
            }
            if v > hi {
                hi = v;
            }
        }
        (lo, hi)
    };
    let bw = band * 0.5;
    let x0 = cx - bw / 2.0;
    let vline = |out: &mut Out, y1: f64, y2: f64| {
        out.line(&format!(
            "<line x1=\"{}\" y1=\"{}\" x2=\"{}\" y2=\"{}\" stroke=\"{TEXT_FILL}\" stroke-width=\"1\"/>",
            num(cx),
            num(y1),
            num(cx),
            num(y2)
        ));
    };
    let cap = |out: &mut Out, y: f64| {
        out.line(&format!(
            "<line x1=\"{}\" y1=\"{}\" x2=\"{}\" y2=\"{}\" stroke=\"{TEXT_FILL}\" stroke-width=\"1\"/>",
            num(cx - bw / 4.0),
            num(y),
            num(cx + bw / 4.0),
            num(y)
        ));
    };
    vline(out, ay.pos(wlo), ay.pos(q1));
    vline(out, ay.pos(q3), ay.pos(whi));
    cap(out, ay.pos(wlo));
    cap(out, ay.pos(whi));
    out.line(&format!(
        "<rect x=\"{}\" y=\"{}\" width=\"{}\" height=\"{}\" fill=\"{color}\" fill-opacity=\"0.6\" stroke=\"{TEXT_FILL}\" stroke-width=\"1\"/>",
        num(x0),
        num(ay.pos(q3)),
        num(bw),
        num(ay.pos(q1) - ay.pos(q3))
    ));
    out.line(&format!(
        "<line x1=\"{}\" y1=\"{}\" x2=\"{}\" y2=\"{}\" stroke=\"{TEXT_FILL}\" stroke-width=\"2\"/>",
        num(x0),
        num(ay.pos(q2)),
        num(x0 + bw),
        num(ay.pos(q2))
    ));
    for &v in s {
        if v < lo_fence || v > hi_fence {
            out.line(&format!(
                "<circle cx=\"{}\" cy=\"{}\" r=\"3\" fill=\"none\" stroke=\"{TEXT_FILL}\" stroke-width=\"1\"/>",
                num(cx),
                num(ay.pos(v))
            ));
        }
    }
}

/// Box plot — one box per column: quartiles, 1.5·IQR whiskers, outliers as dots.
#[must_use]
pub fn box_plot(m: &MatD, title_s: &str, labels: &[String], style: &PlotStyle) -> String {
    let th = theme(style);
    let f = frame(style, title_s, 0);
    let mut out = Out::default();
    open(&mut out, f.w, f.h, &th.chart_bg);
    title(&mut out, &f, title_s, &th);
    plot_area(&mut out, &f, &th);
    let cols: Vec<Vec<f64>> = (0..m.cols())
        .map(|j| {
            let mut c: Vec<f64> = column(m, j).into_iter().filter(|v| v.is_finite()).collect();
            c.sort_by(f64::total_cmp);
            c
        })
        .collect();
    let all: Vec<f64> = cols.iter().flatten().copied().collect();
    let ay = Axis {
        dom: domain(&all),
        log: false,
        p0: f64::from(f.py0),
        len: f64::from(f.ph),
        flip: true,
    };
    y_axis(&mut out, &f, &ay, &th);
    let n = m.cols().max(1);
    #[expect(clippy::cast_precision_loss, reason = "column count")]
    let band = f64::from(f.pw) / n as f64;
    for (j, s) in cols.iter().enumerate() {
        #[expect(clippy::cast_precision_loss, reason = "column index")]
        let cx = f64::from(f.px0) + j as f64 * band + band / 2.0;
        if !s.is_empty() {
            box_one(&mut out, s, cx, band, &ay, th.color(j));
        }
        text(
            &mut out,
            cx,
            f64::from(f.py0 + f.ph) + 16.0,
            &label(labels, j),
            TICK_SIZE,
            "middle",
            &th.text,
            "",
        );
    }
    axis_labels(&mut out, &f, style, &th);
    close(&mut out);
    out.finish()
}

// ── pairs ────────────────────────────────────────────────────────────────────

/// Per-call constants of the scatterplot matrix; integer pixel arithmetic as in Scala.
struct PairsCtx<'a> {
    cell_w: i32,
    cell_h: i32,
    nb: i32,
    dot_size: i32,
    solid: String,
    alpha: String,
    labels: &'a [String],
    tick_font: i32,
    lbl_font: i32,
    lbl_attrs: String,
}

const PAD_L: i32 = 44;
const PAD_R: i32 = 4;
const PAD_T: i32 = 18;
const PAD_B: i32 = 32;
const TICK_LEN: i32 = 3;
const P_GRID: &str = "#dcdcdc";
const P_AXIS: &str = "#a0a0a0";
const P_TEXT: &str = "#404040";

fn finite_min(a: &[f64]) -> f64 {
    let f: Vec<f64> = a.iter().copied().filter(|v| v.is_finite()).collect();
    if f.is_empty() {
        0.0
    } else {
        f.iter()
            .copied()
            .fold(f[0], |acc, v| if v < acc { v } else { acc })
    }
}

fn finite_max(a: &[f64]) -> f64 {
    let f: Vec<f64> = a.iter().copied().filter(|v| v.is_finite()).collect();
    if f.is_empty() {
        1.0
    } else {
        f.iter()
            .copied()
            .fold(f[0], |acc, v| if v > acc { v } else { acc })
    }
}

#[expect(
    clippy::cast_possible_truncation,
    reason = "pixel coordinates from small ratios"
)]
fn trunc(x: f64) -> i32 {
    x as i32
}

fn hline(out: &mut Out, x1: i32, y: i32, x2: i32, color: &str) {
    out.line(&format!("<line x1=\"{x1}\" y1=\"{y}\" x2=\"{x2}\" y2=\"{y}\" stroke=\"{color}\" stroke-width=\"1\"/>"));
}

fn vline(out: &mut Out, x: i32, y1: i32, y2: i32, color: &str) {
    out.line(&format!("<line x1=\"{x}\" y1=\"{y1}\" x2=\"{x}\" y2=\"{y2}\" stroke=\"{color}\" stroke-width=\"1\"/>"));
}

#[expect(
    clippy::too_many_arguments,
    reason = "cell geometry as Scala threads it"
)]
fn pairs_x_ticks(
    out: &mut Out,
    ticks: &[f64],
    mn: f64,
    rng: f64,
    px0: i32,
    py0: i32,
    pw: i32,
    ph: i32,
    c: &PairsCtx<'_>,
) {
    for &t in ticks {
        let tx = px0 + trunc((t - mn) / rng * f64::from(pw));
        if tx >= px0 && tx <= px0 + pw {
            vline(out, tx, py0 + ph, py0 + ph + TICK_LEN, P_AXIS);
            text(
                out,
                f64::from(tx),
                f64::from(py0 + ph + TICK_LEN + c.tick_font),
                &fmt_tick(t),
                c.tick_font,
                "middle",
                P_TEXT,
                "",
            );
        }
    }
}

#[expect(
    clippy::too_many_arguments,
    reason = "cell geometry as Scala threads it"
)]
fn pairs_diag(
    out: &mut Out,
    data: &[f64],
    mn: f64,
    mx: f64,
    x0: i32,
    y0: i32,
    i: usize,
    c: &PairsCtx<'_>,
) {
    let px0 = x0 + PAD_L;
    let py0 = y0 + PAD_T;
    let pw = c.cell_w - PAD_L - PAD_R;
    let ph = c.cell_h - PAD_T - PAD_B;
    let bin_size = if mx == mn {
        1.0
    } else {
        (mx - mn) / f64::from(c.nb)
    };
    #[expect(clippy::cast_sign_loss, reason = "nb ≥ 1")]
    let mut counts = vec![0i64; c.nb as usize];
    for &v in data {
        if v.is_finite() {
            let b = trunc((v - mn) / bin_size).min(c.nb - 1);
            #[expect(clippy::cast_sign_loss, reason = "b ≥ 0")]
            {
                counts[b as usize] += 1;
            }
        }
    }
    #[expect(clippy::cast_precision_loss, reason = "counts")]
    let max_count = counts.iter().copied().max().unwrap_or(0).max(1) as f64;
    let bw = (pw / c.nb - 1).max(1);
    for t in nice_ticks(0.0, max_count, 4) {
        let ty = py0 + ph - trunc(t / max_count * f64::from(ph));
        if ty >= py0 && ty <= py0 + ph {
            hline(out, px0, ty, px0 + pw, P_GRID);
        }
    }
    for (b, &cnt) in counts.iter().enumerate() {
        #[expect(clippy::cast_precision_loss, reason = "counts")]
        let bar_h = trunc((cnt as f64 / max_count) * f64::from(ph));
        #[expect(
            clippy::cast_possible_truncation,
            clippy::cast_possible_wrap,
            reason = "bin index"
        )]
        let bx = px0 + (b as i32) * pw / c.nb;
        out.line(&format!(
            "<rect x=\"{bx}\" y=\"{}\" width=\"{bw}\" height=\"{bar_h}\" fill=\"{}\"/>",
            py0 + ph - bar_h,
            c.solid
        ));
    }
    vline(out, px0, py0, py0 + ph, P_AXIS);
    hline(out, px0, py0 + ph, px0 + pw, P_AXIS);
    let rng = if mx == mn { 1.0 } else { mx - mn };
    pairs_x_ticks(out, &nice_ticks(mn, mx, 4), mn, rng, px0, py0, pw, ph, c);
    text(
        out,
        f64::from(x0) + f64::from(c.cell_w) / 2.0,
        f64::from(y0 + PAD_T) - 3.0,
        &label(c.labels, i),
        c.lbl_font,
        "middle",
        P_TEXT,
        &c.lbl_attrs,
    );
}

struct OffDiag<'a> {
    xs: &'a [f64],
    ys: &'a [f64],
    xmn: f64,
    xmx: f64,
    ymn: f64,
    ymx: f64,
}

#[expect(
    clippy::too_many_arguments,
    reason = "cell geometry as Scala threads it"
)]
fn pairs_off(
    out: &mut Out,
    d: &OffDiag<'_>,
    x0: i32,
    y0: i32,
    i: usize,
    j: usize,
    c: &PairsCtx<'_>,
) {
    let px0 = x0 + PAD_L;
    let py0 = y0 + PAD_T;
    let pw = c.cell_w - PAD_L - PAD_R;
    let ph = c.cell_h - PAD_T - PAD_B;
    let xrng = if d.xmx == d.xmn { 1.0 } else { d.xmx - d.xmn };
    let yrng = if d.ymx == d.ymn { 1.0 } else { d.ymx - d.ymn };
    let x_ticks = nice_ticks(d.xmn, d.xmx, 4);
    let y_ticks = nice_ticks(d.ymn, d.ymx, 4);
    for &t in &x_ticks {
        let tx = px0 + trunc((t - d.xmn) / xrng * f64::from(pw));
        if tx >= px0 && tx <= px0 + pw {
            vline(out, tx, py0, py0 + ph, P_GRID);
        }
    }
    for &t in &y_ticks {
        let ty = py0 + ph - trunc((t - d.ymn) / yrng * f64::from(ph));
        if ty >= py0 && ty <= py0 + ph {
            hline(out, px0, ty, px0 + pw, P_GRID);
        }
    }
    for k in 0..d.xs.len() {
        if d.xs[k].is_finite() && d.ys[k].is_finite() {
            let px = px0 + trunc((d.xs[k] - d.xmn) / xrng * f64::from(pw));
            let py = py0 + ph - trunc((d.ys[k] - d.ymn) / yrng * f64::from(ph));
            out.line(&format!(
                "<rect x=\"{px}\" y=\"{py}\" width=\"{}\" height=\"{}\" fill=\"{}\"{}/>",
                c.dot_size, c.dot_size, c.solid, c.alpha
            ));
        }
    }
    vline(out, px0, py0, py0 + ph, P_AXIS);
    hline(out, px0, py0 + ph, px0 + pw, P_AXIS);
    pairs_x_ticks(out, &x_ticks, d.xmn, xrng, px0, py0, pw, ph, c);
    for &t in &y_ticks {
        let ty = py0 + ph - trunc((t - d.ymn) / yrng * f64::from(ph));
        if ty >= py0 && ty <= py0 + ph {
            hline(out, px0 - TICK_LEN, ty, px0, P_AXIS);
            text(
                out,
                f64::from(px0 - TICK_LEN) - 2.0,
                f64::from(ty) + f64::from(c.tick_font) / 2.0 - 1.0,
                &fmt_tick(t),
                c.tick_font,
                "end",
                P_TEXT,
                "",
            );
        }
    }
    text(
        out,
        f64::from(px0) + f64::from(pw) / 2.0,
        f64::from(y0 + c.cell_h) - 3.0,
        &label(c.labels, j),
        c.tick_font,
        "middle",
        P_TEXT,
        &c.lbl_attrs,
    );
    let cx = f64::from(x0) + 9.0;
    let cy = f64::from(py0) + f64::from(ph) / 2.0;
    let extra = format!(
        "{} transform=\"rotate(-90 {} {})\"",
        c.lbl_attrs,
        num(cx),
        num(cy)
    );
    text(
        out,
        cx,
        cy,
        &label(c.labels, i),
        c.tick_font,
        "middle",
        P_TEXT,
        &extra,
    );
}

/// Options of [`pairs`] beyond the matrix, style and labels.
pub struct PairsArgs<'a> {
    pub labels: &'a [String],
    pub bins: i32,
    pub dot_size: i32,
    pub color: Color,
    pub scatter_alpha: i32,
    pub label_style: i32,
}

/// Scatterplot matrix — histograms on the diagonal, scatters off it, per-cell axes.
#[must_use]
pub fn pairs(m: &MatD, a: &PairsArgs<'_>, style: &PlotStyle) -> String {
    let p = m.cols().max(1);
    #[expect(
        clippy::cast_possible_truncation,
        clippy::cast_possible_wrap,
        reason = "column count"
    )]
    let pi = p as i32;
    let w = style.width;
    let h = style.height;
    let mut out = Out::default();
    open(&mut out, w, h, "#ebebeb");
    let col_data: Vec<Vec<f64>> = (0..p).map(|c| column(m, c)).collect();
    let col_min: Vec<f64> = col_data.iter().map(|c| finite_min(c)).collect();
    let col_max: Vec<f64> = col_data.iter().map(|c| finite_max(c)).collect();
    let cell_w = w / pi;
    let cell_h = h / pi;
    let mut lbl_attrs = String::new();
    if a.label_style & Font::BOLD != 0 {
        lbl_attrs.push_str(" font-weight=\"bold\"");
    }
    if a.label_style & Font::ITALIC != 0 {
        lbl_attrs.push_str(" font-style=\"italic\"");
    }
    let c = PairsCtx {
        cell_w,
        cell_h,
        nb: a.bins.max(1),
        dot_size: a.dot_size,
        solid: hex(a.color),
        alpha: opacity_attr(a.scatter_alpha, "fill-opacity"),
        labels: a.labels,
        tick_font: (cell_h / 14).clamp(7, 10),
        lbl_font: (cell_w / 14).clamp(8, 13),
        lbl_attrs,
    };
    for i in 0..p {
        for j in 0..p {
            #[expect(
                clippy::cast_possible_truncation,
                clippy::cast_possible_wrap,
                reason = "grid index"
            )]
            let x0 = (j as i32) * cell_w;
            #[expect(
                clippy::cast_possible_truncation,
                clippy::cast_possible_wrap,
                reason = "grid index"
            )]
            let y0 = (i as i32) * cell_h;
            out.line(&format!(
                "<rect x=\"{}\" y=\"{}\" width=\"{}\" height=\"{}\" fill=\"#ffffff\"/>",
                x0 + 1,
                y0 + 1,
                cell_w - 2,
                cell_h - 2
            ));
            if i == j {
                pairs_diag(
                    &mut out,
                    &col_data[i],
                    col_min[i],
                    col_max[i],
                    x0,
                    y0,
                    i,
                    &c,
                );
            } else {
                let d = OffDiag {
                    xs: &col_data[j],
                    ys: &col_data[i],
                    xmn: col_min[j],
                    xmx: col_max[j],
                    ymn: col_min[i],
                    ymx: col_max[i],
                };
                pairs_off(&mut out, &d, x0, y0, i, j, &c);
            }
        }
    }
    close(&mut out);
    out.finish()
}

/// A minimal page around the SVG, for the browser.
#[must_use]
pub fn html(svg: &str, title_s: &str) -> String {
    let t = if title_s.is_empty() {
        "uni.plot"
    } else {
        title_s
    };
    let mut s = String::new();
    s.push_str(
        "<!doctype html>
",
    );
    s.push_str(&format!(
        "<html><head><meta charset=\"utf-8\"><title>{}</title>
",
        esc(t)
    ));
    s.push_str("<style>body{margin:0;background:#ffffff;display:flex;justify-content:center;align-items:flex-start;padding:12px}svg{max-width:100%;height:auto}</style>
");
    s.push_str(
        "</head><body>
",
    );
    s.push_str(&format!(
        "{svg}</body></html>
"
    ));
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    // The values here are the ones `uni.plot.PlotSvgSuite` pins on the Scala side.

    #[test]
    fn num_rounds_half_up_and_trims() {
        assert_eq!(num(1.0), "1");
        assert_eq!(num(1.5), "1.5");
        assert_eq!(num(1.25), "1.25");
        assert_eq!(num(1.005), "1"); // 1.005·100 is 100.49999999999999 in binary
        assert_eq!(num(0.125), "0.13");
        assert_eq!(num(-0.004), "0");
        assert_eq!(num(-2.345), "-2.35");
        assert_eq!(num(3.04), "3.04");
        assert_eq!(num(f64::NAN), "0");
    }

    #[test]
    fn fmt_tick_plain_and_scientific() {
        assert_eq!(fmt_tick(0.0), "0");
        assert_eq!(fmt_tick(2.0), "2");
        assert_eq!(fmt_tick(2.5), "2.5");
        assert_eq!(fmt_tick(0.125), "0.125");
        assert_eq!(fmt_tick(0.1234), "0.123");
        assert_eq!(fmt_tick(-7.5), "-7.5");
        assert_eq!(fmt_tick(1_234_567.0), "1234567");
        assert_eq!(fmt_tick(1.5e7), "1.50e7");
        assert_eq!(fmt_tick(2.5e-4), "2.50e-4");
        assert_eq!(fmt_tick(9.999e9), "1.00e10");
    }

    #[test]
    fn log10_matches_the_scala_bits() {
        for k in -20..=20 {
            assert_eq!(log10(pow10(k)), f64::from(k), "10^{k}");
        }
        assert!(log10(0.0).is_nan() && log10(-1.0).is_nan());
        assert_eq!(log10(f64::INFINITY), f64::INFINITY);
        let sub = f64::from_bits(0x0000_0000_0000_0abc);
        let pinned: Vec<String> = [2.0, 3.0, 7.5, 0.3, 123_456.789, 1e-5, 6.02e23, sub]
            .iter()
            .map(|&v| format!("{:016x}", log10(v).to_bits()))
            .collect();
        assert_eq!(
            pinned,
            [
                "3fd34413509f79fe",
                "3fde8927964fd5fd",
                "3fec00807a8870fe",
                "bfe0bb6c34d81501",
                "40145db61a282512",
                "c014000000000000",
                "4037c793a2ba0758",
                "c073fde00ba7964f"
            ]
        );
        for i in 1..2000 {
            let x = f64::from(i) * 0.37 + 1e-9;
            let ours = log10(x);
            let libm = x.log10();
            assert!((ours - libm).abs() <= 1e-15 * libm.abs().max(1.0), "x={x}");
        }
    }

    #[test]
    fn nice_ticks_steps() {
        assert_eq!(
            nice_ticks(0.0, 1.0, 5),
            vec![0.0, 0.2, 0.4, 0.600_000_000_000_000_1, 0.8, 1.0]
        );
        assert_eq!(
            nice_ticks(-3.0, 3.0, 5),
            vec![-3.0, -2.0, -1.0, 0.0, 1.0, 2.0, 3.0]
        );
        assert_eq!(
            nice_ticks(0.0, 100.0, 4),
            vec![0.0, 20.0, 40.0, 60.0, 80.0, 100.0]
        );
        assert_eq!(nice_ticks(2.0, 2.0, 4), vec![2.0]);
        assert_eq!(nice_ticks(0.31, 0.39, 4), vec![0.32, 0.34, 0.36, 0.38]);
    }

    #[test]
    fn domain_padding() {
        assert_eq!(domain(&[0.0, 10.0]), Domain { lo: -0.5, hi: 10.5 });
        assert_eq!(domain(&[4.0, 4.0]), Domain { lo: 3.5, hi: 4.5 });
        assert_eq!(domain(&[]), Domain { lo: 0.0, hi: 1.0 });
        assert_eq!(domain(&[f64::NAN, 1.0, 3.0]), Domain { lo: 0.9, hi: 3.1 });
    }

    #[test]
    fn gradient_stops() {
        let stops: Vec<String> = HEAT_STOPS.iter().map(|s| (*s).to_owned()).collect();
        assert_eq!(gradient(&stops, 0.0), "#2166ac");
        assert_eq!(gradient(&stops, 1.0), "#b2182b");
        assert_eq!(gradient(&stops, 0.5), "#f7f7f7");
        let bw = vec!["#000000".to_owned(), "#ffffff".to_owned()];
        assert_eq!(gradient(&bw, 0.5), "#808080");
    }

    #[test]
    fn esc_specials() {
        assert_eq!(esc("a<b&\"c'"), "a&lt;b&amp;&quot;c&apos;");
    }

    #[test]
    fn charts_are_balanced_and_deterministic() {
        let m = MatD::tabulate(20, 3, |i, j| ((i + 1) * (j + 1)) as f64 * 0.5 - 4.0);
        let s1 = plot(
            &m,
            "t",
            &["a".to_owned(), "b".to_owned()],
            &PlotStyle::sized(900, 600),
        );
        let s2 = plot(
            &m,
            "t",
            &["a".to_owned(), "b".to_owned()],
            &PlotStyle::sized(900, 600),
        );
        assert_eq!(s1, s2);
        assert_eq!(s1.matches("<polyline").count(), 3);
        assert!(s1.contains(">a</text>") && s1.contains(">col2</text>"));
        let opens = s1.matches("<svg").count()
            + s1.matches("<text").count()
            + s1.matches("<rect").count()
            + s1.matches("<line").count()
            + s1.matches("<circle").count()
            + s1.matches("<polyline").count();
        let closes =
            s1.matches("</svg>").count() + s1.matches("</text>").count() + s1.matches("/>").count();
        assert_eq!(opens, closes);
    }
}
