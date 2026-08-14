/// Tests for the stateful expression functions: the fixed-window `moving_*`
/// family and the event-windowed `*_since` family
/// (structured_expressions_design.md §5-§7).
///
/// These functions carry O(1) per-step state in `DataCache.expr_state`,
/// advanced exactly once per step at the owning input's first `get_value`
/// (guarded), unconditionally — including inside untaken `if` branches. The
/// tests drive a DataCache over a known series by writing the whole series up
/// front and stepping `set_current_step` sequentially; every `get_value` at a
/// fresh step advances state once.
///
/// Sections mirror the test plan:
///   A. moving_* semantics (hand-computed, warm-up + steady state).
///   B. *_since semantics (reset-then-accumulate, implicit run-start reset).
///   C. Machinery invariants (guard, untaken-branch advance, variant
///      selection, load errors, independence, program-local interleave).
///   D. End-to-end via a full model (mirrors test_programs.rs).

use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::timeseries::Timeseries;
use crate::tid::utils::wrap_to_u64;
use crate::io::ini_model_io::IniModelIO;

/// 2020-01-01 00:00:00 UTC (wrapped), the start used by every cache here.
fn start_ts() -> u64 {
    wrap_to_u64(1577836800)
}

/// A cache initialised for daily stepping, positioned at step 0. No series.
fn base_cache() -> DataCache {
    let mut dc = DataCache::new();
    let ts0 = start_ts();
    dc.initialize(ts0);
    dc.set_start_and_stepsize(ts0, 86400);
    dc.set_current_step(0);
    dc
}

/// Add a daily series with the given values, returning its cache index.
fn add_series(dc: &mut DataCache, name: &str, values: &[f64], critical: bool) -> usize {
    let idx = dc.get_or_add_new_series(name, critical);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_ts();
    for &v in values {
        ts.push_value(v);
    }
    dc.series[idx] = ts;
    idx
}

/// A cache holding a single series "data.x" with the given values, at step 0.
fn cache_with_x(values: &[f64]) -> DataCache {
    let mut dc = base_cache();
    add_series(&mut dc, "data.x", values, true);
    dc
}

/// Drive an input over `n_steps` sequential steps (one `get_value` per step),
/// returning the value read at each step.
fn run_steps(input: &DynamicInput, dc: &mut DataCache, n_steps: usize) -> Vec<f64> {
    let mut out = Vec::with_capacity(n_steps);
    for k in 0..n_steps {
        dc.set_current_step(k);
        out.push(input.get_value(dc));
    }
    out
}

/// Compare a produced series against a hand-computed one, treating a NaN in
/// `expected` as "must be NaN here".
fn assert_series(actual: &[f64], expected: &[f64], label: &str) {
    assert_eq!(actual.len(), expected.len(), "{}: length mismatch", label);
    for (i, (&a, &e)) in actual.iter().zip(expected).enumerate() {
        if e.is_nan() {
            assert!(a.is_nan(), "{} step {}: expected NaN, got {}", label, i, a);
        } else {
            assert!((a - e).abs() < 1e-9,
                "{} step {}: expected {}, got {}", label, i, e, a);
        }
    }
}

// ============================================================================
// A. moving_* semantics
// ============================================================================

/// A1. moving_sum(x, 3, 0): default-filled warm-up, then steady state. The
/// window is pre-filled with the element default (0), so the sum is defined
/// from step 0 and grows as real values displace the defaults.
#[test]
fn test_moving_sum_warmup_and_steady() {
    let mut dc = cache_with_x(&[1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0]);
    let input = DynamicInput::from_string("moving_sum(data.x, 3, 0)", &mut dc, true, None)
        .expect("moving_sum should parse");
    assert!(matches!(input, DynamicInput::StatefulFunction { .. }),
        "moving_sum(...) should be a StatefulFunction, got {:?}", input);
    let got = run_steps(&input, &mut dc, 8);
    // warm-up: 1, 1+2, then full 3-windows: [1,2,3],[2,3,4],[3,4,5],...
    assert_series(&got, &[1.0, 3.0, 6.0, 9.0, 12.0, 15.0, 18.0, 21.0], "moving_sum");
}

/// A2. moving_mean equals moving_sum / n at every step (same series, n=3).
#[test]
fn test_moving_mean_matches_sum_over_n() {
    let values = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0];
    let mut dc = cache_with_x(&values);
    let sum = DynamicInput::from_string("moving_sum(data.x, 3, 0)", &mut dc, true, None).unwrap();
    let mean = DynamicInput::from_string("moving_mean(data.x, 3, 0)", &mut dc, true, None).unwrap();
    for k in 0..values.len() {
        dc.set_current_step(k);
        let s = sum.get_value(&mut dc);
        let m = mean.get_value(&mut dc);
        assert!((m - s / 3.0).abs() < 1e-12,
            "step {}: mean {} should equal sum {} / 3", k, m, s);
    }
}

