/// Gene-based parameter mapping system for optimisation.
///
/// Each line in the INI `[parameters]` section is a `target = expression` mapping where
/// `expression` is a full Kalix expression that may call `g(i)` (gene lookup), `lin_range`,
/// `log_range`, or any of the built-in math functions. Expressions are parsed by the
/// Kalix expression engine (`crate::functions`) and evaluated against a [`Gene`] that
/// the optimiser populates each iteration.
///
/// The dimensionality of the optimisation problem is determined by a **discovery pass**:
/// every expression is evaluated once with the gene in [`GeneMode::Discovery`], during
/// which each call to `g(i)` registers `i` in the gene's map. After the pass, the gene's
/// dimension count equals the number of unique indices the modeller referenced — sparse
/// indices like `g(1)` and `g(3)` (skipping `g(2)`) produce a 2-D problem, not a 3-D one.

use std::collections::HashMap;
use std::sync::Arc;
use crate::functions::{parse_function, EvaluationConfig, EvaluationError, FunctionRegistry, ParsedFunction, VariableContext};
use crate::numerical::opt::genes::{Gene, GeneMode};

/// Transform from normalized gene [0,1] to physical parameter value.
///
/// Retained as a small helper struct because `lin_range` and `log_range` (the
/// optimisation-context functions) reuse this math internally.
#[derive(Clone, Debug)]
pub enum Transform {
    Linear { min: f64, max: f64 },
    Log { min: f64, max: f64 },
}

impl Transform {
    pub fn apply(&self, normalized: f64) -> f64 {
        match self {
            Transform::Linear { min, max } => min + normalized * (max - min),
            Transform::Log { min, max } => {
                let log_min = min.log10();
                let log_max = max.log10();
                let log_value = log_min + normalized * (log_max - log_min);
                10f64.powf(log_value)
            }
        }
    }

    pub fn invert(&self, physical: f64) -> f64 {
        match self {
            Transform::Linear { min, max } => (physical - min) / (max - min),
            Transform::Log { min, max } => {
                let log_min = min.log10();
                let log_max = max.log10();
                let log_value = physical.log10();
                (log_value - log_min) / (log_max - log_min)
            }
        }
    }
}

/// Implementation of the `lin_range(x, min, max)` expression function.
///
/// Returns `min + x * (max - min)` — the linear transform applied to a normalised value.
fn lin_range(args: &[f64]) -> Result<f64, EvaluationError> {
    if args.len() != 3 {
        return Err(EvaluationError::InvalidFunctionArguments {
            function: "lin_range".to_string(),
            expected: 3,
            found: args.len(),
        });
    }
    Ok(Transform::Linear { min: args[1], max: args[2] }.apply(args[0]))
}

/// Implementation of the `log_range(x, min, max)` expression function.
///
/// Returns `10 ** (log10(min) + x * (log10(max) - log10(min)))` — the log-space transform
/// applied to a normalised value. Suitable for parameters that span orders of magnitude.
fn log_range(args: &[f64]) -> Result<f64, EvaluationError> {
    if args.len() != 3 {
        return Err(EvaluationError::InvalidFunctionArguments {
            function: "log_range".to_string(),
            expected: 3,
            found: args.len(),
        });
    }
    Ok(Transform::Log { min: args[1], max: args[2] }.apply(args[0]))
}

/// Build a [`FunctionRegistry`] containing the three optimisation-context functions:
/// `lin_range`, `log_range`, and `g`.
///
/// The `g` closure captures `gene` so it can register/read gene indices as expressions
/// are evaluated.
fn build_opt_registry(gene: Arc<Gene>) -> FunctionRegistry {
    let mut r = FunctionRegistry::new();
    r.register("lin_range", Box::new(lin_range));
    r.register("log_range", Box::new(log_range));
    r.register("g", Box::new(move |args| {
        if args.len() != 1 {
            return Err(EvaluationError::InvalidFunctionArguments {
                function: "g".to_string(),
                expected: 1,
                found: args.len(),
            });
        }
        gene.get(args[0])
    }));
    r
}

