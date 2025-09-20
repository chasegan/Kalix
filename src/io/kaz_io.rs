use crate::timeseries::Timeseries;
use crate::tid::utils::{u64_to_date_string, wrap_to_u64, wrap_to_i64};
use crate::io::compression::gorilla::{GorillaCompressor, TimeValueDouble, TimeValueFloat};
use std::fs::File;
use std::io::{BufRead, BufReader, BufWriter, Read, Seek, SeekFrom, Write};
use chrono::{Utc, NaiveDateTime, TimeZone, Timelike};

const CODEC_GORILLA_DOUBLE: u16 = 0;
const CODEC_GORILLA_FLOAT: u16 = 1;

#[derive(Debug)]
pub enum KazError {
    IoError(std::io::Error),
    CompressionError(String),
    ParseError(String),
    SeriesNotFound(String),
}

impl std::fmt::Display for KazError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            KazError::IoError(e) => write!(f, "IO error: {}", e),
            KazError::CompressionError(msg) => write!(f, "Compression error: {}", msg),
            KazError::ParseError(msg) => write!(f, "Parse error: {}", msg),
            KazError::SeriesNotFound(name) => write!(f, "Series not found: {}", name),
        }
    }
}

impl std::error::Error for KazError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            KazError::IoError(e) => Some(e),
            _ => None,
        }
    }
}

impl From<std::io::Error> for KazError {
    fn from(error: std::io::Error) -> Self {
        KazError::IoError(error)
    }
}

impl From<KazError> for String {
    fn from(error: KazError) -> Self {
        match error {
            KazError::IoError(e) => format!("IO error: {}", e),
            KazError::CompressionError(msg) => format!("Compression error: {}", msg),
            KazError::ParseError(msg) => format!("Parse error: {}", msg),
            KazError::SeriesNotFound(name) => format!("Series not found: {}", name),
        }
    }
}

#[derive(Debug, Clone)]
pub struct SeriesInfo {
    pub name: String,
    pub point_count: usize,
    pub start_time: u64,
    pub end_time: u64,
    pub timestep_seconds: u64,
}

impl std::fmt::Display for SeriesInfo {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        let start_str = u64_to_date_string(self.start_time);
        let end_str = u64_to_date_string(self.end_time);
        write!(
            f,
            "SeriesInfo{{name='{}', points={}, start={}, end={}, timestep={}s}}",
            self.name, self.point_count, start_str, end_str, self.timestep_seconds
        )
    }
}

#[derive(Debug)]
struct SeriesMetadata {
    index: usize,
    offset: u64,
    start_time: u64,
    end_time: u64,
    timestep: u64,
    length: usize,
    series_name: String,
}

/// Read all time series from Kalix compressed format (.kaz/.kai files)
///
/// # Arguments
/// * `base_path` - Base path without extension (e.g. "/path/to/data" for data.kaz and data.kai)
///
/// # Returns
/// * `Vec<Timeseries>` - All time series from the file
///
/// # Errors
/// * `KalixTsError` - If files cannot be read or data is invalid
pub fn read_all_series(base_path: &str) -> Result<Vec<Timeseries>, KazError> {
    let metadata_path = format!("{}.kai", base_path);
    let binary_path = format!("{}.kaz", base_path);

    // Read metadata
    let metadata_list = read_metadata_file(&metadata_path)?;

    // Read all series from binary file
    let mut file = File::open(binary_path)?;
    let mut result = Vec::new();

    for meta in metadata_list {
        let series = read_series_from_binary(&mut file, &meta)?;
        result.push(series);
    }

    Ok(result)
}

