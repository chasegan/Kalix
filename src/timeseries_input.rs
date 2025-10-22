use crate::timeseries::Timeseries;
use crate::misc::misc_functions::sanitize_name;
use std::path::Path;

#[derive(Clone)]
#[derive(Default)]
pub struct TimeseriesInput {
    pub source_path: String,        //Probably a file path, e.g. "./0_data/flow_data.csv"
    pub source_name: String,        //Unique name for the source. Safe name can be based on the file name, e.g. "flow_data"
    pub col_index: usize,           //The index of the series in the data source (most have that concept)
    pub col_name: String,           //The name identifying the series within the source. "#1" or "GS123456_flow". Convert to safe names when matching cols.
    pub full_colindex_path: String, //This is the full name of the series within the model, using the index, e.g. "data.flow_data.#1"
    pub full_colname_path: String,  //This is the full name of the series within the model, using the column name, e.g. "data.flow_data.GS123456_flow"
    pub timeseries: Timeseries,     //The data
    pub reload_on_run: bool,        //Whether we want to reload the data for this series into the data_cache between runs
}

impl TimeseriesInput {
    pub fn new() -> TimeseriesInput {
        TimeseriesInput {
            ..Default::default()
        }
    }

    /// Loads the timeseries data file. A successful result contains a vector
    /// of TimeseriesInput structs (not just Timeseries).
    pub fn load(file_path: &str) -> Result<Vec<TimeseriesInput>, String> {
        match crate::io::csv_io::read_ts(file_path) {
            Ok(vts) => {
                let mut vinputts: Vec<TimeseriesInput> = vec![];

                // Create an object for each and add it
                for i in 0..vts.len() {
                    let mut inputts = TimeseriesInput::new();
                    let col_name = vts[i].name.clone();
                    let col_index = i + 1;
                    inputts.source_path = file_path.to_string();
                    let path = Path::new(file_path);

                    // Sanitize the source name (filename)
                    let source_name_raw = path.file_name().unwrap().to_str().unwrap().to_owned();
                    let source_name = sanitize_name(&source_name_raw);

                    // Sanitize the column name
                    let col_name_sanitized = sanitize_name(&col_name);

                    inputts.source_name = source_name.clone();
                    inputts.col_index = col_index;
                    inputts.col_name = col_name.clone();
                    inputts.full_colname_path = format!("data.{}.by_name.{}", source_name, col_name_sanitized);
                    inputts.full_colindex_path = format!("data.{}.by_index.{}", source_name, col_index);
                    inputts.timeseries = vts[i].clone();
                    inputts.reload_on_run = false;
                    vinputts.push(inputts);
                }
                Ok(vinputts)
            }
            Err(s) => {
                Err(format!("Error reading {}: {}", file_path, s))
            }
        }
    }


    /// Returns the length of the contained timeseries.
    pub fn len(&self) -> usize {
        self.timeseries.len()
    }

    /// Prints the name (full_colname_path) and length of the contained timeseries
    /// to stdout.
    pub fn print(&self) {
        println!("{}: {}", self.full_colname_path, self.len());
    }
}
