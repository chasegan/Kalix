use chrono::{DateTime, ParseResult, NaiveDate, NaiveDateTime, Timelike, Datelike};

/// Converts a date string (must be "%Y-%m-%d") into an u64 integer timestamp that counts the
/// number of seconds since some fixed time in the past.
///
/// # Arguments
///
/// A string date in "%Y-%m-%d" format.
///
/// # Returns
///
/// A ParseResult enum where the Ok() variant contains an u64 representation of the date. This u64
/// is based on the UNIX timestamp in seconds, but wrapped from an i64 to an u64. I chose to go with
/// an u64 for two reasons: (1) The external compression library "tsz" uses u64 for the timestamp,
/// and (2) min and max values are unlikely to clash with real datetime values meaning errors are
/// easily detected. The representation supports datetimes earlier than -9999-01-01 and later than
/// +9999-12-31.
pub fn date_string_to_u64(date_str: &str) -> ParseResult<u64> {
    let formatter = "%Y-%m-%d"; //"%Y-%m-%d %H:%M:%S"
    match NaiveDate::parse_and_remainder(date_str,formatter) {
        Ok((dt,_)) => Ok(wrap_to_u64(dt.and_hms_opt(0,0,0).unwrap().and_utc().timestamp())),
        Err(e) => Err(e),
    }
}

/// Converts a date/time string with automatic format detection into a u64 timestamp.
///
/// Tries multiple common formats and returns both the timestamp and the detected format.
/// Daily formats are tried first (most common), then sub-daily formats (ISO first).
///
/// # Arguments
///
/// * `date_str` - Date/time string in various formats
///
/// # Returns
///
/// A tuple of (u64 timestamp, detected format string), or an error if no format matches.
pub fn date_string_to_u64_flexible(date_str: &str) -> Result<(u64, &'static str), String> {
    // List of formats to try, in order of preference
    // Daily formats first (most common), then sub-daily (ISO first)
    let formats = vec![
        // Daily formats (no time component) - MOST COMMON
        "%Y-%m-%d",                // 2020-01-15 (ISO, current default)
        "%d/%m/%Y",                // 15/01/2020 (European)
        "%Y/%m/%d",                // 2020/01/15
        "%d-%m-%Y",                // 15-01-2020

        // Sub-daily formats (with time component) - LESS COMMON, ISO FIRST
        "%Y-%m-%dT%H:%M:%S",       // 2020-01-15T14:30:00 (ISO 8601)
        "%Y-%m-%dT%H:%M:%S%.f",    // 2020-01-15T14:30:00.123 (ISO with fractional seconds)
        "%Y-%m-%d %H:%M:%S",       // 2020-01-15 14:30:00 (space separator)
        "%Y-%m-%d %H:%M",          // 2020-01-15 14:30 (space separator, no seconds)
        "%d/%m/%Y %H:%M:%S",       // 15/01/2020 14:30:00
        "%d/%m/%Y %H:%M",          // 15/01/2020 14:30
        "%Y/%m/%d %H:%M:%S",       // 2020/01/15 14:30:00
        "%Y/%m/%d %H:%M",          // 2020/01/15 14:30
    ];

    for format in formats {
        if let Ok(dt) = try_parse_datetime(date_str, format) {
            let timestamp = dt.and_utc().timestamp();
            return Ok((wrap_to_u64(timestamp), format));
        }
    }

    Err(format!("Could not parse date '{}' with any known format", date_str))
}

/// Helper function to try parsing a date/time string with a specific format.
///
/// Handles both date-only formats (sets time to midnight) and date+time formats.
fn try_parse_datetime(date_str: &str, format: &str) -> ParseResult<NaiveDateTime> {
    // Try as datetime first (handles both date+time and date-only formats)
    if let Ok(dt) = NaiveDateTime::parse_from_str(date_str, format) {
        return Ok(dt);
    }

    // Try as date only (set time to midnight)
    if let Ok(date) = NaiveDate::parse_from_str(date_str, format) {
        return Ok(date.and_hms_opt(0, 0, 0).unwrap());
    }

    // Return the error from the datetime parse attempt
    NaiveDateTime::parse_from_str(date_str, format)
}

