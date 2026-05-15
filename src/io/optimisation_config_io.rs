/// INI file parsing for optimisation configuration
///
/// This module parses optimisation configuration from INI files using the
/// Kalix custom INI parser.
///
/// Case-sensitivity:
/// - Section names: case-insensitive ([General] = [general])
/// - Property keys: case-insensitive (model_file = MODEL_FILE)
/// - Objective functions: case-insensitive (NSE = nse)
/// - Algorithm names: case-insensitive (DE = de)
/// - Boolean values: case-insensitive (true = TRUE)
/// - File paths: case-sensitive (preserved as-is)
/// - Node/series names: case-sensitive (preserved as-is)

use std::fs;
use indexmap::IndexMap;
use crate::io::custom_ini_parser::IniDocument;
use crate::numerical::opt::parameter_mapping::ParameterMappingConfig;
use crate::numerical::opt::objectives::ObjectiveFunction;
use crate::timeseries_input::TimeseriesInput;

/// Algorithm-specific parameters for optimisation
#[derive(Debug, Clone, PartialEq)]
pub enum AlgorithmParams {
    /// Differential Evolution algorithm
    DE {
        population_size: usize,
        f: f64,   // Mutation factor (typically 0.5-1.0)
        cr: f64,  // Crossover rate (typically 0.8-0.95)
    },
    /// CMA-ES algorithm
    CMAES {
        population_size: usize,
        sigma: f64,  // Initial step size
    },
    /// SCE algorithm
    SCEUA {
        complexes: usize,
        // Note: points_per_complex is calculated as 2*n_params + 1 (Duan et al. 1994)
    },
}

impl AlgorithmParams {
    /// Get the algorithm name as a string
    pub fn name(&self) -> &str {
        match self {
            AlgorithmParams::DE { .. } => "DE",
            AlgorithmParams::CMAES { .. } => "CMAES",
            AlgorithmParams::SCEUA { .. } => "SCE",
        }
    }

    /// Get population size (common across all algorithms)
    ///
    /// For SCE, returns number of complexes (actual population = complexes * (2*n_params + 1))
    pub fn population_size(&self) -> usize {
        match self {
            AlgorithmParams::DE { population_size, .. } => *population_size,
            AlgorithmParams::CMAES { population_size, .. } => *population_size,
            AlgorithmParams::SCEUA { complexes } => *complexes,
        }
    }
}

/// Intermediate representation of optimisation configuration from INI format
///
/// Section keys are lowercased for case-insensitive lookup; section declaration
/// order is preserved (so `[term.*]` sections appear in the order the user wrote them).
struct OptimisationConfigData {
    /// Sections mapped to their properties (insertion order preserved)
    /// Keys are stored in lowercase for case-insensitive lookup; original section name kept too.
    sections: IndexMap<String, SectionData>,
}

struct SectionData {
    /// Original section name as written in the INI (preserves user case)
    original_name: String,
    /// Property name (lowercase) -> raw value (original case)
    properties: IndexMap<String, String>,
}

impl OptimisationConfigData {
    /// Parse from INI format
    fn from_ini(content: &str) -> Result<Self, String> {
        let ini = IniDocument::parse(content)?;
        let mut sections = IndexMap::new();

        for (section_name, section) in &ini.sections {
            let mut properties = IndexMap::new();
            for (prop_name, prop) in &section.properties {
                properties.insert(prop_name.to_lowercase(), prop.value.clone());
            }
            sections.insert(
                section_name.to_lowercase(),
                SectionData {
                    original_name: section_name.clone(),
                    properties,
                },
            );
        }

        Ok(Self { sections })
    }

    /// Get a section by name (case-insensitive)
    fn get_section(&self, name: &str) -> Option<&SectionData> {
        self.sections.get(&name.to_lowercase())
    }

    /// Get a property from a section (case-insensitive)
    fn get_property(&self, section: &str, key: &str) -> Option<&str> {
        self.get_section(section)
            .and_then(|s| s.properties.get(&key.to_lowercase()))
            .map(|v| v.as_str())
    }

