extern crate csv;

use crate::io::error::KalixIoError;
use crate::timeseries::Timeseries;
use crate::tid::utils::{append_date_string, date_string_to_u64_flexible, date_string_to_u64_with_format, needs_time_of_day};
use flate2::bufread::GzDecoder;
use flate2::{Compression, GzBuilder};
use std::fs;
use std::io::{self, BufRead, BufReader, BufWriter, Read, Seek, SeekFrom, Write};
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

/// How a CSV file is wrapped, named by extension: plain `.csv`, a one-entry
/// zip archive (`.csv.zip`) or a gzip stream (`.csv.gz`). The same CSV grammar sits inside all three.
#[derive(Clone, Copy, PartialEq, Debug)]
pub enum CsvCompression {
    None,
    Zip,
    Gzip,
}

/// The one extension test both the read and write paths key off, so the two
/// can never disagree about what counts as compressed.
pub fn csv_compression(filename: &str) -> CsvCompression {
    let lower = filename.to_ascii_lowercase();
    if lower.ends_with(".csv.zip") {
        CsvCompression::Zip
    } else if lower.ends_with(".csv.gz") {
        CsvCompression::Gzip
    } else {
        CsvCompression::None
    }
}

/// The archive's single entry name: the file name minus its `.zip` — pandas'
/// own convention (`flows.csv.zip` holds `flows.csv`). Total for any input:
/// a name not ending in `.zip` comes back unchanged (the suffix check keeps
/// the byte slice on an ASCII boundary, so no multibyte name can panic).
pub fn zip_inner_name(filename: &str) -> String {
    let name = Path::new(filename)
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| filename.to_string());
    if name.to_ascii_lowercase().ends_with(".zip") {
        name[..name.len() - 4].to_string()
    } else {
        name
    }
}

pub fn read_ts(filename: &str) -> Result<Vec<Timeseries>, KalixIoError> {
    // A .csv.zip / .csv.gz is the same CSV grammar inside a compression
    // wrapper — the parse below never sees the difference.
    let builder = || {
        let mut b = csv::ReaderBuilder::new();
        // Flexible record lengths: allows rows with trailing commas (extra
        // empty fields) without error.
        b.flexible(true);
        b
    };
    match csv_compression(filename) {
        CsvCompression::Zip => read_ts_zip(filename, builder()),
        CsvCompression::Gzip => read_ts_gzip(filename, builder()),
        CsvCompression::None => {
            let reader = builder().from_path(filename)
                .map_err(|e| KalixIoError::Io(format!("Failed to open file '{}': {}", filename, e)))?;
            read_ts_records(reader, filename)
        }
    }
}

fn read_ts_zip(filename: &str, builder: csv::ReaderBuilder) -> Result<Vec<Timeseries>, KalixIoError> {
    let file = fs::File::open(filename)
        .map_err(|e| KalixIoError::Io(format!("Failed to open file '{}': {}", filename, e)))?;
    let mut archive = zip::ZipArchive::new(file)
        .map_err(|e| KalixIoError::Io(format!("'{}' is not a readable zip archive: {}", filename, e)))?;
    // Pandas parity: a .csv.zip holds exactly one file. Reading "the first
    // of several" would silently guess; pandas refuses too.
    // by_index_raw classifies without opening the entry, so an archive
    // holding e.g. an encrypted second entry still gets the clear
    // "exactly one file" refusal rather than a decryption error.
    let mut file_entries: Vec<usize> = Vec::new();
    for i in 0..archive.len() {
        let entry = archive.by_index_raw(i)
            .map_err(|e| KalixIoError::Io(format!("Reading zip entry {} of '{}': {}", i, filename, e)))?;
        if !entry.is_dir() {
            file_entries.push(i);
        }
    }
    if file_entries.len() != 1 {
        return Err(KalixIoError::Io(format!(
            "'{}': a .csv.zip must hold exactly one file, found {}", filename, file_entries.len())));
    }
    let entry = archive.by_index(file_entries[0])
        .map_err(|e| KalixIoError::Io(format!("Reading '{}': {}", filename, e)))?;
    read_ts_records(builder.from_reader(entry), filename)
}

