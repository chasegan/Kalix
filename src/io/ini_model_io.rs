use std::collections::HashMap;
use crate::model::Model;
use crate::io::custom_ini_parser::IniDocument;
use crate::io::ini_model_io_versions::ini_model_io_0_0_1::result_map_to_model_0_0_1;
use crate::io::ini_model_io_versions::ini_doc_model_io_0_0_1::ini_doc_to_model_0_0_1;

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
        let content = std::fs::read_to_string(path)
            .map_err(|e| format!("Failed to read file '{}': {}", path, e))?;
        self.read_model_string(content.as_str())
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
        let ini_doc = IniDocument::parse(ini_string)?;

        let use_old_version = false; //TODO: delete me
        if use_old_version {
            let result_map = ini_doc.to_legacy_format();
            let mut model = Self::result_map_to_model(result_map)?;
            model.ini_document = Some(ini_doc);
            Ok(model)
        } else {
            let model = Self::ini_doc_to_model(ini_doc)?;
            Ok(model)
        }
    }

    /// Converts an ini document to a hydrological model.
    ///
    /// # Arguments
    ///
    /// * `ini_doc` - A Result struct containing a Hashmap representing the ini string parsed
    /// into major sections (for the Ok variant) or a String representing the parsing error (for
    /// the Err variant).
    ///
    /// # Returns
    ///
    /// * `Ok(Model)` - Successfully parsed and validated model ready for simulation
    /// * `Err(String)` - Error message describing parsing failure, validation error, or
    ///   unsupported format version.
    pub fn ini_doc_to_model(ini_doc: IniDocument) -> Result<Model, String> {
        let ini_format_version = ini_doc.get_property("attributes", "ini_version")
            .unwrap_or(&"input-did-not-specify-format-version".to_string())
            .to_string();

        // Use appropriate interpreter for given ini format version
        match ini_format_version.as_str() {
            "0.0.1" => {
                ini_doc_to_model_0_0_1(ini_doc)
            }
            _ => {
                Err(format!("Unsupported model version: {}", ini_format_version))
            }
        }
    }


    /// Converts a result_map to a hydrological model.
    ///
    /// # Arguments
    ///
    /// * `result_map` - A Hashmap representing the ini string parsed into major sections.
    ///
    /// # Returns
    ///
    /// * `Ok(Model)` - Successfully parsed and validated model ready for simulation
    /// * `Err(String)` - Error message describing parsing failure, validation error, or
    ///   unsupported format version.
    pub fn result_map_to_model(map: HashMap<String, HashMap<String, Option<String>>>) -> Result<Model, String> {
        // The first thing we want to read is the ini format version.
        // After that we will just loop through all the sections in the order they appear.
        let ini_format_version = map.get("attributes")
            .and_then(|attrs| attrs.get("ini_version"))
            .and_then(|opt| opt.as_ref())
            .unwrap_or(&"input-did-not-specify-format-version".to_string())
            .to_string();

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