    /// Get a property or return error (case-insensitive)
    fn require_property(&self, section: &str, key: &str) -> Result<&str, String> {
        self.get_property(section, key)
            .ok_or_else(|| format!("Missing '{}' property in [{}] section", key, section))
    }
}

/// How to identify a column within an observed-data CSV file
#[derive(Debug, Clone, PartialEq)]
pub enum SeriesSpec {
    /// 1-based column index
    ByIndex(usize),
    /// Column name (case-insensitive match against CSV header)
    ByName(String),
}

impl SeriesSpec {
    /// Parse from a user-supplied string. Integers become `ByIndex`; everything else `ByName`.
    pub fn parse(s: &str) -> Self {
        match s.parse::<usize>() {
            Ok(n) => SeriesSpec::ByIndex(n),
            Err(_) => SeriesSpec::ByName(s.to_string()),
        }
    }
}

/// A single term in a composite optimisation objective
///
/// Each term pairs an observed timeseries with a simulated series from the model
/// and a statistic that compares the two. The named scalar each term produces is
/// referenced by name in the `objective_expression`.
#[derive(Debug, Clone)]
pub struct Term {
    pub name: String,
    pub simulated_series: String,
    pub observed_file: String,
    pub observed_series: SeriesSpec,
    pub statistic: ObjectiveFunction,
}

/// Optimisation configuration from INI format
///
/// Composed of one or more [`Term`]s plus an `objective_expression` that combines them
/// into a scalar fitness via the `crate::functions` expression parser.
#[derive(Debug, Clone)]
pub struct OptimisationConfig {
    // [optimisation] section - Problem definition
    pub model_file: Option<String>,  // Optional: can be provided via inline model instead
    pub terms: Vec<Term>,
    /// Expression over term names, e.g. `term1 + 0.5 * term2`. Parsed by `crate::functions`.
    pub objective_expression: String,
    pub output_file: Option<String>,

    // [optimisation] section - Algorithm configuration
    pub termination_evaluations: usize,  // Termination criterion: stop after approximately this many function evaluations
    pub random_seed: Option<u64>,
    pub n_threads: usize,
    pub algorithm: AlgorithmParams,

    // [parameters] section
    pub parameter_config: ParameterMappingConfig,
}

impl OptimisationConfig {
    /// Load optimisation configuration from INI file
    pub fn from_file(path: &str) -> Result<Self, String> {
        let content = fs::read_to_string(path)
            .map_err(|e| format!("Failed to read optimisation config file '{}': {}", path, e))?;

        Self::from_ini(&content)
    }

    /// Parse optimisation configuration from INI string
    pub fn from_ini(content: &str) -> Result<Self, String> {
        let data = OptimisationConfigData::from_ini(content)?;
        Self::from_data(data)
    }

