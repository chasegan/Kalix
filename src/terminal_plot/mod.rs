//! Terminal plotting module for rendering plots in the terminal
//!
//! This module provides a flexible and customizable terminal plotting system
//! with support for lines, scatter points, markers, and progress bars.
//!
//! # Example
//!
//! ```rust
//! use kalix::misc::terminal_plot::*;
//!
//! let mut plot = TerminalPlot::builder()
//!     .title("KALIX//OPTIMISER")
//!     .x_label("evals")
//!     .y_label("Objective Function")
//!     .width(55)
//!     .height(12)
//!     .color_scheme(ColorScheme::electric_grid())
//!     .build();
//!
//! plot.add_line(Line {
//!     points: vec![(0.0, 2.0), (1000.0, 1.5), (2000.0, 1.0)],
//!     style: LineStyle::Dots,
//!     color: Some(Color::BrightMagenta),
//! });
//!
//! println!("{}", plot.render());
//! ```

pub mod optimization_plot;

use std::fmt;

/// Main plot structure containing configuration and plot elements
pub struct TerminalPlot {
    config: PlotConfig,
    elements: Vec<PlotElement>,
    progress: Option<ProgressBar>,
    footer_lines: Vec<String>,
    has_rendered: bool,
}

/// Configuration for the plot
#[derive(Clone)]
pub struct PlotConfig {
    pub width: usize,
    pub height: usize,
    pub x_label: String,
    pub y_label: String,
    pub title: String,
    pub x_range: Option<(f64, f64)>,
    pub y_range: Option<(f64, f64)>,
    pub color_scheme: ColorScheme,
}

/// Plot elements that can be rendered
pub enum PlotElement {
    Line(Line),
    ScatterPoints(Vec<ScatterPoint>),
    Marker(Marker),
}

/// A line to be plotted
pub struct Line {
    pub points: Vec<(f64, f64)>,
    pub style: LineStyle,
    pub color: Option<Color>,
}

/// Individual scatter point
pub struct ScatterPoint {
    pub x: f64,
    pub y: f64,
    pub color: Option<Color>,
    pub symbol: char,
}

/// Marker with optional label
pub struct Marker {
    pub x: f64,
    pub y: f64,
    pub symbol: char,
    pub color: Option<Color>,
    pub label: Option<String>,
}

/// Style for rendering lines
#[derive(Clone, Copy)]
pub enum LineStyle {
    Dots,          // '·'
    Solid,         // '━'
    Dashed,        // '┄'
    Custom(char),
}

/// Color scheme for the plot
#[derive(Clone)]
pub struct ColorScheme {
    pub default: Color,
    pub border: Color,
    pub title: Color,
    pub axis: Color,
    pub axis_labels: Color,
    pub progress_bar: Color,
}

/// Terminal colors using ANSI codes
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Color {
    Reset,
    Black,
    Red,
    Green,
    Yellow,
    Blue,
    Magenta,
    Cyan,
    White,
    BrightBlack,
    BrightRed,
    BrightGreen,
    BrightYellow,
    BrightBlue,
    BrightMagenta,
    BrightCyan,
    BrightWhite,
}

/// Progress bar for showing optimization progress
pub struct ProgressBar {
    pub current: usize,
    pub total: usize,
}

/// Builder for creating TerminalPlot instances
pub struct PlotBuilder {
    config: PlotConfig,
}

impl Default for PlotConfig {
    fn default() -> Self {
        Self {
            width: 50,
            height: 10,
            x_label: "X".to_string(),
            y_label: "Y".to_string(),
            title: "Plot".to_string(),
            x_range: None,
            y_range: None,
            color_scheme: ColorScheme::default(),
        }
    }
}

impl Default for ColorScheme {
    fn default() -> Self {
        Self::monochrome()
    }
}

impl ColorScheme {
    /// Electric Grid color scheme (synthwave style)
    pub fn electric_grid() -> Self {
        Self {
            default: Color::White,
            border: Color::BrightCyan,
            title: Color::BrightCyan,
            axis: Color::Cyan,
            axis_labels: Color::Cyan,
            progress_bar: Color::BrightCyan,
        }
    }

    /// Monochrome color scheme for terminals with limited color support
    pub fn monochrome() -> Self {
        Self {
            default: Color::White,
            border: Color::White,
            title: Color::White,
            axis: Color::White,
            axis_labels: Color::White,
            progress_bar: Color::White,
        }
    }
}

