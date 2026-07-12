//! Tests for user-defined `[fn]` functions at the *inlining* level
//! (structured_expressions_design.md §8; `crate::functions::inline`).
//!
//! `test_fn_section_io.rs` covers the IO wiring — that definitions load,
//! round-trip, and that a duplicate/recursive/invalid/reserved definition is
//! rejected at load. This file covers the *semantics* of a call: that inlining
//! is a hygienic, evaluate-once macro expansion (§8.2), that stateful builtins
//! inside a body get per-call-site state, that `this.` late-binds to the
//! calling node, and that the call-site error class is reported clearly.
//!
//! Most tests build a registry directly (`data_cache.fns.parse_and_insert`)
//! and lower a value with `DynamicInput::from_string`, driving it over a known
//! series exactly as `test_stateful_functions.rs` does. The `this.` rebinding
//! and the in-model assert are exercised through a full model load, since those
//! only have meaning with a real node context.
//!
//! Sections mirror the test plan:
//!   A. Core semantics (registry + from_string, hand-computed).
//!   B. Call-site errors (message-substring asserts).
//!   C. Composition (fn inside moving_*/if, in-model assert, round-trip).

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

/// Compare a produced series against a hand-computed one.
fn assert_series(actual: &[f64], expected: &[f64], label: &str) {
    assert_eq!(actual.len(), expected.len(), "{}: length mismatch", label);
    for (i, (&a, &e)) in actual.iter().zip(expected).enumerate() {
        assert!((a - e).abs() < 1e-9,
            "{} step {}: expected {}, got {}", label, i, e, a);
    }
}

/// Load a value that should fail to lower, returning the error. `fns` is a list
/// of `(signature, body)` definitions inserted before the value is built; the
/// value itself may reference `data.x` and `data.y`, both present, so an
/// unknown name never masquerades as a genuine data reference.
fn expect_from_string_err(fns: &[(&str, &str)], value: &str, needle: &str) {
    let mut dc = base_cache();
    add_series(&mut dc, "data.x", &[1.0], true);
    add_series(&mut dc, "data.y", &[1.0], true);
    for (sig, body) in fns {
        dc.fns.parse_and_insert(sig, body)
            .unwrap_or_else(|e| panic!("registering '{}' should succeed, got: {}", sig, e));
    }
    match DynamicInput::from_string(value, &mut dc, true, None) {
        Ok(_) => panic!("'{}' should fail to lower, but it did not", value),
        Err(e) => assert!(e.contains(needle),
            "'{}' error should contain '{}', got: {}", value, needle, e),
    }
}

// ============================================================================
// A. Core semantics
// ============================================================================

/// A1. An expression-body function evaluates correctly, once per step, over a
/// driven series. `double(x) = x * 2` called as `fn.double(data.x)`.
#[test]
fn test_expression_body_over_steps() {
    let mut dc = cache_with_x(&[1.0, 2.0, 3.0, 4.0]);
    dc.fns.parse_and_insert("double(x)", "x * 2").unwrap();
    let input = DynamicInput::from_string("fn.double(data.x)", &mut dc, true, None)
        .expect("fn.double(data.x) should lower");
    let got = run_steps(&input, &mut dc, 4);
    assert_series(&got, &[2.0, 4.0, 6.0, 8.0], "fn.double");
}

/// A2. A zero-argument expression-body function (`two() = 2`) evaluates to its
/// constant, and — because inlining leaves no statements — the plain-expression
/// path unwraps it to a `Function` variant, NOT a `Program`. (It is a `Function`
/// rather than a `Constant`: the empty-statement unwrap recovers the plain
/// function variants, it does not constant-fold.)
#[test]
fn test_zero_arg_lowers_to_function() {
    let mut dc = cache_with_x(&[0.0, 0.0, 0.0]);
    dc.fns.parse_and_insert("two()", "2").unwrap();
    let input = DynamicInput::from_string("fn.two()", &mut dc, true, None)
        .expect("fn.two() should lower");
    assert!(matches!(input, DynamicInput::Function { .. }),
        "fn.two() should unwrap to a Function variant, got {:?}", input);
    let got = run_steps(&input, &mut dc, 3);
    assert_series(&got, &[2.0, 2.0, 2.0], "fn.two");
}

