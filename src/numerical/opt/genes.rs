/// Gene object that backs the `g(i)` function in parameter expressions.
///
/// During optimisation, parameter expressions like `lin_range(g(1), 10, 2000)` are
/// evaluated against a [`Gene`] holding the optimiser's current population member as
/// a sparse `usize -> f64` map. Indices are 1-based and may be non-contiguous — the
/// modeller is free to use `g(1)` and `g(3)` and skip `g(2)`.
///
/// ## Two-phase lifecycle
///
/// - **Discovery** (set via [`Gene::set_mode`] with [`GeneMode::Discovery`]):
///   every call to [`Gene::get`] registers the requested index in the map and
///   returns a placeholder value (0.5) so the surrounding expression can still
///   evaluate without error. After the host evaluates each parameter expression
///   exactly once, the gene knows the full set of indices the modeller referenced
///   — that's the optimisation problem's dimensionality.
///
/// - **Run** (set via [`Gene::set_mode`] with [`GeneMode::Run`]):
///   [`Gene::get`] reads the value the optimiser most recently wrote into the map
///   via [`Gene::set_values`]. Calling `g(i)` for an index that wasn't registered
///   during discovery is an error.
///
/// ## Thread safety
///
/// Internally uses `Mutex` so a `Gene` is `Sync`, allowing `OptimisationProblem`
/// (which carries an `Arc<Gene>` via [`crate::numerical::opt::parameter_mapping::ParameterMappingConfig`])
/// to satisfy the `Send` bound on the [`crate::numerical::opt::optimisable::Optimisable`] trait.
/// Each parallel worker is expected to hold its own deep-cloned `Gene` (see
/// [`Gene::deep_clone`]), so the lock is uncontended in practice.

use std::collections::BTreeMap;
use std::sync::Mutex;
use crate::functions::EvaluationError;

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum GeneMode {
    Discovery,
    Run,
}

#[derive(Debug)]
struct GeneState {
    /// BTreeMap to keep iteration in sorted-ascending key order — gives the
    /// optimiser a deterministic mapping from population-vector index to gene index.
    values: BTreeMap<usize, f64>,
    mode: GeneMode,
}

#[derive(Debug)]
pub struct Gene {
    state: Mutex<GeneState>,
}

impl Gene {
    pub fn new() -> Self {
        Self {
            state: Mutex::new(GeneState {
                values: BTreeMap::new(),
                mode: GeneMode::Discovery,
            }),
        }
    }

    /// Function-form lookup: `g(i_arg)`.
    ///
    /// Returns 0.5 (placeholder) and registers `i_arg` during discovery.
    /// In run mode, looks up the stored value. Errors if `i_arg` is not a positive
    /// integer, or if it wasn't registered during discovery.
    pub fn get(&self, i_arg: f64) -> Result<f64, EvaluationError> {
        let i = Self::validate_index(i_arg)?;
        let mut state = self.state.lock().unwrap();
        match state.mode {
            GeneMode::Discovery => {
                state.values.entry(i).or_insert(0.5);
                Ok(0.5)
            }
            GeneMode::Run => match state.values.get(&i) {
                Some(v) => Ok(*v),
                None => Err(EvaluationError::InvalidOperation {
                    message: format!(
                        "g({}) was not registered during discovery — \
                         this index is not part of the optimisation problem",
                        i
                    ),
                }),
            },
        }
    }

    /// Validate that the argument to `g()` is a clean positive integer.
    ///
    /// This is the boundary where non-integer or non-positive indices are rejected.
    fn validate_index(i_arg: f64) -> Result<usize, EvaluationError> {
        if !i_arg.is_finite() {
            return Err(EvaluationError::InvalidOperation {
                message: format!("g(i) requires a positive integer index, got {}", i_arg),
            });
        }
        if i_arg.fract() != 0.0 {
            return Err(EvaluationError::InvalidOperation {
                message: format!("g(i) requires a positive integer index, got {}", i_arg),
            });
        }
        if i_arg < 1.0 {
            return Err(EvaluationError::InvalidOperation {
                message: format!("g(i) requires a positive integer index (>= 1), got {}", i_arg as i64),
            });
        }
        Ok(i_arg as usize)
    }

    pub fn set_mode(&self, mode: GeneMode) {
        self.state.lock().unwrap().mode = mode;
    }

