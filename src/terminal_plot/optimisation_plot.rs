//! Optimisation progress visualisation
//!
//! Provides a specialised plot for visualising optimisation progress with:
//! - Best objective function evolution over time
//! - Current generation population scatter points
//! - Progress tracking and timing information

use super::*;
use crate::numerical::opt::OptimizationProgress;

/// Specialised plot for tracking optimisation progress
pub struct OptimisationPlot {
    plot: TerminalPlot,
    history: Vec<(f64, f64)>,  // (evaluation, objective) pairs
    all_scatter_points: Vec<ScatterPoint>,  // All generation scatter points
    termination_evaluations: usize,
}

impl OptimisationPlot {
    /// Create a new optimisation plot
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
            all_scatter_points: Vec::new(),
            termination_evaluations,
        }
    }

    /// Calculate x-range with minimum clamped to 0
    fn calculate_x_range(&self) -> (f64, f64) {
        let mut x_max = 0.0f64;

        // Find max from history
        for &(x, _) in &self.history {
            x_max = x_max.max(x);
        }

        // Find max from scatter points
        for point in &self.all_scatter_points {
            x_max = x_max.max(point.x);
        }

        // Add 10% padding to max
        let x_range = x_max - 0.0;
        if x_range > 0.0 {
            x_max += x_range * 0.1;
        } else {
            x_max = 1.0;
        }

        (0.0, x_max)  // Always start at 0
    }

    /// Update the plot with progress from the optimiser
    pub fn update_from_progress(&mut self, progress: &OptimizationProgress) {
        // Clear previous elements and footer
        self.plot.clear_elements();
        self.plot.clear_footer();

        // Update history
        self.history.push((progress.n_evaluations as f64, progress.best_objective));

        // Set progress bar
        self.plot.set_progress(progress.n_evaluations, self.termination_evaluations);

        // Set x-range with minimum clamped to 0
        let (x_min, x_max) = self.calculate_x_range();
        self.plot.set_x_range(x_min, x_max);

        // Add best evolution line
        if !self.history.is_empty() {
            self.plot.add_line(Line {
                points: self.history.clone(),
                style: LineStyle::Dots,
                color: Some(Color::BrightMagenta),
            });
        }

        // Add current generation/iteration scatter points to accumulated collection
        if let Some(ref pop_objectives) = progress.population_objectives {
            let new_scatter_points: Vec<ScatterPoint> = pop_objectives
                .iter()
                .map(|&obj| ScatterPoint {
                    x: progress.n_evaluations as f64,
                    y: obj,
                    color: Some(Color::BrightYellow),
                    symbol: '∘',
                })
                .collect();
            self.all_scatter_points.extend(new_scatter_points);
        }

        // Render all accumulated scatter points
        if !self.all_scatter_points.is_empty() {
            self.plot.add_scatter_points(self.all_scatter_points.clone());
        }

        // Add best marker
        self.plot.add_marker(Marker {
            x: progress.n_evaluations as f64,
            y: progress.best_objective,
            symbol: '★',
            color: Some(Color::BrightGreen),
            label: Some(format!("← BEST: {:.6}", progress.best_objective)),
        });

        // Add footer information (generic across all algorithms)
        self.plot.add_footer_line(format!("Best: {:.6}", progress.best_objective));
        self.plot.add_footer_line(format!("Time: {:.1}s", progress.elapsed.as_secs_f64()));
    }

    /// Render the final optimisation result
    pub fn render_final(&mut self, best_objective: f64, n_evaluations: usize, elapsed: std::time::Duration) {
        // Clear previous elements and footer
        self.plot.clear_elements();
        self.plot.clear_footer();

        // Set progress bar to 100%
        self.plot.set_progress(n_evaluations, self.termination_evaluations);

        // Set x-range with minimum clamped to 0
        let (x_min, x_max) = self.calculate_x_range();
        self.plot.set_x_range(x_min, x_max.max(n_evaluations as f64 * 1.1));

        // Add best evolution line with final point
        let mut final_history = self.history.clone();
        final_history.push((n_evaluations as f64, best_objective));
        self.plot.add_line(Line {
            points: final_history,
            style: LineStyle::Dots,
            color: Some(Color::BrightMagenta),
        });

        // Render all accumulated scatter points
        if !self.all_scatter_points.is_empty() {
            self.plot.add_scatter_points(self.all_scatter_points.clone());
        }

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
