use crate::io::ini_model_io::IniModelIO;
use crate::tid::utils::date_string_to_u64_flexible;
use crate::timeseries::Timeseries;

// Inputs must be in step with the simulation: out-of-phase inputs and a
// start/end between steps are rejected, never realigned. Fixtures: example_models/9,
// daily data on 1-5 Jan 2020 at 09:00 and at midnight, and hourly data on 1 Jan 2020.

const DIR: &str = "./src/tests/example_models/9";

fn configure(model_file: &str) -> Result<crate::model::Model, String> {
    let mut model = IniModelIO::read_model_file(&format!("{}/{}", DIR, model_file))
        .expect("model should parse");
    model.configure().map(|_| model)
}

fn ts(s: &str) -> u64 {
    date_string_to_u64_flexible(s).unwrap().0
}

#[test]
fn test_mixed_phase_inputs_rejected_in_either_order() {
    for model_file in ["mixed_9am_first.ini", "mixed_midnight_first.ini"] {
        let err = configure(model_file).err()
            .unwrap_or_else(|| panic!("{} should be rejected", model_file));
        assert!(err.contains("09:00:00") && err.contains("00:00:00"), "{}: {}", model_file, err);
        assert!(err.contains("same time of day"), "{}: {}", model_file, err);
    }
}

#[test]
fn test_single_phase_9am_runs_all_steps() {
    let mut model = configure("nine_only.ini").expect("9am-only model should configure");
    assert_eq!(model.configuration.sim_nsteps, 5);
    assert_eq!(model.configuration.sim_start_timestamp, ts("2020-01-01T09:00:00"));
    model.run().expect("9am-only model should run");
}

#[test]
fn test_start_between_steps_rejected_with_suggestion() {
    let err = configure("nine_start_date.ini").err().expect("midnight start on 9am data should be rejected");
    assert!(err.contains("'2020-01-02'") && err.contains("'2020-01-02T09:00:00'"), "{}", err);
}

#[test]
fn test_end_between_steps_rejected_with_suggestion() {
    let err = configure("nine_end_date.ini").err().expect("midnight end on 9am data should be rejected");
    assert!(err.contains("end '2020-01-04'") && err.contains("'2020-01-04T09:00:00'"), "{}", err);
}

#[test]
fn test_sub_daily_start_between_steps_rejected_with_suggestion() {
    let err = configure("hourly_start_half_past.ini").err().expect("half-past start on hourly data should be rejected");
    assert!(err.contains("'2020-01-01T01:30:00'") && err.contains("'2020-01-01T02:00:00'"), "{}", err);
}

#[test]
fn test_start_in_step_accepted() {
    let model = configure("nine_start_time.ini").expect("9am start on 9am data should configure");
    assert_eq!(model.configuration.sim_start_timestamp, ts("2020-01-02T09:00:00"));
    assert_eq!(model.configuration.sim_nsteps, 4);
}

#[test]
fn test_mask_with_aligned_offset() {
    // Mask starts two days after the base at the same 9am phase.
    let mut base = Timeseries::new(86400);
    base.start_timestamp = ts("2020-01-01T09:00:00");
    for v in [1.0, 2.0, 3.0, 4.0, 5.0] { base.values.push(v); }
    let mut mask = Timeseries::new(86400);
    mask.start_timestamp = ts("2020-01-03T09:00:00");
    for v in [0.0, f64::NAN, 0.0] { mask.values.push(v); }

    base.mask_with(&mask);
    let kept: Vec<bool> = base.values.iter().map(|v| !v.is_nan()).collect();
    assert_eq!(kept, vec![false, false, true, false, true]);
}