/// A3. Hygiene: a block-body function's local `m` cannot collide with the
/// caller's local `m`. Caller `{ m = 5; y = fn.addm(m); y + m }` with
/// `addm(x) = { m = x + 1; m * 2 }` gives 17: inside the function m = 6, so
/// y = 12; the caller's own m is still 5, so 12 + 5 = 17.
#[test]
fn test_hygiene_local_does_not_collide() {
    let mut dc = cache_with_x(&[0.0]);
    dc.fns.parse_and_insert("addm(x)", "{ m = x + 1; m * 2 }").unwrap();
    let input = DynamicInput::from_string(
        "{ m = 5; y = fn.addm(m); y + m }", &mut dc, true, None)
        .expect("hygiene program should lower");
    dc.set_current_step(0);
    assert_eq!(input.get_value(&mut dc), 17.0,
        "the function's m must not overwrite the caller's m");
}

/// A4. Arguments evaluate exactly once (§8.2). `twice_used(a) = a + a` called
/// with a stateful argument `moving_sum(data.x, 2, 0)`: the window must advance
/// once per step (bound to one hidden local), so the result is 2 * moving_sum.
/// Were the argument evaluated per use, the window would double-advance.
#[test]
fn test_argument_evaluated_once() {
    let mut dc = cache_with_x(&[1.0, 2.0, 3.0]);
    dc.fns.parse_and_insert("twice_used(a)", "a + a").unwrap();
    let input = DynamicInput::from_string(
        "fn.twice_used(moving_sum(data.x, 2, 0))", &mut dc, true, None)
        .expect("fn over a stateful arg should lower");
    let got = run_steps(&input, &mut dc, 3);
    // moving_sum(x,2,0) = [1, 3, 5]; twice_used doubles each: [2, 6, 10].
    // A per-use (double-advancing) window would NOT give these values.
    assert_series(&got, &[2.0, 6.0, 10.0], "evaluate-once");
}

/// A5. Positional binding: `sub(a, b) = a - b`, `fn.sub(10, 3) = 7` (order,
/// not name, decides which argument is which).
#[test]
fn test_positional_params() {
    let mut dc = cache_with_x(&[0.0]);
    dc.fns.parse_and_insert("sub(a, b)", "a - b").unwrap();
    let input = DynamicInput::from_string("fn.sub(10, 3)", &mut dc, true, None)
        .expect("fn.sub(10, 3) should lower");
    dc.set_current_step(0);
    assert_eq!(input.get_value(&mut dc), 7.0);
}

/// A6. Case-insensitivity throughout: signature `Mixed(X)`, body uses `x`,
/// call is `fn.MIXED(5)`. All fold to the same names; result is 10.
#[test]
fn test_case_insensitive() {
    let mut dc = cache_with_x(&[0.0]);
    dc.fns.parse_and_insert("Mixed(X)", "x * 2").unwrap();
    let input = DynamicInput::from_string("fn.MIXED(5)", &mut dc, true, None)
        .expect("mixed-case call should lower");
    dc.set_current_step(0);
    assert_eq!(input.get_value(&mut dc), 10.0);
}

/// A7. A function may call another, nested three levels deep.
/// f3(x)=x*10, f2(x)=fn.f3(x)+1, f1(x)=fn.f2(x)+1; fn.f1(2): f3(2)=20,
/// f2=21, f1=22.
#[test]
fn test_fn_calls_fn_three_levels() {
    let mut dc = cache_with_x(&[0.0]);
    dc.fns.parse_and_insert("f3(x)", "x * 10").unwrap();
    dc.fns.parse_and_insert("f2(x)", "fn.f3(x) + 1").unwrap();
    dc.fns.parse_and_insert("f1(x)", "fn.f2(x) + 1").unwrap();
    let input = DynamicInput::from_string("fn.f1(2)", &mut dc, true, None)
        .expect("three-level nesting should lower");
    dc.set_current_step(0);
    assert_eq!(input.get_value(&mut dc), 22.0);
}

