/// Optimisation wrapper that makes a Model optimisable
///
/// This module wraps a hydrological Model with optimisation-specific information:
/// - Parameter mappings (genes -> model parameters)
/// - One or more comparison pairs (observed/simulated/statistic terms)
/// - A composite objective expression over the per-term losses
///
/// The wrapper implements the Optimisable trait, presenting a simple normalised
/// parameter interface to optimisation algorithms.

use std::collections::HashMap;
use crate::model::Model;
use crate::nodes::NodeEnum;
use crate::timeseries::Timeseries;
use crate::functions::{ParsedFunction, VariableContext, EvaluationConfig, parse_function};
use super::optimisable::Optimisable;
use super::optimisable_component::OptimisableComponent;
use super::parameter_mapping::ParameterMappingConfig;
use super::objectives::ObjectiveFunction;

/// One term in a composite optimisation objective
///
/// Pairs an observed timeseries with a named simulated series and the statistic
/// used to compare them. The per-term loss is exposed in the objective expression
/// under [`ComparisonPair::name`].
#[derive(Clone)]
pub struct ComparisonPair {
    /// Term name, used as a variable in the objective expression
    pub name: String,

    /// Observed timeseries (includes timestamps and values)
    pub observed: Timeseries,

    /// Name of simulated series to compare (e.g., "node.sacramento_a.dsflow")
    pub simulated_series_name: String,

    /// Statistic to compute over this (observed, simulated) pair (all return lower-better loss)
    pub statistic: ObjectiveFunction,
}

/// Wraps a Model to make it Optimisable
///
/// # Example
/// ```ignore
/// let config = ParameterMappingConfig::from_strings(vec![
///     "node.sacramento_a.lztwm = log_range(g(1), 50, 300)",
/// ])?;
///
/// let comparison = ComparisonPair {
///     name: "term1".to_string(),
///     observed: observed_timeseries,
///     simulated_series_name: "node.sacramento_a.dsflow".to_string(),
///     statistic: ObjectiveFunction::OneMinusNse(NseObjective::new()),
/// };
///
/// let expression = parse_function("term1").unwrap();
/// let problem = OptimisationProblem::new(model, config, vec![comparison], expression);
/// ```
pub struct OptimisationProblem {
    /// The hydrological model
    pub model: Model,

    /// Gene-based parameter configuration
    pub config: ParameterMappingConfig,

    /// Comparison pairs (one per term)
    pub comparisons: Vec<ComparisonPair>,

    /// Composite objective expression over per-term losses
    pub expression: ParsedFunction,
}

impl OptimisationProblem {
    /// Create a new optimisation problem
    pub fn new(
        model: Model,
        config: ParameterMappingConfig,
        comparisons: Vec<ComparisonPair>,
        expression: ParsedFunction,
    ) -> Self {
        Self { model, config, comparisons, expression }
    }

    /// Create a single-comparison problem with a trivial expression of just the term name
    pub fn single_comparison(
        model: Model,
        config: ParameterMappingConfig,
        observed: Timeseries,
        simulated_series_name: String,
        statistic: ObjectiveFunction,
    ) -> Self {
        let expression = parse_function("term1").expect("trivial expression parses");
        Self::new(
            model,
            config,
            vec![ComparisonPair {
                name: "term1".to_string(),
                observed,
                simulated_series_name,
                statistic,
            }],
            expression,
        )
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

        // Compute each term's loss and stash by term name for expression evaluation
        let mut term_values: HashMap<String, f64> = HashMap::with_capacity(self.comparisons.len());
        for comparison in &self.comparisons {
            let sim_idx = self
                .model
                .data_cache
                .get_series_idx(&comparison.simulated_series_name, false)
                .ok_or_else(|| {
                    format!(
                        "Simulated series not found for term '{}': {}",
                        comparison.name, comparison.simulated_series_name
                    )
                })?;

            let simulated_ts = &self.model.data_cache.series[sim_idx];
            let (aligned_obs, aligned_sim) = self.align_timeseries(&comparison.observed, simulated_ts)
                .map_err(|e| format!("In term '{}': {}", comparison.name, e))?;

            let value = comparison.statistic.calculate(&aligned_obs, &aligned_sim)
                .map_err(|e| format!("In term '{}': {}", comparison.name, e))?;
            term_values.insert(comparison.name.clone(), value);
        }

        // Evaluate the composite expression against the per-term losses
        let eval_config = EvaluationConfig::default();
        let context = VariableContext::new(&term_values, &eval_config);
        self.expression.evaluate(&context)
            .map_err(|e| format!("Failed to evaluate objective_expression: {}", e))
    }

    fn param_names(&self) -> Vec<String> {
        self.config.gene_names()
    }

    fn clone_for_parallel(&self) -> Box<dyn Optimisable> {
        Box::new(Self {
            model: self.model.clone(),
            config: self.config.clone(),
            comparisons: self.comparisons.clone(),
            expression: self.expression.clone(),
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::numerical::opt::objectives::{NseObjective, KgeObjective};

    fn obs_fixture() -> Timeseries {
        let mut observed = Timeseries::new_daily();
        observed.push(0, 1.0);
        observed.push(1, 2.0);
        observed.push(2, 3.0);
        observed
    }

    #[test]
    fn test_optimisation_problem_creation_nse() {
        let problem = OptimisationProblem::single_comparison(
            Model::new(),
            ParameterMappingConfig::new(),
            obs_fixture(),
            "node.test.output".to_string(),
            ObjectiveFunction::OneMinusNse(NseObjective::new()),
        );
        assert_eq!(problem.comparisons.len(), 1);
        assert_eq!(problem.comparisons[0].name, "term1");
        assert_eq!(problem.comparisons[0].statistic.name(), "ONE_MINUS_NSE");
    }

    #[test]
    fn test_optimisation_problem_creation_kge() {
        let problem = OptimisationProblem::single_comparison(
            Model::new(),
            ParameterMappingConfig::new(),
            obs_fixture(),
            "node.test.output".to_string(),
            ObjectiveFunction::OneMinusKge(KgeObjective::new()),
        );
        assert_eq!(problem.comparisons[0].statistic.name(), "ONE_MINUS_KGE");
    }

    #[test]
    fn test_composite_expression_two_terms() {
        // Build a problem with two comparisons; evaluate the expression manually
        // against synthetic term-value HashMap to verify the wiring.
        use std::collections::HashMap;
        let expression = parse_function("term1 + 0.5 * term2").unwrap();
        let mut values: HashMap<String, f64> = HashMap::new();
        values.insert("term1".to_string(), 0.2);
        values.insert("term2".to_string(), 0.4);

        let cfg = EvaluationConfig::default();
        let context = VariableContext::new(&values, &cfg);
        let result = expression.evaluate(&context).unwrap();
        assert!((result - (0.2 + 0.5 * 0.4)).abs() < 1e-12);
    }
}
