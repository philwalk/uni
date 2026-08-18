//! `PlotStyle`, `Color`, `Font` and the per-chart option structs — the Scala methods'
//! named parameters, as structs with `Default` (Rust has no defaulted arguments).

/// An sRGB colour with alpha, `java.awt.Color`'s data.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Color {
    pub r: u8,
    pub g: u8,
    pub b: u8,
    pub a: u8,
}

impl Color {
    pub const BLACK: Color = Color::rgb(0, 0, 0);
    pub const WHITE: Color = Color::rgb(255, 255, 255);
    pub const RED: Color = Color::rgb(255, 0, 0);
    pub const GREEN: Color = Color::rgb(0, 255, 0);
    pub const BLUE: Color = Color::rgb(0, 0, 255);
    pub const DARK_GRAY: Color = Color::rgb(64, 64, 64);

    #[must_use]
    pub const fn rgb(r: u8, g: u8, b: u8) -> Self {
        Self { r, g, b, a: 255 }
    }

    #[must_use]
    pub const fn rgba(r: u8, g: u8, b: u8, a: u8) -> Self {
        Self { r, g, b, a }
    }
}

/// `java.awt.Font`'s style bits, for `PairsOpts::labelStyle`.
pub struct Font;

impl Font {
    pub const PLAIN: i32 = 0;
    pub const BOLD: i32 = 1;
    pub const ITALIC: i32 = 2;
}

/// Display and export styling, `uni.plot.PlotStyle` field for field. `None` and empty
/// fields defer to the theme default.
#[derive(Clone, Debug, PartialEq)]
pub struct PlotStyle {
    pub width: i32,
    pub height: i32,
    pub background: Option<Color>,
    pub plotBackground: Option<Color>,
    pub foreground: Option<Color>,
    /// Per-series colours in order; on a heatmap, two or more become the gradient stops.
    pub seriesColors: Vec<Color>,
    pub xLabel: String,
    pub yLabel: String,
    pub xLog: bool,
    pub yLog: bool,
}

impl Default for PlotStyle {
    fn default() -> Self {
        Self {
            width: 800,
            height: 500,
            background: None,
            plotBackground: None,
            foreground: None,
            seriesColors: Vec::new(),
            xLabel: String::new(),
            yLabel: String::new(),
            xLog: false,
            yLog: false,
        }
    }
}

impl PlotStyle {
    /// Scala's `PlotStyle.uniform`: 800×500.
    #[must_use]
    pub fn uniform() -> Self {
        Self::default()
    }

    /// `PlotStyle(width = w, height = h)`.
    #[must_use]
    pub fn sized(width: i32, height: i32) -> Self {
        Self {
            width,
            height,
            ..Self::default()
        }
    }
}

/// `plot(title, labels, saveTo, style)`; the default style is 900×600 as in Scala.
#[derive(Clone, Debug, PartialEq)]
pub struct PlotOpts {
    pub title: String,
    pub labels: Vec<String>,
    pub saveTo: String,
    pub style: PlotStyle,
}

impl Default for PlotOpts {
    fn default() -> Self {
        Self {
            title: String::new(),
            labels: Vec::new(),
            saveTo: String::new(),
            style: PlotStyle::sized(900, 600),
        }
    }
}

/// `scatter(xCol, yCol, title, saveTo, groupCol, style)`; default style 700×700.
#[derive(Clone, Debug, PartialEq)]
pub struct ScatterOpts {
    pub xCol: usize,
    pub yCol: usize,
    pub groupCol: i64,
    pub title: String,
    pub saveTo: String,
    pub style: PlotStyle,
}

impl Default for ScatterOpts {
    fn default() -> Self {
        Self {
            xCol: 0,
            yCol: 1,
            groupCol: -1,
            title: String::new(),
            saveTo: String::new(),
            style: PlotStyle::sized(700, 700),
        }
    }
}

/// `hist(bins, title, saveTo, style)`; default style 800×500.
#[derive(Clone, Debug, PartialEq)]
pub struct HistOpts {
    pub bins: i32,
    pub title: String,
    pub saveTo: String,
    pub style: PlotStyle,
}

impl Default for HistOpts {
    fn default() -> Self {
        Self {
            bins: 20,
            title: String::new(),
            saveTo: String::new(),
            style: PlotStyle::sized(800, 500),
        }
    }
}

/// `bar(col, labelCol, title, saveTo, style)`; default style 800×500.
#[derive(Clone, Debug, PartialEq)]
pub struct BarOpts {
    pub col: usize,
    pub labelCol: i64,
    pub title: String,
    pub saveTo: String,
    pub style: PlotStyle,
}

impl Default for BarOpts {
    fn default() -> Self {
        Self {
            col: 0,
            labelCol: -1,
            title: String::new(),
            saveTo: String::new(),
            style: PlotStyle::sized(800, 500),
        }
    }
}

/// `heatmap(title, rowLabels, colLabels, saveTo, style)`; default style 800×700.
#[derive(Clone, Debug, PartialEq)]
pub struct HeatmapOpts {
    pub title: String,
    pub rowLabels: Vec<String>,
    pub colLabels: Vec<String>,
    pub saveTo: String,
    pub style: PlotStyle,
}

impl Default for HeatmapOpts {
    fn default() -> Self {
        Self {
            title: String::new(),
            rowLabels: Vec::new(),
            colLabels: Vec::new(),
            saveTo: String::new(),
            style: PlotStyle::sized(800, 700),
        }
    }
}

/// `boxPlot(title, labels, saveTo, style)`; default style 800×600.
#[derive(Clone, Debug, PartialEq)]
pub struct BoxPlotOpts {
    pub title: String,
    pub labels: Vec<String>,
    pub saveTo: String,
    pub style: PlotStyle,
}

impl Default for BoxPlotOpts {
    fn default() -> Self {
        Self {
            title: String::new(),
            labels: Vec::new(),
            saveTo: String::new(),
            style: PlotStyle::sized(800, 600),
        }
    }
}

/// `pairs(title, labels, bins, dotSize, color, scatterAlpha, labelStyle, saveTo, style)`;
/// default style 1400×600.
#[derive(Clone, Debug, PartialEq)]
pub struct PairsOpts {
    pub title: String,
    pub labels: Vec<String>,
    pub bins: i32,
    pub dotSize: i32,
    pub color: Color,
    pub scatterAlpha: i32,
    pub labelStyle: i32,
    pub saveTo: String,
    pub style: PlotStyle,
}

impl Default for PairsOpts {
    fn default() -> Self {
        Self {
            title: "Scatterplot matrix".to_owned(),
            labels: Vec::new(),
            bins: 10,
            dotSize: 3,
            color: Color::rgb(31, 119, 180),
            scatterAlpha: 80,
            labelStyle: Font::BOLD,
            saveTo: String::new(),
            style: PlotStyle::sized(1400, 600),
        }
    }
}
