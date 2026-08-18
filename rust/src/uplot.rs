//! Charts — a port of Scala's `uni.plot`: `plot`, `scatter`, `hist`, `bar`, `heatmap`,
//! `boxPlot`, `pairs` on `MatD`, rendered to SVG text and either saved or opened in the
//! default browser.
//!
//! # Same bytes in both languages
//!
//! There is no charting library on either side. [`svg`] draws every chart itself — axes,
//! ticks, bins, quartiles, layout — from constants and integer-exact arithmetic, and the
//! Scala `uni.plot.Svg` is the same code in the other language, so the SVG for a given
//! matrix is byte-identical on the JVM and here. That is what the `pl.` rows of
//! `test-data/mat-parity/scala-reference.txt` pin (`tests/mat_parity.rs`); the doc for
//! [`svg`] lists the determinism rules the port depends on.
//!
//! # API shape
//!
//! Scala's named parameters become option structs with `Default` — `PlotOpts`,
//! `ScatterOpts`, … — so a call reads `m.plot(&PlotOpts { title: "…".into(),
//! ..Default::default() })`. Every display method has a `*Svg` twin returning the text.
//! `saveTo` writes `<name>.svg` (or `.html`); empty shows in the browser, and a headless
//! run (no opener, or `UNI_PLOT_NO_OPEN` set) prints the page's path instead of failing.

#![allow(
    non_snake_case,
    reason = "public items mirror the Scala API name-for-name, so a script kept in both \
              languages needs no mental translation. Internal helpers stay snake_case, \
              so the case says whether a Scala counterpart exists."
)]

mod charts;
pub mod deliver;
pub mod style;
pub mod svg;

pub use style::BarOpts;
pub use style::BoxPlotOpts;
pub use style::Color;
pub use style::Font;
pub use style::HeatmapOpts;
pub use style::HistOpts;
pub use style::PairsOpts;
pub use style::PlotOpts;
pub use style::PlotStyle;
pub use style::ScatterOpts;
