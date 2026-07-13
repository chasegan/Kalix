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
use crate::numerical::mathfn::u64_subtraction;
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

    /// Parameter targets resolved once (first set_params) from their string
    /// addresses to direct indices - no per-evaluation string splitting,
    /// lowercasing, or node lookups. One entry per config mapping, in order.
    resolved_targets: Option<Vec<ResolvedTarget>>,

    /// Simulated-series index per comparison, resolved on first evaluation.
    sim_series_indices: Option<Vec<usize>>,
}

/// A parameter target address, pre-resolved for the evaluation hot path.
enum ResolvedTarget {
    /// A constant in the data cache's constants table.
    Constant(usize),
    /// A node parameter, applied via OptimisableComponent::set_param.
    NodeParam { node_idx: usize, param_name: String },
}

impl OptimisationProblem {
    /// Create a new optimisation problem
    pub fn new(
        model: Model,
        config: ParameterMappingConfig,
        comparisons: Vec<ComparisonPair>,
        expression: ParsedFunction,
    ) -> Self {
        Self {
            model, config, comparisons, expression,
            resolved_targets: None,
            sim_series_indices: None,
        }
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

    /// Resolve every mapping's target address ("node.name.param" or
    /// "c.constant") to a direct index. Runs once per problem; the per-
    /// evaluation path then applies values by index with no string work.
    fn resolve_targets(&mut self) -> Result<Vec<ResolvedTarget>, String> {
        let mut resolved = Vec::with_capacity(self.config.mappings.len());
        for mapping in &self.config.mappings {
            let target = &mapping.target;
            let parts: Vec<&str> = target.split('.').collect();

            if parts.len() >= 2 && parts[0] == "c" {
                // Same registration path set_param used, so behaviour
                // (including creating a not-yet-seen constant) is unchanged.
                let idx = self.model.data_cache.constants.add_if_needed_and_get_idx(target);
                resolved.push(ResolvedTarget::Constant(idx));
            } else if parts.len() == 3 && parts[0] == "node" {
                let node_name = parts[1];
                let param_name = parts[2];
                let node_idx = self.model.get_node_idx(node_name)
                    .ok_or_else(|| format!("Node not found: {}", node_name))?;
                match &self.model.nodes[node_idx] {
                    NodeEnum::SacramentoNode(_) | NodeEnum::Gr4jNode(_) | NodeEnum::RoutingNode(_) => {}
                    other => {
                        return Err(format!(
                            "Node '{}' (type: {}) does not support parameter optimisation",
                            node_name, other.get_type_as_string()
                        ));
                    }
                }
                resolved.push(ResolvedTarget::NodeParam {
                    node_idx,
                    param_name: param_name.to_string(),
                });
            } else {
                return Err(format!("Invalid target address: '{}'. Expected 'node.name.param' or 'c.constant_name'", target));
            }
        }
        Ok(resolved)
    }

    /// Apply parameter values to the model via the pre-resolved targets.
    fn apply_params_to_model(&mut self, genes: &[f64]) -> Result<(), String> {
        if self.resolved_targets.is_none() {
            self.resolved_targets = Some(self.resolve_targets()?);
        }
        let targets = self.resolved_targets.as_ref().unwrap();

        // Physical values in mapping order (no target strings on this path)
        let values = self.config.evaluate_values(genes);

        for (target, &value) in targets.iter().zip(values.iter()) {
            match target {
                ResolvedTarget::Constant(idx) => {
                    self.model.data_cache.constants.set_value_by_idx(*idx, value);
                }
                ResolvedTarget::NodeParam { node_idx, param_name } => {
                    match &mut self.model.nodes[*node_idx] {
                        NodeEnum::SacramentoNode(node) => node.set_param(param_name, value),
                        NodeEnum::Gr4jNode(node) => node.set_param(param_name, value),
                        NodeEnum::RoutingNode(node) => node.set_param(param_name, value),
                        _ => unreachable!("checked during target resolution"),
                    }
                    .map_err(|e| format!("Error setting {}: {}", param_name, e))?;
                }
            }
        }
        Ok(())
    }

    /// Align observed and simulated timeseries temporally.
    ///
    /// Both series live on regular grids (timestamp of point i is
    /// start + i * step), so alignment is pure index arithmetic. Returns the
    /// overlapping index ranges into observed.values and simulated.values -
    /// the caller slices both directly, so nothing is copied per evaluation.
    fn align_ranges(
        observed: &Timeseries,
        simulated: &Timeseries,
    ) -> Result<(std::ops::Range<usize>, std::ops::Range<usize>), String> {
        if observed.step_size != simulated.step_size {
            return Err(format!(
                "Observed step_size ({}s) differs from simulated step_size ({}s)",
                observed.step_size, simulated.step_size
            ));
        }
        let step = simulated.step_size;
        if step == 0 {
            return Err("Cannot align timeseries with step_size 0".to_string());
        }

        // How many steps is observed[0] ahead of simulated[0]?
        let offset = u64_subtraction(observed.start_timestamp / step,
                                     simulated.start_timestamp / step);
        if observed.start_timestamp % step != simulated.start_timestamp % step {
            return Err(format!(
                "Observed and simulated grids are not aligned: starts {} and {} \
                 differ by a non-whole number of {}s steps",
                observed.start_timestamp, simulated.start_timestamp, step
            ));
        }

        // Observed index i pairs with simulated index i + offset. Clip to the
        // overlap of both series.
        let obs_len = observed.values.len() as i64;
        let sim_len = simulated.values.len() as i64;
        let i_start = 0.max(-offset);
        let i_end = obs_len.min(sim_len - offset);

        if i_end <= i_start {
            return Err(format!(
                "No overlapping timestamps found between observed ({}..{}) and simulated ({}..{}) data",
                observed.timestamp_at(0),
                observed.timestamp_at(observed.values.len().saturating_sub(1)),
                simulated.timestamp_at(0),
                simulated.timestamp_at(simulated.values.len().saturating_sub(1)),
            ));
        }

        let (i_start, i_end) = (i_start as usize, i_end as usize);
        let sim_start = (i_start as i64 + offset) as usize;
        let sim_end = (i_end as i64 + offset) as usize;

        Ok((i_start..i_end, sim_start..sim_end))
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

        // Resolve each term's simulated-series index once (stable for the
        // life of the problem and its worker clones).
        if self.sim_series_indices.is_none() {
            let mut indices = Vec::with_capacity(self.comparisons.len());
            for comparison in &self.comparisons {
                let idx = self.model.data_cache
                    .get_existing_series_idx(&comparison.simulated_series_name)
                    .ok_or_else(|| {
                        format!(
                            "Simulated series not found for term '{}': {}",
                            comparison.name, comparison.simulated_series_name
                        )
                    })?;
                indices.push(idx);
            }
            self.sim_series_indices = Some(indices);
        }
        let sim_indices = self.sim_series_indices.as_ref().unwrap();

        // Compute each term's loss and stash by term name for expression evaluation
        let mut term_values: HashMap<String, f64> = HashMap::with_capacity(self.comparisons.len());
        for (comparison, &sim_idx) in self.comparisons.iter().zip(sim_indices.iter()) {
            let simulated_ts = &self.model.data_cache.series[sim_idx];
            let (obs_range, sim_range) = Self::align_ranges(&comparison.observed, simulated_ts)
                .map_err(|e| format!("In term '{}': {}", comparison.name, e))?;

            // Aligned data are contiguous slices of both series - no copies.
            let value = comparison.statistic.calculate(
                &comparison.observed.values[obs_range],
                &simulated_ts.values[sim_range],
            )
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
            // Slim clone: no round-trip INI documents, no raw inputs once
            // configured (see Model::clone_for_run) - a worker only needs
            // what the simulation itself touches.
            model: self.model.clone_for_run(),
            config: self.config.clone(),
            comparisons: self.comparisons.clone(),
            expression: self.expression.clone(),
            // Resolved indices stay valid in the clone (same node order,
            // same constants table, same series layout).
            resolved_targets: None,
            sim_series_indices: self.sim_series_indices.clone(),
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