/// A3. moving_min / moving_max over NON-monotonic data with n=3, exercising
/// deque expiry and domination. Defaults are chosen to also be visible during
/// warm-up (min default 0 wins steps 0-1; max default 100 wins steps 0-1),
/// then expire after step n-2 = 1.
#[test]
fn test_moving_min_max_nonmonotonic() {
    let values = [5.0, 1.0, 4.0, 1.0, 5.0, 9.0, 2.0, 6.0];

    let mut dc = cache_with_x(&values);
    let min = DynamicInput::from_string("moving_min(data.x, 3, 0)", &mut dc, true, None).unwrap();
    let got_min = run_steps(&min, &mut dc, 8);
    // step0 {0,0,5}=0; step1 {0,5,1}=0; then defaults expire:
    // {5,1,4}=1,{1,4,1}=1,{4,1,5}=1,{1,5,9}=1,{5,9,2}=2,{9,2,6}=2
    assert_series(&got_min, &[0.0, 0.0, 1.0, 1.0, 1.0, 1.0, 2.0, 2.0], "moving_min");

    let mut dc = cache_with_x(&values);
    let max = DynamicInput::from_string("moving_max(data.x, 3, 100)", &mut dc, true, None).unwrap();
    let got_max = run_steps(&max, &mut dc, 8);
    // step0 {100,100,5}=100; step1 {100,5,1}=100; then defaults expire:
    // {5,1,4}=5,{1,4,1}=4,{4,1,5}=5,{1,5,9}=9,{5,9,2}=9,{9,2,6}=9
    assert_series(&got_max, &[100.0, 100.0, 5.0, 4.0, 5.0, 9.0, 9.0, 9.0], "moving_max");
}

/// A4. n=1 window: moving_sum(x, 1, 42) equals x at every step from step 0 —
/// a one-step window never contains a warm-up default.
#[test]
fn test_moving_sum_window_one_is_identity() {
    let values = [3.0, 7.0, -2.0, 0.0, 5.0];
    let mut dc = cache_with_x(&values);
    let input = DynamicInput::from_string("moving_sum(data.x, 1, 42)", &mut dc, true, None).unwrap();
    let got = run_steps(&input, &mut dc, values.len());
    assert_series(&got, &values, "moving_sum n=1");
}

/// A5. A single NaN poisons moving_sum for exactly n=3 steps, then the sum
/// recovers (recompute-on-evict). moving_min SKIPS NaN — a NaN step leaves the
/// running minimum unchanged (the NaN was not the minimum).
#[test]
fn test_moving_nan_transient() {
    // moving_sum: NaN at step 2 is in the 3-window for steps 2,3,4, then leaves.
    let sum_values = [1.0, 2.0, f64::NAN, 4.0, 5.0, 6.0, 7.0, 8.0];
    let mut dc = cache_with_x(&sum_values);
    let sum = DynamicInput::from_string("moving_sum(data.x, 3, 0)", &mut dc, true, None).unwrap();
    let got_sum = run_steps(&sum, &mut dc, 8);
    // steps 2,3,4 NaN; step5 recomputes to [4,5,6]=15; then [5,6,7],[6,7,8].
    assert_series(&got_sum,
        &[1.0, 3.0, f64::NAN, f64::NAN, f64::NAN, 15.0, 18.0, 21.0], "moving_sum NaN");

    // moving_min: NaN at step 3 is skipped; the minimum stays what it was.
    let min_values = [5.0, 1.0, 4.0, f64::NAN, 2.0, 6.0, 7.0, 8.0];
    let mut dc = cache_with_x(&min_values);
    let min = DynamicInput::from_string("moving_min(data.x, 3, 1000)", &mut dc, true, None).unwrap();
    let got_min = run_steps(&min, &mut dc, 8);
    // step3 NaN skipped -> min unchanged (1); windows otherwise skip the NaN.
    assert_series(&got_min, &[5.0, 1.0, 1.0, 1.0, 2.0, 2.0, 2.0, 6.0], "moving_min NaN");
}

/// A6. Element-default warm-up: moving_max(x, 5, 100) reads 100 while a
/// pre-filled default is still in the window AND dominates the real values.
/// With all real values below 100, the default stops winning the instant it
/// leaves the window — exactly step 4 (the newest default, entered at step -1,
/// expires after step n-2 = 3).
#[test]
fn test_moving_max_default_warmup_pins_expiry_step() {
    let values = [10.0, 20.0, 30.0, 40.0, 50.0, 60.0, 70.0, 80.0];
    let mut dc = cache_with_x(&values);
    let input = DynamicInput::from_string("moving_max(data.x, 5, 100)", &mut dc, true, None).unwrap();
    let got = run_steps(&input, &mut dc, 8);
    // 100 wins steps 0-3; at step 4 the last default has expired, so the max is
    // the max of the last 5 real values: [10..50]=50, then 60, 70, 80.
    assert_series(&got, &[100.0, 100.0, 100.0, 100.0, 50.0, 60.0, 70.0, 80.0],
        "moving_max default warm-up");
    assert_eq!(got[3], 100.0, "step 3 must still read the default");
    assert_ne!(got[4], 100.0, "step 4 is where the default stops winning");
}

