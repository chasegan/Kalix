// Tests that stepping the simulation date forward one step at a time gives
// the date that working it out from the timestamp gives, on every step.
// `increment_current_step` advances the date it holds where it can, and these
// tests are what hold that to the calendar.

use crate::data_management::data_cache::DataCache;
use crate::tid::utils::{u64_to_year_month_day_and_seconds, wrap_to_u64};

/// Steps `n_steps` forward from `start` (Unix seconds) and checks the date,
/// the time of day and the calendar-boundary flags against the timestamp.
fn check(start: i64, step_size: u64, n_steps: usize) {
    let start = wrap_to_u64(start);
    let mut stepped = DataCache::new();
    stepped.needs_calendar_flags = true;
    stepped.set_start_and_stepsize(start, step_size);
    stepped.set_current_step(0);
    let mut previous = u64_to_year_month_day_and_seconds(start);
    for step in 1..=n_steps {
        stepped.increment_current_step();
        let expected = u64_to_year_month_day_and_seconds(start + step_size * step as u64);
        let (year, month, day, seconds) = expected;
        let what = format!("step {step} of size {step_size} from {start}");
        assert_eq!(stepped.current_step, step, "{what}");
        assert_eq!(stepped.current_timestamp, start + step_size * step as u64, "timestamp at {what}");
        assert_eq!(stepped.get_timestamp_year(), year, "year at {what}");
        assert_eq!(stepped.get_timestamp_month(), month, "month at {what}");
        assert_eq!(stepped.get_timestamp_day(), day, "day at {what}");
        assert_eq!(stepped.get_timestamp_seconds(), seconds, "seconds at {what}");
        let new_year = year != previous.0;
        let new_month = new_year || month != previous.1;
        let new_day = new_month || day != previous.2;
        assert_eq!(stepped.is_new_year(), new_year, "new_year at {what}");
        assert_eq!(stepped.is_new_month(), new_month, "new_month at {what}");
        assert_eq!(stepped.is_new_day(), new_day, "new_day at {what}");
        previous = expected;
    }
}

// 1889-01-01, 1970-01-01, 2000-02-27 (a leap year in a century), 2099-12-30 (into 2100,
// which is not a leap year)
const STARTS: [i64; 4] = [-2_556_057_600, 0, 951_609_600, 4_102_272_000];

#[test]
fn test_daily_steps_follow_the_calendar_for_centuries() {
    check(STARTS[0], 86_400, 250 * 366);
}

#[test]
fn test_daily_steps_at_nine_in_the_morning_follow_the_calendar() {
    for start in STARTS {
        check(start + 9 * 3600, 86_400, 3 * 366);
    }
}

#[test]
fn test_steps_within_the_day_follow_the_calendar() {
    for start in STARTS {
        // The last four do not divide a day, so the time of day drifts
        for step_size in [1, 900, 3600, 5400, 21_600, 43_200, 7, 25_200, 50_000, 86_399] {
            check(start, step_size, 20_000);
            check(start + 23 * 3600 + 59 * 60 + 59, step_size, 2_000);
        }
    }
}

#[test]
fn test_steps_longer_than_a_day_follow_the_calendar() {
    for start in STARTS {
        // 172_799 can land on the next day and 172_801 cannot
        for step_size in [86_401, 100_000, 172_799, 2 * 86_400, 172_801, 7 * 86_400, 30 * 86_400, 365 * 86_400] {
            check(start, step_size, 3_000);
            check(start + 13 * 3600, step_size, 3_000);
        }
    }
}

#[test]
fn test_a_step_of_no_length_stays_where_it_is() {
    check(STARTS[2] + 9 * 3600, 0, 10);
}

#[test]
fn test_stepping_on_from_a_step_that_was_set_follows_the_calendar() {
    // set_current_step works the date out from the timestamp. Stepping on from there must
    // agree with setting each step in turn.
    for step_size in [3600, 86_400, 100_000] {
        let start = wrap_to_u64(STARTS[2]);
        let mut stepped = DataCache::new();
        let mut set = DataCache::new();
        for cache in [&mut stepped, &mut set] {
            cache.needs_calendar_flags = true;
            cache.set_start_and_stepsize(start, step_size);
        }
        for from in [0, 1, 26, 27, 28, 400, 12_345] {
            stepped.set_current_step(from);
            for step in from + 1..from + 100 {
                stepped.increment_current_step();
                set.set_current_step(step - 1);
                set.set_current_step(step);
                assert_eq!(stepped.current_timestamp, set.current_timestamp);
                assert_eq!(
                    (stepped.get_timestamp_year(), stepped.get_timestamp_month(), stepped.get_timestamp_day(), stepped.get_timestamp_seconds()),
                    (set.get_timestamp_year(), set.get_timestamp_month(), set.get_timestamp_day(), set.get_timestamp_seconds()),
                    "step {step} of size {step_size}, stepping on from {from}");
                assert_eq!(
                    (stepped.is_new_year(), stepped.is_new_month(), stepped.is_new_day()),
                    (set.is_new_year(), set.is_new_month(), set.is_new_day()),
                    "flags at step {step} of size {step_size}, stepping on from {from}");
            }
        }
    }
}

#[test]
fn test_the_flags_stay_off_for_a_model_that_does_not_read_them() {
    let mut stepped = DataCache::new();
    stepped.set_start_and_stepsize(wrap_to_u64(STARTS[3]), 86_400);
    stepped.set_current_step(0);
    for _ in 0..800 {
        stepped.increment_current_step();
        assert!(!stepped.is_new_year() && !stepped.is_new_month() && !stepped.is_new_day());
    }
}
