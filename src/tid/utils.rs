use chrono::{DateTime, ParseResult, NaiveDate};

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


pub fn wrap_to_u64(x: i64) -> u64 {
    (x as u64).wrapping_add(u64::MAX/2 + 1)
}

pub fn wrap_to_i64(x: u64) -> i64 {
    x.wrapping_sub(u64::MAX/2 + 1) as i64
}