/// Read a specific time series by name from Kalix compressed format
///
/// # Arguments
/// * `base_path` - Base path without extension
/// * `series_name` - Name of the series to read
///
/// # Returns
/// * `Timeseries` - The requested time series
///
/// # Errors
/// * `KalixTsError::SeriesNotFound` - If the named series doesn't exist
/// * `KalixTsError` - If files cannot be read or data is invalid
pub fn read_series(base_path: &str, series_name: &str) -> Result<Timeseries, KazError> {
    let metadata_path = format!("{}.kai", base_path);
    let binary_path = format!("{}.kaz", base_path);

    // Read metadata to find the series
    let metadata_list = read_metadata_file(&metadata_path)?;
    let target_metadata = metadata_list
        .iter()
        .find(|meta| meta.series_name == series_name)
        .ok_or_else(|| KazError::SeriesNotFound(series_name.to_string()))?;

    // Read specific series from binary file
    let mut file = File::open(binary_path)?;
    read_series_from_binary(&mut file, target_metadata)
}

/// Get series information without reading the binary data
///
/// # Arguments
/// * `base_path` - Base path without extension
///
/// # Returns
/// * `Vec<SeriesInfo>` - Metadata for all series in the file
///
/// # Errors
/// * `KalixTsError` - If metadata file cannot be read or is invalid
pub fn get_series_info(base_path: &str) -> Result<Vec<SeriesInfo>, KazError> {
    let metadata_path = format!("{}.kai", base_path);
    let metadata_list = read_metadata_file(&metadata_path)?;

    let result = metadata_list
        .into_iter()
        .map(|meta| SeriesInfo {
            name: meta.series_name,
            point_count: meta.length,
            start_time: meta.start_time,
            end_time: meta.end_time,
            timestep_seconds: meta.timestep,
        })
        .collect();

    Ok(result)
}

/// Write time series data to Kalix compressed format with default 32-bit precision
///
/// # Arguments
/// * `base_path` - Base path without extension
/// * `series_list` - Vector of references to time series to write
///
/// # Errors
/// * `KalixTsError` - If files cannot be written or series list is empty
pub fn write_series(base_path: &str, series_list: &[&Timeseries]) -> Result<(), KazError> {
    write_series_with_precision(base_path, series_list, false)
}

/// Write time series data to Kalix compressed format with specified precision
///
/// # Arguments
/// * `base_path` - Base path without extension
/// * `series_list` - Vector of references to time series to write
/// * `use_64bit_precision` - true for 64-bit double precision, false for 32-bit float precision
///
/// # Errors
/// * `KalixTsError` - If files cannot be written or series list is empty
pub fn write_series_with_precision(
    base_path: &str,
    series_list: &[&Timeseries],
    use_64bit_precision: bool
) -> Result<(), KazError> {
    if series_list.is_empty() {
        return Err(KazError::ParseError("No series data to write".to_string()));
    }

    let binary_path = format!("{}.kaz", base_path);
    let metadata_path = format!("{}.kai", base_path);

    let mut metadata_list = Vec::new();

    // Write binary file and collect metadata
    {
        let mut file = File::create(&binary_path)?;
        let mut current_offset = 0u64;

        for (i, series) in series_list.iter().enumerate() {
            let offset = current_offset;

            // Determine codec based on precision preference
            let codec = if use_64bit_precision {
                CODEC_GORILLA_DOUBLE
            } else {
                CODEC_GORILLA_FLOAT
            };

            // Detect timestep in seconds
            let timestep_seconds = detect_timestep(series);

            // Compress data
            let compressed = compress_series(series, timestep_seconds, codec)?;

            // Write block: codec(2) + length(4) + data
            write_u16(&mut file, codec)?;
            write_u32(&mut file, compressed.len() as u32)?;
            file.write_all(&compressed)?;

            // Update current offset
            current_offset += 2 + 4 + compressed.len() as u64;

            // Store metadata
            let metadata = SeriesMetadata {
                index: i + 1, // Base-1 indexing to match Java
                offset,
                start_time: if series.timestamps.is_empty() { 0 } else { series.timestamps[0] },
                end_time: if series.timestamps.is_empty() {
                    0
                } else {
                    series.timestamps[series.timestamps.len() - 1]
                },
                timestep: timestep_seconds, // Already in seconds
                length: series.timestamps.len(),
                series_name: series.name.clone(),
            };
            metadata_list.push(metadata);
        }
    }

    // Write metadata file
    write_metadata_file(&metadata_path, &metadata_list)?;

    Ok(())
}