/// One mapping from a target parameter address to its driving expression.
///
/// Example INI line: `node.gr4j.x1 = lin_range(g(1), 10, 2000)` produces a mapping
/// with `target = "node.gr4j.x1"` and `expression` = the parsed AST.
#[derive(Clone, Debug)]
pub struct ParameterMapping {
    pub target: String,
    pub expression: ParsedFunction,
}

impl ParameterMapping {
    /// Parse a single mapping line like `node.gr4j.x1 = lin_range(g(1), 10, 2000)`.
    pub fn from_string(s: &str) -> Result<Self, String> {
        let (target, expr) = s.split_once('=').ok_or_else(|| {
            format!("Invalid mapping (expected '='): {}", s)
        })?;
        let target = target.trim().to_string();
        if target.is_empty() {
            return Err(format!("Empty target in mapping: {}", s));
        }
        let parsed = parse_function(expr.trim()).map_err(|e| {
            format!("Failed to parse parameter expression '{}': {}", expr.trim(), e)
        })?;
        Ok(Self { target, expression: parsed })
    }

    /// Parse target address `node.<node_name>.<param_name>` into its parts.
    pub fn parse_target(&self) -> Result<(String, String), String> {
        let parts: Vec<&str> = self.target.split('.').collect();
        if parts.len() != 3 || parts[0] != "node" {
            return Err(format!(
                "Invalid target: {}. Expected format: node.<node_name>.<param_name>",
                self.target
            ));
        }
        Ok((parts[1].to_string(), parts[2].to_string()))
    }
}

/// All parameter mappings for an optimisation, plus the [`Gene`] backing `g(i)` lookups.
#[derive(Debug)]
pub struct ParameterMappingConfig {
    pub mappings: Vec<ParameterMapping>,
    /// Shared gene state used by the `g(i)` function closure.
    /// Cloned (deep-cloned, see [`Clone`] impl) per parallel worker.
    gene: Arc<Gene>,
}

impl ParameterMappingConfig {
    /// Create empty configuration (no mappings, no genes).
    pub fn new() -> Self {
        Self {
            mappings: Vec::new(),
            gene: Arc::new(Gene::new()),
        }
    }

    /// Parse a list of mapping strings and run the gene-discovery pass.
    pub fn from_strings(strings: Vec<&str>) -> Result<Self, String> {
        let mappings: Vec<ParameterMapping> = strings.iter()
            .filter(|s| !s.trim().is_empty())
            .map(|s| ParameterMapping::from_string(s))
            .collect::<Result<_, _>>()?;

        let gene = Arc::new(Gene::new());
        Self::run_discovery_pass(&mappings, &gene)?;

        Ok(Self { mappings, gene })
    }

    /// Evaluate every expression once with the gene in discovery mode, then switch to run mode.
    fn run_discovery_pass(mappings: &[ParameterMapping], gene: &Arc<Gene>) -> Result<(), String> {
        gene.set_mode(GeneMode::Discovery);

        let registry = build_opt_registry(gene.clone());
        let empty_vars: HashMap<String, f64> = HashMap::new();
        let cfg = EvaluationConfig::default();
        let ctx = VariableContext::new(&empty_vars, &cfg).with_functions(&registry);

        for m in mappings {
            m.expression.evaluate(&ctx).map_err(|e| {
                format!("While discovering genes for '{}': {}", m.target, e)
            })?;
        }

        gene.set_mode(GeneMode::Run);
        Ok(())
    }

