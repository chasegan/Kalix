extern crate csv;

use crate::timeseries::Timeseries;
use crate::tid::utils::{date_string_to_u64, u64_to_date_string};
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

pub fn read_ts(filename: &str) -> Result<Vec<Timeseries>, CsvError> {

    //Here is where we will construct our result
    let mut answer: Vec<Timeseries> = Vec::new();

    //Create a new csv reader
    let mut reader = match csv::Reader::from_path(filename) {
        Ok(r) => r,
        Err(_) => {
            return Err(CsvError::OpenFileError);
        }
    };

    //Get the headers from the reader
    let mut file_line = 1;
    let n_data_cols;
    match reader.headers() {
        Ok(headers) => {
            let headers_len = headers.len();
            n_data_cols = (headers_len as i32) - 1; //exclude the index column
            for i in 1..headers_len {
                let mut ts = Timeseries::new_daily();
                ts.name = headers.get(i).unwrap_or("").to_string();
                answer.push(ts);
            }
        },
        Err(_) => {
            return Err(CsvError::ReadError(format!("Error reading '{filename}' line {file_line}.")));
        }
    };
    // if n_data_cols <= 0 {
    //     return Err(CsvError::ReadError(format!("File '{filename}' has no data columns on line {file_line}.")));
    // }

    //Iterate through the records and parse the data
    for result in reader.records() {
        file_line += 1;

        //Unwrap the record
        let record = match result {
            Ok(r) => r,
            Err(e) => {
                println!("Error reading file '{filename}': {e}");
                return Err(CsvError::ReadError(format!("Error reading '{filename}' line {file_line}.")));
            }
        };

        //Parse the index column
        let t_str = record.get(0).unwrap();
        let t_u64 = date_string_to_u64(t_str);

        //Parse each data colum into the respective timeseries
        for i in 0..(n_data_cols as usize) {
            //Parse the data value as a float
            let f = match record.get(i + 1).unwrap().parse() {
                Ok(v) => v,
                Err(_) => {
                    let one_based_data_column = i + 1;
                    return Err(CsvError::ReadError(format!("Error reading '{filename}' line {file_line} data column {one_based_data_column}.")));
                }
            };
            let t = t_u64.unwrap();
            answer[i].push(t, f);
        }
    }

    //Return
    Ok(answer)
}


pub fn write_ts(filename: &str, timeseries_vector: Vec<Timeseries>) -> Result<(), CsvError> {

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



pub fn csv_string_to_f64_vec(s: &str) -> Result<Vec<f64>, CsvError> {
    let mut result = Vec::new();
    for (i, part) in s.split(",").enumerate() {
        match part.trim().parse::<f64>() {
            Ok(val) => result.push(val),
            Err(_) => {
                return Err(CsvError::ReadError(format!(
                    "Failed to parse '{}' as f64 at position {} in string '{}'", 
                    part, i, s
                )));
            }
        }
    }
    Ok(result)
}


