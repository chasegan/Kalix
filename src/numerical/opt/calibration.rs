/// Calibration wrapper that makes a Model optimisable
///
/// This module wraps a hydrological Model with calibration-specific information:
/// - Parameter mappings (genes -> model parameters)
/// - Observed data for comparison
/// - Objective function selection
///
/// The wrapper implements the Optimisable trait, presenting a simple normalized
/// parameter interface to optimization algorithms.

use crate::model::Model;
use crate::nodes::NodeEnum;
use super::optimisable::Optimisable;
use super::calibratable::Calibratable;
use super::parameter_mapping::ParameterMappingConfig;
use super::objectives::ObjectiveFunction;

/// Wraps a Model to make it Optimisable
///
/// # Example
/// ```ignore
/// let config = ParameterMappingConfig::from_strings(vec![
///     "node.sacramento_a.lztwm = log_range(g(1), 50, 300)",
///     "node.sacramento_a.uzk = log_range(g(2), 0.1, 0.7)",
/// ])?;
///
/// let problem = CalibrationProblem::new(
///     model,
///     config,
///     observed_data,
///     "node.sacramento_a.dsflow".to_string()
/// );
/// ```
pub struct CalibrationProblem {
    /// The hydrological model
    pub model: Model,

    /// Gene-based parameter configuration
    pub config: ParameterMappingConfig,

    /// Observed data for comparison
    pub observed_data: Vec<f64>,

    /// Name of simulated series to compare (e.g., "node.sacramento_a.dsflow")
    pub simulated_series_name: String,

    /// Objective function to use
    pub objective: ObjectiveFunction,
}

impl CalibrationProblem {
    /// Create a new calibration problem
    pub fn new(
        model: Model,
        config: ParameterMappingConfig,
        observed_data: Vec<f64>,
        simulated_series_name: String,
    ) -> Self {
        Self {
            model,
            config,
            observed_data,
            simulated_series_name,
            objective: ObjectiveFunction::NashSutcliffe,
        }
    }

    /// Set the objective function
    pub fn with_objective(mut self, objective: ObjectiveFunction) -> Self {
        self.objective = objective;
        self
    }

    /// Apply parameter values to the model
    ///
    /// This maps from genes to model parameters using the CalibrationConfig,
    /// then sets each parameter on the appropriate node.
    fn apply_params_to_model(&mut self, genes: &[f64]) -> Result<(), String> {
        // Evaluate all mappings: genes -> (target, physical_value)
        let param_values = self.config.evaluate(genes);

        // Apply each parameter to the model
        for (target, value) in param_values {
            // Parse target address: "node.sacramento_a.lztwm"
            let parts: Vec<&str> = target.split('.').collect();
            if parts.len() != 3 || parts[0] != "node" {
                return Err(format!("Invalid target address: {}", target));
            }
            let node_name = parts[1];
            let param_name = parts[2];

            // Get node index
            let node_idx = self
                .model
                .get_node_idx(node_name)
                .ok_or_else(|| format!("Node not found: {}", node_name))?;

            // Set parameter on the node using Calibratable trait
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
                        "Node '{}' (type: {}) does not support calibration",
                        node_name,
                        self.model.nodes[node_idx].get_type_as_string()
                    ));
                }
            }
        }

        Ok(())
    }

    /// Extract simulated data from model after run
    fn extract_simulated(&mut self) -> Result<Vec<f64>, String> {
        let idx = self
            .model
            .data_cache
            .get_series_idx(&self.simulated_series_name, false)
            .ok_or_else(|| {
                format!(
                    "Simulated series not found: {}",
                    self.simulated_series_name
                )
            })?;

        Ok(self.model.data_cache.series[idx].values.clone())
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

impl Optimisable for CalibrationProblem {
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

        // Extract simulated data
        let simulated = self.extract_simulated()?; //TODO: this is a clone

        // Check lengths match
        if simulated.len() != self.observed_data.len() {
            return Err(format!(
                "Simulated and observed data length mismatch ({} vs {})",
                simulated.len(),
                self.observed_data.len()
            ));
        }

        // Calculate objective
        self.objective.calculate(&self.observed_data, &simulated)
    }

    fn param_names(&self) -> Vec<String> {
        self.config.gene_names()
    }

    fn clone_for_parallel(&self) -> Box<dyn Optimisable> {
        Box::new(Self {
            model: self.model.clone(),
            config: self.config.clone(),
            observed_data: self.observed_data.clone(),
            simulated_series_name: self.simulated_series_name.clone(),
            objective: self.objective,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_calibration_problem_creation() {
        let model = Model::new();
        let config = ParameterMappingConfig::new();
        let observed = vec![1.0, 2.0, 3.0];

        let problem = CalibrationProblem::new(
            model,
            config,
            observed,
            "node.test.output".to_string(),
        );

        assert_eq!(problem.objective, ObjectiveFunction::NashSutcliffe);
    }

    #[test]
    fn test_with_objective() {
        let model = Model::new();
        let config = ParameterMappingConfig::new();
        let observed = vec![1.0, 2.0, 3.0];

        let problem = CalibrationProblem::new(
            model,
            config,
            observed,
            "node.test.output".to_string(),
        )
        .with_objective(ObjectiveFunction::KlingGupta);

        assert_eq!(problem.objective, ObjectiveFunction::KlingGupta);
    }
}