    pub fn add_mapping(&mut self, mapping: ParameterMapping) {
        // Run a single-expression discovery pass to register any new genes
        self.gene.set_mode(GeneMode::Discovery);
        let registry = build_opt_registry(self.gene.clone());
        let empty_vars: HashMap<String, f64> = HashMap::new();
        let cfg = EvaluationConfig::default();
        let ctx = VariableContext::new(&empty_vars, &cfg).with_functions(&registry);
        let _ = mapping.expression.evaluate(&ctx);
        self.gene.set_mode(GeneMode::Run);
        self.mappings.push(mapping);
    }

    /// Number of optimisation dimensions (= number of unique gene indices used across all mappings).
    pub fn n_genes(&self) -> usize {
        self.gene.n_dimensions()
    }

    /// Evaluate all mappings against the supplied population vector.
    ///
    /// `genes.len()` must equal [`Self::n_genes`]. Values are written into the gene's map
    /// in dimension order (sorted-ascending registered indices), then each parameter
    /// expression is evaluated to produce its physical value.
    pub fn evaluate(&self, genes: &[f64]) -> Vec<(String, f64)> {
        self.gene.set_values(genes);

        let registry = build_opt_registry(self.gene.clone());
        let empty_vars: HashMap<String, f64> = HashMap::new();
        let cfg = EvaluationConfig::default();
        let ctx = VariableContext::new(&empty_vars, &cfg).with_functions(&registry);

        self.mappings.iter()
            .map(|m| {
                let value = m.expression.evaluate(&ctx)
                    .expect("expression already validated during discovery pass");
                (m.target.clone(), value)
            })
            .collect()
    }

    /// Human-readable gene names in dimension order, e.g. `["g(1)", "g(3)"]`.
    pub fn gene_names(&self) -> Vec<String> {
        self.gene.gene_names()
    }
}

impl Default for ParameterMappingConfig {
    fn default() -> Self { Self::new() }
}

