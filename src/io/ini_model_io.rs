use crate::model::Model;
use crate::io::custom_ini_parser::IniDocument;
use crate::io::error::KalixIoError;
use crate::io::ini_model_io_versions::ini_doc_model_io_0_0_1::{ini_doc_to_model_0_0_1, model_to_ini_doc_0_0_1};

/// Namespace for INI model I/O. Carries no state -- every method is a pure
/// function grouped here for discoverability (`IniModelIO::read_model_file(...)`).
pub struct IniModelIO;

impl IniModelIO {
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
    pub fn read_model_file(path: &str) -> Result<Model, KalixIoError> {
        // Read file content
        let content = std::fs::read_to_string(path)
            .map_err(|e| KalixIoError::Io(format!("Failed to read file '{}': {}", path, e)))?;

        // Convert to absolute path and extract the directory containing the model file
        let abs_path = std::path::Path::new(path)
            .canonicalize()
            .unwrap_or_else(|_| {
                // If canonicalize fails, try to make it absolute manually
                let path_obj = std::path::Path::new(path);
                if path_obj.is_absolute() {
                    path_obj.to_path_buf()
                } else {
                    std::env::current_dir().unwrap_or_else(|_| std::path::PathBuf::from(".")).join(path)
                }
            });

        let model_dir = abs_path
            .parent()
            .map(|p| p.to_path_buf())
            .unwrap_or_else(|| std::path::PathBuf::from("."));

        // Parse the model with the working directory set BEFORE loading any data
        // This allows relative paths in the INI to be resolved correctly
        let model = Self::read_model_string_with_working_directory(content.as_str(), Some(model_dir))?;

        Ok(model)
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
    pub fn read_model_string(ini_string: &str) -> Result<Model, KalixIoError> {
        Self::read_model_string_with_working_directory(ini_string, None)
    }

    /// Parses a hydrological model from a string with a specified working directory.
    ///
    /// # Arguments
    ///
    /// * `ini_string` - A string slice containing the complete INI-formatted model definition
    /// * `working_directory` - Optional working directory for resolving relative paths
    ///
    /// # Returns
    ///
    /// * `Ok(Model)` - Successfully parsed and validated model ready for simulation
    /// * `Err(String)` - Error message describing parsing failure, validation error, or
    ///   unsupported format version.
    pub fn read_model_string_with_working_directory(ini_string: &str, working_directory: Option<std::path::PathBuf>) -> Result<Model, KalixIoError> {
        let ini_doc = IniDocument::parse(ini_string).map_err(KalixIoError::Parse)?;
        let model = Self::ini_doc_to_model_with_working_directory(ini_doc, working_directory)?;
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
    pub fn ini_doc_to_model(ini_doc: IniDocument) -> Result<Model, KalixIoError> {
        Self::ini_doc_to_model_with_working_directory(ini_doc, None)
    }

    /// Converts an ini document to a hydrological model with a specified working directory.
    ///
    /// # Arguments
    ///
    /// * `ini_doc` - A Result struct containing a Hashmap representing the ini string parsed
    /// into major sections (for the Ok variant) or a String representing the parsing error (for
    /// the Err variant).
    /// * `working_directory` - Optional working directory for resolving relative paths
    ///
    /// # Returns
    ///
    /// * `Ok(Model)` - Successfully parsed and validated model ready for simulation
    /// * `Err(String)` - Error message describing parsing failure, validation error, or
    ///   unsupported format version.
    pub fn ini_doc_to_model_with_working_directory(ini_doc: IniDocument, working_directory: Option<std::path::PathBuf>) -> Result<Model, KalixIoError> {

        // Read kalix software version and model ini version
        let software_version = env!("KALIX_VERSION");
        let ini_version = ini_doc.get_property("kalix", "version")
            .unwrap_or(&"no-version".to_string())
            .to_string();

        // Use appropriate interpreter for given ini format version
        if (ini_version == software_version) ||
            (ini_version == "no-version") {
            // Use main reader function
            ini_doc_to_model_0_0_1(ini_doc, working_directory)
        } else {
            // Abort with error message
            Err(KalixIoError::Validate(format!("Wrong version! Kalix version = {}, but model specifies version = {}.", software_version, ini_version)))
        }

        // match ini_format_version.as_str() {
        //     "0.0.1" => {
        //         ini_doc_to_model_0_0_1(ini_doc, working_directory)
        //     }
        //     _ => {
        //     }
        // }
    }

    /// Convert a Model to an INI string
    pub fn model_to_string(model: &Model) -> String {
        // Get the ini doc
        let ini_doc = model_to_ini_doc_0_0_1(model);

        // Convert to string
        ini_doc.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn model_ini() -> &'static str {
        "[kalix]\n\
         start = 2000-01-01T00:00:00\n\
         end = 2000-01-10T00:00:00\n\
         \n\
         [node.bh]\n\
         type = blackhole\n\
         loc = 1, 2\n"
    }

    #[test]
    fn read_model_string_missing_input_file_is_io_error() {
        let ini = format!(
            "[kalix]\n\
             start = 2000-01-01T00:00:00\n\
             end = 2000-01-10T00:00:00\n\
             \n\
             [data]\n\
             ./does_not_exist_{}.csv\n\
             \n\
             [node.bh]\n\
             type = blackhole\n\
             loc = 1, 2\n",
            "kalix_ini_model_io_test"
        );

        let result = IniModelIO::read_model_string(&ini);
        match result {
            Ok(_) => panic!("expected an error, got Ok"),
            Err(KalixIoError::Io(_)) => {}
            Err(other) => panic!("expected KalixIoError::Io, got {:?}", other),
        }
    }

    #[test]
    fn read_model_string_malformed_ini_is_parse_error() {
        let result = IniModelIO::read_model_string("not valid ini [[[");
        match result {
            Ok(_) => panic!("expected an error, got Ok"),
            Err(KalixIoError::Parse(_)) => {}
            Err(other) => panic!("expected KalixIoError::Parse, got {:?}", other),
        }
    }

    #[test]
    fn read_model_file_missing_path_is_io_error() {
        let result = IniModelIO::read_model_file("does_not_exist_kalix_ini_model_io_test.ini");
        match result {
            Ok(_) => panic!("expected an error, got Ok"),
            Err(KalixIoError::Io(_)) => {}
            Err(other) => panic!("expected KalixIoError::Io, got {:?}", other),
        }
    }

    #[test]
    fn read_model_string_valid_model_loads() {
        assert!(IniModelIO::read_model_string(model_ini()).is_ok());
    }
}