    pub fn mode(&self) -> GeneMode {
        self.state.lock().unwrap().mode
    }

    /// Registered gene indices in ascending order.
    pub fn dimensions(&self) -> Vec<usize> {
        self.state.lock().unwrap().values.keys().copied().collect()
    }

    /// Number of registered dimensions.
    pub fn n_dimensions(&self) -> usize {
        self.state.lock().unwrap().values.len()
    }

    /// Write the optimiser's population vector into the map, by dimension order
    /// (matching `dimensions()`).
    ///
    /// Panics if `values.len()` does not equal `n_dimensions()`.
    pub fn set_values(&self, values: &[f64]) {
        let mut state = self.state.lock().unwrap();
        assert_eq!(
            values.len(),
            state.values.len(),
            "Gene::set_values expects {} values, got {}",
            state.values.len(),
            values.len()
        );
        let keys: Vec<usize> = state.values.keys().copied().collect();
        for (k, v) in keys.iter().zip(values.iter()) {
            state.values.insert(*k, *v);
        }
    }

    /// Human-readable names like `["g(1)", "g(3)"]` in dimension order.
    pub fn gene_names(&self) -> Vec<String> {
        self.state.lock().unwrap().values.keys().map(|i| format!("g({})", i)).collect()
    }

    /// Deep-clone for parallel evaluation: keeps the registered indices and current
    /// values but produces an independent map so writes in one worker don't bleed
    /// into another.
    pub fn deep_clone(&self) -> Self {
        let state = self.state.lock().unwrap();
        Self {
            state: Mutex::new(GeneState {
                values: state.values.clone(),
                mode: state.mode,
            }),
        }
    }
}

impl Default for Gene {
    fn default() -> Self { Self::new() }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn discovery_then_run_with_sparse_indices() {
        let gene = Gene::new();
        assert_eq!(gene.mode(), GeneMode::Discovery);
        let v1 = gene.get(1.0).unwrap();
        let v3 = gene.get(3.0).unwrap();
        assert_eq!(v1, 0.5);
        assert_eq!(v3, 0.5);
        assert_eq!(gene.dimensions(), vec![1, 3]);

        gene.set_mode(GeneMode::Run);
        gene.set_values(&[0.2, 0.7]);
        assert_eq!(gene.get(1.0).unwrap(), 0.2);
        assert_eq!(gene.get(3.0).unwrap(), 0.7);
    }

    #[test]
    fn non_integer_index_errors() {
        let gene = Gene::new();
        let err = gene.get(2.5).unwrap_err();
        match err {
            EvaluationError::InvalidOperation { message } => {
                assert!(message.contains("positive integer"), "got: {}", message);
            }
            _ => panic!("expected InvalidOperation, got {:?}", err),
        }
    }

    #[test]
    fn zero_or_negative_index_errors() {
        let gene = Gene::new();
        assert!(gene.get(0.0).is_err());
        assert!(gene.get(-1.0).is_err());
    }

    #[test]
    fn unknown_index_in_run_mode_errors() {
        let gene = Gene::new();
        gene.get(1.0).unwrap();
        gene.set_mode(GeneMode::Run);
        let err = gene.get(2.0).unwrap_err();
        match err {
            EvaluationError::InvalidOperation { message } => {
                assert!(message.contains("not registered"), "got: {}", message);
            }
            _ => panic!("expected InvalidOperation, got {:?}", err),
        }
    }

    #[test]
    fn gene_names_match_dimensions() {
        let gene = Gene::new();
        gene.get(5.0).unwrap();
        gene.get(2.0).unwrap();
        gene.get(8.0).unwrap();
        assert_eq!(gene.dimensions(), vec![2, 5, 8]);
        assert_eq!(gene.gene_names(), vec!["g(2)", "g(5)", "g(8)"]);
    }

    #[test]
    fn deep_clone_is_independent() {
        let gene = Gene::new();
        gene.get(1.0).unwrap();
        gene.get(2.0).unwrap();
        gene.set_mode(GeneMode::Run);
        gene.set_values(&[0.1, 0.2]);

        let cloned = gene.deep_clone();
        cloned.set_values(&[0.9, 0.9]);
        assert_eq!(gene.get(1.0).unwrap(), 0.1);
        assert_eq!(cloned.get(1.0).unwrap(), 0.9);
    }
}
