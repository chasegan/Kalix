extern crate csv;

use crate::timeseries::Timeseries;
use crate::tid::utils::{date_string_to_u64_flexible, date_string_to_u64_with_format, u64_to_date_string_for_step_size};
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

    // Create a new csv reader with flexible record lengths
    // This allows rows with trailing commas (extra empty fields) without error
    let mut reader = csv::ReaderBuilder::new()
        .flexible(true)
        .from_path(filename)
        .map_err(|e| format!("Failed to open file '{}': {}", filename, e))?;

    // Get the first row (what csv crate thinks are headers)
    let first_row = reader.headers()
        .map_err(|_| format!("Error reading first row from '{}'", filename))?;

    // Check if the first cell is actually a date (meaning no header row exists)
    let has_header = match first_row.get(0) {
        Some(first_cell) => {
            // If it parses as a date, then this is data, not a header
            date_string_to_u64_flexible(first_cell).is_err()
        }
        None => return Err(format!("Empty file '{}'", filename))
    };

    // Calculate effective header length, ignoring trailing empty columns (from trailing commas)
    let mut headers_len = first_row.len();
    while headers_len > 1 && first_row.get(headers_len - 1).map(|s| s.trim().is_empty()).unwrap_or(false) {
        headers_len -= 1;
    }
    let n_data_cols = headers_len.saturating_sub(1); // exclude the index column

    // Initialize timeseries with column names. step_size is left as 0 here; it gets inferred
    // from the timestamps after the data has been loaded (see infer_step_size below).
    if has_header {
        // Use actual column names from the header row (trimmed of whitespace)
        for i in 1..headers_len {
            let mut ts = Timeseries::new(0);
            ts.name = first_row.get(i).unwrap_or("").trim().to_string();
            answer.push(ts);
        }
    } else {
        // Generate default column names (just the column number)
        for i in 1..headers_len {
            let mut ts = Timeseries::new(0);
            ts.name = format!("{}", i);
            answer.push(ts);
        }
    }

    // Detect date format from first data row, then reuse for all subsequent rows
    let mut detected_format: Option<&str> = None;
    let mut file_line = 1;

    // If there's no header, we need to process the first row as data
    if !has_header {
        file_line += 1;

        // Parse the timestamp column (first column)
        let t_str = first_row.get(0)
            .ok_or_else(|| format!("Missing timestamp in '{}' line {}", filename, file_line))?;

        // Detect format on first data row
        let (t_u64, format) = date_string_to_u64_flexible(t_str)
            .map_err(|e| format!("{} in '{}' line {}", e, filename, file_line))?;
        detected_format = Some(format);

        // Parse each data column into the respective timeseries
        for i in 0..n_data_cols {
            let field = first_row.get(i + 1)
                .ok_or_else(|| format!("Missing data column {} in '{}' line {}", i + 1, filename, file_line))?;

            let value: f64 = if field.trim().is_empty() {
                f64::NAN
            } else {
                field.trim().parse()
                    .map_err(|_| format!("Invalid number '{}' in '{}' line {} column {}",
                        field, filename, file_line, i + 1))?
            };

            answer[i].push(t_u64, value);
        }
    }

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
            // Get the field value (might be empty for missing data)
            let field = record.get(i + 1)
                .ok_or_else(|| format!("Missing data column {} in '{}' line {}", i + 1, filename, file_line))?;

            // Parse the data value as a float
            // If empty or whitespace-only, treat as missing data (NaN)
            let value: f64 = if field.trim().is_empty() {
                f64::NAN
            } else {
                field.trim().parse()
                    .map_err(|_| format!("Invalid number '{}' in '{}' line {} column {}",
                        field, filename, file_line, i + 1))?
            };

            answer[i].push(t_u64, value);
        }
    }

    // Set the start_timestamp and infer step_size from the loaded timestamps.
    // TODO: I should get rid of this "start_timestamp" property. It is a recipe for disaster.
    let inferred_step_size = infer_step_size(&answer.first().map(|ts| ts.timestamps.as_slice()).unwrap_or(&[]))
        .map_err(|e| format!("In '{}': {}", filename, e))?;
    for ts in answer.iter_mut() {
        if ts.len() > 0 {
            ts.start_timestamp = ts.timestamps[0];
        }
        if let Some(step_size) = inferred_step_size {
            ts.step_size = step_size;
        }
        // If step_size could not be inferred (single row or empty), step_size remains 0.
        // The downstream simulation step-size validation will surface any mismatch.
    }

    // Return
    Ok(answer)
}


/// Infer the step_size (in seconds) from a sequence of timestamps. Returns None if there are
/// fewer than two timestamps to compare. Returns an error if the spacing between consecutive
/// timestamps is not constant (the simulation engine assumes regularly-spaced input data).
fn infer_step_size(timestamps: &[u64]) -> Result<Option<u64>, String> {
    if timestamps.len() < 2 {
        return Ok(None);
    }
    let step_size = timestamps[1].saturating_sub(timestamps[0]);
    if step_size == 0 {
        return Err(format!(
            "Input timestamps are not strictly increasing: rows 1 and 2 have the same timestamp ({}).",
            timestamps[0]
        ));
    }
    // Validate that all subsequent gaps match. Cheap to check (single pass) and catches
    // missing/duplicated rows or DST-style shifts that would otherwise silently corrupt results.
    for i in 2..timestamps.len() {
        let gap = timestamps[i].saturating_sub(timestamps[i - 1]);
        if gap != step_size {
            return Err(format!(
                "Input timestamps are not regularly spaced: expected step_size {}s but row {} -> {} has gap {}s. \
                 The simulation requires evenly-spaced timestamps.",
                step_size,
                i,
                i + 1,
                gap
            ));
        }
    }
    Ok(Some(step_size))
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

    // Build the data section. Pick a single date format for the whole file based on the
    // step_size of the first series (all series in a write share the same step_size in
    // practice). Sub-daily data gets ISO datetime; daily-or-coarser gets date-only.
    let mut i = 0;
    if timeseries_vector.len() > 0 {
        let step_size = timeseries_vector[0].step_size;
        for timestamp in timeseries_vector[0].timestamps.iter() {
            let timestamp_string = u64_to_date_string_for_step_size(*timestamp, step_size);
            data_string.push_str(&timestamp_string);
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


pub fn csv_to_string_vec(s: &str) -> Vec<String> {
    s.trim_end_matches(|c: char| c == ',' || c.is_whitespace())
        .split(",")
        .map(|part| part.trim().to_lowercase())
        .collect()
}