fn read_metadata_file(metadata_path: &str) -> Result<Vec<SeriesMetadata>, KazError> {
    let file = File::open(metadata_path)?;
    let reader = BufReader::new(file);
    let mut lines = reader.lines();

    // Read and validate header
    let header = lines
        .next()
        .ok_or_else(|| KazError::ParseError("Empty metadata file".to_string()))??;
    if !header.starts_with("index,") {
        return Err(KazError::ParseError("Invalid metadata file format".to_string()));
    }

    let mut result = Vec::new();
    for (line_num, line) in lines.enumerate() {
        let line = line?;
        let line = line.trim();
        if line.is_empty() {
            continue;
        }

        let meta = parse_metadata_line(&line, line_num + 2)?;
        result.push(meta);
    }

    // Sort by index to ensure correct order
    result.sort_by_key(|m| m.index);

    Ok(result)
}

fn parse_metadata_line(line: &str, line_num: usize) -> Result<SeriesMetadata, KazError> {
    let fields = parse_csv_line(line);

    if fields.len() != 7 {
        return Err(KazError::ParseError(format!(
            "Invalid metadata line format at line {}: {}",
            line_num, line
        )));
    }

    let index = fields[0].trim().parse::<usize>().map_err(|_| {
        KazError::ParseError(format!("Invalid index at line {}: {}", line_num, fields[0]))
    })?;

    let offset = fields[1].trim().parse::<u64>().map_err(|_| {
        KazError::ParseError(format!("Invalid offset at line {}: {}", line_num, fields[1]))
    })?;

    let start_time = parse_timestamp(&fields[2].trim()).map_err(|e| {
        KazError::ParseError(format!("Invalid start time at line {}: {}", line_num, e))
    })?;

    let end_time = parse_timestamp(&fields[3].trim()).map_err(|e| {
        KazError::ParseError(format!("Invalid end time at line {}: {}", line_num, e))
    })?;

    let timestep = fields[4].trim().parse::<u64>().map_err(|_| {
        KazError::ParseError(format!("Invalid timestep at line {}: {}", line_num, fields[4]))
    })?;

    let length = fields[5].trim().parse::<usize>().map_err(|_| {
        KazError::ParseError(format!("Invalid length at line {}: {}", line_num, fields[5]))
    })?;

    let series_name = fields[6].trim().to_string();

    Ok(SeriesMetadata {
        index,
        offset,
        start_time,
        end_time,
        timestep,
        length,
        series_name,
    })
}

fn parse_csv_line(line: &str) -> Vec<String> {
    let mut result = Vec::new();
    let mut current_field = String::new();
    let mut in_quotes = false;

    for c in line.chars() {
        match c {
            '"' => in_quotes = !in_quotes,
            ',' if !in_quotes => {
                result.push(current_field);
                current_field = String::new();
            }
            _ => current_field.push(c),
        }
    }

    result.push(current_field);
    result
}

fn parse_timestamp(timestamp_str: &str) -> Result<u64, String> {
    if timestamp_str.contains('T') {
        // Full timestamp with time component
        let dt = NaiveDateTime::parse_from_str(timestamp_str, "%Y-%m-%dT%H:%M:%S")
            .map_err(|e| format!("Failed to parse timestamp '{}': {}", timestamp_str, e))?;
        Ok(wrap_to_u64(dt.and_utc().timestamp()))
    } else {
        // Date only (midnight)
        let full_timestamp = format!("{}T00:00:00", timestamp_str);
        let dt = NaiveDateTime::parse_from_str(&full_timestamp, "%Y-%m-%dT%H:%M:%S")
            .map_err(|e| format!("Failed to parse date '{}': {}", timestamp_str, e))?;
        Ok(wrap_to_u64(dt.and_utc().timestamp()))
    }
}

