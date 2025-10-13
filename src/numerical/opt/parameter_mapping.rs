/// Gene-based parameter mapping system for optimization
///
/// This module implements a flexible parameter system where:
/// - "Genes" g(1), g(2), ... are the actual optimization parameters (normalized [0,1])
/// - Model parameters are derived from genes via mapping expressions
/// - Multiple model parameters can share the same gene (parameter tying)
/// - Each mapping has its own bounds and transform space (linear/log)

/// Transform from normalized gene [0,1] to physical parameter value
#[derive(Clone, Debug)]
pub enum Transform {
    /// Linear mapping: value = min + normalized * (max - min)
    Linear { min: f64, max: f64 },

    /// Log mapping: normalized [0,1] maps to log-space between [min, max]
    Log { min: f64, max: f64 },
}

impl Transform {
    /// Apply transform: normalized [0,1] -> physical value
    pub fn apply(&self, normalized: f64) -> f64 {
        match self {
            Transform::Linear { min, max } => {
                min + normalized * (max - min)
            },
            Transform::Log { min, max } => {
                let log_min = min.log10();
                let log_max = max.log10();
                let log_value = log_min + normalized * (log_max - log_min);
                10f64.powf(log_value)
            }
        }
    }

    /// Invert transform: physical value -> normalized [0,1]
    pub fn invert(&self, physical: f64) -> f64 {
        match self {
            Transform::Linear { min, max } => {
                (physical - min) / (max - min)
            },
            Transform::Log { min, max } => {
                let log_min = min.log10();
                let log_max = max.log10();
                let log_value = physical.log10();
                (log_value - log_min) / (log_max - log_min)
            }
        }
    }
}

/// A mapping from a gene to a model parameter
///
/// Example: "node.sacramento_a.adimp = log_range(g(1), 1E-05, 0.15)"
/// - target: "node.sacramento_a.adimp"
/// - gene_index: 1
/// - transform: Log { min: 1E-05, max: 0.15 }
#[derive(Clone, Debug)]
pub struct ParameterMapping {
    /// Target parameter address (e.g., "node.sacramento_a.adimp")
    pub target: String,

    /// Which gene index (1-based, like g(1), g(2), ...)
    pub gene_index: usize,

    /// Transform to apply to gene value
    pub transform: Transform,
}

impl ParameterMapping {
    /// Parse from string like: "node.sacramento_a.adimp = log_range(g(1), 1E-05, 0.15)"
    pub fn from_string(s: &str) -> Result<Self, String> {
        // Split on '='
        let parts: Vec<&str> = s.split('=').map(|p| p.trim()).collect();
        if parts.len() != 2 {
            return Err(format!("Invalid mapping (expected '='): {}", s));
        }

        let target = parts[0].to_string();
        let expr = parts[1];

        // Parse log_range expression
        if let Some(log_range) = expr.strip_prefix("log_range(") {
            let content = log_range.trim_end_matches(')');
            let args: Vec<&str> = content.split(',').map(|a| a.trim()).collect();
            if args.len() != 3 {
                return Err(format!("log_range expects 3 arguments: {}", expr));
            }

            let gene_index = Self::parse_gene(args[0])?;
            let min = Self::parse_float(args[1])?;
            let max = Self::parse_float(args[2])?;

            return Ok(Self {
                target,
                gene_index,
                transform: Transform::Log { min, max },
            });
        }

        // Parse lin_range expression
        if let Some(lin_range) = expr.strip_prefix("lin_range(") {
            let content = lin_range.trim_end_matches(')');
            let args: Vec<&str> = content.split(',').map(|a| a.trim()).collect();
            if args.len() != 3 {
                return Err(format!("lin_range expects 3 arguments: {}", expr));
            }

            let gene_index = Self::parse_gene(args[0])?;
            let min = Self::parse_float(args[1])?;
            let max = Self::parse_float(args[2])?;

            return Ok(Self {
                target,
                gene_index,
                transform: Transform::Linear { min, max },
            });
        }

        Err(format!("Unknown transform function (expected log_range or lin_range): {}", expr))
    }

    /// Parse gene notation: "g(1)" -> 1
    fn parse_gene(s: &str) -> Result<usize, String> {
        if let Some(inner) = s.strip_prefix("g(") {
            if let Some(num_str) = inner.strip_suffix(")") {
                return num_str.trim().parse::<usize>()
                    .map_err(|_| format!("Invalid gene index: {}", s));
            }
        }
        Err(format!("Invalid gene format (expected g(n)): {}", s))
    }

    /// Parse float, handling scientific notation
    fn parse_float(s: &str) -> Result<f64, String> {
        s.trim().parse::<f64>()
            .map_err(|_| format!("Invalid number: {}", s))
    }

    /// Parse target address into (node_name, param_name)
    ///
    /// Example: "node.sacramento_a.lztwm" -> ("sacramento_a", "lztwm")
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

/// Configuration for calibration with gene-based parameter system
#[derive(Clone, Debug)]
pub struct CalibrationConfig {
    /// List of parameter mappings
    pub mappings: Vec<ParameterMapping>,

