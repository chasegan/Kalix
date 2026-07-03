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


// ---------------------------------------------------------------------------
// Fast date parsing / formatting
//
// CSV loading parses one date per row and chrono's format-string interpreter
// was measured at ~half of total load time (and the write side's chrono
// formatting a further slice). The twelve formats accepted by
// date_string_to_u64_flexible are all digit runs with fixed separators, so
// once the format is detected (still via chrono) subsequent rows go through
// the hand-rolled parser below. chrono remains the fallback for anything the
// fast path declines, so accepted inputs and error behaviour are unchanged:
// same formats, same leniency (1-2 digit day/month), same strictness
// (invalid dates like Feb 30 are rejected; trailing garbage is rejected).
// ---------------------------------------------------------------------------

/// Field order for a known date format.
#[derive(Clone, Copy, PartialEq)]
enum DateOrder { Ymd, Dmy }

/// Time-of-day expectation for a known format.
#[derive(Clone, Copy, PartialEq)]
enum TimePart {
    None,
    /// "%H:%M" — hours and minutes
    Hm,
    /// "%H:%M:%S" — full time
    Hms,
    /// "%H:%M:%S%.f" — full time with optional fractional seconds (truncated)
    HmsFrac,
}

/// Parse spec for one of the known formats (see date_string_to_u64_flexible).
#[derive(Clone, Copy)]
struct KnownFormat {
    order: DateOrder,
    date_sep: u8,
    /// Separator between date and time ('T' or ' '); irrelevant for TimePart::None
    time_sep: u8,
    time: TimePart,
}

/// Map a chrono format string to its fast-parse spec. Returns None for
/// formats the fast path doesn't know (callers then fall back to chrono).
fn known_format(format: &str) -> Option<KnownFormat> {
    use DateOrder::{Dmy, Ymd};
    use TimePart::{Hm, Hms, HmsFrac};
    let spec = match format {
        "%Y-%m-%d" => KnownFormat { order: Ymd, date_sep: b'-', time_sep: 0, time: TimePart::None },
        "%d/%m/%Y" => KnownFormat { order: Dmy, date_sep: b'/', time_sep: 0, time: TimePart::None },
        "%Y/%m/%d" => KnownFormat { order: Ymd, date_sep: b'/', time_sep: 0, time: TimePart::None },
        "%d-%m-%Y" => KnownFormat { order: Dmy, date_sep: b'-', time_sep: 0, time: TimePart::None },
        "%Y-%m-%dT%H:%M:%S" => KnownFormat { order: Ymd, date_sep: b'-', time_sep: b'T', time: Hms },
        "%Y-%m-%dT%H:%M:%S%.f" => KnownFormat { order: Ymd, date_sep: b'-', time_sep: b'T', time: HmsFrac },
        "%Y-%m-%d %H:%M:%S" => KnownFormat { order: Ymd, date_sep: b'-', time_sep: b' ', time: Hms },
        "%Y-%m-%d %H:%M" => KnownFormat { order: Ymd, date_sep: b'-', time_sep: b' ', time: Hm },
        "%d/%m/%Y %H:%M:%S" => KnownFormat { order: Dmy, date_sep: b'/', time_sep: b' ', time: Hms },
        "%d/%m/%Y %H:%M" => KnownFormat { order: Dmy, date_sep: b'/', time_sep: b' ', time: Hm },
        "%Y/%m/%d %H:%M:%S" => KnownFormat { order: Ymd, date_sep: b'/', time_sep: b' ', time: Hms },
        "%Y/%m/%d %H:%M" => KnownFormat { order: Ymd, date_sep: b'/', time_sep: b' ', time: Hm },
        _ => return Option::None,
    };
    Some(spec)
}

/// Days from 1970-01-01 for a civil date (Howard Hinnant's algorithm).
/// Valid across the whole i64-representable range we care about.
fn days_from_civil(y: i64, m: u32, d: u32) -> i64 {
    let y = if m <= 2 { y - 1 } else { y };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let yoe = y - era * 400;
    let mp = if m > 2 { m - 3 } else { m + 9 } as i64;
    let doy = (153 * mp + 2) / 5 + d as i64 - 1;
    let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
    era * 146097 + doe - 719468
}

