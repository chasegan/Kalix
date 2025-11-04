/// Optimisation wrapper that makes a Model optimisable
///
/// This module wraps a hydrological Model with optimisation-specific information:
/// - Parameter mappings (genes -> model parameters)
/// - Observed data for comparison (with temporal alignment)
/// - Objective function selection
///
/// The wrapper implements the Optimisable trait, presenting a simple normalised
/// parameter interface to optimisation algorithms.

use crate::model::Model;
use crate::nodes::NodeEnum;
use crate::timeseries::Timeseries;
use super::optimisable::Optimisable;
use super::optimisable_component::OptimisableComponent;
use super::parameter_mapping::ParameterMappingConfig;
use super::objectives::ObjectiveFunction;

/// A pair of observed and simulated series for comparison
///
/// Stores observed timeseries (with timestamps) and the name of the
/// simulated series to extract from the model for comparison.
#[derive(Clone)]
pub struct ComparisonPair {
    /// Observed timeseries (includes timestamps and values)
    pub observed: Timeseries,

    /// Name of simulated series to compare (e.g., "node.sacramento_a.dsflow")
    pub simulated_series_name: String,
}

/// Wraps a Model to make it Optimisable
///
/// # Example
/// ```ignore
/// let config = ParameterMappingConfig::from_strings(vec![
///     "node.sacramento_a.lztwm = log_range(g(1), 50, 300)",
///     "node.sacramento_a.uzk = log_range(g(2), 0.1, 0.7)",
/// ])?;
///
/// let comparison = ComparisonPair {
///     observed: observed_timeseries,
///     simulated_series_name: "node.sacramento_a.dsflow".to_string(),
/// };
///
/// let problem = OptimisationProblem::new(
///     model,
///     config,
///     vec![comparison],
/// );
/// ```
pub struct OptimisationProblem {
    /// The hydrological model
    pub model: Model,

    /// Gene-based parameter configuration
    pub config: ParameterMappingConfig,

    /// Comparison pairs (observed vs simulated series)
    /// Supports multiple pairs for multi-objective optimization
    pub comparisons: Vec<ComparisonPair>,

    /// Objective function to use
    pub objective: ObjectiveFunction,
}

impl OptimisationProblem {
    /// Create a new optimisation problem
    pub fn new(
        model: Model,
        config: ParameterMappingConfig,
        comparisons: Vec<ComparisonPair>,
    ) -> Self {
        use crate::numerical::opt::objectives::NseObjective;
        Self {
            model,
            config,
            comparisons,
            objective: ObjectiveFunction::NashSutcliffe(NseObjective::new()),
        }
    }

    /// Create a single-comparison problem (convenience method for backward compatibility)
    pub fn single_comparison(
        model: Model,
        config: ParameterMappingConfig,
        observed: Timeseries,
        simulated_series_name: String,
    ) -> Self {
        Self::new(
            model,
            config,
            vec![ComparisonPair {
                observed,
                simulated_series_name,
            }],
        )
    }

    /// Set the objective function
    pub fn with_objective(mut self, objective: ObjectiveFunction) -> Self {
        self.objective = objective;
        self
    }

    /// Apply parameter values to the model
    ///
    /// This maps from genes to model parameters using the ParameterMappingConfig,
    /// then sets each parameter on the appropriate component (node or constant).
    ///
    /// Supports two address formats:
    /// - "node.name.param" - for node parameters
    /// - "c.blah.blah.blah" - for constants
    fn apply_params_to_model(&mut self, genes: &[f64]) -> Result<(), String> {
        // Evaluate all mappings: genes -> (target, physical_value)
        let param_values = self.config.evaluate(genes);

        // Apply each parameter to the model
        for (target, value) in param_values {
            // Parse target address
            let parts: Vec<&str> = target.split('.').collect();

            if parts.len() >= 2 && parts[0] == "c" {
                // Handle constant: "c.something"
                self.model.data_cache.set_param(&target, value)
                    .map_err(|e| format!("Error setting constant {}: {}", &target, e))?;
            } else if parts.len() == 3 && parts[0] == "node" {
                // Handle node parameter: "node.name.param"
                let node_name = parts[1];
                let param_name = parts[2];

                // Get node index
                let node_idx = self
                    .model
                    .get_node_idx(node_name)
                    .ok_or_else(|| format!("Node not found: {}", node_name))?;

                // Set parameter on the node using OptimisableComponent trait
                match &mut self.model.nodes[node_idx] {
                    NodeEnum::SacramentoNode(node) => {
                        node.set_param(param_name, value)
                            .map_err(|e| format!("Error setting {}.{}: {}", node_name, param_name, e))?;
                    }
                    NodeEnum::Gr4jNode(node) => {
                        node.set_param(param_name, value)
                            .map_err(|e| format!("Error setting {}.{}: {}", node_name, param_name, e))?;
                    }
                    _ => {
                        return Err(format!(
                            "Node '{}' (type: {}) does not support parameter optimisation",
                            node_name,
                            self.model.nodes[node_idx].get_type_as_string()
                        ));
                    }
                }
            } else {
                return Err(format!("Invalid target address: '{}'. Expected 'node.name.param' or 'c.constant_name'", target));
            }
        }

        Ok(())
    }