    /// Number of genes (automatically determined from mappings)
    n_genes: usize,
}

impl CalibrationConfig {
    /// Create empty configuration
    pub fn new() -> Self {
        Self {
            mappings: vec![],
            n_genes: 0,
        }
    }

    /// Create configuration from string definitions
    ///
    /// # Example
    /// ```ignore
    /// let strings = vec![
    ///     "node.sacramento_a.adimp = log_range(g(1), 1E-05, 0.15)",
    ///     "node.sacramento_a.lzfpm = log_range(g(2), 1, 300)",
    ///     "node.sacramento_b.adimp = log_range(g(1), 1E-05, 0.15)",  // Tied!
    /// ];
    /// let config = CalibrationConfig::from_strings(strings)?;
    /// ```
    pub fn from_strings(strings: Vec<&str>) -> Result<Self, String> {
        let mut mappings = vec![];
        for s in strings {
            if s.trim().is_empty() {
                continue;  // Skip empty lines
            }
            mappings.push(ParameterMapping::from_string(s)?);
        }

        // Determine number of genes (highest gene index)
        let n_genes = mappings.iter()
            .map(|m| m.gene_index)
            .max()
            .unwrap_or(0);

        Ok(Self { mappings, n_genes })
    }

    /// Add a mapping
    pub fn add_mapping(&mut self, mapping: ParameterMapping) {
        self.n_genes = self.n_genes.max(mapping.gene_index);
        self.mappings.push(mapping);
    }

    /// Get number of genes (optimization parameters)
    pub fn n_genes(&self) -> usize {
        self.n_genes
    }

    /// Evaluate all mappings: genes -> model parameter values
    ///
    /// # Arguments
    /// * `genes` - Normalized gene values [0,1], indexed from 0 (g(1) is genes[0])
    ///
    /// # Returns
    /// Vec of (target_address, physical_value) pairs
    pub fn evaluate(&self, genes: &[f64]) -> Vec<(String, f64)> {
        self.mappings.iter().map(|mapping| {
            let normalized = genes[mapping.gene_index - 1];  // g(1) -> index 0
            let physical = mapping.transform.apply(normalized);
            (mapping.target.clone(), physical)
        }).collect()
    }

    /// Get gene names for reporting
    pub fn gene_names(&self) -> Vec<String> {
        (1..=self.n_genes)
            .map(|i| format!("g({})", i))
            .collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_simple_mapping() {
        let s = "node.sacramento_a.adimp = log_range(g(1), 1E-05, 0.15)";
        let mapping = ParameterMapping::from_string(s).unwrap();

        assert_eq!(mapping.target, "node.sacramento_a.adimp");
        assert_eq!(mapping.gene_index, 1);

        match mapping.transform {
            Transform::Log { min, max } => {
                assert!((min - 1E-05).abs() < 1e-10);
                assert!((max - 0.15).abs() < 1e-10);
            },
            _ => panic!("Expected Log transform"),
        }
    }

    #[test]
    fn test_parse_linear_mapping() {
        let s = "node.sacramento_a.laguh = lin_range(g(17), 0, 3)";
        let mapping = ParameterMapping::from_string(s).unwrap();

        assert_eq!(mapping.gene_index, 17);
        match mapping.transform {
            Transform::Linear { min, max } => {
                assert_eq!(min, 0.0);
                assert_eq!(max, 3.0);
            },
            _ => panic!("Expected Linear transform"),
        }
    }

    #[test]
    fn test_config_from_strings() {
        let strings = vec![
            "node.sac_a.adimp = log_range(g(1), 1E-05, 0.15)",
            "node.sac_b.adimp = log_range(g(1), 1E-05, 0.15)",
            "node.sac_a.laguh = lin_range(g(2), 0, 3)",
        ];

        let config = CalibrationConfig::from_strings(strings).unwrap();
        assert_eq!(config.n_genes(), 2);
        assert_eq!(config.mappings.len(), 3);
    }

    #[test]
    fn test_transform_linear() {
        let t = Transform::Linear { min: 10.0, max: 20.0 };
        assert_eq!(t.apply(0.0), 10.0);
        assert_eq!(t.apply(1.0), 20.0);
        assert_eq!(t.apply(0.5), 15.0);

        assert_eq!(t.invert(10.0), 0.0);
        assert_eq!(t.invert(20.0), 1.0);
        assert_eq!(t.invert(15.0), 0.5);
    }

    #[test]
    fn test_transform_log() {
        let t = Transform::Log { min: 1.0, max: 100.0 };

        let val = t.apply(0.0);
        assert!((val - 1.0).abs() < 1e-10);

        let val = t.apply(1.0);
        assert!((val - 100.0).abs() < 1e-10);

        let val = t.apply(0.5);
        assert!((val - 10.0).abs() < 1e-10);  // Midpoint in log space
    }
}