/// A8. A stateful builtin inside a body gets per-call-site state: two separate
/// inputs, both calling `winsum(a) = moving_sum(a, 2, 0)` but over different
/// data, keep independent windows.
#[test]
fn test_stateful_body_per_call_site_state() {
    let mut dc = base_cache();
    add_series(&mut dc, "data.x", &[1.0, 2.0, 3.0, 4.0], true);
    add_series(&mut dc, "data.y", &[10.0, 20.0, 30.0, 40.0], true);
    dc.fns.parse_and_insert("winsum(a)", "moving_sum(a, 2, 0)").unwrap();

    let ix = DynamicInput::from_string("fn.winsum(data.x)", &mut dc, true, None).unwrap();
    let iy = DynamicInput::from_string("fn.winsum(data.y)", &mut dc, true, None).unwrap();

    let mut xs = Vec::new();
    let mut ys = Vec::new();
    for k in 0..4 {
        dc.set_current_step(k);
        xs.push(ix.get_value(&mut dc));
        ys.push(iy.get_value(&mut dc));
    }
    // moving_sum(x,2,0) = [1,3,5,7]; moving_sum(y,2,0) = [10,30,50,70].
    // Independent windows: neither total leaks into the other.
    assert_series(&xs, &[1.0, 3.0, 5.0, 7.0], "winsum(x)");
    assert_series(&ys, &[10.0, 30.0, 50.0, 70.0], "winsum(y)");
}

/// A9. `this.` late-binds to the calling node (through a full model load).
/// `prev_own() = this.inflow[-1, 99]`; a node whose `inflow = fn.prev_own() + 1`
/// reads its own previous inflow (default 99 at step 0), giving 100, 101, 102...
#[test]
fn test_this_rebinding_in_model() {
    let ini = "\
[kalix]
start = 2020-01-01
end = 2020-01-04

[node.a]
loc = 0, 0
type = inflow
inflow = fn.prev_own() + 1
ds_1 = sink

[node.sink]
loc = 0, 10
type = blackhole

[outputs]
node.a.dsflow

[fn]
prev_own() = this.inflow[-1, 99]
";
    let mut model = IniModelIO::new().read_model_string(ini)
        .expect("model with a this.-using fn should load");
    model.configure().expect("configuration should succeed");
    model.run().expect("run should succeed");

    let idx = model.data_cache.get_existing_series_idx("node.a.dsflow")
        .expect("node.a.dsflow should exist");
    let values = &model.data_cache.series[idx].values;
    // step0: prev inflow defaults to 99 -> 100; then each step reads the last -> 101, 102, 103.
    assert_series(values, &[100.0, 101.0, 102.0, 103.0], "this.-rebinding");
}

// ============================================================================
// B. Call-site errors
// ============================================================================

/// B10. Calling an undefined function.
#[test]
fn test_err_unknown_function() {
    expect_from_string_err(&[], "fn.nope(1)", "unknown function");
}

/// B11. Wrong arity is reported with the expected count.
#[test]
fn test_err_wrong_arity() {
    expect_from_string_err(&[("double(x)", "x * 2")], "fn.double(1, 2)", "expects 1 argument");
}

/// B12. A bare `fn.name` without call parentheses is rejected before it can
/// register a phantom data series.
#[test]
fn test_err_bare_fn_without_parens() {
    expect_from_string_err(&[("double(x)", "x * 2")], "fn.double", "must be called with parentheses");
}

/// B13. `this.` used from a context with no node (self_context = None) is a
/// load error naming the missing node context.
#[test]
fn test_err_this_without_node() {
    expect_from_string_err(&[("own()", "this.inflow[-1, 99]")], "fn.own()", "no node");
}

/// B14. Direct recursion is caught by the inliner's expansion stack even when
/// the load-time DAG check is bypassed (`parse_and_insert` alone, no
/// `check_dag`). `loopy(x) = fn.loopy(x) + 1` called directly.
#[test]
fn test_err_direct_recursion_at_inline() {
    expect_from_string_err(&[("loopy(x)", "fn.loopy(x) + 1")], "fn.loopy(1)", "recursive function call");
}

/// B15. A temporal offset on a parameter (a bound value, not a series) is a
/// load error naming the parameter. `f(x) = x[-1, 0] + 1`.
#[test]
fn test_err_offset_on_parameter() {
    expect_from_string_err(&[("f(x)", "x[-1, 0] + 1")], "fn.f(data.y)",
        "parameter or local, not a series");
}

