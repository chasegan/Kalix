/// Tests for the calendar-boundary flags in the `sim.*` namespace:
/// `sim.new_day`, `sim.new_month`, `sim.new_year`
/// (structured_expressions_design.md §7).
///
/// Each flag is 1.0 when this step's calendar field differs from the previous
/// step's, and at step 0 (the run start counts as a boundary). The flags are
/// computed once per step in DataCache::update_current_timestamp and read back
/// through is_new_day/is_new_month/is_new_year, so these tests exercise both the
/// DataCache computation directly and the expression-language surface.
///
/// The comparison is against the *previous call's* decomposed date, so tests
/// must advance the step counter sequentially (increment_current_step), never
/// jump — a jump would compare against a non-adjacent step.

use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::tid::utils::date_string_to_u64;

/// Build a configured, daily-stepping cache starting on `date` (YYYY-MM-DD),
/// positioned at step 0.
fn daily_cache(date: &str) -> DataCache {
    let start = date_string_to_u64(date).unwrap();
    let mut dc = DataCache::new();
    // These tests exercise the flag machinery directly (no expression is
    // lowered to opt the cache in), so opt in explicitly — the flags are only
    // computed for models that reference sim.new_* (a measured per-step cost
    // otherwise).
    dc.needs_calendar_flags = true;
    dc.initialize(start);
    dc.set_start_and_stepsize(start, 86400);
    dc.set_current_step(0);
    dc
}

// ============================================================================
// DataCache-level computation
// ============================================================================

#[test]
fn test_flags_daily_mid_month() {
    // Start mid-month so the first month/year boundaries are unambiguous.
    let mut dc = daily_cache("2020-01-15");

    // Step 0: the run start is an implicit boundary — all three true.
    assert!(dc.is_new_day());
    assert!(dc.is_new_month());
    assert!(dc.is_new_year());

    // Step 1 (Jan 16): only the day changed.
    dc.increment_current_step();
    assert_eq!((dc.get_timestamp_month(), dc.get_timestamp_day()), (1, 16));
    assert!(dc.is_new_day());
    assert!(!dc.is_new_month());
    assert!(!dc.is_new_year());

    // Advance sequentially to the first step of February (Feb 1).
    while dc.get_timestamp_month() == 1 {
        dc.increment_current_step();
    }
    assert_eq!((dc.get_timestamp_month(), dc.get_timestamp_day()), (2, 1));
    assert!(dc.is_new_day());
    assert!(dc.is_new_month());
    assert!(!dc.is_new_year());

    // Advance sequentially to the first step of the next calendar year
    // (Jan 1 2021) — a day, month, and year boundary at once.
    while dc.get_timestamp_year() == 2020 {
        dc.increment_current_step();
    }
    assert_eq!(
        (dc.get_timestamp_year(), dc.get_timestamp_month(), dc.get_timestamp_day()),
        (2021, 1, 1)
    );
    assert!(dc.is_new_day());
    assert!(dc.is_new_month());
    assert!(dc.is_new_year());
}

#[test]
fn test_flags_leap_year_feb29_to_mar1() {
    // 2020 is a leap year: Feb 28 -> Feb 29 -> Mar 1.
    let mut dc = daily_cache("2020-02-28");

    // Step 1: Feb 29 — a new day, still within February.
    dc.increment_current_step();
    assert_eq!((dc.get_timestamp_month(), dc.get_timestamp_day()), (2, 29));
    assert!(dc.is_new_day());
    assert!(!dc.is_new_month());
    assert!(!dc.is_new_year());

    // Step 2: Mar 1 — crossing out of the leap month sets new_month.
    dc.increment_current_step();
    assert_eq!((dc.get_timestamp_month(), dc.get_timestamp_day()), (3, 1));
    assert!(dc.is_new_day());
    assert!(dc.is_new_month());
    assert!(!dc.is_new_year());
}