impl Color {
    /// Convert color to ANSI escape code
    pub fn to_ansi(&self) -> &'static str {
        match self {
            Color::Reset => "\x1b[0m",
            Color::Black => "\x1b[30m",
            Color::Red => "\x1b[31m",
            Color::Green => "\x1b[32m",
            Color::Yellow => "\x1b[33m",
            Color::Blue => "\x1b[34m",
            Color::Magenta => "\x1b[35m",
            Color::Cyan => "\x1b[36m",
            Color::White => "\x1b[37m",
            Color::BrightBlack => "\x1b[90m",
            Color::BrightRed => "\x1b[91m",
            Color::BrightGreen => "\x1b[92m",
            Color::BrightYellow => "\x1b[93m",
            Color::BrightBlue => "\x1b[94m",
            Color::BrightMagenta => "\x1b[95m",
            Color::BrightCyan => "\x1b[96m",
            Color::BrightWhite => "\x1b[97m",
        }
    }
}

impl LineStyle {
    fn to_char(&self) -> char {
        match self {
            LineStyle::Dots => '·',
            LineStyle::Solid => '━',
            LineStyle::Dashed => '┄',
            LineStyle::Custom(c) => *c,
        }
    }
}

impl TerminalPlot {
    /// Create a new plot builder
    pub fn builder() -> PlotBuilder {
        PlotBuilder {
            config: PlotConfig::default(),
        }
    }

    /// Add a line to the plot
    pub fn add_line(&mut self, line: Line) {
        self.elements.push(PlotElement::Line(line));
    }

    /// Add scatter points to the plot
    pub fn add_scatter_points(&mut self, points: Vec<ScatterPoint>) {
        self.elements.push(PlotElement::ScatterPoints(points));
    }

    /// Add a marker to the plot
    pub fn add_marker(&mut self, marker: Marker) {
        self.elements.push(PlotElement::Marker(marker));
    }

    /// Clear all plot elements
    pub fn clear_elements(&mut self) {
        self.elements.clear();
    }

    /// Set progress bar
    pub fn set_progress(&mut self, current: usize, total: usize) {
        self.progress = Some(ProgressBar { current, total });
    }

    /// Clear progress bar
    pub fn clear_progress(&mut self) {
        self.progress = None;
    }

    /// Add a line of text to the footer (displayed below the plot)
    pub fn add_footer_line(&mut self, line: impl Into<String>) {
        self.footer_lines.push(line.into());
    }

    /// Clear all footer lines
    pub fn clear_footer(&mut self) {
        self.footer_lines.clear();
    }

    /// Set multiple footer lines at once (replaces existing footer)
    pub fn set_footer(&mut self, lines: Vec<String>) {
        self.footer_lines = lines;
    }

    /// Calculate the total height of the rendered plot
    pub fn total_height(&self) -> usize {
        let mut height = 0;

        // Title line + border
        height += 2;

        // Plot area
        height += self.config.height;

        // X-axis labels
        height += 2;

        // Bottom border
        height += 1;

        // Footer lines
        height += self.footer_lines.len();

        height
    }

    /// Render the plot (automatically clears previous render if needed)
    pub fn render(&mut self) -> String {
        let output = if self.has_rendered {
            // Not first render - clear previous and redraw
            let height = self.total_height();
            format!("\x1b[{}A\r{}", height, self.render_plot())
        } else {
            // First render
            self.render_plot()
        };

        self.has_rendered = true;
        output
    }

    /// Calculate actual x and y ranges based on data
    fn calculate_ranges(&self) -> ((f64, f64), (f64, f64)) {
        let mut x_min = f64::INFINITY;
        let mut x_max = f64::NEG_INFINITY;
        let mut y_min = f64::INFINITY;
        let mut y_max = f64::NEG_INFINITY;

        // Collect all points from elements
        for element in &self.elements {
            match element {
                PlotElement::Line(line) => {
                    for &(x, y) in &line.points {
                        x_min = x_min.min(x);
                        x_max = x_max.max(x);
                        y_min = y_min.min(y);
                        y_max = y_max.max(y);
                    }
                }
                PlotElement::ScatterPoints(points) => {
                    for point in points {
                        x_min = x_min.min(point.x);
                        x_max = x_max.max(point.x);
                        y_min = y_min.min(point.y);
                        y_max = y_max.max(point.y);
                    }
                }
                PlotElement::Marker(marker) => {
                    x_min = x_min.min(marker.x);
                    x_max = x_max.max(marker.x);
                    y_min = y_min.min(marker.y);
                    y_max = y_max.max(marker.y);
                }
            }
        }

        // Add padding (10% on each side)
        let x_range = x_max - x_min;
        let y_range = y_max - y_min;

        if x_range > 0.0 {
            x_min -= x_range * 0.1;
            x_max += x_range * 0.1;
        } else {
            x_min = 0.0;
            x_max = 1.0;
        }

        if y_range > 0.0 {
            y_min -= y_range * 0.1;
            y_max += y_range * 0.1;
        } else {
            y_min = 0.0;
            y_max = 1.0;
        }

        let x_range = self.config.x_range.unwrap_or((x_min, x_max));
        let y_range = self.config.y_range.unwrap_or((y_min, y_max));

        (x_range, y_range)
    }