fn read_ts_gzip(filename: &str, builder: csv::ReaderBuilder) -> Result<Vec<Timeseries>, KalixIoError> {
    let mut file = fs::File::open(filename)
        .map_err(|e| KalixIoError::Io(format!("Failed to open file '{}': {}", filename, e)))?;
    // flate2 reads the header lazily, so a file that isn't gzip at all would
    // otherwise surface as a puzzling first-row error. Check the magic up front.
    let mut magic = [0u8; 2];
    let is_gzip = file.read_exact(&mut magic).is_ok() && magic == [0x1f, 0x8b];
    if !is_gzip {
        return Err(KalixIoError::Io(format!("'{}' is not a readable gzip file", filename)));
    }
    file.seek(SeekFrom::Start(0))
        .map_err(|e| KalixIoError::Io(format!("Reading '{}': {}", filename, e)))?;
    // 32 KiB, as flate2's own MultiGzDecoder buffers.
    let reader = BufReader::with_capacity(32 * 1024, file);
    read_ts_records(builder.from_reader(GzMembers::new(reader)), filename)
}

/// A gzip file read as Python's gzip (and so pandas) reads it: concatenated
/// members (`cat a.gz b.gz`, bgzip) are one stream, and zero padding after a
/// member is skipped. Any other trailing byte must start a valid member, so
/// garbage and truncation are errors (gzip(1) only warns on garbage).
struct GzMembers<R: BufRead> {
    member: Option<GzDecoder<R>>,
}

impl<R: BufRead> GzMembers<R> {
    fn new(reader: R) -> Self {
        GzMembers { member: Some(GzDecoder::new(reader)) }
    }
}

impl<R: BufRead> Read for GzMembers<R> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        loop {
            let Some(member) = self.member.as_mut() else { return Ok(0) };
            let n = match member.read(buf) {
                Ok(n) => n,
                Err(e) => {
                    self.member = None; // fused: a retry must not restart mid-stream
                    return Err(e);
                }
            };
            if n > 0 || buf.is_empty() {
                return Ok(n);
            }
            // The member ended on a verified trailer; the reader sits just past it.
            let mut rest = self.member.take().expect("member present").into_inner();
            if skip_zero_padding(&mut rest)? {
                return Ok(0);
            }
            self.member = Some(GzDecoder::new(rest));
        }
    }
}

/// Consumes zero bytes; true if the input ends there.
fn skip_zero_padding<R: BufRead>(reader: &mut R) -> io::Result<bool> {
    loop {
        let buf = reader.fill_buf()?;
        if buf.is_empty() {
            return Ok(true);
        }
        let zeros = buf.iter().take_while(|&&b| b == 0).count();
        let all_zero = zeros == buf.len();
        reader.consume(zeros);
        if !all_zero {
            return Ok(false);
        }
    }
}

