//! The `MatD` extension methods of `uni.plot`, name for name: each chart has a `*Svg`
//! twin returning the SVG text and a display method that saves or shows it.

use super::deliver;
use super::style::BarOpts;
use super::style::BoxPlotOpts;
use super::style::HeatmapOpts;
use super::style::HistOpts;
use super::style::PairsOpts;
use super::style::PlotOpts;
use super::style::ScatterOpts;
use super::svg;
use crate::udata::MatD;

impl MatD {
    /// Line plot — one series per column, x-axis = row indices; saved or shown per `saveTo`.
    pub fn plot(&self, o: &PlotOpts) {
        deliver::deliver(
            &self.plotSvg(o),
            &o.title,
            &o.saveTo,
            o.style.width,
            o.style.height,
        );
    }

    /// The SVG text [`MatD::plot`] renders.
    #[must_use]
    pub fn plotSvg(&self, o: &PlotOpts) -> String {
        svg::plot(self, &o.title, &o.labels, &o.style)
    }

    /// Scatter of two columns; `groupCol >= 0` colours by that column's distinct values.
    pub fn scatter(&self, o: &ScatterOpts) {
        deliver::deliver(
            &self.scatterSvg(o),
            &o.title,
            &o.saveTo,
            o.style.width,
            o.style.height,
        );
    }

    /// The SVG text [`MatD::scatter`] renders.
    #[must_use]
    pub fn scatterSvg(&self, o: &ScatterOpts) -> String {
        svg::scatter(self, o.xCol, o.yCol, o.groupCol, &o.title, &o.style)
    }

    /// Histogram of all values in the matrix.
    pub fn hist(&self, o: &HistOpts) {
        deliver::deliver(
            &self.histSvg(o),
            &o.title,
            &o.saveTo,
            o.style.width,
            o.style.height,
        );
    }

    /// The SVG text [`MatD::hist`] renders.
    #[must_use]
    pub fn histSvg(&self, o: &HistOpts) -> String {
        svg::hist(self, o.bins, &o.title, &o.style)
    }

    /// Bar chart — one bar per row in `col`, labels from `labelCol` or the row index.
    pub fn bar(&self, o: &BarOpts) {
        deliver::deliver(
            &self.barSvg(o),
            &o.title,
            &o.saveTo,
            o.style.width,
            o.style.height,
        );
    }

    /// The SVG text [`MatD::bar`] renders.
    #[must_use]
    pub fn barSvg(&self, o: &BarOpts) -> String {
        svg::bar(self, o.col, o.labelCol, &o.title, &o.style)
    }

    /// Heatmap — the matrix as a colour grid with a colour bar.
    pub fn heatmap(&self, o: &HeatmapOpts) {
        deliver::deliver(
            &self.heatmapSvg(o),
            &o.title,
            &o.saveTo,
            o.style.width,
            o.style.height,
        );
    }

    /// The SVG text [`MatD::heatmap`] renders.
    #[must_use]
    pub fn heatmapSvg(&self, o: &HeatmapOpts) -> String {
        svg::heatmap(self, &o.title, &o.rowLabels, &o.colLabels, &o.style)
    }

    /// Box plot — one box per column: median, quartiles, whiskers, outliers.
    pub fn boxPlot(&self, o: &BoxPlotOpts) {
        deliver::deliver(
            &self.boxPlotSvg(o),
            &o.title,
            &o.saveTo,
            o.style.width,
            o.style.height,
        );
    }

    /// The SVG text [`MatD::boxPlot`] renders.
    #[must_use]
    pub fn boxPlotSvg(&self, o: &BoxPlotOpts) -> String {
        svg::box_plot(self, &o.title, &o.labels, &o.style)
    }

    /// Scatterplot matrix — histograms on the diagonal, scatters off it.
    pub fn pairs(&self, o: &PairsOpts) {
        deliver::deliver(
            &self.pairsSvg(o),
            &o.title,
            &o.saveTo,
            o.style.width,
            o.style.height,
        );
    }

    /// The SVG text [`MatD::pairs`] renders.
    #[must_use]
    pub fn pairsSvg(&self, o: &PairsOpts) -> String {
        let a = svg::PairsArgs {
            labels: &o.labels,
            bins: o.bins,
            dot_size: o.dotSize,
            color: o.color,
            scatter_alpha: o.scatterAlpha,
            label_style: o.labelStyle,
        };
        svg::pairs(self, &a, &o.style)
    }
}