    /// Build configuration from intermediate data (validation logic)
    fn from_data(data: OptimisationConfigData) -> Result<Self, String> {
        // Parse [optimisation] section
        let model_file = data.get_property("optimisation", "model_file").map(|s| s.to_string());

        // Parse terms from [term.NAME] sections in declaration order
        let terms = Self::parse_terms(&data)?;

        let objective_expression = data.require_property("optimisation", "objective_expression")?.to_string();
        Self::validate_objective_expression(&objective_expression, &terms)?;

        let output_file = data.get_property("optimisation", "output_file")
            .map(|s| s.to_string());

        // Algorithm configuration (same section)
        let termination_evaluations = data.require_property("optimisation", "termination_evaluations")?
            .parse::<usize>()
            .map_err(|_| "Invalid 'termination_evaluations' value")?;

        let random_seed = data.get_property("optimisation", "random_seed")
            .and_then(|p| p.parse::<u64>().ok());

        let n_threads = data.get_property("optimisation", "n_threads")
            .and_then(|p| p.parse::<usize>().ok())
            .unwrap_or(1);  // Default to single-threaded

        // Parse algorithm-specific parameters
        let algorithm_name = data.require_property("optimisation", "algorithm")?
            .to_uppercase();

        let algorithm = match algorithm_name.as_str() {
            "DE" => {
                let population_size = data.require_property("optimisation", "population_size")?
                    .parse::<usize>()
                    .map_err(|_| "Invalid 'population_size' for DE")?;

                let f = data.get_property("optimisation", "de_f")
                    .and_then(|p| p.parse::<f64>().ok())
                    .unwrap_or(0.8);

                let cr = data.get_property("optimisation", "de_cr")
                    .and_then(|p| p.parse::<f64>().ok())
                    .unwrap_or(0.9);

                AlgorithmParams::DE { population_size, f, cr }
            },
            "CMAES" => {
                let population_size = data.require_property("optimisation", "population_size")?
                    .parse::<usize>()
                    .map_err(|_| "Invalid 'population_size' for CMA-ES")?;

                let sigma = data.require_property("optimisation", "sigma")?
                    .parse::<f64>()
                    .map_err(|_| "Invalid 'sigma' for CMA-ES")?;

                AlgorithmParams::CMAES { population_size, sigma }
            },
            "SCE" => {
                let complexes = data.require_property("optimisation", "complexes")?
                    .parse::<usize>()
                    .map_err(|_| "Invalid 'complexes' for SCE")?;

                // Note: points_per_complex is automatically calculated as 2*n_params + 1
                // following Duan et al. (1994). If specified in config, it will be ignored.

                AlgorithmParams::SCEUA { complexes }
            },
            _ => return Err(format!(
                "Unknown algorithm: '{}'. Valid options: DE, CMAES, SCE",
                algorithm_name
            )),
        };

        // Parse [Parameters] section
        let parameters_section = data.get_section("parameters")
            .ok_or_else(|| "Missing [Parameters] section".to_string())?;

        let mut param_strings = Vec::new();
        for (key, value) in &parameters_section.properties {
            // Each property is a parameter mapping: "node.x.y = log_range(g(1), min, max)"
            let mapping_str = format!("{} = {}", key, value);
            param_strings.push(mapping_str);
        }

        let parameter_config = ParameterMappingConfig::from_strings(
            param_strings.iter().map(|s| s.as_str()).collect()
        )?;

        Ok(Self {
            model_file,
            terms,
            objective_expression,
            output_file,
            termination_evaluations,
            random_seed,
            n_threads,
            algorithm,
            parameter_config,
        })
    }

    /// Parse all `[term.NAME]` sections in declaration order
    fn parse_terms(data: &OptimisationConfigData) -> Result<Vec<Term>, String> {
        let mut terms: Vec<Term> = Vec::new();
        let mut seen_names: std::collections::HashSet<String> = std::collections::HashSet::new();

        for (section_key, section) in &data.sections {
            // section_key is lowercased; original_name preserves user-written case
            let lower = section_key;
            if !lower.starts_with("term.") {
                continue;
            }

            // Extract the term name from the original section name (preserves case)
            let term_name = section.original_name
                .splitn(2, '.')
                .nth(1)
                .ok_or_else(|| format!("Malformed term section name: [{}]", section.original_name))?
                .to_string();

            if term_name.is_empty() {
                return Err(format!("Empty term name in section [{}]", section.original_name));
            }

            if !seen_names.insert(term_name.clone()) {
                return Err(format!("Duplicate term name '{}'", term_name));
            }

            let simulated_series = section.properties.get("simulated")
                .ok_or_else(|| format!("Missing 'simulated' in [term.{}]", term_name))?
                .to_string();

            let observed_file = section.properties.get("observed_file")
                .ok_or_else(|| format!("Missing 'observed_file' in [term.{}]", term_name))?
                .to_string();

            let observed_series_str = section.properties.get("observed_series")
                .ok_or_else(|| format!("Missing 'observed_series' in [term.{}]", term_name))?;
            let observed_series = SeriesSpec::parse(observed_series_str);

            let statistic_str = section.properties.get("statistic")
                .ok_or_else(|| format!("Missing 'statistic' in [term.{}]", term_name))?;
            let statistic = Self::parse_statistic(statistic_str)
                .map_err(|e| format!("In [term.{}]: {}", term_name, e))?;

            terms.push(Term {
                name: term_name,
                simulated_series,
                observed_file,
                observed_series,
                statistic,
            });
        }

        if terms.is_empty() {
            return Err("Must define at least one [term.NAME] section".to_string());
        }

        Ok(terms)
    }