#[test]
fn test_flags_hourly_new_day() {
    // At sub-daily timesteps new_day fires only on the first step of each day —
    // the reason the flags exist (design §7: the naive month==7 && day==1 spelling
    // is true for every hourly step of the day).
    let start = date_string_to_u64("2020-01-15").unwrap();
    let mut dc = DataCache::new();
    dc.needs_calendar_flags = true; // direct flag test: opt in (see daily_cache)
    dc.initialize(start);
    dc.set_start_and_stepsize(start, 3600); // hourly
    dc.set_current_step(0);

    // Step 0: implicit boundary.
    assert!(dc.is_new_day());

    // Step 1 (01:00, same date): not a new day.
    dc.increment_current_step();
    assert_eq!(dc.get_timestamp_day(), 15);
    assert!(!dc.is_new_day());
    assert!(!dc.is_new_month());

    // Advance to the first step of the next day (Jan 16 00:00 = step 24).
    while dc.get_timestamp_day() == 15 {
        dc.increment_current_step();
    }
    assert_eq!((dc.get_timestamp_day(), dc.get_timestamp_seconds()), (16, 0));
    assert!(dc.is_new_day());
    assert!(!dc.is_new_month());
}

#[test]
fn test_flags_reset_across_reruns() {
    // A fresh run always begins at set_current_step(0), where the step-0 rule
    // forces every flag true — stale prev-date state cannot leak across runs.
    let mut dc = daily_cache("2020-01-15");
    // Walk a few days so the internal decomposed date is mid-month.
    for _ in 0..5 {
        dc.increment_current_step();
    }
    assert!(!dc.is_new_month());

    // Re-run: back to step 0.
    dc.set_current_step(0);
    assert!(dc.is_new_day());
    assert!(dc.is_new_month());
    assert!(dc.is_new_year());
}

// ============================================================================
// Expression-language surface
// ============================================================================

#[test]
fn test_expression_new_month() {
    // Start near a month end so a boundary occurs within a few steps.
    let mut dc = daily_cache("2020-01-30");

    let input = DynamicInput::from_string("sim.new_month", &mut dc, true, None)
        .expect("Failed to parse sim.new_month");

    // Step 0 (Jan 30): run-start boundary -> 1.0.
    assert_eq!(input.get_value(&mut dc), 1.0);

    // Step 1 (Jan 31): still January -> 0.0.
    dc.increment_current_step();
    assert_eq!(input.get_value(&mut dc), 0.0);

    // Step 2 (Feb 1): month changed -> 1.0.
    dc.increment_current_step();
    assert_eq!((dc.get_timestamp_month(), dc.get_timestamp_day()), (2, 1));
    assert_eq!(input.get_value(&mut dc), 1.0);

    // Step 3 (Feb 2): back to 0.0.
    dc.increment_current_step();
    assert_eq!(input.get_value(&mut dc), 0.0);
}

#[test]
fn test_expression_water_year_idiom() {
    // The design's motivating composition: `sim.new_month && sim.month == 7`
    // fires exactly once, on the first step of July (design §7).
    let mut dc = daily_cache("2020-06-29");

    let input = DynamicInput::from_string(
        "sim.new_month && sim.month == 7",
        &mut dc,
        true,
        None,
    ).expect("Failed to parse water-year idiom");

    // Step 0 (Jun 29): new_month is true (step 0) but month is 6 -> 0.0.
    assert_eq!(input.get_value(&mut dc), 0.0);

    // Step 1 (Jun 30): still June -> 0.0.
    dc.increment_current_step();
    assert_eq!(input.get_value(&mut dc), 0.0);

    // Step 2 (Jul 1): new month AND July -> 1.0.
    dc.increment_current_step();
    assert_eq!((dc.get_timestamp_month(), dc.get_timestamp_day()), (7, 1));
    assert_eq!(input.get_value(&mut dc), 1.0);

    // Step 3 (Jul 2): still July but not a new month -> 0.0.
    dc.increment_current_step();
    assert_eq!(input.get_value(&mut dc), 0.0);
}

#[test]
fn test_new_flags_offset_rejected() {
    // Offset syntax is meaningless on a per-step calendar flag; the existing
    // sim.* offset rejection must cover the new fields.
    let mut dc = daily_cache("2020-01-15");

    for expr in ["sim.new_day[-1, 0]", "sim.new_month[-1, 0]", "sim.new_year[-1, 0]"] {
        let result = DynamicInput::from_string(expr, &mut dc, true, None);
        assert!(result.is_err(), "offset on {expr} should be rejected");
        assert!(
            result.unwrap_err().contains("Offset syntax not supported"),
            "unexpected error message for {expr}"
        );
    }
}