fn read_ts_records<R: Read>(
    mut reader: csv::Reader<R>,
    filename: &str,
) -> Result<Vec<Timeseries>, KalixIoError> {
    // Here is where we will construct our result
    let mut answer: Vec<Timeseries> = Vec::new();

    // Get the first row (what csv crate thinks are headers) — where a corrupt
    // stream surfaces, so the underlying error is worth carrying.
    let first_row = reader.headers()
        .map_err(|e| KalixIoError::Io(format!("Error reading first row from '{}': {}", filename, e)))?;

    // Check if the first cell is actually a date (meaning no header row exists)
    let has_header = match first_row.get(0) {
        Some(first_cell) => {
            // If it parses as a date, then this is data, not a header
            date_string_to_u64_flexible(first_cell).is_err()
        }
        None => return Err(KalixIoError::Parse(format!("Empty file '{}'", filename)))
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

    // Timestamps are parsed for validation (regular spacing) and to anchor the
    // series, but stored only locally: series carry values on a regular grid.
    let mut row_timestamps: Vec<u64> = Vec::new();

    // If there's no header, we need to process the first row as data
    if !has_header {
        file_line += 1;

        // Parse the timestamp column (first column)
        let t_str = first_row.get(0)
            .ok_or_else(|| KalixIoError::Parse(format!("Missing timestamp in '{}' line {}", filename, file_line)))?;

        // Detect format on first data row
        let (t_u64, format) = date_string_to_u64_flexible(t_str)
            .map_err(|e| KalixIoError::Parse(format!("{} in '{}' line {}", e, filename, file_line)))?;
        detected_format = Some(format);

        // Parse each data column into the respective timeseries
        for i in 0..n_data_cols {
            let field = first_row.get(i + 1)
                .ok_or_else(|| KalixIoError::Parse(format!("Missing data column {} in '{}' line {}", i + 1, filename, file_line)))?;

            let value: f64 = if field.trim().is_empty() {
                f64::NAN
            } else {
                field.trim().parse()
                    .map_err(|_| KalixIoError::Parse(format!("Invalid number '{}' in '{}' line {} column {}",
                        field, filename, file_line, i + 1)))?
            };

            answer[i].values.push(value);
        }
        row_timestamps.push(t_u64);
    }

    // Iterate through the records and parse the data. One StringRecord is
    // reused for every row (the csv crate's documented zero-allocation
    // pattern) instead of allocating a fresh record per row.
    let mut record = csv::StringRecord::new();
    loop {
        match reader.read_record(&mut record) {
            Ok(true) => {}
            Ok(false) => break,
            Err(e) => return Err(
                KalixIoError::Parse(format!("Error reading '{}' line {}: {}", filename, file_line + 1, e))),
        }
        file_line += 1;

        // Parse the timestamp column (first column)
        let t_str = record.get(0)
            .ok_or_else(|| KalixIoError::Parse(format!("Missing timestamp in '{}' line {}", filename, file_line)))?;

        // Detect format on first data row
        let t_u64 = if detected_format.is_none() {
            let (timestamp, format) = date_string_to_u64_flexible(t_str)
                .map_err(|e| KalixIoError::Parse(format!("{} in '{}' line {}", e, filename, file_line)))?;
            detected_format = Some(format);
            timestamp
        } else {
            // Use detected format for subsequent rows (much faster)
            date_string_to_u64_with_format(t_str, detected_format.unwrap())
                .map_err(|e| KalixIoError::Parse(format!("Parse error in '{}' line {}: {}", filename, file_line, e)))?
        };

        // Parse each data column into the respective timeseries
        for i in 0..n_data_cols {
            // Get the field value (might be empty for missing data)
            let field = record.get(i + 1)
                .ok_or_else(|| KalixIoError::Parse(format!("Missing data column {} in '{}' line {}", i + 1, filename, file_line)))?;

            // Parse the data value as a float
            // If empty or whitespace-only, treat as missing data (NaN)
            let value: f64 = if field.trim().is_empty() {
                f64::NAN
            } else {
                field.trim().parse()
                    .map_err(|_| KalixIoError::Parse(format!("Invalid number '{}' in '{}' line {} column {}",
                        field, filename, file_line, i + 1)))?
            };

            answer[i].values.push(value);
        }
        row_timestamps.push(t_u64);
    }

    // Set the start_timestamp and infer step_size from the parsed row timestamps
    // (kept locally during the read: series store values only, on a regular grid).
    let inferred_step_size = infer_step_size(&row_timestamps)
        .map_err(|e| KalixIoError::Parse(format!("In '{}': {}", filename, e)))?;
    for ts in answer.iter_mut() {
        if ts.len() > 0 {
            ts.start_timestamp = row_timestamps[0];
        }
        if let Some(step_size) = inferred_step_size {
            ts.step_size = step_size;
        }
        // If step_size could not be inferred (single row or empty), step_size remains 0.
        // The reader stays permissive so plots and convert can open such a file;
        // configure() rejects it (TimeseriesInputDefinition::check_has_timestep).
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


/// Appends `value` to `buf` as text that reads back to exactly the same `f64`,
/// in exponent notation when the value is of extreme magnitude and that form
/// is shorter.
///
/// Rust's `Display` already prints the shortest round-trip digits, but never
/// uses exponent notation, so 1.38462e-47 came out as a 0.000…0138462 of fifty
/// characters and 1e20 as twenty-one digits (issue #169). `LowerExp` prints the
/// same digits with an exponent, so both spellings are exact and no fidelity is
/// traded. Values in the ordinary band, from 1e-4 up to (not including) 1e15 —
/// nearly all hydrological data — take the single `Display` path and are written
/// byte-for-byte as before, even where an exponent form would be a character or
/// two shorter (`0.001`, `100000`): the saving that matters is the run of zeros
/// outside the band.
///
/// Allocation-free (performance.md §6): below 1e-4 the exponent form is always
/// shorter, so it is written directly; at 1e15 and above both forms are written
/// into `buf` and the longer one removed. Every Kalix reader (the engine,
/// KalixIDE's importer, pandas) accepts the `1.5e-7` spelling.
pub fn push_f64_compact(buf: &mut String, value: f64) {
    use std::fmt::Write as _;
    let magnitude = value.abs();
    if !value.is_finite() || magnitude == 0.0 || (magnitude >= 1e-4 && magnitude < 1e15) {
        let _ = write!(buf, "{value}");
    } else if magnitude < 1e-4 {
        // Fixed form is at least "0." plus four zeros plus the digits; exponent
        // form is the digits plus "e-N". The exponent form always wins here.
        let _ = write!(buf, "{value:e}");
    } else {
        // Large: "1e20" beats "100000000000000000000", but "1234567890123456"
        // beats "1.234567890123456e15". Write both, keep the shorter (fixed on a tie).
        let start = buf.len();
        let _ = write!(buf, "{value:e}");
        let exponent_end = buf.len();
        let _ = write!(buf, "{value}");
        let exponent_len = exponent_end - start;
        let fixed_len = buf.len() - exponent_end;
        if exponent_len < fixed_len {
            buf.truncate(exponent_end);
        } else {
            buf.drain(start..exponent_end);
        }
    }
}

pub fn write_ts(filename: &str, timeseries_vector: Vec<&Timeseries>) -> Result<(), CsvError> {
    write_ts_opts(filename, timeseries_vector, filename)
}

/// Like [`write_ts`], but writes to `filename` in the format `named_as`
/// implies: plain CSV, a one-entry zip archive whose entry is named after
/// `named_as` (`.csv.zip`), or gzip (`.csv.gz`). For writers that stage output
/// under a temporary name (the conversion path), where the temp name's
/// extension lies about both the format and the zip entry name.
pub fn write_ts_opts(filename: &str, timeseries_vector: Vec<&Timeseries>, named_as: &str) -> Result<(), CsvError> {

    // Check that all timeseries in the vector have the same length
    let data_length = match timeseries_vector.len() {
        0 => { 0 }
        _ => { timeseries_vector[0].values.len() }
    };
    for tsv in &timeseries_vector {
        if tsv.values.len() != data_length {
            return Err(CsvError::WriteError("Cannot handle timeseries with different lengths.".to_string()))
        }
    }

    // One date format for the whole file, from the first series' grid (all series in a
    // write share it in practice); the heading names it, "date" being what most
    // post-processing tools look for.
    let first = timeseries_vector.first();
    let with_time = first.is_some_and(|ts| !ts.values.is_empty()
        && needs_time_of_day(ts.start_timestamp, ts.step_size));

    // Build the whole file in one buffer, reserved up front so a large output
    // never regrows (a 45 MB result file would otherwise be memcpy'd ~20
    // times as the buffer doubles).
    let estimated_row_bytes = 21 + timeseries_vector.len() * 20;
    let mut data_string = String::with_capacity(64 + data_length * estimated_row_bytes);
    data_string.push_str(if with_time { "datetime" } else { "date" });
    for ts in timeseries_vector.iter() {
        data_string.push_str(",");
        data_string.push_str(&ts.name);
    }
    data_string.push_str("\r\n");

    // Build the data section. Values are written straight into the buffer (no per-cell
    // temporary String for ordinary magnitudes), in the shortest exact form - see
    // push_f64_compact.
    if let Some(first) = first {
        for i in 0..data_length {
            let timestamp = first.timestamp_at(i);
            append_date_string(&mut data_string, timestamp, with_time);
            for ts in timeseries_vector.iter() {
                data_string.push(',');
                push_f64_compact(&mut data_string, ts.values[i]);
            }
            data_string.push_str("\r\n");
        }
    }

    // Write it all to file. The zip entry's timestamp is pinned to the DOS
    // epoch, and the gzip header's MTIME to 0, so identical content
    // compresses to identical bytes run-to-run: the cross-platform contract
    // is byte-identical *decompressed* content (Java and Rust encoders
    // legitimately differ), with reproducible output within each stack.
    let filename_path = Path::new(filename);
    let result = match csv_compression(named_as) {
        CsvCompression::Zip => fs::File::create(filename_path).and_then(|file| {
            let entry_name = zip_inner_name(named_as);
            let mut writer = zip::ZipWriter::new(BufWriter::new(file));
            let options = zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Deflated)
                .last_modified_time(zip::DateTime::default());
            writer.start_file(entry_name, options).map_err(io::Error::other)?;
            writer.write_all(data_string.as_bytes())?;
            // finish() writes the central directory into the BufWriter but
            // does not flush it; letting Drop flush would swallow a disk-full
            // error and report a truncated file as written.
            let mut inner = writer.finish().map_err(io::Error::other)?;
            inner.flush()
        }),
        CsvCompression::Gzip => fs::File::create(filename_path).and_then(|file| {
            let mut encoder = GzBuilder::new()
                .mtime(0)
                .write(BufWriter::new(file), Compression::default());
            encoder.write_all(data_string.as_bytes())?;
            // As for zip: finish() writes the trailer but does not flush.
            let mut inner = encoder.finish()?;
            inner.flush()
        }),
        CsvCompression::None => fs::write(filename_path, data_string),
    };
    match result {
        Ok(_) => Ok(()),
        Err(e) => Err(CsvError::WriteError(format!("Error writing file {filename}: {e}")))
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