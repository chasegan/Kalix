extern crate csv;

use crate::timeseries::Timeseries;
use crate::tid::utils::{date_string_to_u64_flexible, date_string_to_u64_with_format, u64_to_date_string};
use std::fs;
use std::path::Path;

#[derive(Debug)]
pub enum CsvError {
    OpenFileError,
    ReadError(String),
    WriteError(String),
}

impl From<CsvError> for String {
    fn from(error: CsvError) -> Self {
        match error {
            CsvError::OpenFileError => "Failed to open file".to_string(),
            CsvError::ReadError(msg) => format!("Read error: {}", msg),
            CsvError::WriteError(msg) => format!("Write error: {}", msg),
        }
    }
}

pub fn read_ts(filename: &str) -> Result<Vec<Timeseries>, String> {
    // Here is where we will construct our result
    let mut answer: Vec<Timeseries> = Vec::new();

    // Create a new csv reader
    let mut reader = csv::Reader::from_path(filename)
        .map_err(|e| format!("Failed to open file '{}': {}", filename, e))?;

    // Get the headers from the reader
    let headers = reader.headers()
        .map_err(|_| format!("Error reading headers from '{}'", filename))?;

    let headers_len = headers.len();
    let n_data_cols = headers_len.saturating_sub(1); // exclude the index column

    // Initialize timeseries with column names from headers
    for i in 1..headers_len {
        let mut ts = Timeseries::new_daily();
        ts.name = headers.get(i).unwrap_or("").to_string();
        answer.push(ts);
    }

    // Detect date format from first row, then reuse for all subsequent rows
    let mut detected_format: Option<&str> = None;
    let mut file_line = 1;

    // Iterate through the records and parse the data
    for result in reader.records() {
        file_line += 1;

        // Unwrap the record
        let record = result.map_err(|e|
            format!("Error reading '{}' line {}: {}", filename, file_line, e))?;

        // Parse the timestamp column (first column)
        let t_str = record.get(0)
            .ok_or_else(|| format!("Missing timestamp in '{}' line {}", filename, file_line))?;

        // Detect format on first data row
        let t_u64 = if detected_format.is_none() {
            let (timestamp, format) = date_string_to_u64_flexible(t_str)
                .map_err(|e| format!("{} in '{}' line {}", e, filename, file_line))?;
            detected_format = Some(format);
            timestamp
        } else {
            // Use detected format for subsequent rows (much faster)
            date_string_to_u64_with_format(t_str, detected_format.unwrap())
                .map_err(|e| format!("Parse error in '{}' line {}: {}", filename, file_line, e))?
        };

        // Parse each data column into the respective timeseries
        for i in 0..n_data_cols {
            // Parse the data value as a float
            let value: f64 = record.get(i + 1)
                .ok_or_else(|| format!("Missing data column {} in '{}' line {}", i + 1, filename, file_line))?
                .parse()
                .map_err(|_| format!("Invalid number in '{}' line {} column {}", filename, file_line, i + 1))?;

            answer[i].push(t_u64, value);
        }
    }

    // Set the start_timestamp
    // TODO: I should get rid of this "start_timestamp" property. It is a recipe for disaster.
    for ts in answer.iter_mut() {
        if ts.len() > 0 {
            ts.start_timestamp = ts.timestamps[0];
        }
    }

    // Return
    Ok(answer)
}


pub fn write_ts(filename: &str, timeseries_vector: Vec<&Timeseries>) -> Result<(), CsvError> {

    // Check that all timeseries in the vector have the same length
    let data_length = match timeseries_vector.len() {
        0 => { 0 }
        _ => {timeseries_vector[0].timestamps.len() }
    };
    for tsv in &timeseries_vector {
        if tsv.timestamps.len() != data_length {
            return Err(CsvError::WriteError("Cannot handle timeseries with different lengths.".to_string()))
        }
    }

    // Starting building the file contents, starting with the header row
    let mut data_string = String::new();
    data_string.push_str("Time");
    for ts in timeseries_vector.iter() {
        data_string.push_str(",");
        data_string.push_str(&ts.name);
    }
    data_string.push_str("\r\n");

    // Build the data section
    let mut i = 0;
    if timeseries_vector.len() > 0 {
        for timestamp in timeseries_vector[0].timestamps.iter() {
            let timestamp_string = u64_to_date_string(*timestamp);
            data_string.push_str(&timestamp_string);
            //data_string.push_str(",");
            for ts in timeseries_vector.iter() {
                let value = ts.values[i];
                data_string.push_str(format!(",{value}").as_str());
            }
            data_string.push_str("\r\n");
            i += 1;
        }
    }

    // Write it all to file
    let filename_path = Path::new(filename);
    match fs::write(filename_path, data_string) {
        Ok(_) => Ok(()),
        Err(_) => Err(CsvError::WriteError(format!("Error writing file {filename}.")))
    }
}



pub fn csv_string_to_f64_vec(s: &str) -> Result<Vec<f64>, String> {
    let mut result = Vec::new();
    let ss = s.trim_end_matches(|c: char| c == ',' || c.is_whitespace()).split(",");
    for (i, part) in ss.enumerate() {
        match part.trim().parse::<f64>() {
            Ok(val) => result.push(val),
            Err(_) => {
                return Err(format!("Failed to parse '{}' as f64 at position {} in string '{}'", part, i, s));
            }
        }
    }
    Ok(result)
}