fn read_series_from_binary(file: &mut File, meta: &SeriesMetadata) -> Result<Timeseries, KazError> {
    // Seek to the series block
    file.seek(SeekFrom::Start(meta.offset))?;

    // Read block header
    let codec = read_u16(file)?;
    let data_length = read_u32(file)?;

    // Read compressed data
    let mut compressed_data = vec![0u8; data_length as usize];
    file.read_exact(&mut compressed_data)?;

    // Decompress based on codec
    let points: Vec<(u64, f64)> = match codec {
        CODEC_GORILLA_DOUBLE => {
            let compressor = GorillaCompressor::new(meta.timestep); // Already in seconds
            let double_points = compressor.decompress_double(&compressed_data)
                .map_err(|e| KazError::CompressionError(format!("Failed to decompress double data: {}", e)))?;
            double_points.into_iter().map(|p| {
                // Convert back from Unix seconds to wrapped u64
                let wrapped_timestamp = wrap_to_u64(p.timestamp as i64);
                (wrapped_timestamp, p.value)
            }).collect()
        },
        CODEC_GORILLA_FLOAT => {
            let compressor = GorillaCompressor::new(meta.timestep); // Already in seconds
            let float_points = compressor.decompress_float(&compressed_data)
                .map_err(|e| KazError::CompressionError(format!("Failed to decompress float data: {}", e)))?;
            float_points.into_iter().map(|p| {
                // Convert back from Unix seconds to wrapped u64
                let wrapped_timestamp = wrap_to_u64(p.timestamp as i64);
                (wrapped_timestamp, p.value as f64)
            }).collect()
        },
        _ => return Err(KazError::CompressionError(format!("Unsupported codec: {}", codec))),
    };

    // Convert to Timeseries
    let mut timeseries = Timeseries::new_daily();
    timeseries.name = meta.series_name.clone();
    timeseries.step_size = meta.timestep;

    for (timestamp, value) in points {
        timeseries.push(timestamp, value);
    }

    if !timeseries.timestamps.is_empty() {
        timeseries.start_timestamp = timeseries.timestamps[0];
    }

    Ok(timeseries)
}

fn compress_series(series: &Timeseries, timestep_seconds: u64, codec: u16) -> Result<Vec<u8>, KazError> {
    if series.timestamps.is_empty() {
        return Ok(Vec::new());
    }

    let compressor = GorillaCompressor::new(timestep_seconds);

    match codec {
        CODEC_GORILLA_DOUBLE => {
            // Convert to TimeValueDouble format, unwrapping timestamps to i64 for gorilla
            let points: Vec<TimeValueDouble> = series.timestamps.iter()
                .zip(series.values.iter())
                .map(|(&timestamp, &value)| {
                    let unix_timestamp = wrap_to_i64(timestamp);
                    TimeValueDouble::new(unix_timestamp as u64, value)
                })
                .collect();

            compressor.compress_double(&points)
                .map_err(|e| KazError::CompressionError(format!("Failed to compress double data: {}", e)))
        },
        CODEC_GORILLA_FLOAT => {
            // Convert to TimeValueFloat format, unwrapping timestamps to i64 for gorilla
            let points: Vec<TimeValueFloat> = series.timestamps.iter()
                .zip(series.values.iter())
                .map(|(&timestamp, &value)| {
                    let unix_timestamp = wrap_to_i64(timestamp);
                    TimeValueFloat::new(unix_timestamp as u64, value as f32)
                })
                .collect();

            compressor.compress_float(&points)
                .map_err(|e| KazError::CompressionError(format!("Failed to compress float data: {}", e)))
        },
        _ => Err(KazError::CompressionError(format!("Unsupported codec: {}", codec))),
    }
}

