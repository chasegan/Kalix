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

    /// Write a two-day Pixie pair to a unique temp dir; returns the base path.
    fn write_pixie_fixture(test_name: &str) -> std::path::PathBuf {
        use crate::io::pixie_io;
        use crate::timeseries::Timeseries;

        let dir = std::env::temp_dir()
            .join("kalix_tests")
            .join(format!("{}_{}", test_name, uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&dir).unwrap();

        let mut ts = Timeseries::new_daily();
        ts.name = "value".to_string();
        ts.start_timestamp =
            crate::tid::utils::date_string_to_u64_flexible("2000-01-01").unwrap().0;
        ts.values = vec![1.0, 2.0, 3.0];

        let base_path = dir.join("climate");
        pixie_io::write_series_with_precision(base_path.to_str().unwrap(), &[&ts], true)
            .expect("write pixie fixture");
        base_path
    }

    /// A `[data]` entry names the `.pxt` half of a Pixie pair; the `.pxb` is
    /// read alongside it and never appears in the model file.
    #[test]
    fn read_model_string_accepts_pixie_input() {
        let base_path = write_pixie_fixture("ini_pixie_input");

        let ini = format!(
            "[kalix]\n\
             start = 2000-01-01\n\
             end = 2000-01-03\n\
             \n\
             [data]\n\
             {}.pxt\n\
             \n\
             [node.a]\n\
             loc = 0, 0\n\
             type = inflow\n\
             inflow = data.climate_pxt.by_name.value\n\
             ds_1 = sink\n\
             \n\
             [node.sink]\n\
             loc = 1, 1\n\
             type = blackhole\n\
             \n\
             [outputs]\n\
             node.a.dsflow\n",
            base_path.display()
        );

        let mut model = IniModelIO::read_model_string(&ini)
            .unwrap_or_else(|e| panic!("model with Pixie input failed to load: {}", e));
        model.configure().expect("configure");
        model.run().expect("run");

        let idx = model
            .data_cache
            .get_series_idx("node.a.dsflow", false)
            .expect("output series");
        assert_eq!(model.data_cache.series[idx].sum(), 6.0);

        let _ = std::fs::remove_dir_all(base_path.parent().unwrap());
    }

    /// Naming the `.pxb` half in `[data]` is refused, and the message names the
    /// `.pxt` to use instead. It is a `Parse` error, not `Io`: the file is there
    /// and readable, it is the model file that names the wrong half of the pair.
    #[test]
    fn read_model_string_pixie_pxb_is_parse_error() {
        let base_path = write_pixie_fixture("ini_pixie_pxb");

        let ini = format!(
            "[kalix]\n\
             start = 2000-01-01\n\
             end = 2000-01-03\n\
             \n\
             [data]\n\
             {}.pxb\n\
             \n\
             [node.bh]\n\
             type = blackhole\n\
             loc = 1, 2\n",
            base_path.display()
        );

        match IniModelIO::read_model_string(&ini) {
            Ok(_) => panic!("expected an error, got Ok"),
            Err(KalixIoError::Parse(msg)) => {
                assert!(
                    msg.contains("climate.pxt"),
                    "error should name the .pxt to use instead, got: {}",
                    msg
                );
            }
            Err(other) => panic!("expected KalixIoError::Parse, got {:?}", other),
        }

        let _ = std::fs::remove_dir_all(base_path.parent().unwrap());
    }

    /// A Pixie source missing its companion is a filesystem problem, so it must
    /// stay `Io` (an `OSError` at the PyO3 boundary) rather than being mistaken
    /// for unreadable content, and must name the file that's actually absent.
    #[test]
    fn read_model_string_pixie_missing_companion_is_io_error() {
        let base_path = write_pixie_fixture("ini_pixie_missing_companion");
        std::fs::remove_file(format!("{}.pxb", base_path.display())).unwrap();

        let ini = format!(
            "[kalix]\n\
             start = 2000-01-01\n\
             end = 2000-01-03\n\
             \n\
             [data]\n\
             {}.pxt\n\
             \n\
             [node.bh]\n\
             type = blackhole\n\
             loc = 1, 2\n",
            base_path.display()
        );

        match IniModelIO::read_model_string(&ini) {
            Ok(_) => panic!("expected an error, got Ok"),
            Err(KalixIoError::Io(msg)) => {
                assert!(
                    msg.contains("companion") && msg.contains(".pxb"),
                    "error should name the missing companion, got: {}",
                    msg
                );
            }
            Err(other) => panic!("expected KalixIoError::Io, got {:?}", other),
        }

        let _ = std::fs::remove_dir_all(base_path.parent().unwrap());
    }
}