/// Custom [`Clone`] that deep-clones the [`Gene`] so parallel workers don't share state.
impl Clone for ParameterMappingConfig {
    fn clone(&self) -> Self {
        Self {
            mappings: self.mappings.clone(),
            gene: Arc::new(self.gene.deep_clone()),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_simple_mapping() {
        let m = ParameterMapping::from_string(
            "node.sacramento_a.adimp = log_range(g(1), 1E-05, 0.15)"
        ).unwrap();
        assert_eq!(m.target, "node.sacramento_a.adimp");
    }

    #[test]
    fn parse_invalid_expression_errors() {
        let err = ParameterMapping::from_string("node.x.y = 12 + ").unwrap_err();
        assert!(err.contains("Failed to parse"), "got: {}", err);
    }

    #[test]
    fn config_from_strings_counts_unique_genes() {
        let strings = vec![
            "node.sac_a.adimp = log_range(g(1), 1E-05, 0.15)",
            "node.sac_b.adimp = log_range(g(1), 1E-05, 0.15)",   // tied
            "node.sac_a.laguh = lin_range(g(2), 0, 3)",
        ];
        let config = ParameterMappingConfig::from_strings(strings).unwrap();
        assert_eq!(config.n_genes(), 2);
        assert_eq!(config.mappings.len(), 3);
        assert_eq!(config.gene_names(), vec!["g(1)", "g(2)"]);
    }

    #[test]
    fn sparse_gene_indices() {
        let strings = vec![
            "node.x.x1 = lin_range(g(1), 10, 2000)",
            "node.x.x4 = lin_range(g(3), 0.0001, 4.0)",   // skips g(2)
        ];
        let config = ParameterMappingConfig::from_strings(strings).unwrap();
        assert_eq!(config.n_genes(), 2);
        assert_eq!(config.gene_names(), vec!["g(1)", "g(3)"]);
    }

    #[test]
    fn evaluate_lin_range_at_endpoints() {
        let strings = vec!["node.x.x1 = lin_range(g(1), 10, 20)"];
        let config = ParameterMappingConfig::from_strings(strings).unwrap();
        let at_zero = config.evaluate(&[0.0]);
        assert_eq!(at_zero[0].0, "node.x.x1");
        assert!((at_zero[0].1 - 10.0).abs() < 1e-10);
        let at_one = config.evaluate(&[1.0]);
        assert!((at_one[0].1 - 20.0).abs() < 1e-10);
        let at_half = config.evaluate(&[0.5]);
        assert!((at_half[0].1 - 15.0).abs() < 1e-10);
    }

    #[test]
    fn evaluate_log_range_at_endpoints() {
        let strings = vec!["node.x.x1 = log_range(g(1), 1, 100)"];
        let config = ParameterMappingConfig::from_strings(strings).unwrap();
        let at_zero = config.evaluate(&[0.0]);
        assert!((at_zero[0].1 - 1.0).abs() < 1e-10);
        let at_half = config.evaluate(&[0.5]);
        assert!((at_half[0].1 - 10.0).abs() < 1e-10);
        let at_one = config.evaluate(&[1.0]);
        assert!((at_one[0].1 - 100.0).abs() < 1e-10);
    }

    #[test]
    fn evaluate_composed_expression() {
        let strings = vec![
            "node.x.x1 = 0.5 * lin_range(g(1), 0, 100) + 0.5 * lin_range(g(2), 0, 100)",
        ];
        let config = ParameterMappingConfig::from_strings(strings).unwrap();
        assert_eq!(config.n_genes(), 2);
        // 0.5 * 50 + 0.5 * 50 = 50
        let values = config.evaluate(&[0.5, 0.5]);
        assert!((values[0].1 - 50.0).abs() < 1e-10);
    }

    #[test]
    fn sparse_evaluation_order_matches_dimensions() {
        // g(3) gets the second dimension, g(1) gets the first
        let strings = vec![
            "node.x.x1 = lin_range(g(1), 0, 100)",
            "node.x.x4 = lin_range(g(3), 0, 1000)",
        ];
        let config = ParameterMappingConfig::from_strings(strings).unwrap();
        let values = config.evaluate(&[0.1, 0.2]);
        // g(1) = 0.1 → x1 = 10
        assert!((values[0].1 - 10.0).abs() < 1e-10);
        // g(3) = 0.2 → x4 = 200
        assert!((values[1].1 - 200.0).abs() < 1e-10);
    }

    #[test]
    fn non_integer_gene_index_errors_at_parse_time_discovery() {
        // g(2.5) should error during the discovery pass
        let strings = vec!["node.x.x1 = lin_range(g(2.5), 0, 100)"];
        let err = ParameterMappingConfig::from_strings(strings).unwrap_err();
        assert!(err.contains("positive integer"), "got: {}", err);
    }

    #[test]
    fn clone_for_parallel_is_independent() {
        let strings = vec!["node.x.x1 = lin_range(g(1), 0, 100)"];
        let original = ParameterMappingConfig::from_strings(strings).unwrap();
        let original_values = original.evaluate(&[0.3]);

        let cloned = original.clone();
        let cloned_values = cloned.evaluate(&[0.7]);

        // Cloning shouldn't disturb the original's state
        let original_again = original.evaluate(&[0.3]);
        assert!((original_again[0].1 - 30.0).abs() < 1e-10);
        assert!((original_values[0].1 - 30.0).abs() < 1e-10);
        assert!((cloned_values[0].1 - 70.0).abs() < 1e-10);
    }

    #[test]
    fn transform_linear_apply() {
        let t = Transform::Linear { min: 10.0, max: 20.0 };
        assert_eq!(t.apply(0.0), 10.0);
        assert_eq!(t.apply(1.0), 20.0);
        assert_eq!(t.apply(0.5), 15.0);
        assert_eq!(t.invert(15.0), 0.5);
    }

    #[test]
    fn transform_log_apply() {
        let t = Transform::Log { min: 1.0, max: 100.0 };
        assert!((t.apply(0.5) - 10.0).abs() < 1e-10);
    }
}