/// Converts a date/time string to u64 using a known format string.
///
/// Used after format detection to parse subsequent rows more efficiently.
///
/// # Arguments
///
/// * `date_str` - Date/time string to parse
/// * `format` - chrono format string (e.g., "%Y-%m-%d", "%d/%m/%Y %H:%M:%S")
///
/// # Returns
///
/// A u64 timestamp, or an error if parsing fails.
pub fn date_string_to_u64_with_format(date_str: &str, format: &str) -> Result<u64, String> {
    try_parse_datetime(date_str, format)
        .map(|dt| wrap_to_u64(dt.and_utc().timestamp()))
        .map_err(|e| format!("Failed to parse '{}' with format '{}': {}", date_str, format, e))
}



/// Converts an u64 datetime integer into a string.
///
/// # Arguments
///
/// An u64 value representing the datetime. This u64
/// is based on the UNIX timestamp in seconds, but wrapped from an i64 to an u64. I chose to go with
/// an u64 for two reasons: (1) The external compression library "tsz" uses u64 for the timestamp,
/// and (2) min and max values are unlikely to clash with real datetime values meaning errors are
/// easily detected. The representation supports datetimes earlier than -9999-01-01 and later than
/// +9999-12-31.
///
/// # Returns
///
/// A date in "%Y-%m-%d" format. Partial days are truncated to fit in the "%Y-%m-%d" format.
pub fn u64_to_date_string(value: u64) -> String {
    let formatter = "%Y-%m-%d"; //"%Y-%m-%d %H:%M:%S"
    match DateTime::from_timestamp(wrap_to_i64(value), 0).
        map(|dt| format!("{}", dt.format(formatter))) {
        Some(s) => s,
        None => value.to_string(),
    }
}

/// Converts an u64 datetime integer into a ISO-formatted datetime string.
///
/// # Arguments
///
/// An u64 value representing the datetime. This u64
/// is based on the UNIX timestamp in seconds, but wrapped from an i64 to an u64. I chose to go with
/// an u64 for two reasons: (1) The external compression library "tsz" uses u64 for the timestamp,
/// and (2) min and max values are unlikely to clash with real datetime values meaning errors are
/// easily detected. The representation supports datetimes earlier than -9999-01-01 and later than
/// +9999-12-31.
///
/// # Returns
///
/// A date in "%Y-%m-%dT%H:%M:%S%.3fZ" format.
pub fn u64_to_iso_datetime_string(value: u64) -> String {
    let formatter = "%Y-%m-%dT%H:%M:%S%.3fZ";
    match DateTime::from_timestamp(wrap_to_i64(value), 0).
        map(|dt| format!("{}", dt.format(formatter))) {
        Some(s) => s,
        None => value.to_string(),
    }
}

/// Converts a u64 timestamp to a date/datetime string, automatically choosing the format.
///
/// # Arguments
///
/// * `value` - A u64 timestamp (wrapped UNIX timestamp in seconds)
///
/// # Returns
///
/// * `YYYY-MM-DD` if the time is exactly midnight (00:00:00)
/// * `YYYY-MM-DDTHH:MM:SS` if there is any partial-day information
pub fn u64_to_auto_datetime_string(value: u64) -> String {
    match DateTime::from_timestamp(wrap_to_i64(value), 0) {
        Some(dt) => {
            // Check if it's a whole day (midnight)
            if dt.hour() == 0 && dt.minute() == 0 && dt.second() == 0 {
                dt.format("%Y-%m-%d").to_string()
            } else {
                dt.format("%Y-%m-%dT%H:%M:%S").to_string()
            }
        }
        None => format!("INVALID_TIMESTAMP_{}", value),
    }
}


pub fn u64_to_year_month_day_and_seconds(value: u64) -> (i32, u32, u32, u32) {
    match DateTime::from_timestamp(wrap_to_i64(value), 0) {
        Some(dt) => {
            let y = dt.year();
            let m = dt.month();
            let d = dt.day();
            let s = dt.num_seconds_from_midnight();
            (y, m, d, s)
        }
        None => panic!("Error wrapping value to datetime {}", value)
    }
}


pub fn wrap_to_u64(x: i64) -> u64 {
    (x as u64).wrapping_add(u64::MAX/2 + 1)
}

pub fn wrap_to_i64(x: u64) -> i64 {
    x.wrapping_sub(u64::MAX/2 + 1) as i64
}
