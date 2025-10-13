/// Trait for problems that can be optimized
///
/// This trait provides a generic interface for optimization algorithms.
/// The optimizer only sees normalized parameters [0,1] and a fitness value.
/// It knows nothing about the underlying problem domain (hydrology, etc.)
pub trait Optimisable: Send {
    /// Number of parameters to optimize
    fn n_params(&self) -> usize;

    /// Set parameters (normalized [0,1])
    ///
    /// # Arguments
    /// * `params` - Slice of normalized parameter values, one per optimization parameter
    fn set_params(&mut self, params: &[f64]) -> Result<(), String>;

    /// Get current parameters (normalized [0,1])
    ///
    /// Used for warm starts - returns the current parameter values
    fn get_params(&self) -> Vec<f64>;

    /// Evaluate objective function (lower = better)
    ///
    /// Returns the objective value to be minimized.
    /// This typically involves running a model and comparing to observations.
    fn evaluate(&mut self) -> Result<f64, String>;

    /// Get parameter names for reporting
    ///
    /// Returns human-readable names like "g(1)", "g(2)", etc.
    fn param_names(&self) -> Vec<String> {
        (0..self.n_params())
            .map(|i| format!("param_{}", i))
            .collect()
    }

    /// Clone for parallel evaluation
    ///
    /// Creates an independent copy that can be evaluated in parallel.
    /// Essential for population-based optimizers (DE, SCE-UA, etc.)
    fn clone_for_parallel(&self) -> Box<dyn Optimisable>;
}

/// Helper function to create n clones for parallel evaluation
pub fn clone_multi(problem: &dyn Optimisable, n: usize) -> Vec<Box<dyn Optimisable>> {
    (0..n).map(|_| problem.clone_for_parallel()).collect()
}