fn detect_timestep(series: &Timeseries) -> u64 {
    if series.timestamps.len() < 2 {
        return 86400; // Default 86400 seconds (1 day)
    }

    // Use the step_size if it's reasonable, otherwise calculate from data
    if series.step_size > 0 && series.step_size < 1_000_000 {
        // step_size is in seconds
        series.step_size
    } else {
        // Calculate average interval from timestamps
        let total_interval = series.timestamps[series.timestamps.len() - 1] - series.timestamps[0];
        let avg_interval = total_interval / (series.timestamps.len() - 1) as u64;
        avg_interval
    }
}

fn write_metadata_file(metadata_path: &str, metadata_list: &[SeriesMetadata]) -> Result<(), KazError> {
    let file = File::create(metadata_path)?;
    let mut writer = BufWriter::new(file);

    // Write header
    writeln!(writer, "index,offset,start_time,end_time,timestep,length,series_name")?;

    // Write data rows
    for meta in metadata_list {
        let start_time = format_timestamp(meta.start_time);
        let end_time = format_timestamp(meta.end_time);

        writeln!(
            writer,
            "{},{},{},{},{},{},{}",
            meta.index,
            meta.offset,
            start_time,
            end_time,
            meta.timestep,
            meta.length,
            meta.series_name
        )?;
    }

    writer.flush()?;
    Ok(())
}

fn format_timestamp(timestamp: u64) -> String {
    match Utc.timestamp_opt(wrap_to_i64(timestamp), 0) {
        chrono::LocalResult::Single(dt) => {
            // Check if it's a whole day (midnight)
            if dt.time().hour() == 0 && dt.time().minute() == 0 && dt.time().second() == 0 {
                dt.format("%Y-%m-%d").to_string()
            } else {
                dt.format("%Y-%m-%dT%H:%M:%S").to_string()
            }
        }
        _ => {
            // Fallback for invalid timestamps
            format!("INVALID_TIMESTAMP_{}", timestamp)
        }
    }
}

fn read_u16(file: &mut File) -> Result<u16, KazError> {
    let mut buf = [0u8; 2];
    file.read_exact(&mut buf)?;
    Ok(u16::from_be_bytes(buf))
}

fn read_u32(file: &mut File) -> Result<u32, KazError> {
    let mut buf = [0u8; 4];
    file.read_exact(&mut buf)?;
    Ok(u32::from_be_bytes(buf))
}

fn write_u16(file: &mut File, value: u16) -> Result<(), KazError> {
    file.write_all(&value.to_be_bytes())?;
    Ok(())
}

