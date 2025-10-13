/// INI file parsing for calibration configuration
///
/// This module parses calibration configuration from INI files using the
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
use crate::io::custom_ini_parser::IniDocument;
use crate::numerical::opt::parameter_mapping::CalibrationConfig;
use crate::numerical::opt::objectives::ObjectiveFunction;
use crate::timeseries_input::TimeseriesInput;

/// Calibration configuration loaded from INI file
#[derive(Debug, Clone)]
pub struct CalibrationIniConfig {
    // [General] section
    pub model_file: String,
    pub observed_data_series: String,  // Will be "file.csv.name" or "file.csv.N"
    pub simulated_series: String,
    pub objective_function: ObjectiveFunction,
    pub output_file: Option<String>,

    // [Algorithm] section
    pub algorithm: String,  // Case-insensitive, normalized to uppercase
    pub population_size: usize,
    pub max_generations: usize,
    pub de_f: f64,
    pub de_cr: f64,
    pub random_seed: Option<u64>,

    // [Parameters] section
    pub parameter_config: CalibrationConfig,

    // [Reporting] section
    pub report_frequency: usize,
    pub verbose: bool,
}

impl CalibrationIniConfig {
    /// Load calibration configuration from INI file
    pub fn from_file(path: &str) -> Result<Self, String> {
        let content = fs::read_to_string(path)
            .map_err(|e| format!("Failed to read calibration config file '{}': {}", path, e))?;

        Self::from_string(&content)
    }

    /// Parse calibration configuration from INI string
    pub fn from_string(content: &str) -> Result<Self, String> {
        let ini = IniDocument::parse(content)?;

        // Parse [General] section (case-insensitive)
        let general = Self::get_section_ci(&ini, "General")?;

        let model_file = Self::get_property_ci(&general, "model_file")?
            .value.clone();  // Case-sensitive value

        // Parse observed data series (by name or index)
        let observed_data_series = if let Some(prop) = Self::try_get_property_ci(&general, "observed_data_by_name") {
            prop.value.clone()  // Case-sensitive value
        } else if let Some(prop) = Self::try_get_property_ci(&general, "observed_data_by_index") {
            prop.value.clone()  // Case-sensitive value
        } else {
            return Err("Must specify either 'observed_data_by_name' or 'observed_data_by_index' in [General] section".to_string());
        };

        let simulated_series = Self::get_property_ci(&general, "simulated_series")?
            .value.clone();  // Case-sensitive value

        let objective_str = Self::get_property_ci(&general, "objective_function")?
            .value.clone();
        let objective_function = Self::parse_objective_function(&objective_str)?;

        let output_file = Self::try_get_property_ci(&general, "output_file")
            .map(|p| p.value.clone());  // Case-sensitive value

        // Parse [Algorithm] section (case-insensitive)
        let algorithm_section = Self::get_section_ci(&ini, "Algorithm")?;

        let algorithm = Self::get_property_ci(&algorithm_section, "algorithm")?
            .value.to_uppercase();  // Normalize to uppercase

        let population_size = Self::get_property_ci(&algorithm_section, "population_size")?
            .value.parse::<usize>()
            .map_err(|_| "Invalid 'population_size' value")?;

        let max_generations = Self::get_property_ci(&algorithm_section, "max_generations")?
            .value.parse::<usize>()
            .map_err(|_| "Invalid 'max_generations' value")?;

        let de_f = Self::try_get_property_ci(&algorithm_section, "de_f")
            .and_then(|p| p.value.parse::<f64>().ok())
            .unwrap_or(0.8);

        let de_cr = Self::try_get_property_ci(&algorithm_section, "de_cr")
            .and_then(|p| p.value.parse::<f64>().ok())
            .unwrap_or(0.9);

        let random_seed = Self::try_get_property_ci(&algorithm_section, "random_seed")
            .and_then(|p| p.value.parse::<u64>().ok());

        // Parse [Parameters] section (keys are case-insensitive, but values are case-sensitive)
        let parameters_section = Self::get_section_ci(&ini, "Parameters")?;

        let mut param_strings = Vec::new();
        for (key, prop) in &parameters_section.properties {
            // Each property is a parameter mapping: "node.x.y = log_range(g(1), min, max)"
            // Key and value both preserved as-is (values are case-sensitive for node names)
            let mapping_str = format!("{} = {}", key, prop.value);
            param_strings.push(mapping_str);
        }

        let parameter_config = CalibrationConfig::from_strings(
            param_strings.iter().map(|s| s.as_str()).collect()
        )?;

        // Parse [Reporting] section (optional, case-insensitive)
        let report_frequency = if let Some(section) = Self::try_get_section_ci(&ini, "Reporting") {
            Self::try_get_property_ci(&section, "report_frequency")
                .and_then(|p| p.value.parse::<usize>().ok())
                .unwrap_or(10)
        } else {
            10
        };

        let verbose = if let Some(section) = Self::try_get_section_ci(&ini, "Reporting") {
            Self::try_get_property_ci(&section, "verbose")
                .map(|p| p.value.to_lowercase() == "true")
                .unwrap_or(false)
        } else {
            false
        };

        Ok(Self {
            model_file,
            observed_data_series,
            simulated_series,
            objective_function,
            output_file,
            algorithm,
            population_size,
            max_generations,
            de_f,
            de_cr,
            random_seed,
            parameter_config,
            report_frequency,
            verbose,
        })
    }

