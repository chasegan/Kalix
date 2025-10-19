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
use std::collections::HashMap;
use crate::io::custom_ini_parser::IniDocument;
use crate::numerical::opt::parameter_mapping::ParameterMappingConfig;
use crate::numerical::opt::objectives::ObjectiveFunction;
use crate::timeseries_input::TimeseriesInput;

/// Algorithm-specific parameters for optimization
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
    /// SCE-UA algorithm
    SCEUA {
        complexes: usize,
        points_per_complex: usize,
    },
}

impl AlgorithmParams {
    /// Get the algorithm name as a string
    pub fn name(&self) -> &str {
        match self {
            AlgorithmParams::DE { .. } => "DE",
            AlgorithmParams::CMAES { .. } => "CMAES",
            AlgorithmParams::SCEUA { .. } => "SCEUA",
        }
    }

    /// Get population size (common across all algorithms)
    pub fn population_size(&self) -> usize {
        match self {
            AlgorithmParams::DE { population_size, .. } => *population_size,
            AlgorithmParams::CMAES { population_size, .. } => *population_size,
            AlgorithmParams::SCEUA { complexes, points_per_complex } => complexes * points_per_complex,
        }
    }
}

/// Intermediate representation of optimisation configuration from INI format
///
/// This structure represents configuration data as nested HashMaps,
/// parsed from INI format before final validation and conversion to OptimisationConfig.
struct OptimisationConfigData {
    /// Sections mapped to their properties
    /// All keys are stored in lowercase for case-insensitive lookup
    sections: HashMap<String, HashMap<String, String>>,
}

impl OptimisationConfigData {
    /// Parse from INI format
    fn from_ini(content: &str) -> Result<Self, String> {
        let ini = IniDocument::parse(content)?;
        let mut sections = HashMap::new();

        // Convert IniDocument to HashMap structure with lowercase keys
        for (section_name, section) in &ini.sections {
            let mut properties = HashMap::new();
            for (prop_name, prop) in &section.properties {
                // Store with lowercase key for case-insensitive lookup
                // but preserve the original value (case-sensitive for paths, node names)
                properties.insert(prop_name.to_lowercase(), prop.value.clone());
            }
            sections.insert(section_name.to_lowercase(), properties);
        }

        Ok(Self { sections })
    }

    /// Get a section by name (case-insensitive)
    fn get_section(&self, name: &str) -> Option<&HashMap<String, String>> {
        self.sections.get(&name.to_lowercase())
    }

    /// Get a property from a section (case-insensitive)
    fn get_property(&self, section: &str, key: &str) -> Option<&str> {
        self.get_section(section)
            .and_then(|s| s.get(&key.to_lowercase()))
            .map(|v| v.as_str())
    }

    /// Get a property or return error (case-insensitive)
    fn require_property(&self, section: &str, key: &str) -> Result<&str, String> {
        self.get_property(section, key)
            .ok_or_else(|| format!("Missing '{}' property in [{}] section", key, section))
    }

    /// Check if a section exists (case-insensitive)
    fn has_section(&self, name: &str) -> bool {
        self.sections.contains_key(&name.to_lowercase())
    }
}

/// Optimisation configuration from INI format
///
/// This structure represents a complete optimisation run configuration,
/// parsed from INI format.
#[derive(Debug, Clone)]
pub struct OptimisationConfig {
    // [General] section
    pub model_file: Option<String>,  // Optional: can be provided via inline model instead
    pub observed_data_series: String,  // Will be "file.csv.name" or "file.csv.N"
    pub simulated_series: String,
    pub objective_function: ObjectiveFunction,
    pub output_file: Option<String>,

    // [Algorithm] section - Common parameters
    pub termination_evaluations: usize,  // Termination criterion: stop after approximately this many function evaluations
    pub random_seed: Option<u64>,
    pub n_threads: usize,

    // [Algorithm] section - Algorithm-specific parameters
    pub algorithm: AlgorithmParams,

    // [Parameters] section
    pub parameter_config: ParameterMappingConfig,

    // [Reporting] section
    pub report_frequency: usize,
    pub verbose: bool,
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
        // Parse [General] section
        let model_file = data.get_property("general", "model_file").map(|s| s.to_string());

        // Parse observed data series (by name or index)
        let observed_data_series = if let Some(val) = data.get_property("general", "observed_data_by_name") {
            val.to_string()
        } else if let Some(val) = data.get_property("general", "observed_data_by_index") {
            val.to_string()
        } else {
            return Err("Must specify either 'observed_data_by_name' or 'observed_data_by_index' in [General] section".to_string());
        };

        let simulated_series = data.require_property("general", "simulated_series")?.to_string();

        let objective_str = data.require_property("general", "objective_function")?;
        let objective_function = Self::parse_objective_function(objective_str)?;

        let output_file = data.get_property("general", "output_file")
            .map(|s| s.to_string());

        // Parse [Algorithm] section - Common parameters
        let termination_evaluations = data.require_property("algorithm", "termination_evaluations")?
            .parse::<usize>()
            .map_err(|_| "Invalid 'termination_evaluations' value")?;