fn write_u32(file: &mut File, value: u32) -> Result<(), KazError> {
    file.write_all(&value.to_be_bytes())?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    #[test]
    fn test_round_trip_single_series() {
        let mut ts = Timeseries::new_daily();
        ts.name = "test_series".to_string();

        // Use realistic timestamps (starting from 2020-01-01)
        let base_time = 1577836800u64; // 2020-01-01 00:00:00 UTC
        for i in 0..5 {
            let timestamp = wrap_to_u64((base_time + (i * 86400)) as i64);
            let value = 100.0 + (i as f64) * 5.0;
            ts.push(timestamp, value);
        }

        let base_path = "/tmp/test_kalix_ts";

        // Write
        write_series_with_precision(base_path, &[&ts], true).unwrap();

        // Read back
        let result = read_series(base_path, "test_series").unwrap();

        // Verify
        assert_eq!(result.name, ts.name);
        assert_eq!(result.timestamps, ts.timestamps);

        // Check values with some tolerance for floating point precision
        for (original, result) in ts.values.iter().zip(result.values.iter()) {
            assert!((original - result).abs() < 1e-10, "Values don't match: {} vs {}", original, result);
        }

        // Clean up
        let _ = fs::remove_file(format!("{}.kaz", base_path));
        let _ = fs::remove_file(format!("{}.kai", base_path));
    }

    #[test]
    fn test_read_all_series() {
        let mut ts1 = Timeseries::new_daily();
        ts1.name = "series1".to_string();

        let mut ts2 = Timeseries::new_daily();
        ts2.name = "series2".to_string();

        // Use realistic timestamps (starting from 2020-01-01)
        let base_time = 1577836800u64; // 2020-01-01 00:00:00 UTC
        for i in 0..3 {
            let timestamp = wrap_to_u64((base_time + (i * 86400)) as i64);
            ts1.push(timestamp, (i as f64) * 10.0);
            ts2.push(timestamp, (i as f64) * 100.0);
        }

        let base_path = "/tmp/test_kalix_multiple";

        // Write
        write_series_with_precision(base_path, &[&ts1, &ts2], false).unwrap();

        // Read all back
        let result = read_all_series(base_path).unwrap();

        // Verify
        assert_eq!(result.len(), 2);
        assert_eq!(result[0].name, "series1");
        assert_eq!(result[1].name, "series2");

        // Clean up
        let _ = fs::remove_file(format!("{}.kaz", base_path));
        let _ = fs::remove_file(format!("{}.kai", base_path));
    }

    #[test]
    fn test_get_series_info() {
        let mut ts = Timeseries::new_daily();
        ts.name = "info_test".to_string();

        // Use realistic timestamps (starting from 2020-01-01)
        let base_time = 1577836800u64; // 2020-01-01 00:00:00 UTC
        let start_timestamp = wrap_to_u64(base_time as i64);
        let end_timestamp = wrap_to_u64((base_time + 86400) as i64);

        ts.push(start_timestamp, 1.0);
        ts.push(end_timestamp, 2.0);

        let base_path = "/tmp/test_kalix_info";

        // Write
        write_series_with_precision(base_path, &[&ts], true).unwrap();

        // Get info
        let info = get_series_info(base_path).unwrap();

        // Verify
        assert_eq!(info.len(), 1);
        assert_eq!(info[0].name, "info_test");
        assert_eq!(info[0].point_count, 2);
        assert_eq!(info[0].start_time, start_timestamp);
        assert_eq!(info[0].end_time, end_timestamp);

        // Clean up
        let _ = fs::remove_file(format!("{}.kaz", base_path));
        let _ = fs::remove_file(format!("{}.kai", base_path));
    }

    #[test]
    fn test_float_vs_double_precision() {
        let mut ts = Timeseries::new_daily();
        ts.name = "precision_test".to_string();

        // Use realistic timestamps (starting from 2020-01-01)
        let base_time = 1577836800u64; // 2020-01-01 00:00:00 UTC
        ts.push(wrap_to_u64(base_time as i64), 1.234567890123456);
        ts.push(wrap_to_u64((base_time + 86400) as i64), 2.345678901234567);

        let base_path_double = "/tmp/test_kalix_double";
        let base_path_float = "/tmp/test_kalix_float";

        // Write with double precision
        write_series_with_precision(base_path_double, &[&ts], true).unwrap();

        // Write with float precision
        write_series_with_precision(base_path_float, &[&ts], false).unwrap();

        // Read back
        let result_double = read_series(base_path_double, "precision_test").unwrap();
        let result_float = read_series(base_path_float, "precision_test").unwrap();

        // Double precision should be more accurate
        let double_error = (ts.values[0] - result_double.values[0]).abs();
        let float_error = (ts.values[0] - result_float.values[0]).abs();

        assert!(double_error < float_error || double_error < 1e-15);

        // Clean up
        let _ = fs::remove_file(format!("{}.kaz", base_path_double));
        let _ = fs::remove_file(format!("{}.kai", base_path_double));
        let _ = fs::remove_file(format!("{}.kaz", base_path_float));
        let _ = fs::remove_file(format!("{}.kai", base_path_float));
    }
}