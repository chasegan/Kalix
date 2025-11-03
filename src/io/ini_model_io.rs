use crate::model::Model;
use crate::io::custom_ini_parser::IniDocument;
use crate::io::ini_model_io_versions::ini_doc_model_io_0_0_1::{ini_doc_to_model_0_0_1, model_to_ini_doc_0_0_1};

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
        let model = Self::ini_doc_to_model(ini_doc)?;
        Ok(model)
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


    /// Convert a Model to an INI string
    pub fn model_to_string(&self, model: &Model) -> String {
        // Get the ini doc
        let ini_doc = model_to_ini_doc_0_0_1(model);

        // Convert to string
        ini_doc.to_string()
    }
}