    /// Transform data coordinates to screen coordinates
    fn data_to_screen(&self, x: f64, y: f64, x_range: (f64, f64), y_range: (f64, f64)) -> Option<(usize, usize)> {
        let (x_min, x_max) = x_range;
        let (y_min, y_max) = y_range;

        if x < x_min || x > x_max || y < y_min || y > y_max {
            return None;
        }

        let screen_x = ((x - x_min) / (x_max - x_min) * self.config.width as f64) as usize;
        let screen_y = self.config.height - 1 -
                       ((y - y_min) / (y_max - y_min) * self.config.height as f64) as usize;

        Some((screen_x.min(self.config.width - 1), screen_y.min(self.config.height - 1)))
    }

    /// Internal method to render the plot without clearing
    fn render_plot(&self) -> String {
        let mut output = String::new();
        let scheme = &self.config.color_scheme;

        // Calculate ranges
        let (x_range, y_range) = self.calculate_ranges();

        // Render title and progress bar
        output.push_str(scheme.title.to_ansi());
        output.push_str("  ");
        output.push_str(&self.config.title);
        output.push_str("              ");

        if let Some(ref progress) = self.progress {
            let progress_width = 10;
            let filled = (progress.current as f64 / progress.total as f64 * progress_width as f64) as usize;
            output.push('[');
            output.push_str(scheme.progress_bar.to_ansi());
            for _ in 0..filled {
                output.push('▰');
            }
            output.push_str(Color::BrightBlack.to_ansi());
            for _ in filled..progress_width {
                output.push('▱');
            }
            output.push_str(scheme.title.to_ansi());
            output.push_str(&format!("] {}/{}", progress.current, progress.total));
        }

        output.push_str(Color::Reset.to_ansi());
        output.push('\n');

        // Render top border
        output.push_str(scheme.border.to_ansi());
        output.push_str("  ");
        for _ in 0..(self.config.width + 12) {
            output.push('━');
        }
        output.push_str(Color::Reset.to_ansi());
        output.push('\n');

        // Create a 2D grid for the plot area
        let mut grid: Vec<Vec<Option<(char, Color)>>> = vec![vec![None; self.config.width]; self.config.height];

        // Render all elements onto the grid
        for element in &self.elements {
            match element {
                PlotElement::Line(line) => {
                    self.render_line_to_grid(&mut grid, line, x_range, y_range);
                }
                PlotElement::ScatterPoints(points) => {
                    self.render_scatter_to_grid(&mut grid, points, x_range, y_range);
                }
                PlotElement::Marker(marker) => {
                    self.render_marker_to_grid(&mut grid, marker, x_range, y_range);
                }
            }
        }

        // Determine label formatting based on y-range
        let y_span = (y_range.1 - y_range.0).abs();
        let max_abs_y = y_range.0.abs().max(y_range.1.abs());

        // Use scientific notation for very small or very large values
        let use_scientific = y_span < 0.01 || max_abs_y > 10000.0;

        // Determine decimal precision for regular notation
        let decimals = if y_span < 0.1 {
            2
        } else if y_span < 1.0 {
            2
        } else if y_span < 10.0 {
            1
        } else {
            0
        };

        // Render Y-axis and grid
        let mut last_label = String::new();
        for row in 0..self.config.height {
            // Y-axis label - only show every 2 rows and avoid duplicates
            if row % 2 == 0 {
                let y_value = y_range.1 - (row as f64 / self.config.height as f64) * (y_range.1 - y_range.0);

                let label = if use_scientific {
                    format!("{:.1e}", y_value)
                } else {
                    format!("{:.*}", decimals, y_value)
                };

                // Only render if different from last label (avoid duplicates)
                if label != last_label {
                    output.push_str(scheme.axis_labels.to_ansi());
                    output.push_str(&format!(" {:>7} ", label));
                    last_label = label;
                } else {
                    output.push_str(scheme.axis.to_ansi());
                    output.push_str("         ");
                }
            } else {
                output.push_str(scheme.axis.to_ansi());
                output.push_str("         ");
            }

            // Y-axis separator
            output.push_str(scheme.axis.to_ansi());
            output.push('┊');
            output.push_str(Color::Reset.to_ansi());

            // Plot area
            for col in 0..self.config.width {
                if let Some((ch, color)) = grid[row][col] {
                    output.push_str(color.to_ansi());
                    output.push(ch);
                    output.push_str(Color::Reset.to_ansi());
                } else {
                    output.push(' ');
                }
            }

            output.push('\n');
        }

        // Render X-axis
        output.push_str(scheme.axis.to_ansi());
        output.push_str("         └");
        for _ in 0..self.config.width {
            output.push('─');
        }
        output.push('→');
        output.push_str(Color::Reset.to_ansi());
        output.push('\n');

        // Render X-axis labels
        output.push_str(scheme.axis_labels.to_ansi());
        output.push_str("         ");
        let x_step = (x_range.1 - x_range.0) / 5.0;
        for i in 0..6 {
            let x_value = x_range.0 + i as f64 * x_step;
            if x_value >= 1000.0 {
                output.push_str(&format!("{:>5.0}k ", x_value / 1000.0));
            } else {
                output.push_str(&format!("{:>6.0} ", x_value));
            }
        }
        output.push_str(&self.config.x_label);
        output.push_str(Color::Reset.to_ansi());
        output.push('\n');

        // Bottom border
        output.push_str(scheme.border.to_ansi());
        output.push_str("  ");
        for _ in 0..(self.config.width + 12) {
            output.push('━');
        }
        output.push_str(Color::Reset.to_ansi());
        output.push('\n');

        // Footer lines
        for line in &self.footer_lines {
            output.push_str(scheme.title.to_ansi());
            output.push_str("  ");
            output.push_str(line);
            output.push_str(Color::Reset.to_ansi());
            output.push('\n');
        }

        output
    }