        let random_seed = data.get_property("algorithm", "random_seed")
            .and_then(|p| p.parse::<u64>().ok());

        let n_threads = data.get_property("algorithm", "n_threads")
            .and_then(|p| p.parse::<usize>().ok())
            .unwrap_or(1);  // Default to single-threaded

        // Parse algorithm-specific parameters
        let algorithm_name = data.require_property("algorithm", "algorithm")?
            .to_uppercase();

        let algorithm = match algorithm_name.as_str() {
            "DE" => {
                let population_size = data.require_property("algorithm", "population_size")?
                    .parse::<usize>()
                    .map_err(|_| "Invalid 'population_size' for DE")?;

                let f = data.get_property("algorithm", "de_f")
                    .and_then(|p| p.parse::<f64>().ok())
                    .unwrap_or(0.8);

                let cr = data.get_property("algorithm", "de_cr")
                    .and_then(|p| p.parse::<f64>().ok())
                    .unwrap_or(0.9);

                AlgorithmParams::DE { population_size, f, cr }
            },
            "CMAES" => {
                let population_size = data.require_property("algorithm", "population_size")?
                    .parse::<usize>()
                    .map_err(|_| "Invalid 'population_size' for CMA-ES")?;

                let sigma = data.require_property("algorithm", "sigma")?
                    .parse::<f64>()
                    .map_err(|_| "Invalid 'sigma' for CMA-ES")?;

                AlgorithmParams::CMAES { population_size, sigma }
            },
            "SCEUA" | "SCE-UA" => {
                let complexes = data.require_property("algorithm", "complexes")?
                    .parse::<usize>()
                    .map_err(|_| "Invalid 'complexes' for SCE-UA")?;

                let points_per_complex = data.get_property("algorithm", "points_per_complex")
                    .and_then(|p| p.parse::<usize>().ok())
                    .unwrap_or(19);  // Common default

                AlgorithmParams::SCEUA { complexes, points_per_complex }
            },
            _ => return Err(format!(
                "Unknown algorithm: '{}'. Valid options: DE, CMAES, SCEUA",
                algorithm_name
            )),
        };

        // Parse [Parameters] section
        let parameters_section = data.get_section("parameters")
            .ok_or_else(|| "Missing [Parameters] section".to_string())?;

        let mut param_strings = Vec::new();
        for (key, value) in parameters_section {
            // Each property is a parameter mapping: "node.x.y = log_range(g(1), min, max)"
            let mapping_str = format!("{} = {}", key, value);
            param_strings.push(mapping_str);
        }

        let parameter_config = ParameterMappingConfig::from_strings(
            param_strings.iter().map(|s| s.as_str()).collect()
        )?;

        // Parse [Reporting] section (optional)
        let report_frequency = data.get_property("reporting", "report_frequency")
            .and_then(|p| p.parse::<usize>().ok())
            .unwrap_or(10);

        let verbose = data.get_property("reporting", "verbose")
            .map(|p| p.to_lowercase() == "true")
            .unwrap_or(false);

        Ok(Self {
            model_file,
            observed_data_series,
            simulated_series,
            objective_function,
            output_file,
            termination_evaluations,
            random_seed,
            n_threads,
            algorithm,
            parameter_config,
            report_frequency,
            verbose,
        })
    }

    /// Parse objective function string to enum (case-insensitive)
    fn parse_objective_function(s: &str) -> Result<ObjectiveFunction, String> {
        match s.to_uppercase().as_str() {
            "NSE" => Ok(ObjectiveFunction::NashSutcliffe),
            "LNSE" => Ok(ObjectiveFunction::NashSutcliffeLog),
            "RMSE" => Ok(ObjectiveFunction::RMSE),
            "MAE" => Ok(ObjectiveFunction::MAE),
            "KGE" => Ok(ObjectiveFunction::KlingGupta),
            "PBIAS" => Ok(ObjectiveFunction::PercentBias),
            _ => Err(format!("Unknown objective function: {}. Valid options: NSE, LNSE, RMSE, MAE, KGE, PBIAS", s)),
        }
    }
}