    /// Get section by name (case-insensitive)
    fn get_section_ci<'a>(ini: &'a IniDocument, name: &str) -> Result<&'a crate::io::custom_ini_parser::IniSection, String> {
        let name_lower = name.to_lowercase();
        for (section_name, section) in &ini.sections {
            if section_name.to_lowercase() == name_lower {
                return Ok(section);
            }
        }
        Err(format!("Missing [{}] section in calibration config", name))
    }

    /// Try to get section by name (case-insensitive), returns None if not found
    fn try_get_section_ci<'a>(ini: &'a IniDocument, name: &str) -> Option<&'a crate::io::custom_ini_parser::IniSection> {
        let name_lower = name.to_lowercase();
        for (section_name, section) in &ini.sections {
            if section_name.to_lowercase() == name_lower {
                return Some(section);
            }
        }
        None
    }

    /// Get property by name (case-insensitive)
    fn get_property_ci<'a>(section: &'a crate::io::custom_ini_parser::IniSection, name: &str) -> Result<&'a crate::io::custom_ini_parser::IniProperty, String> {
        let name_lower = name.to_lowercase();
        for (prop_name, prop) in &section.properties {
            if prop_name.to_lowercase() == name_lower {
                return Ok(prop);
            }
        }
        Err(format!("Missing '{}' property", name))
    }

    /// Try to get property by name (case-insensitive), returns None if not found
    fn try_get_property_ci<'a>(section: &'a crate::io::custom_ini_parser::IniSection, name: &str) -> Option<&'a crate::io::custom_ini_parser::IniProperty> {
        let name_lower = name.to_lowercase();
        for (prop_name, prop) in &section.properties {
            if prop_name.to_lowercase() == name_lower {
                return Some(prop);
            }
        }
        None
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
    fn test_parse_calibration_config() {
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
max_generations = 50
de_f = 0.8
de_cr = 0.9

[Parameters]
node.gr4j.x1 = log_range(g(1), 100, 1200)
node.gr4j.x2 = lin_range(g(2), -5, 3)

[Reporting]
report_frequency = 5
verbose = true
"#;

        let config = CalibrationIniConfig::from_string(ini_content).unwrap();

        assert_eq!(config.model_file, "test.kai");
        assert_eq!(config.observed_data_series, "obs.csv.flow");
        assert_eq!(config.simulated_series, "node.gr4j.dsflow");
        assert_eq!(config.objective_function, ObjectiveFunction::NashSutcliffe);
        assert_eq!(config.algorithm, "DE");
        assert_eq!(config.population_size, 30);
        assert_eq!(config.max_generations, 50);
        assert_eq!(config.parameter_config.n_genes(), 2);
        assert_eq!(config.report_frequency, 5);
        assert_eq!(config.verbose, true);
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
MAX_GENERATIONS = 10

[parameters]
Node.GR4J.X1 = log_range(g(1), 100, 1200)

[reporting]
VERBOSE = TRUE
"#;

        let config = CalibrationIniConfig::from_string(ini_content).unwrap();

        // File paths are case-sensitive
        assert_eq!(config.model_file, "Test.KAI");
        assert_eq!(config.observed_data_series, "Obs.CSV.Flow");

        // Node names in series are case-sensitive
        assert_eq!(config.simulated_series, "node.GR4J.dsflow");

        // Objective function parsing is case-insensitive
        assert_eq!(config.objective_function, ObjectiveFunction::NashSutcliffe);

        // Algorithm is normalized to uppercase
        assert_eq!(config.algorithm, "DE");

        assert_eq!(config.verbose, true);
    }

    #[test]
    fn test_parse_objective_functions() {
        assert_eq!(
            CalibrationIniConfig::parse_objective_function("NSE").unwrap(),
            ObjectiveFunction::NashSutcliffe
        );
        assert_eq!(
            CalibrationIniConfig::parse_objective_function("nse").unwrap(),
            ObjectiveFunction::NashSutcliffe
        );
        assert_eq!(
            CalibrationIniConfig::parse_objective_function("KGE").unwrap(),
            ObjectiveFunction::KlingGupta
        );
        assert_eq!(
            CalibrationIniConfig::parse_objective_function("kge").unwrap(),
            ObjectiveFunction::KlingGupta
        );
        assert!(CalibrationIniConfig::parse_objective_function("INVALID").is_err());
    }
}
