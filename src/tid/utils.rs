use chrono::{DateTime, ParseResult, NaiveDate};

pub fn date_string_to_u64(date_str: &str) -> ParseResult<u64> {
    let formatter = "%Y-%m-%d"; //"%Y-%m-%d %H:%M:%S"
    match NaiveDate::parse_and_remainder(date_str,formatter) {
        Ok((dt,_)) => Ok(wrap_to_u64(dt.and_hms_opt(0,0,0).unwrap().and_utc().timestamp())),
        Err(e) => Err(e),
    }
}

pub fn u64_to_date_string(value: u64) -> String {
    let formatter = "%Y-%m-%d"; //"%Y-%m-%d %H:%M:%S"
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
