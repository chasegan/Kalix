/// Common optimizer trait and types for all optimization algorithms
///
/// This module provides a unified interface for different optimization algorithms
/// (DE, CMA-ES, SCE-UA, etc.) with common progress reporting and result types.

use super::optimisable::Optimisable;
use std::time::Duration;
use std::collections::HashMap;

/// Progress information that works across all optimization algorithms
#[derive(Debug, Clone)]
pub struct OptimizationProgress {
    /// Total number of function evaluations performed so far
    pub n_evaluations: usize,

    /// Best objective value found so far (lower is better)
    pub best_objective: f64,

    /// Current population objective values (for diversity reporting)
    /// Used by population-based algorithms (DE, CMA-ES, etc.)
    pub population_objectives: Option<Vec<f64>>,

    /// Elapsed time since optimization started
    pub elapsed: Duration,

    /// Algorithm-specific metrics (e.g., generation number, step size, etc.)
    /// Keys might include: "generation", "sigma", "f_value", etc.
    pub algorithm_data: HashMap<String, f64>,
}

impl OptimizationProgress {
    /// Create a basic progress report with required fields
    pub fn new(n_evaluations: usize, best_objective: f64, elapsed: Duration) -> Self {
        Self {
            n_evaluations,
            best_objective,
            population_objectives: None,
            elapsed,
            algorithm_data: HashMap::new(),
        }
    }

    /// Add population objectives for diversity reporting
    pub fn with_population(mut self, objectives: Vec<f64>) -> Self {
        self.population_objectives = Some(objectives);
        self
    }

    /// Add algorithm-specific data
    pub fn with_data(mut self, key: impl Into<String>, value: f64) -> Self {
        self.algorithm_data.insert(key.into(), value);
        self
    }
}

/// Result of an optimization run (common across all algorithms)
#[derive(Debug, Clone)]
pub struct OptimizationResult {
    /// Best parameter values found (normalized [0,1])
    pub best_params: Vec<f64>,

    /// Best objective function value (lower is better)
    pub best_objective: f64,

    /// Total number of function evaluations performed
    pub n_evaluations: usize,

    /// Whether optimization terminated successfully
    pub success: bool,

    /// Termination message
    pub message: String,

    /// Total elapsed time
    pub elapsed: Duration,

    /// Algorithm-specific result data
    /// Can include convergence history, final state, etc.
    pub algorithm_data: HashMap<String, serde_json::Value>,
}

impl OptimizationResult {
    /// Create a basic result with required fields
    pub fn new(
        best_params: Vec<f64>,
        best_objective: f64,
        n_evaluations: usize,
        success: bool,
        message: impl Into<String>,
        elapsed: Duration,
    ) -> Self {
        Self {
            best_params,
            best_objective,
            n_evaluations,
            success,
            message: message.into(),
            elapsed,
            algorithm_data: HashMap::new(),
        }
    }

    /// Add algorithm-specific data to result
    pub fn with_data(mut self, key: impl Into<String>, value: serde_json::Value) -> Self {
        self.algorithm_data.insert(key.into(), value);
        self
    }
}

/// Common trait for all optimization algorithms
///
/// Implementations include DifferentialEvolution, CmaEs, SceUa, etc.
pub trait Optimizer: Send + Sync {
    /// Run optimization on the given problem
    ///
    /// # Arguments
    /// * `problem` - The optimization problem implementing Optimisable
    /// * `progress_callback` - Optional callback for progress updates
    ///
    /// # Returns
    /// OptimizationResult containing best parameters and metadata
    fn optimize(
        &self,
        problem: &mut dyn Optimisable,
        progress_callback: Option<Box<dyn Fn(&OptimizationProgress) + Send + Sync>>,
    ) -> OptimizationResult;

    /// Get the name of this optimizer (e.g., "DE", "CMA-ES", "SCE-UA")
    fn name(&self) -> &str;
}
