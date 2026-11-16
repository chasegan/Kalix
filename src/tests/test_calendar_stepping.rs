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
        for step_size in [1, 900, 3600, 5400, 21_600, 43_200] {
            check(start, step_size, 20_000);
            check(start + 23 * 3600 + 59 * 60 + 59, step_size, 2_000);
        }
    }
}

#[test]
fn test_steps_longer_than_a_day_follow_the_calendar() {
    for start in STARTS {
        for step_size in [86_401, 100_000, 2 * 86_400, 7 * 86_400, 30 * 86_400, 365 * 86_400] {
            check(start, step_size, 3_000);
            check(start + 13 * 3600, step_size, 3_000);
        }
    }
}
