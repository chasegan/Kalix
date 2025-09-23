use std::collections::HashMap;
use crate::model::Model;
use configparser::ini::Ini;
use crate::io::ini_model_io_versions::ini_model_io_0_0_1::result_map_to_model_0_0_1;

#[derive(Default)]
pub struct IniModelIO {
    pub name: String,
}


impl IniModelIO {
    pub fn new() -> IniModelIO {
        IniModelIO {
            ..Default::default()
        }
    }

    /// Parses a hydrological model from a file.
    ///
    /// This function takes an INI-formatted file containing a complete model definition
    /// and converts it into a Model object. The format must follow the Kalix model
    /// specification.
    ///
    /// # Arguments
    ///
    /// * `path` - A string slice containing the path to the model file.
    ///
    /// # Returns
    ///
    /// * `Ok(Model)` - Successfully parsed and validated model ready for simulation
    /// * `Err(String)` - Error message describing parsing failure, validation error, or
    ///   unsupported format version.
    pub fn read_model_file(&self, path: &str) -> Result<Model, String> {
        // This is just a wrapper in case we want to change the lib we use for this.
        let result_map = ini!(safe path);
        Self::result_map_to_model(result_map?)
    }

    /// Parses a hydrological model from a string.
    ///
    /// This function takes an INI-formatted string containing a complete model definition
    /// and converts it into a Model object. The format must follow the Kalix model
    /// specification.
    ///
    /// # Arguments
    ///
    /// * `ini_string` - A string slice containing the complete INI-formatted model definition
    ///
    /// # Returns
    ///
    /// * `Ok(Model)` - Successfully parsed and validated model ready for simulation
    /// * `Err(String)` - Error message describing parsing failure, validation error, or
    ///   unsupported format version.
    pub fn read_model_string(&self, ini_string: &str) -> Result<Model, String> {
        let result_map = Ini::new().read(String::from(ini_string));
        Self::result_map_to_model(result_map?)
    }

    /// Converts a result_map to a hydrological model.
    ///
    /// # Arguments
    ///
    /// * `result_map` - A Result struct containing a Hashmap representing the ini string parsed
    /// into major sections (for the Ok variant) or a String representing the parsing error (for
    /// the Err variant).
    ///
    /// # Returns
    ///
    /// * `Ok(Model)` - Successfully parsed and validated model ready for simulation
    /// * `Err(String)` - Error message describing parsing failure, validation error, or
    ///   unsupported format version.
    pub fn result_map_to_model(map: HashMap<String, HashMap<String, Option<String>>>) -> Result<Model, String> {
        // The first thing we want to read is the ini format version.
        // After that we will just loop through all the sections in the order they appear.
        let ini_format_version = map["attributes"]["ini_version"].clone().unwrap_or("".to_string());

        // Use appropriate interpreter for given ini format version
        match ini_format_version.as_str() {
            "0.0.1" => {
                result_map_to_model_0_0_1(map)
            }
            _ => {
                Err(format!("Unsupported model version: {}", ini_format_version))
            }
        }
    }
}