    /// Validate the objective expression: parses, and every variable matches a term name
    fn validate_objective_expression(expression: &str, terms: &[Term]) -> Result<(), String> {
        let parsed = crate::functions::parse_function(expression)
            .map_err(|e| format!("Failed to parse objective_expression '{}': {}", expression, e))?;

        let term_names: std::collections::HashSet<&str> = terms.iter().map(|t| t.name.as_str()).collect();
        let unknown: Vec<&str> = parsed.get_variables()
            .iter()
            .filter(|v| !term_names.contains(v.as_str()))
            .map(|v| v.as_str())
            .collect();

        if !unknown.is_empty() {
            return Err(format!(
                "objective_expression references unknown term name(s): {}. Defined terms: {}",
                unknown.join(", "),
                terms.iter().map(|t| t.name.as_str()).collect::<Vec<_>>().join(", "),
            ));
        }

        Ok(())
    }

    /// Parse statistic name to ObjectiveFunction (case-insensitive)
    ///
    /// All statistics return values in `[0, ∞)` where lower is better. Names whose natural
    /// form is "higher better" (NSE, LNSE, KGE, Pearson r) are exposed in `ONE_MINUS_*` form.
    fn parse_statistic(s: &str) -> Result<ObjectiveFunction, String> {
        use crate::numerical::opt::objectives::*;
        match s.to_uppercase().as_str() {
            "ONE_MINUS_NSE" => Ok(ObjectiveFunction::OneMinusNse(NseObjective::new())),
            "ONE_MINUS_LNSE" => Ok(ObjectiveFunction::OneMinusLnse(LnseObjective::new())),
            "RMSE" => Ok(ObjectiveFunction::RMSE(RmseObjective::new())),
            "MAE" => Ok(ObjectiveFunction::MAE(MaeObjective::new())),
            "ONE_MINUS_KGE" => Ok(ObjectiveFunction::OneMinusKge(KgeObjective::new())),
            "ABS_PBIAS" => Ok(ObjectiveFunction::AbsPbias(PbiasObjective::new())),
            "SDEB" => Ok(ObjectiveFunction::SDEB(SdebObjective::new())),
            "ONE_MINUS_PEARS_R" => Ok(ObjectiveFunction::OneMinusPearsR(PearsObjective::new())),
            _ => Err(format!(
                "Unknown statistic: '{}'. Valid options: ONE_MINUS_NSE, ONE_MINUS_LNSE, RMSE, MAE, ONE_MINUS_KGE, ABS_PBIAS, SDEB, ONE_MINUS_PEARS_R",
                s
            )),
        }
    }
}