// ============================================================================
// B. *_since semantics
// ============================================================================

/// B7. sum_since(x, reset): reset-then-accumulate. The reset step's own x is
/// included in the fresh total, and the step before the reset holds the full
/// accumulated sum.
#[test]
fn test_sum_since_reset_then_accumulate() {
    let mut dc = cache_with_x(&[1.0, 2.0, 3.0, 4.0, 5.0, 6.0]);
    // Reset fires at step 3.
    let input = DynamicInput::from_string("sum_since(data.x, sim.step == 3)", &mut dc, true, None)
        .expect("sum_since should parse");
    assert!(matches!(input, DynamicInput::StatefulFunction { .. }),
        "sum_since(...) should be a StatefulFunction");
    let got = run_steps(&input, &mut dc, 6);
    // steps 0-2 accumulate 1,3,6; step 3 resets and counts its own 4;
    // then 4+5=9, 9+6=15.
    assert_series(&got, &[1.0, 3.0, 6.0, 4.0, 9.0, 15.0], "sum_since");
    assert_eq!(got[2], 6.0, "the step before reset holds the accumulated total");
    assert_eq!(got[3], 4.0, "the reset step includes its own x");
}

/// B8. steps_since(reset): 0 at step 0 (implicit run-start reset), increments
/// each step, and returns to 0 on the explicit reset step.
#[test]
fn test_steps_since_counts_and_resets() {
    let mut dc = cache_with_x(&[0.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
    let input = DynamicInput::from_string("steps_since(sim.step == 3)", &mut dc, true, None)
        .expect("steps_since should parse");
    let got = run_steps(&input, &mut dc, 6);
    assert_series(&got, &[0.0, 1.0, 2.0, 0.0, 1.0, 2.0], "steps_since");
    assert_eq!(got[0], 0.0, "steps_since reads 0 at step 0");
    assert_eq!(got[3], 0.0, "steps_since reads 0 on its reset step");
}

/// B9. count_since(cond, reset): counts the steps on which cond held since the
/// last reset, INCLUDING the reset step's own cond. Reset fires at step 4,
/// where cond (x > 3) is true, so the fresh count starts at 1.
#[test]
fn test_count_since_includes_reset_step_cond() {
    // cond = x > 3 -> [T, F, T, F, T, T]
    let mut dc = cache_with_x(&[5.0, 1.0, 4.0, 2.0, 6.0, 7.0]);
    let input = DynamicInput::from_string(
        "count_since(data.x > 3, sim.step == 4)", &mut dc, true, None)
        .expect("count_since should parse");
    let got = run_steps(&input, &mut dc, 6);
    // 1,1,2,2 then reset at step4 with its own true cond -> 1, then 2.
    assert_series(&got, &[1.0, 1.0, 2.0, 2.0, 1.0, 2.0], "count_since");
    assert_eq!(got[4], 1.0, "the reset step's own true cond is counted");
}

/// B10. min_since / max_since bootstrap from the first value (the run-start NaN
/// init is never visible), track since the reset, and re-bootstrap on reset.
#[test]
fn test_min_max_since_bootstrap_and_rebootstrap() {
    let values = [5.0, 3.0, 8.0, 2.0, 9.0, 1.0];

    let mut dc = cache_with_x(&values);
    let min = DynamicInput::from_string("min_since(data.x, sim.step == 3)", &mut dc, true, None).unwrap();
    let got_min = run_steps(&min, &mut dc, 6);
    // min(5), min(5,3)=3, min(3,8)=3, reset->2, min(2,9)=2, min(2,1)=1
    assert_series(&got_min, &[5.0, 3.0, 3.0, 2.0, 2.0, 1.0], "min_since");
    assert_eq!(got_min[0], 5.0, "min_since bootstraps to the first value, not NaN");

    let mut dc = cache_with_x(&values);
    let max = DynamicInput::from_string("max_since(data.x, sim.step == 3)", &mut dc, true, None).unwrap();
    let got_max = run_steps(&max, &mut dc, 6);
    // max(5), max(5,3)=5, max(5,8)=8, reset->2, max(2,9)=9, max(9,1)=9
    assert_series(&got_max, &[5.0, 5.0, 8.0, 2.0, 9.0, 9.0], "max_since");
    assert_eq!(got_max[0], 5.0, "max_since bootstraps to the first value, not NaN");
}

// ============================================================================
// C. Machinery invariants
// ============================================================================

/// C11. Guard: two get_value calls at the same step return the identical value
/// and do not double-advance. A twice-per-step run tracks a once-per-step run.
#[test]
fn test_guard_no_double_advance() {
    let values = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0];
    let mut dc = cache_with_x(&values);
    let once = DynamicInput::from_string("moving_sum(data.x, 3, 0)", &mut dc, true, None).unwrap();
    let twice = DynamicInput::from_string("moving_sum(data.x, 3, 0)", &mut dc, true, None).unwrap();

    let mut once_series = Vec::new();
    for k in 0..values.len() {
        dc.set_current_step(k);
        let a = once.get_value(&mut dc);
        let b1 = twice.get_value(&mut dc);
        let b2 = twice.get_value(&mut dc); // second call, same step
        assert_eq!(b1, b2, "step {}: a second call must not advance state", k);
        assert_eq!(a, b1, "step {}: twice-calling must match a once-called input", k);
        once_series.push(a);
    }
    assert_series(&once_series, &[1.0, 3.0, 6.0, 9.0, 12.0, 15.0, 18.0, 21.0], "guarded moving_sum");
}

/// C12. Untaken-branch advance: state advances even when its branch is not
/// taken. `if(sim.step >= 3, moving_sum(data.x, 3, 0), -1)` returns -1 for
/// steps 0-2, but at step 3 the moving_sum reflects the FULL history — proving
/// the window advanced while its branch was untaken.
#[test]
fn test_untaken_branch_still_advances() {
    let mut dc = cache_with_x(&[1.0, 2.0, 3.0, 4.0, 5.0, 6.0]);
    let input = DynamicInput::from_string(
        "if(sim.step >= 3, moving_sum(data.x, 3, 0), -1)", &mut dc, true, None)
        .expect("if(...) with a stateful branch should parse");
    assert!(matches!(input, DynamicInput::StatefulFunction { .. }),
        "an expression containing moving_sum should be a StatefulFunction");
    let got = run_steps(&input, &mut dc, 6);
    // steps 0-2 => -1; step 3 window is [2,3,4]=9 (not just [4]=4), proving the
    // untaken advances happened; then [3,4,5]=12, [4,5,6]=15.
    assert_series(&got, &[-1.0, -1.0, -1.0, 9.0, 12.0, 15.0], "untaken-branch advance");
    assert_eq!(got[3], 9.0,
        "state must reflect full history, not a window that started at step 3");
}

/// C13. Variant selection: a stateful call lowers to StatefulFunction; a plain
/// function stays Function (no guard); a block is a Program whether or not it
/// contains a stateful call.
#[test]
fn test_variant_selection() {
    // Stateful call -> StatefulFunction.
    {
        let mut dc = cache_with_x(&[1.0]);
        let input = DynamicInput::from_string("moving_sum(data.x, 3, 0)", &mut dc, true, None).unwrap();
        assert!(matches!(input, DynamicInput::StatefulFunction { .. }),
            "moving_sum -> StatefulFunction, got {:?}", input);
    }
    // Genuine stateless function -> Function (allocates no arena state).
    {
        let mut dc = cache_with_x(&[9.0]);
        let input = DynamicInput::from_string("sqrt(data.x)", &mut dc, true, None).unwrap();
        assert!(matches!(input, DynamicInput::Function { .. }),
            "sqrt(data.x) -> Function, got {:?}", input);
    }
    // Block containing a stateful call -> Program.
    {
        let mut dc = cache_with_x(&[1.0]);
        let input = DynamicInput::from_string("{ moving_sum(data.x, 2, 0) }", &mut dc, true, None).unwrap();
        assert!(matches!(input, DynamicInput::Program { .. }),
            "block with a stateful call -> Program, got {:?}", input);
    }
    // Plain block -> Program.
    {
        let mut dc = cache_with_x(&[1.0]);
        let input = DynamicInput::from_string("{ data.x }", &mut dc, true, None).unwrap();
        assert!(matches!(input, DynamicInput::Program { .. }),
            "plain block -> Program, got {:?}", input);
    }
}

/// Helper: a stateful call that should fail to load, with a key phrase in the
/// error. Both data.x and data.y exist so a non-constant argument is a genuine
/// data reference, not an unknown name.
fn expect_load_err(body: &str, needle: &str) {
    let mut dc = base_cache();
    add_series(&mut dc, "data.x", &[1.0], true);
    add_series(&mut dc, "data.y", &[1.0], true);
    let result = DynamicInput::from_string(body, &mut dc, true, None);
    match result {
        Ok(_) => panic!("'{}' should fail to load, but it parsed", body),
        Err(e) => assert!(e.contains(needle),
            "'{}' error should contain '{}', got: {}", body, needle, e),
    }
}

/// C14. Load errors: window length must be a constant positive integer;
/// arities are fixed for both families.
#[test]
fn test_load_errors() {
    // Non-constant window length: state cannot be sized at load.
    expect_load_err("moving_sum(data.x, data.y, 0)", "must be a constant");
    // Zero and non-integer window lengths.
    expect_load_err("moving_sum(data.x, 0, 0)", "positive integer");
    expect_load_err("moving_sum(data.x, 2.5, 0)", "positive integer");
    // Wrong arity for moving_* (needs exactly 3).
    expect_load_err("moving_sum(data.x, 3)", "expects 3 arguments");
    expect_load_err("moving_sum(data.x, 3, 0, 1)", "expects 3 arguments");
    // Wrong arity for *_since (needs exactly 2 for the value families).
    expect_load_err("sum_since(data.x)", "expects 2 argument(s)");
    expect_load_err("sum_since(data.x, 0, 1)", "expects 2 argument(s)");
    // steps_since takes exactly 1 argument (the reset).
    expect_load_err("steps_since(0, 1)", "expects 1 argument(s)");
}

/// C15. Two independent StatefulFunction inputs with the same expression do not
/// share state. A second input evaluated only from step 3 onward starts its
/// window cold (no history from the first input's advances).
#[test]
fn test_two_inputs_do_not_share_state() {
    let values = [1.0, 2.0, 3.0, 4.0, 5.0, 6.0];
    let mut dc = cache_with_x(&values);
    let a = DynamicInput::from_string("moving_sum(data.x, 3, 0)", &mut dc, true, None).unwrap();
    let b = DynamicInput::from_string("moving_sum(data.x, 3, 0)", &mut dc, true, None).unwrap();

    let mut a_series = Vec::new();
    let mut b_series = Vec::new();
    for k in 0..values.len() {
        dc.set_current_step(k);
        a_series.push(a.get_value(&mut dc)); // advanced every step
        // b advances only from step 3 — it never saw steps 0-2.
        b_series.push(if k >= 3 { b.get_value(&mut dc) } else { f64::NAN });
    }
    assert_series(&a_series, &[1.0, 3.0, 6.0, 9.0, 12.0, 15.0], "input A");
    // b's first advance is at step 3 with x=4 alone (default-filled window),
    // so b[3]=4, b[4]=[4,5]=9, b[5]=[4,5,6]=15 — independent of A's history.
    assert_eq!(b_series[3], 4.0, "B must start cold: only x=4, not A's history");
    assert_eq!(b_series[4], 9.0, "B window [4,5]");
    assert_eq!(b_series[5], 15.0, "B window [4,5,6]");
    assert_ne!(a_series[3], b_series[3], "A and B must not share window state");
}

/// C16. A stateful call inside a program samples a LOCAL assigned earlier in
/// the same block: `{ y = data.x * 2; moving_sum(y, 2, 0) }`. The interleaved
/// advance samples y AFTER its assignment, so the window sees 2*x each step.
#[test]
fn test_stateful_over_program_local() {
    let mut dc = cache_with_x(&[1.0, 2.0, 3.0, 4.0]);
    let input = DynamicInput::from_string(
        "{ y = data.x * 2; moving_sum(y, 2, 0) }", &mut dc, true, None)
        .expect("program with a stateful call over a local should parse");
    assert!(matches!(input, DynamicInput::Program { .. }),
        "block form -> Program, got {:?}", input);
    let got = run_steps(&input, &mut dc, 4);
    // y = [2,4,6,8]; moving_sum(y,2,0) = 2, [2,4]=6, [4,6]=10, [6,8]=14
    assert_series(&got, &[2.0, 6.0, 10.0, 14.0], "moving_sum over local");
}

// ============================================================================
// D. End-to-end via a full model (mirrors test_programs.rs).
// ============================================================================

/// The end-to-end model for tests D17/D18. `up` is an inflow node producing a
/// constant dsflow of 10; `calc` sits downstream and its inflow accumulates
/// `sum_since(node.up.dsflow, sim.new_month)`. Because calc's upstream flow is
/// up's dsflow (10), the recorded node.calc.dsflow is 10 + the running sum.
///
/// Run spans a month boundary: 2020-01-30 .. 2020-02-02.
///   step0 Jan30: new_month(step0)  -> reset, sum = 10
///   step1 Jan31: same month        -> sum = 20
///   step2 Feb01: month changed     -> reset, sum = 10
///   step3 Feb02: same month        -> sum = 20
/// node.calc.dsflow = usflow(10) + sum = [20, 30, 20, 30].
fn month_boundary_model() -> crate::model::Model {
    let ini = "\
[kalix]
start = 2020-01-30
end = 2020-02-02

[node.up]
loc = 0, 0
type = inflow
inflow = 10
ds_1 = calc

[node.calc]
loc = 0, 10
type = inflow
inflow = sum_since(node.up.dsflow, sim.new_month)
ds_1 = sink

[node.sink]
loc = 0, 20
type = blackhole

[outputs]
node.up.dsflow
node.calc.dsflow
";
    IniModelIO::read_model_string(ini)
        .expect("month-boundary model should parse")
}

/// D17. sum_since across a month boundary, hand-computed.
#[test]
fn test_model_sum_since_month_boundary() {
    let mut model = month_boundary_model();
    model.configure().expect("configuration should succeed");
    model.run().expect("run should succeed");

    let up_idx = model.data_cache.get_existing_series_idx("node.up.dsflow")
        .expect("node.up.dsflow should exist");
    let up = &model.data_cache.series[up_idx].values;
    assert_series(up, &[10.0, 10.0, 10.0, 10.0], "node.up.dsflow");

    let calc_idx = model.data_cache.get_existing_series_idx("node.calc.dsflow")
        .expect("node.calc.dsflow should exist");
    let calc = &model.data_cache.series[calc_idx].values;
    // usflow(10) + sum_since = 10 + [10,20,10,20].
    assert_series(calc, &[20.0, 30.0, 20.0, 30.0], "node.calc.dsflow");
}

/// D18. The same model run twice gives identical outputs — expression state is
/// reset and the advance guards re-armed at the start of every run.
#[test]
fn test_model_run_twice_identical() {
    let mut model = month_boundary_model();
    model.configure().expect("configuration should succeed");

    model.run().expect("first run should succeed");
    let idx = model.data_cache.get_existing_series_idx("node.calc.dsflow").unwrap();
    let first: Vec<f64> = model.data_cache.series[idx].values.clone();

    model.run().expect("second run should succeed");
    let idx2 = model.data_cache.get_existing_series_idx("node.calc.dsflow").unwrap();
    let second: Vec<f64> = model.data_cache.series[idx2].values.clone();

    assert_eq!(first, second, "two runs must produce identical stateful outputs");
    assert_eq!(first, vec![20.0, 30.0, 20.0, 30.0], "hand-computed sanity check");
}

/// A signed literal is a literal: unary +/- over a numeric constant folds at
/// parse, so a negative moving_* element default is accepted (caught by the
/// July 2026 IDE-lockstep review verification — previously `-1` lowered as a
/// UnaryOp and was rejected by the literal-only rule).
#[test]
fn test_moving_default_accepts_signed_literal() {
    let mut dc = DataCache::new();
    dc.get_or_add_new_series("data.x", true);
    for expr in ["moving_min(data.x, 3, -1)", "moving_max(data.x, 3, +2.5)", "moving_sum(data.x, 3, -0.5)"] {
        assert!(DynamicInput::from_string(expr, &mut dc, false, None).is_ok(),
            "'{}' should lower (signed literal default)", expr);
    }
    // The window length must still be a positive integer: a negative literal
    // is a literal, but not a valid window.
    assert!(DynamicInput::from_string("moving_sum(data.x, -3, 0)", &mut dc, false, None).is_err(),
        "negative window must still be rejected");
}

// ============================================================================
// E. moving_annual_* semantics
// ============================================================================
//
// moving_annual_sum/mean(x, wy_month, n_years) bucket x by water year (a new
// bucket starts on the first day whose month equals wy_month) and report the
// sum/mean of the last n_years buckets, including the in-progress one. Only
// sum and mean are implemented so far; min/max are deliberately unrecognised
// (see the "not yet implemented" note in lower_stateful_call).

/// Independent oracle for moving_annual_sum/mean: buckets `values` (one entry
/// per daily step from `start_ts()`) by water year using the same boundary
/// rule DataCache applies (is_new_month() && month == wy_month, with day 0
/// always a boundary), then slides a last-n_years window over the buckets.
/// Deliberately reimplemented from scratch against `crate::tid::utils` date
/// decoding, rather than exercising the engine's own flags, so it doesn't
/// just echo back whatever the implementation does.
fn expected_annual(values: &[f64], wy_month: u32, n_years: usize, mean: bool) -> Vec<f64> {
    let mut buckets: Vec<f64> = Vec::new();
    let mut out = Vec::with_capacity(values.len());
    let mut prev_month = 0u32;
    for (d, &x) in values.iter().enumerate() {
        let ts = start_ts() + 86400u64 * d as u64;
        let (_year, month, _day, _secs) = crate::tid::utils::u64_to_year_month_day_and_seconds(ts);
        let boundary = if d == 0 { month == wy_month } else { month != prev_month && month == wy_month };
        if boundary || buckets.is_empty() {
            buckets.push(x);
        } else {
            *buckets.last_mut().unwrap() += x;
        }
        prev_month = month;
        let start = buckets.len().saturating_sub(n_years);
        let sum: f64 = buckets[start..].iter().sum();
        out.push(if mean { sum / n_years as f64 } else { sum });
    }
    out
}

/// E17. moving_annual_sum against the bucket oracle, wy_month=1 (so water
/// years align with calendar years — 2020 is a leap year, so this exercises
/// a 366-day then two 365-day buckets), n_years=2, spanning 3 boundaries.
/// This is exactly the n_years>=2 scenario the advance_ring eviction-target
/// fix (this session) targeted: a wrong fix would leak or corrupt values at
/// the second and third boundary, not the first.
#[test]
fn test_moving_annual_sum_matches_bucket_oracle() {
    let n_days = 900; // 2020-01-01 .. into 2022, crossing 3 Jan-1 boundaries
    let values: Vec<f64> = (0..n_days).map(|i| 1.0 + (i % 5) as f64).collect();
    let mut dc = cache_with_x(&values);
    let input = DynamicInput::from_string("moving_annual_sum(data.x, 1, 2)", &mut dc, true, None)
        .expect("moving_annual_sum should parse");
    let got = run_steps(&input, &mut dc, n_days);
    let expected = expected_annual(&values, 1, 2, false);
    assert_series(&got, &expected, "moving_annual_sum");
}

/// E18. moving_annual_mean equals moving_annual_sum / n_years at every step,
/// same series and boundary as E17 (mirrors test_moving_mean_matches_sum_over_n).
#[test]
fn test_moving_annual_mean_matches_sum_over_n_years() {
    let n_days = 900;
    let values: Vec<f64> = (0..n_days).map(|i| 1.0 + (i % 5) as f64).collect();
    let mut dc = cache_with_x(&values);
    let sum = DynamicInput::from_string("moving_annual_sum(data.x, 1, 2)", &mut dc, true, None).unwrap();
    let mean = DynamicInput::from_string("moving_annual_mean(data.x, 1, 2)", &mut dc, true, None).unwrap();
    for k in 0..n_days {
        dc.set_current_step(k);
        let s = sum.get_value(&mut dc);
        let m = mean.get_value(&mut dc);
        assert!((m - s / 2.0).abs() < 1e-9,
            "step {}: mean {} should equal sum {} / 2", k, m, s);
    }
}

/// E19. n_years=1 edge case: the ring never leaves slot 0 (head+1==n wraps
/// straight back to 0), which is exactly why this case alone didn't catch
/// the eviction-target bug earlier this session — it's a weak case on its
/// own, kept here as a basic sanity check, not as the main regression test
/// (E17/E18 above are, since they require n_years>=2).
#[test]
fn test_moving_annual_sum_n_years_one() {
    let n_days = 400;
    let values: Vec<f64> = (0..n_days).map(|i| (i % 3) as f64).collect();
    let mut dc = cache_with_x(&values);
    let input = DynamicInput::from_string("moving_annual_sum(data.x, 1, 1)", &mut dc, true, None).unwrap();
    let got = run_steps(&input, &mut dc, n_days);
    let expected = expected_annual(&values, 1, 1, false);
    assert_series(&got, &expected, "moving_annual_sum n_years=1");
}

/// E20. A NaN entering moving_annual_sum poisons the sum for as long as that
/// water year's bucket remains in the ring (up to n_years boundaries), then
/// clears once the poisoned year is evicted — the annual analogue of A5's
/// n-step poisoning, scaled from steps to years.
#[test]
fn test_moving_annual_sum_nan_transient() {
    let n_days = 800; // 2020-01-01 .. 2022-ish, crossing 2 Jan-1 boundaries
    let mut values: Vec<f64> = vec![1.0; n_days];
    values[100] = f64::NAN; // lands in the first (2020) water-year bucket
    let mut dc = cache_with_x(&values);
    let input = DynamicInput::from_string("moving_annual_sum(data.x, 1, 2)", &mut dc, true, None).unwrap();
    let got = run_steps(&input, &mut dc, n_days);

    // Before the NaN: finite. From the NaN through the end of the window
    // that still includes the 2020 bucket: NaN. After the 2020 bucket is
    // evicted (n_years=2 after it stops being one of the last 2 buckets):
    // finite again.
    assert!(got[99].is_finite(), "day 99: before the NaN, must be finite");
    assert!(got[100].is_nan(), "day 100: the NaN's own day must be NaN");
    assert!(got[400].is_nan(), "well within the 2-year window: still NaN");
    assert!(got[n_days - 1].is_finite(),
        "by the end of the run the poisoned 2020 bucket must have been evicted");
}

/// Independent oracle for moving_annual_min/max: same water-year bucketing
/// as expected_annual, but each bucket holds the running min/max of its
/// values, and the window statistic is the min/max over the last n_years
/// buckets. Reimplemented from scratch (not sharing code with the ring
/// helpers under test).
fn expected_annual_extreme(values: &[f64], wy_month: u32, n_years: usize, is_min: bool) -> Vec<f64> {
    let mut buckets: Vec<f64> = Vec::new();
    let mut out = Vec::with_capacity(values.len());
    let mut prev_month = 0u32;
    for (d, &x) in values.iter().enumerate() {
        let ts = start_ts() + 86400u64 * d as u64;
        let (_year, month, _day, _secs) = crate::tid::utils::u64_to_year_month_day_and_seconds(ts);
        let boundary = if d == 0 { month == wy_month } else { month != prev_month && month == wy_month };
        if boundary || buckets.is_empty() {
            buckets.push(x);
        } else {
            let last = buckets.last_mut().unwrap();
            *last = if is_min { last.min(x) } else { last.max(x) };
        }
        prev_month = month;
        let start = buckets.len().saturating_sub(n_years);
        let mut s = f64::NAN;
        for &b in &buckets[start..] {
            s = if is_min { s.min(b) } else { s.max(b) };
        }
        out.push(s);
    }
    out
}

/// E21. moving_annual_min/max against the bucket-extreme oracle, same
/// wy_month=1/n_years=2/3-boundary shape as E17, with non-monotonic input.
#[test]
fn test_moving_annual_min_max_matches_bucket_oracle() {
    let n_days = 900;
    let values: Vec<f64> = (0..n_days).map(|i| ((i * 37 + 5) % 23) as f64 - 11.0).collect();

    let mut dc = cache_with_x(&values);
    let min = DynamicInput::from_string("moving_annual_min(data.x, 1, 2)", &mut dc, true, None)
        .expect("moving_annual_min should parse");
    let got_min = run_steps(&min, &mut dc, n_days);
    assert_series(&got_min, &expected_annual_extreme(&values, 1, 2, true), "moving_annual_min");

    let mut dc = cache_with_x(&values);
    let max = DynamicInput::from_string("moving_annual_max(data.x, 1, 2)", &mut dc, true, None)
        .expect("moving_annual_max should parse");
    let got_max = run_steps(&max, &mut dc, n_days);
    assert_series(&got_max, &expected_annual_extreme(&values, 1, 2, false), "moving_annual_max");
}

/// E22. A year's extremum must leave the window exactly n_years boundaries
/// after it entered, exercising advance_ring_extreme's O(n) rescan-on-evict
/// (min/max has no incremental inverse for eviction the way sum does).
/// wy_month=1, n_years=2: an outlier in the 2020 (leap-year) bucket must
/// still be the window minimum through day 730 (2020's bucket is still one
/// of the last 2), and must be gone from day 731 (2022's boundary, which
/// evicts 2020's bucket).
#[test]
fn test_moving_annual_min_evicts_after_n_years() {
    let n_days = 900;
    let mut values = vec![10.0; n_days];
    values[10] = -1000.0; // sits in the 2020 water-year bucket
    let mut dc = cache_with_x(&values);
    let input = DynamicInput::from_string("moving_annual_min(data.x, 1, 2)", &mut dc, true, None).unwrap();
    let got = run_steps(&input, &mut dc, n_days);
    assert_eq!(got[10], -1000.0, "day 10: the outlier's own day");
    assert_eq!(got[365], -1000.0, "day 365: still within the 2-year window");
    assert_eq!(got[730], -1000.0, "day 730: last day before the 2020 bucket is evicted");
    assert_eq!(got[731], 10.0, "day 731: 2020's bucket evicted, outlier gone");
}

/// E23. NaN suppression for annual min/max: unlike moving_annual_sum (which
/// poisons until the poisoned year is evicted), a NaN step must never affect
/// the running min/max at all — matching moving_min/moving_max's NaN
/// suppression (f64::min/f64::max return the non-NaN operand).
#[test]
fn test_moving_annual_min_nan_suppressed() {
    let n_days = 400;
    let mut values = vec![5.0; n_days];
    values[3] = 1.0;          // the real minimum
    values[4] = f64::NAN;     // must not disturb it
    let mut dc = cache_with_x(&values);
    let input = DynamicInput::from_string("moving_annual_min(data.x, 1, 2)", &mut dc, true, None).unwrap();
    let got = run_steps(&input, &mut dc, n_days);
    assert!(got[4].is_finite(), "day 4 (the NaN step itself) must not read NaN");
    assert_eq!(got[4], 1.0, "day 4: minimum unaffected by the NaN");
    assert_eq!(got[n_days - 1], 1.0, "minimum still holds at the end of the run");
}

/// E24. Load errors for the annual family: wy_month must be a constant
/// integer in [1, 12]; n_years must be a constant positive integer; arity is
/// fixed at 3. Mirrors C14's fixed-window load-error coverage.
#[test]
fn test_moving_annual_load_errors() {
    for name in ["moving_annual_sum", "moving_annual_mean", "moving_annual_min", "moving_annual_max"] {
        expect_load_err(&format!("{}(data.x, data.y, 2)", name), "must be a constant");
        expect_load_err(&format!("{}(data.x, 0, 2)", name), "between 1 and 12");
        expect_load_err(&format!("{}(data.x, 13, 2)", name), "between 1 and 12");
        expect_load_err(&format!("{}(data.x, 6.5, 2)", name), "between 1 and 12");
        expect_load_err(&format!("{}(data.x, 6, data.y)", name), "must be a constant");
        expect_load_err(&format!("{}(data.x, 6, 0)", name), "positive integer");
        expect_load_err(&format!("{}(data.x, 6, 2.5)", name), "positive integer");
        expect_load_err(&format!("{}(data.x, 6, 2, 1)", name), "expects 3 arguments");
        expect_load_err(&format!("{}(data.x, 6)", name), "expects 3 arguments");
    }
    // Sanity: a well-formed call is NOT an error.
    let mut dc = base_cache();
    add_series(&mut dc, "data.x", &[1.0], true);
    assert!(DynamicInput::from_string("moving_annual_sum(data.x, 6, 2)", &mut dc, true, None).is_ok(),
        "a well-formed moving_annual_sum call should parse");
}