/// Civil date from days since 1970-01-01 (inverse of days_from_civil).
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719468;
    let era = if z >= 0 { z } else { z - 146096 } / 146097;
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let m = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if m <= 2 { y + 1 } else { y }, m, d)
}

fn is_leap_year(y: i64) -> bool {
    (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
}

fn days_in_month(y: i64, m: u32) -> u32 {
    match m {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 => if is_leap_year(y) { 29 } else { 28 },
        _ => 0,
    }
}

/// Read a run of 1..=max_digits ASCII digits from bytes[*pos..], advancing pos.
fn read_digits(bytes: &[u8], pos: &mut usize, max_digits: usize) -> Option<i64> {
    let start = *pos;
    let mut value: i64 = 0;
    while *pos < bytes.len() && bytes[*pos].is_ascii_digit() && *pos - start < max_digits {
        value = value * 10 + (bytes[*pos] - b'0') as i64;
        *pos += 1;
    }
    if *pos == start { None } else { Some(value) }
}

fn expect_byte(bytes: &[u8], pos: &mut usize, expected: u8) -> Option<()> {
    if *pos < bytes.len() && bytes[*pos] == expected {
        *pos += 1;
        Some(())
    } else {
        None
    }
}

/// Fast parse of a date/time string against a known format spec. Returns the
/// unix timestamp in seconds, or None if the string doesn't match (callers
/// fall back to chrono, which reproduces today's error behaviour exactly).
fn fast_parse_timestamp(date_str: &str, spec: KnownFormat) -> Option<i64> {
    let bytes = date_str.as_bytes();
    let mut pos = 0usize;

    // Date fields in the spec's order (1-2 digit day/month accepted, like chrono)
    let (year, month, day) = match spec.order {
        DateOrder::Ymd => {
            let y = read_digits(bytes, &mut pos, 4)?;
            expect_byte(bytes, &mut pos, spec.date_sep)?;
            let m = read_digits(bytes, &mut pos, 2)?;
            expect_byte(bytes, &mut pos, spec.date_sep)?;
            let d = read_digits(bytes, &mut pos, 2)?;
            (y, m as u32, d as u32)
        }
        DateOrder::Dmy => {
            let d = read_digits(bytes, &mut pos, 2)?;
            expect_byte(bytes, &mut pos, spec.date_sep)?;
            let m = read_digits(bytes, &mut pos, 2)?;
            expect_byte(bytes, &mut pos, spec.date_sep)?;
            let y = read_digits(bytes, &mut pos, 4)?;
            (y, m as u32, d as u32)
        }
    };

    // Validate the civil date (reject Feb 30 etc., matching chrono)
    if month < 1 || month > 12 || day < 1 || day > days_in_month(year, month) {
        return None;
    }

    // Time fields, if the format has them
    let mut seconds_of_day: i64 = 0;
    if spec.time != TimePart::None {
        expect_byte(bytes, &mut pos, spec.time_sep)?;
        let h = read_digits(bytes, &mut pos, 2)?;
        expect_byte(bytes, &mut pos, b':')?;
        let mi = read_digits(bytes, &mut pos, 2)?;
        let mut sec: i64 = 0;
        if matches!(spec.time, TimePart::Hms | TimePart::HmsFrac) {
            expect_byte(bytes, &mut pos, b':')?;
            sec = read_digits(bytes, &mut pos, 2)?;
        }
        if spec.time == TimePart::HmsFrac && pos < bytes.len() && bytes[pos] == b'.' {
            // Fractional seconds are accepted and truncated (chrono's
            // .timestamp() truncates them too)
            pos += 1;
            read_digits(bytes, &mut pos, 9)?;
        }
        if h > 23 || mi > 59 || sec > 59 {
            return None;
        }
        seconds_of_day = h * 3600 + mi * 60 + sec;
    }

    // Trailing garbage is a mismatch (chrono is strict about this too)
    if pos != bytes.len() {
        return None;
    }

    Some(days_from_civil(year, month, day) * 86400 + seconds_of_day)
}

/// Format a timestamp as "%Y-%m-%d" or "%Y-%m-%dT%H:%M:%S" straight into a
/// String, without chrono's formatter or a temporary allocation. Only handles
/// years 0..=9999 (returns false outside that, callers fall back to chrono).
fn fast_format_timestamp(out: &mut String, timestamp: i64, with_time: bool) -> bool {
    let days = timestamp.div_euclid(86400);
    let secs = timestamp.rem_euclid(86400);
    let (y, m, d) = civil_from_days(days);
    if !(0..=9999).contains(&y) {
        return false;
    }
    push_4digit(out, y as u32);
    out.push('-');
    push_2digit(out, m);
    out.push('-');
    push_2digit(out, d);
    if with_time {
        out.push('T');
        push_2digit(out, (secs / 3600) as u32);
        out.push(':');
        push_2digit(out, ((secs / 60) % 60) as u32);
        out.push(':');
        push_2digit(out, (secs % 60) as u32);
    }
    true
}

fn push_2digit(out: &mut String, v: u32) {
    out.push((b'0' + (v / 10 % 10) as u8) as char);
    out.push((b'0' + (v % 10) as u8) as char);
}

fn push_4digit(out: &mut String, v: u32) {
    out.push((b'0' + (v / 1000 % 10) as u8) as char);
    out.push((b'0' + (v / 100 % 10) as u8) as char);
    out.push((b'0' + (v / 10 % 10) as u8) as char);
    out.push((b'0' + (v % 10) as u8) as char);
}

/// Append the date/datetime string for `value` to `out`, choosing the format
/// from step_size like u64_to_date_string_for_step_size, via the fast
/// formatter (chrono fallback outside years 0-9999).
pub fn append_date_string_for_step_size(out: &mut String, value: u64, step_size: u64) {
    let with_time = !(step_size == 0 || step_size % 86400 == 0);
    if !fast_format_timestamp(out, wrap_to_i64(value), with_time) {
        out.push_str(&u64_to_date_string_for_step_size(value, step_size));
    }
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
    // Fast path for the known formats (measured ~10x chrono); chrono handles
    // anything the fast parser declines, reproducing today's errors exactly.
    if let Some(spec) = known_format(format) {
        if let Some(timestamp) = fast_parse_timestamp(date_str, spec) {
            return Ok(wrap_to_u64(timestamp));
        }
    }
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

/// Converts a u64 timestamp to a date/datetime string, using a format chosen by the step_size.
///
/// Unlike `u64_to_auto_datetime_string`, this picks one format for the whole timeseries based on
/// its step_size, so every row in a file uses the same format. This avoids the visual mess of a
/// hourly file where most rows are `YYYY-MM-DDTHH:MM:SS` but every midnight row collapses to
/// `YYYY-MM-DD`.
///
/// # Arguments
///
/// * `value` - A u64 timestamp (wrapped UNIX timestamp in seconds)
/// * `step_size` - Step size in seconds. A multiple of 86400 (daily or coarser) selects date-only
///   format; anything sub-daily selects ISO datetime format. Step size 0 is treated as date-only
///   (legacy/unconfigured fallback).
///
/// # Returns
///
/// * `YYYY-MM-DD` if step_size is 0 or a multiple of 86400
/// * `YYYY-MM-DDTHH:MM:SS` otherwise
pub fn u64_to_date_string_for_step_size(value: u64, step_size: u64) -> String {
    let format = if step_size == 0 || step_size % 86400 == 0 {
        "%Y-%m-%d"
    } else {
        "%Y-%m-%dT%H:%M:%S"
    };
    match DateTime::from_timestamp(wrap_to_i64(value), 0) {
        Some(dt) => dt.format(format).to_string(),
        None => value.to_string(),
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

#[cfg(test)]
mod tests {
    use super::*;

    /// The fast parser must agree with chrono on every accepted string, and
    /// decline (falling back to chrono's rejection) on every invalid one.
    /// This drives date_string_to_u64_with_format, whose fast path and chrono
    /// fallback must be indistinguishable to callers.
    #[test]
    fn test_fast_parse_matches_chrono_on_all_formats() {
        let cases: [(&str, &str); 16] = [
            ("%Y-%m-%d", "2020-01-15"),
            ("%Y-%m-%d", "1889-12-31"),
            ("%Y-%m-%d", "2024-02-29"),          // leap day
            ("%d/%m/%Y", "15/01/2020"),
            ("%d/%m/%Y", "5/1/2020"),            // single-digit day/month (lenient)
            ("%Y/%m/%d", "2020/01/15"),
            ("%d-%m-%Y", "15-01-2020"),
            ("%Y-%m-%dT%H:%M:%S", "2020-01-15T14:30:00"),
            ("%Y-%m-%dT%H:%M:%S", "2020-01-15T00:00:00"),
            ("%Y-%m-%dT%H:%M:%S%.f", "2020-01-15T14:30:00.123"),
            ("%Y-%m-%d %H:%M:%S", "2020-01-15 14:30:59"),
            ("%Y-%m-%d %H:%M", "2020-01-15 14:30"),
            ("%d/%m/%Y %H:%M:%S", "15/01/2020 14:30:00"),
            ("%d/%m/%Y %H:%M", "15/01/2020 14:30"),
            ("%Y/%m/%d %H:%M:%S", "2020/01/15 23:59:59"),
            ("%Y/%m/%d %H:%M", "2020/01/15 00:01"),
        ];
        for (format, input) in cases {
            let via_fast = date_string_to_u64_with_format(input, format)
                .unwrap_or_else(|e| panic!("'{}' ({}) failed: {}", input, format, e));
            let via_chrono = try_parse_datetime(input, format)
                .map(|dt| wrap_to_u64(dt.and_utc().timestamp()))
                .unwrap_or_else(|e| panic!("chrono rejected '{}' ({}): {}", input, format, e));
            assert_eq!(via_fast, via_chrono, "mismatch for '{}' ({})", input, format);
        }
    }

    /// Invalid inputs must be rejected — same behaviour as before the fast path.
    #[test]
    fn test_fast_parse_rejects_invalid_dates() {
        let cases: [(&str, &str); 7] = [
            ("%Y-%m-%d", "2023-02-29"),          // not a leap year
            ("%Y-%m-%d", "2020-13-01"),          // month 13
            ("%Y-%m-%d", "2020-04-31"),          // April has 30 days
            ("%Y-%m-%d", "2020-01-15junk"),      // trailing garbage
            ("%Y-%m-%d", "2020/01/15"),          // wrong separator
            ("%Y-%m-%dT%H:%M:%S", "2020-01-15T24:00:00"), // hour 24
            ("%Y-%m-%d", ""),
        ];
        for (format, input) in cases {
            assert!(date_string_to_u64_with_format(input, format).is_err(),
                "'{}' ({}) should be rejected", input, format);
        }
    }

    /// Round-trip: fast formatter output must re-parse to the same timestamp
    /// and match chrono's formatting byte-for-byte.
    #[test]
    fn test_fast_format_matches_chrono() {
        for &(timestamp, step) in &[
            (0i64, 86400u64),
            (86400 * 365 * 51, 86400),           // ~2021
            (-86400 * 365 * 81, 86400),          // ~1889
            (86400 * 365 * 51 + 52200, 3600),    // sub-daily -> datetime format
        ] {
            let wrapped = wrap_to_u64(timestamp);
            let mut fast = String::new();
            append_date_string_for_step_size(&mut fast, wrapped, step);
            let chrono_str = u64_to_date_string_for_step_size(wrapped, step);
            assert_eq!(fast, chrono_str, "format mismatch at timestamp {}", timestamp);
        }
    }
}