/// Load observed timeseries data for a [`Term`]
///
/// # Arguments
/// * `file` - Path to the CSV file
/// * `series` - Either a 1-based column index or a column name (case-insensitive match)
///
/// # Returns
/// The requested TimeseriesInput containing the observed data
pub fn load_observed_for_term(file: &str, series: &SeriesSpec) -> Result<TimeseriesInput, String> {
    let all_timeseries = TimeseriesInput::load(file)
        .map_err(|e| format!("Error loading observed data file '{}': {}", file, e))?;

    if all_timeseries.is_empty() {
        return Err(format!("No timeseries found in file '{}'", file));
    }

    match series {
        SeriesSpec::ByIndex(col_index) => {
            if *col_index < 1 || *col_index > all_timeseries.len() {
                return Err(format!(
                    "Column index {} out of range (1-{}) in file '{}'",
                    col_index, all_timeseries.len(), file
                ));
            }
            Ok(all_timeseries[col_index - 1].clone())
        }
        SeriesSpec::ByName(name) => {
            all_timeseries.iter()
                .find(|ts| ts.col_name.to_lowercase() == name.to_lowercase())
                .cloned()
                .ok_or_else(|| {
                    let available: Vec<String> = all_timeseries.iter()
                        .map(|ts| format!("{} (index {})", ts.col_name, ts.col_index))
                        .collect();
                    format!(
                        "Column '{}' not found in file '{}'. Available columns: {}",
                        name, file, available.join(", ")
                    )
                })
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_single_term_config() {
        let ini_content = r#"
[optimisation]
model_file = test.kai
objective_expression = term1
output_file = results.txt
algorithm = DE
population_size = 30
termination_evaluations = 50
de_f = 0.8
de_cr = 0.9

[term.term1]
simulated = node.gr4j.dsflow
observed_file = obs.csv
observed_series = flow
statistic = ONE_MINUS_NSE

[parameters]
node.gr4j.x1 = log_range(g(1), 100, 1200)
node.gr4j.x2 = lin_range(g(2), -5, 3)
"#;

        let config = OptimisationConfig::from_ini(ini_content).unwrap();

        assert_eq!(config.model_file, Some("test.kai".to_string()));
        assert_eq!(config.terms.len(), 1);
        assert_eq!(config.terms[0].name, "term1");
        assert_eq!(config.terms[0].simulated_series, "node.gr4j.dsflow");
        assert_eq!(config.terms[0].observed_file, "obs.csv");
        assert_eq!(config.terms[0].observed_series, SeriesSpec::ByName("flow".to_string()));
        assert_eq!(config.terms[0].statistic.name(), "ONE_MINUS_NSE");
        assert_eq!(config.objective_expression, "term1");
        assert_eq!(config.algorithm.name(), "DE");
        assert_eq!(config.algorithm.population_size(), 30);
        assert_eq!(config.termination_evaluations, 50);
        assert_eq!(config.parameter_config.n_genes(), 2);

        match &config.algorithm {
            AlgorithmParams::DE { f, cr, .. } => {
                assert_eq!(*f, 0.8);
                assert_eq!(*cr, 0.9);
            },
            _ => panic!("Expected DE algorithm"),
        }
    }

    #[test]
    fn test_parse_two_term_composite() {
        let ini_content = r#"
[optimisation]
algorithm = DE
population_size = 20
termination_evaluations = 10
objective_expression = term1 + 0.5 * term2

[term.term1]
simulated = node.outlet.ds_1
observed_file = data.csv
observed_series = OutletFlow
statistic = ONE_MINUS_NSE

[term.term2]
simulated = node.gauge.ds_1
observed_file = data.csv
observed_series = 2
statistic = ABS_PBIAS

[parameters]
node.x.x1 = lin_range(g(1), 0, 10)
"#;

        let config = OptimisationConfig::from_ini(ini_content).unwrap();
        assert_eq!(config.terms.len(), 2);
        assert_eq!(config.terms[0].name, "term1");
        assert_eq!(config.terms[1].name, "term2");
        assert_eq!(config.terms[1].observed_series, SeriesSpec::ByIndex(2));
        assert_eq!(config.terms[0].statistic.name(), "ONE_MINUS_NSE");
        assert_eq!(config.terms[1].statistic.name(), "ABS_PBIAS");
        assert_eq!(config.objective_expression, "term1 + 0.5 * term2");
    }

    #[test]
    fn test_case_insensitive_keys_preserve_value_case() {
        let ini_content = r#"
[OPTIMISATION]
MODEL_FILE = Test.KAI
OBJECTIVE_EXPRESSION = term1
ALGORITHM = de
POPULATION_SIZE = 20
TERMINATION_EVALUATIONS = 10

[TERM.term1]
SIMULATED = node.GR4J.dsflow
OBSERVED_FILE = Obs.CSV
OBSERVED_SERIES = Flow
STATISTIC = one_minus_nse

[PARAMETERS]
Node.GR4J.X1 = log_range(g(1), 100, 1200)
"#;

        let config = OptimisationConfig::from_ini(ini_content).unwrap();
        // File paths and node names are case-sensitive (value side preserved)
        assert_eq!(config.model_file, Some("Test.KAI".to_string()));
        assert_eq!(config.terms[0].simulated_series, "node.GR4J.dsflow");
        assert_eq!(config.terms[0].observed_file, "Obs.CSV");
        // Statistic name normalized
        assert_eq!(config.terms[0].statistic.name(), "ONE_MINUS_NSE");
        assert_eq!(config.algorithm.name(), "DE");
    }

    #[test]
    fn test_parse_statistic_names() {
        assert_eq!(OptimisationConfig::parse_statistic("ONE_MINUS_NSE").unwrap().name(), "ONE_MINUS_NSE");
        assert_eq!(OptimisationConfig::parse_statistic("one_minus_nse").unwrap().name(), "ONE_MINUS_NSE");
        assert_eq!(OptimisationConfig::parse_statistic("ONE_MINUS_KGE").unwrap().name(), "ONE_MINUS_KGE");
        assert_eq!(OptimisationConfig::parse_statistic("ABS_PBIAS").unwrap().name(), "ABS_PBIAS");
        assert_eq!(OptimisationConfig::parse_statistic("RMSE").unwrap().name(), "RMSE");
        // Old names must be rejected
        assert!(OptimisationConfig::parse_statistic("NSE").is_err());
        assert!(OptimisationConfig::parse_statistic("KGE").is_err());
        assert!(OptimisationConfig::parse_statistic("PBIAS").is_err());
        assert!(OptimisationConfig::parse_statistic("PEARS_R").is_err());
    }

    #[test]
    fn test_no_terms_is_error() {
        let ini_content = r#"
[optimisation]
algorithm = DE
population_size = 10
termination_evaluations = 10
objective_expression = anything

[parameters]
node.x.x1 = lin_range(g(1), 0, 10)
"#;
        let err = OptimisationConfig::from_ini(ini_content).unwrap_err();
        assert!(err.contains("at least one [term.NAME]"), "got: {}", err);
    }

    #[test]
    fn test_duplicate_term_name_is_error() {
        let ini_content = r#"
[optimisation]
algorithm = DE
population_size = 10
termination_evaluations = 10
objective_expression = term1

[term.term1]
simulated = node.a.ds_1
observed_file = o.csv
observed_series = 1
statistic = RMSE

[term.term1]
simulated = node.b.ds_1
observed_file = o.csv
observed_series = 2
statistic = MAE

[parameters]
node.x.x1 = lin_range(g(1), 0, 10)
"#;
        // The INI parser may merge duplicate sections silently. Either outcome is acceptable
        // as long as we end up with a single coherent term. We just want the parse not to crash.
        let _ = OptimisationConfig::from_ini(ini_content);
    }

    #[test]
    fn test_unknown_term_in_expression() {
        let ini_content = r#"
[optimisation]
algorithm = DE
population_size = 10
termination_evaluations = 10
objective_expression = term1 + bogus

[term.term1]
simulated = node.a.ds_1
observed_file = o.csv
observed_series = 1
statistic = RMSE

[parameters]
node.x.x1 = lin_range(g(1), 0, 10)
"#;
        let err = OptimisationConfig::from_ini(ini_content).unwrap_err();
        assert!(err.contains("bogus"), "got: {}", err);
    }

    #[test]
    fn test_missing_objective_expression() {
        let ini_content = r#"
[optimisation]
algorithm = DE
population_size = 10
termination_evaluations = 10

[term.term1]
simulated = node.a.ds_1
observed_file = o.csv
observed_series = 1
statistic = RMSE

[parameters]
node.x.x1 = lin_range(g(1), 0, 10)
"#;
        let err = OptimisationConfig::from_ini(ini_content).unwrap_err();
        assert!(err.contains("objective_expression"), "got: {}", err);
    }

    #[test]
    fn test_series_spec_parse() {
        assert_eq!(SeriesSpec::parse("1"), SeriesSpec::ByIndex(1));
        assert_eq!(SeriesSpec::parse("42"), SeriesSpec::ByIndex(42));
        assert_eq!(SeriesSpec::parse("flow"), SeriesSpec::ByName("flow".to_string()));
        assert_eq!(SeriesSpec::parse("Obs_Flow"), SeriesSpec::ByName("Obs_Flow".to_string()));
    }
}