    fn render_line_to_grid(&self, grid: &mut Vec<Vec<Option<(char, Color)>>>, line: &Line, x_range: (f64, f64), y_range: (f64, f64)) {
        let ch = line.style.to_char();
        let color = line.color.unwrap_or(self.config.color_scheme.default);

        for i in 0..line.points.len() {
            if let Some((sx, sy)) = self.data_to_screen(line.points[i].0, line.points[i].1, x_range, y_range) {
                grid[sy][sx] = Some((ch, color));
            }

            // Interpolate between points for continuous line
            if i > 0 {
                let (x0, y0) = line.points[i - 1];
                let (x1, y1) = line.points[i];

                let steps = ((x1 - x0).abs().max((y1 - y0).abs()) * 10.0) as usize;
                for step in 0..steps {
                    let t = step as f64 / steps.max(1) as f64;
                    let x = x0 + (x1 - x0) * t;
                    let y = y0 + (y1 - y0) * t;

                    if let Some((sx, sy)) = self.data_to_screen(x, y, x_range, y_range) {
                        grid[sy][sx] = Some((ch, color));
                    }
                }
            }
        }
    }

    fn render_scatter_to_grid(&self, grid: &mut Vec<Vec<Option<(char, Color)>>>, points: &[ScatterPoint], x_range: (f64, f64), y_range: (f64, f64)) {
        for point in points {
            if let Some((sx, sy)) = self.data_to_screen(point.x, point.y, x_range, y_range) {
                let color = point.color.unwrap_or(self.config.color_scheme.default);
                grid[sy][sx] = Some((point.symbol, color));
            }
        }
    }

    fn render_marker_to_grid(&self, grid: &mut Vec<Vec<Option<(char, Color)>>>, marker: &Marker, x_range: (f64, f64), y_range: (f64, f64)) {
        if let Some((sx, sy)) = self.data_to_screen(marker.x, marker.y, x_range, y_range) {
            let color = marker.color.unwrap_or(self.config.color_scheme.default);
            grid[sy][sx] = Some((marker.symbol, color));
        }
    }
}

impl PlotBuilder {
    pub fn title(mut self, title: impl Into<String>) -> Self {
        self.config.title = title.into();
        self
    }

    pub fn x_label(mut self, label: impl Into<String>) -> Self {
        self.config.x_label = label.into();
        self
    }

    pub fn y_label(mut self, label: impl Into<String>) -> Self {
        self.config.y_label = label.into();
        self
    }

    pub fn width(mut self, width: usize) -> Self {
        self.config.width = width;
        self
    }

    pub fn height(mut self, height: usize) -> Self {
        self.config.height = height;
        self
    }

    pub fn x_range(mut self, min: f64, max: f64) -> Self {
        self.config.x_range = Some((min, max));
        self
    }

    pub fn y_range(mut self, min: f64, max: f64) -> Self {
        self.config.y_range = Some((min, max));
        self
    }

    pub fn color_scheme(mut self, scheme: ColorScheme) -> Self {
        self.config.color_scheme = scheme;
        self
    }

    pub fn build(self) -> TerminalPlot {
        TerminalPlot {
            config: self.config,
            elements: Vec::new(),
            progress: None,
            footer_lines: Vec::new(),
            has_rendered: false,
        }
    }
}

impl fmt::Display for TerminalPlot {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.render_plot())
    }
}
