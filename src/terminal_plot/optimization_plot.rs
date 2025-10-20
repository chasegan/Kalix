//! Optimization progress visualization
//!
//! Provides a specialized plot for visualizing optimization progress with:
//! - Best objective function evolution over time
//! - Current generation population scatter points
//! - Progress tracking and timing information

use super::*;
use crate::numerical::opt::DEProgress;

/// Specialized plot for tracking optimization progress
pub struct OptimizationPlot {
    plot: TerminalPlot,
    history: Vec<(f64, f64)>,  // (evaluation, objective) pairs
    termination_evaluations: usize,
}

impl OptimizationPlot {
    /// Create a new optimization plot
    pub fn new(
        title: impl Into<String>,
        termination_evaluations: usize,
        width: usize,
        height: usize,
    ) -> Self {
        let plot = TerminalPlot::builder()
            .title(title)
            .x_label("evals")
            .y_label("Objective Function")
            .width(width)
            .height(height)
            .color_scheme(ColorScheme::electric_grid())
            .build();

        Self {
            plot,
            history: Vec::new(),
            termination_evaluations,
        }
    }

    /// Update the plot with progress from the optimizer
    pub fn update_from_progress(&mut self, progress: &DEProgress) {
        // Clear previous elements and footer
        self.plot.clear_elements();
        self.plot.clear_footer();

        // Update history
        self.history.push((progress.n_evaluations as f64, progress.best_objective));

        // Set progress bar
        self.plot.set_progress(progress.n_evaluations, self.termination_evaluations);

        // Add best evolution line
        if !self.history.is_empty() {
            self.plot.add_line(Line {
                points: self.history.clone(),
                style: LineStyle::Dots,
                color: Some(Color::BrightMagenta),
            });
        }

        // Add current population scatter points
        if let Some(ref pop_objectives) = progress.population_objectives {
            let scatter_points: Vec<ScatterPoint> = pop_objectives
                .iter()
                .map(|&obj| ScatterPoint {
                    x: progress.n_evaluations as f64,
                    y: obj,
                    color: Some(Color::BrightYellow),
                    symbol: '∘',
                })
                .collect();
            self.plot.add_scatter_points(scatter_points);
        }

        // Add best marker
        self.plot.add_marker(Marker {
            x: progress.n_evaluations as f64,
            y: progress.best_objective,
            symbol: '★',
            color: Some(Color::BrightGreen),
            label: Some(format!("← BEST: {:.6}", progress.best_objective)),
        });

        // Add footer information
        self.plot.add_footer_line(format!("Best: {:.6}", progress.best_objective));
        self.plot.add_footer_line(format!("Time: {:.1}s", progress.elapsed.as_secs_f64()));
    }

    /// Render the final optimization result
    pub fn render_final(&mut self, best_objective: f64, n_evaluations: usize, elapsed: std::time::Duration) {
        // Clear previous elements and footer
        self.plot.clear_elements();
        self.plot.clear_footer();

        // Set progress bar to 100%
        self.plot.set_progress(n_evaluations, self.termination_evaluations);

        // Add best evolution line with final point
        let mut final_history = self.history.clone();
        final_history.push((n_evaluations as f64, best_objective));
        self.plot.add_line(Line {
            points: final_history,
            style: LineStyle::Dots,
            color: Some(Color::BrightMagenta),
        });

        // Add best marker at final position
        self.plot.add_marker(Marker {
            x: n_evaluations as f64,
            y: best_objective,
            symbol: '★',
            color: Some(Color::BrightGreen),
            label: Some(format!("← BEST: {:.6}", best_objective)),
        });

        // Add footer information
        self.plot.add_footer_line(format!("Best: {:.6}", best_objective));
        self.plot.add_footer_line(format!("Time: {:.1}s", elapsed.as_secs_f64()));
    }

    /// Render the plot (automatically handles clearing/redrawing)
    pub fn render(&mut self) -> String {
        self.plot.render()
    }
}