    /// Align observed and simulated timeseries temporally
    ///
    /// Returns aligned (observed, simulated) vectors that only include timesteps
    /// where both series have data.
    fn align_timeseries(
        &self,
        observed: &Timeseries,
        simulated: &Timeseries,
    ) -> Result<(Vec<f64>, Vec<f64>), String> {
        let mut aligned_obs = Vec::new();
        let mut aligned_sim = Vec::new();

        // Create lookup map for simulated data
        let sim_map: std::collections::HashMap<u64, f64> = simulated
            .timestamps
            .iter()
            .zip(&simulated.values)
            .map(|(&t, &v)| (t, v))
            .collect();

        // Iterate through observed timestamps and find matches
        for (&obs_time, &obs_value) in observed.timestamps.iter().zip(&observed.values) {
            // Look for matching timestamp in simulated
            if let Some(&sim_value) = sim_map.get(&obs_time) {
                aligned_obs.push(obs_value);
                aligned_sim.push(sim_value);
            }
        }

        if aligned_obs.is_empty() {
            return Err(format!(
                "No overlapping timestamps found between observed ({}..{}) and simulated ({}..{}) data",
                observed.timestamps.first().unwrap_or(&0),
                observed.timestamps.last().unwrap_or(&0),
                simulated.timestamps.first().unwrap_or(&0),
                simulated.timestamps.last().unwrap_or(&0),
            ));
        }

        Ok((aligned_obs, aligned_sim))
    }

    /// Extract current parameter values from model
    ///
    /// Used for warm starts - reads current model state and normalizes to [0,1]
    fn extract_current_genes(&self) -> Vec<f64> {
        // For now, return mid-range values
        // TODO: Extract actual values from model and normalize via transform.invert()
        vec![0.5; self.config.n_genes()]
    }
}

impl Optimisable for OptimisationProblem {
    fn n_params(&self) -> usize {
        self.config.n_genes()
    }

    fn set_params(&mut self, genes: &[f64]) -> Result<(), String> {
        if genes.len() != self.n_params() {
            return Err(format!(
                "Expected {} parameters, got {}",
                self.n_params(),
                genes.len()
            ));
        }

        self.apply_params_to_model(genes)
    }

    fn get_params(&self) -> Vec<f64> {
        self.extract_current_genes()
    }

    fn evaluate(&mut self) -> Result<f64, String> {
        // Configure model if needed (first time)
        if self.model.execution_order.is_empty() {
            self.model.configure()?;
        }

        // Run the model
        self.model.run()?;

        // For now, only support single comparison (multi-objective in future)
        if self.comparisons.len() != 1 {
            return Err(format!(
                "Currently only single comparison is supported, got {} comparisons",
                self.comparisons.len()
            ));
        }

        let comparison = &self.comparisons[0];

        // Extract simulated timeseries from model
        let sim_idx = self
            .model
            .data_cache
            .get_series_idx(&comparison.simulated_series_name, false)
            .ok_or_else(|| {
                format!(
                    "Simulated series not found: {}",
                    comparison.simulated_series_name
                )
            })?;

        let simulated_ts = &self.model.data_cache.series[sim_idx];

        // Align observed and simulated temporally
        let (aligned_obs, aligned_sim) = self.align_timeseries(&comparison.observed, simulated_ts)?;

        // Calculate objective using aligned data
        self.objective.calculate(&aligned_obs, &aligned_sim)
    }

    fn param_names(&self) -> Vec<String> {
        self.config.gene_names()
    }

    fn clone_for_parallel(&self) -> Box<dyn Optimisable> {
        Box::new(Self {
            model: self.model.clone(),
            config: self.config.clone(),
            comparisons: self.comparisons.clone(),
            objective: self.objective.clone(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_optimisation_problem_creation() {
        let model = Model::new();
        let config = ParameterMappingConfig::new();
        let mut observed = Timeseries::new_daily();
        observed.push(0, 1.0);
        observed.push(1, 2.0);
        observed.push(2, 3.0);

        let problem = OptimisationProblem::single_comparison(
            model,
            config,
            observed,
            "node.test.output".to_string(),
        );

        assert_eq!(problem.objective.name(), "NSE");
    }

    #[test]
    fn test_with_objective() {
        let model = Model::new();
        let config = ParameterMappingConfig::new();
        let mut observed = Timeseries::new_daily();
        observed.push(0, 1.0);
        observed.push(1, 2.0);
        observed.push(2, 3.0);

        use crate::numerical::opt::objectives::KgeObjective;
        let problem = OptimisationProblem::single_comparison(
            model,
            config,
            observed,
            "node.test.output".to_string(),
        )
        .with_objective(ObjectiveFunction::KlingGupta(KgeObjective::new()));

        assert_eq!(problem.objective.name(), "KGE");
    }
}