/// Load observed timeseries data from a series identifier string
///
/// Parses strings in format:
/// - By name: "/path/to/file.csv.column_name"
/// - By index: "/path/to/file.csv.N" (1-based index)
///
/// # Arguments
/// * `series_id` - Series identifier string (e.g., "data.csv.ObsFlow" or "data.csv.3")
///
/// # Returns
/// The requested TimeseriesInput containing the observed data
pub fn load_observed_timeseries(series_id: &str) -> Result<TimeseriesInput, String> {
    // Find the CSV file path by looking for ".csv"
    let csv_pos = series_id.rfind(".csv")
        .ok_or_else(|| format!("Invalid observed data format '{}'. Expected: file.csv.column_name or file.csv.N", series_id))?;

    let file_end = csv_pos + 4; // Position after ".csv"
    let file_path = &series_id[..file_end];

    // Extract column identifier (everything after the last dot following .csv)
    let column_id = if file_end < series_id.len() && series_id.chars().nth(file_end) == Some('.') {
        &series_id[file_end + 1..]
    } else {
        return Err(format!("Invalid observed data format '{}'. Expected: file.csv.column_name or file.csv.N", series_id));
    };

    // Load all timeseries from the file
    let all_timeseries = TimeseriesInput::load(file_path)
        .map_err(|e| format!("Error loading observed data file '{}': {}", file_path, e))?;

    if all_timeseries.is_empty() {
        return Err(format!("No timeseries found in file '{}'", file_path));
    }

    // Find the requested column by index or name
    if let Ok(col_index) = column_id.parse::<usize>() {
        // By index (1-based)
        if col_index < 1 || col_index > all_timeseries.len() {
            return Err(format!(
                "Column index {} out of range (1-{}) in file '{}'",
                col_index, all_timeseries.len(), file_path
            ));
        }
        Ok(all_timeseries[col_index - 1].clone())
    } else {
        // By name (case-insensitive)
        all_timeseries.iter()
            .find(|ts| ts.col_name.to_lowercase() == column_id.to_lowercase())
            .cloned()
            .ok_or_else(|| {
                let available: Vec<String> = all_timeseries.iter()
                    .map(|ts| format!("{} (index {})", ts.col_name, ts.col_index))
                    .collect();
                format!(
                    "Column '{}' not found in file '{}'. Available columns: {}",
                    column_id, file_path, available.join(", ")
                )
            })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_optimisation_config() {
        let ini_content = r#"
[General]
model_file = test.kai
observed_data_by_name = obs.csv.flow
simulated_series = node.gr4j.dsflow
objective_function = NSE
output_file = results.txt

[Algorithm]
algorithm = DE
population_size = 30
termination_evaluations = 50
de_f = 0.8
de_cr = 0.9

[Parameters]
node.gr4j.x1 = log_range(g(1), 100, 1200)
node.gr4j.x2 = lin_range(g(2), -5, 3)

[Reporting]
report_frequency = 5
verbose = true
"#;

        let config = OptimisationConfig::from_ini(ini_content).unwrap();

        assert_eq!(config.model_file, Some("test.kai".to_string()));
        assert_eq!(config.observed_data_series, "obs.csv.flow");
        assert_eq!(config.simulated_series, "node.gr4j.dsflow");
        assert_eq!(config.objective_function, ObjectiveFunction::NashSutcliffe);
        assert_eq!(config.algorithm.name(), "DE");
        assert_eq!(config.algorithm.population_size(), 30);
        assert_eq!(config.termination_evaluations, 50);
        assert_eq!(config.parameter_config.n_genes(), 2);
        assert_eq!(config.report_frequency, 5);
        assert_eq!(config.verbose, true);

        // Verify algorithm-specific parameters
        match &config.algorithm {
            AlgorithmParams::DE { f, cr, .. } => {
                assert_eq!(*f, 0.8);
                assert_eq!(*cr, 0.9);
            },
            _ => panic!("Expected DE algorithm"),
        }
    }

    #[test]
    fn test_case_insensitive_parsing() {
        let ini_content = r#"
[GENERAL]
MODEL_FILE = Test.KAI
observed_DATA_by_name = Obs.CSV.Flow
Simulated_Series = node.GR4J.dsflow
OBJECTIVE_FUNCTION = nse

[algorithm]
ALGORITHM = de
POPULATION_SIZE = 20
TERMINATION_EVALUATIONS = 10

[parameters]
Node.GR4J.X1 = log_range(g(1), 100, 1200)

[reporting]
VERBOSE = TRUE
"#;

        let config = OptimisationConfig::from_ini(ini_content).unwrap();

        // File paths are case-sensitive
        assert_eq!(config.model_file, Some("Test.KAI".to_string()));
        assert_eq!(config.observed_data_series, "Obs.CSV.Flow");

        // Node names in series are case-sensitive
        assert_eq!(config.simulated_series, "node.GR4J.dsflow");

        // Objective function parsing is case-insensitive
        assert_eq!(config.objective_function, ObjectiveFunction::NashSutcliffe);

        // Algorithm is normalized to uppercase
        assert_eq!(config.algorithm.name(), "DE");
        assert_eq!(config.algorithm.population_size(), 20);

        assert_eq!(config.verbose, true);
    }

    #[test]
    fn test_parse_objective_functions() {
        assert_eq!(
            OptimisationConfig::parse_objective_function("NSE").unwrap(),
            ObjectiveFunction::NashSutcliffe
        );
        assert_eq!(
            OptimisationConfig::parse_objective_function("nse").unwrap(),
            ObjectiveFunction::NashSutcliffe
        );
        assert_eq!(
            OptimisationConfig::parse_objective_function("KGE").unwrap(),
            ObjectiveFunction::KlingGupta
        );
        assert_eq!(
            OptimisationConfig::parse_objective_function("kge").unwrap(),
            ObjectiveFunction::KlingGupta
        );
        assert!(OptimisationConfig::parse_objective_function("INVALID").is_err());
    }
}