// ============================================================================
// C. Composition
// ============================================================================

/// C16. A function call inside a `moving_*` argument.
/// `moving_mean(fn.double(data.x), 2, 0)` over x=[1,2,3,4]: the window sees
/// 2*x = [2,4,6,8], so the 2-step mean is [1, 3, 5, 7].
#[test]
fn test_fn_inside_moving_argument() {
    let mut dc = cache_with_x(&[1.0, 2.0, 3.0, 4.0]);
    dc.fns.parse_and_insert("double(x)", "x * 2").unwrap();
    let input = DynamicInput::from_string(
        "moving_mean(fn.double(data.x), 2, 0)", &mut dc, true, None)
        .expect("fn inside moving_mean arg should lower");
    let got = run_steps(&input, &mut dc, 4);
    // moving_sum(2x,2,0) = [2,6,10,14]; /2 = [1,3,5,7].
    assert_series(&got, &[1.0, 3.0, 5.0, 7.0], "fn inside moving_mean");
}

/// C17. A function containing a stateful builtin, called inside an untaken `if`
/// branch, still advances (deliberate hoisting — see `inline.rs`).
/// `if(sim.step >= 2, fn.wet_sum(data.x), -1)` with
/// `wet_sum(a) = moving_sum(a, 3, 0)`: steps 0-1 read -1, but at step 2 the
/// value is the FULL 3-window [1,2,3]=6, proving the window advanced while its
/// branch was untaken.
#[test]
fn test_fn_inside_untaken_if_branch_hoists() {
    let mut dc = cache_with_x(&[1.0, 2.0, 3.0, 4.0, 5.0]);
    dc.fns.parse_and_insert("wet_sum(a)", "moving_sum(a, 3, 0)").unwrap();
    let input = DynamicInput::from_string(
        "if(sim.step >= 2, fn.wet_sum(data.x), -1)", &mut dc, true, None)
        .expect("fn inside an if branch should lower");
    let got = run_steps(&input, &mut dc, 5);
    // moving_sum(x,3,0) = [1,3,6,9,12]; gated: [-1,-1,6,9,12].
    assert_series(&got, &[-1.0, -1.0, 6.0, 9.0, 12.0], "fn in untaken if");
    assert_eq!(got[2], 6.0,
        "step 2 must reflect full history, not a window that started at step 2");
}

/// C18. A `fn` whose body asserts, called in a model: the assert fires mid-run
/// and the run error names the function (the panic message carries the
/// `fn.<name>:` prefix the inliner prepends to the assert's source text).
#[test]
fn test_fn_body_assert_fires_in_model() {
    let ini = "\
[kalix]
start = 2020-01-01
end = 2020-01-03

[node.a]
loc = 0, 0
type = inflow
inflow = fn.must_be_positive(-5)
ds_1 = sink

[node.sink]
loc = 0, 10
type = blackhole

[outputs]
node.a.dsflow

[fn]
must_be_positive(x) = {
    assert(x > 0);
    x
    }
";
    let mut model = IniModelIO::new().read_model_string(ini)
        .expect("model with an asserting fn should load");
    model.configure().expect("configuration should succeed");

    let err = match model.run() {
        Ok(_) => panic!("run should fail when the fn's assert fires"),
        Err(e) => e,
    };
    assert!(err.contains("fn.") && err.contains("must_be_positive"),
        "run error should name the function via its fn. prefix, got: {}", err);
}

/// C19. Serialization round-trip: after inlining, `original_string()` and
/// `to_string()` return the ORIGINAL call text, not the expanded form — the
/// model file must re-emit what the modeller wrote.
#[test]
fn test_round_trip_returns_original_text() {
    let mut dc = cache_with_x(&[1.0]);
    dc.fns.parse_and_insert("double(x)", "x * 2").unwrap();
    let original = "fn.double(data.x) + 1";
    let input = DynamicInput::from_string(original, &mut dc, true, None)
        .expect("fn call + arithmetic should lower");
    assert_eq!(input.original_string(), original,
        "original_string() must return the pre-inlining text");
    assert_eq!(input.to_string(), original,
        "to_string() must return the pre-inlining text");
}
