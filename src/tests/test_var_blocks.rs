//! Tests for the `[var.*]` model-variable section (structured_expressions_design.md §9).
//!
//! Unlike `[fn]` and `[table.*]` (passive — looked up, may sit anywhere), a
//! `[var.*]` block is **active**: it executes at its file position among the
//! node sections, in the flow phase, top-to-bottom within the block. Each
//! `key = expression` line publishes the data-cache series `var.<block>.<key>`,
//! computed exactly once per timestep, readable anywhere, offset-addressable,
//! and recordable in `[outputs]` like any node output.
//!
//! These tests drive whole models from INI strings and hand-compute every
//! expectation. The reference smoke model runs 2020-01-30 .. 2020-02-03 (5
//! daily steps: Jan30, Jan31, Feb1, Feb2, Feb3). Inflow is written as
//! `(sim.step + 1) * 10`, so an inflow node's `ds_1` output is a known ramp
//! `[10, 20, 30, 40, 50]` with no data file needed (`sim.step` is 0-based).

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

/// Load a model expected to fail at parse/load, returning the error text.
/// (`Model` is not `Debug`, so `Result::expect_err` cannot be used.)
fn load_err(ini: &str) -> String {
    match IniModelIO::new().read_model_string(ini) {
        Ok(_) => panic!("expected a load error, but the model loaded"),
        Err(e) => e,
    }
}

/// Load, configure and run a model from INI, panicking with context on any
/// failure. Returns the configured, run model for series inspection.
fn run_model(ini: &str) -> Model {
    let mut model = IniModelIO::new().read_model_string(ini)
        .unwrap_or_else(|e| panic!("model should load: {e}"));
    model.configure().unwrap_or_else(|e| panic!("configure should succeed: {e}"));
    model.run().unwrap_or_else(|e| panic!("run should succeed: {e}"));
    model
}

/// Read a series' values by name, panicking if the series does not exist.
fn series(model: &Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_existing_series_idx(name)
        .unwrap_or_else(|| panic!("series '{name}' should exist"));
    model.data_cache.series[idx].values.clone()
}

/// Assert a produced series matches a hand-computed one within tolerance.
fn assert_series(actual: &[f64], expected: &[f64], label: &str) {
    assert_eq!(actual.len(), expected.len(),
        "{label}: length mismatch (got {actual:?}, expected {expected:?})");
    for (i, (&a, &e)) in actual.iter().zip(expected).enumerate() {
        assert!((a - e).abs() < 1e-9,
            "{label} step {i}: expected {e}, got {a} (full: {actual:?})");
    }
}

// ============================================================================
// 1. Basic: a var reads a node output computed above it, recorded via [outputs].
// ============================================================================

/// A var block placed AFTER the node it reads publishes a series over the whole
/// run. `doubled = node.headwater.ds_1 * 2` over the ramp [10..50] gives
/// [20,40,60,80,100]. The series is named in [outputs] and its existence is
/// asserted before its values are checked.
#[test]
fn var_reads_node_output_above_it() {
    let ini = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[node.headwater]
loc = 0, 0
type = inflow
inflow = (sim.step + 1) * 10
ds_1 = outlet

[var.calc]
doubled = node.headwater.ds_1 * 2

[node.outlet]
loc = 0, 10
type = blackhole

[outputs]
var.calc.doubled
";
    let model = run_model(ini);
    // Existence: the [outputs] entry names a series the var wrote.
    assert!(model.data_cache.get_existing_series_idx("var.calc.doubled").is_some(),
        "var.calc.doubled should exist as a recorded series");
    assert_series(&series(&model, "var.calc.doubled"),
        &[20.0, 40.0, 60.0, 80.0, 100.0], "var.calc.doubled");
}

// ============================================================================
// 2. Within-block sequencing: key B reads key A (same block) — this step's A.
// ============================================================================

/// `a` then `b = var.seq.a + 1` in the same block: b sees THIS step's a, so
/// b = a + 1 at every step (no one-step lag). a=[10..50], b=[11,21,31,41,51].
#[test]
fn within_block_later_key_sees_this_step_earlier_key() {
    let ini = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[node.headwater]
loc = 0, 0
type = inflow
inflow = (sim.step + 1) * 10
ds_1 = outlet

[var.seq]
a = node.headwater.ds_1
b = var.seq.a + 1

[node.outlet]
loc = 0, 10
type = blackhole

[outputs]
var.seq.a
var.seq.b
";
    let model = run_model(ini);
    assert_series(&series(&model, "var.seq.a"), &[10.0, 20.0, 30.0, 40.0, 50.0], "var.seq.a");
    assert_series(&series(&model, "var.seq.b"), &[11.0, 21.0, 31.0, 41.0, 51.0], "var.seq.b");
}

// ============================================================================
// 3. Cross-var offset: a var reads another var's [-1, default].
// ============================================================================

/// `prev = var.calc.x[-1, -999]` reads the PREVIOUS step's x. x=[10..50], so
/// prev=[-999,10,20,30,40] (step 0 has no previous → default -999).
#[test]
fn var_offset_reads_previous_step_with_default() {
    let ini = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[node.headwater]
loc = 0, 0
type = inflow
inflow = (sim.step + 1) * 10
ds_1 = outlet

[var.calc]
x = node.headwater.ds_1
prev = var.calc.x[-1, -999]

[node.outlet]
loc = 0, 10
type = blackhole

[outputs]
var.calc.prev
";
    let model = run_model(ini);
    assert_series(&series(&model, "var.calc.prev"),
        &[-999.0, 10.0, 20.0, 30.0, 40.0], "var.calc.prev");
}

// ============================================================================
// 4. Position matters — reading this-step node output.
// ============================================================================

/// A var reading `node.headwater.ds_1` with NO offset works when it is placed
/// AFTER the node (the node has already run this step). Same reference with the
/// var placed BEFORE the node fails at run start: step 0 reads an unwritten
/// series and the panic is surfaced as a run() Err mentioning "computed later
/// in the timestep".
#[test]
fn var_position_relative_to_node_decides_this_step_read() {
    // AFTER the node — the this-step read is legal.
    let after = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[node.headwater]
loc = 0, 0
type = inflow
inflow = (sim.step + 1) * 10
ds_1 = outlet

[var.calc]
seen = node.headwater.ds_1

[node.outlet]
loc = 0, 10
type = blackhole

[outputs]
var.calc.seen
";
    let model = run_model(after);
    assert_series(&series(&model, "var.calc.seen"),
        &[10.0, 20.0, 30.0, 40.0, 50.0], "var.calc.seen (after)");

    // BEFORE the node — the same this-step read is illegal.
    let before = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[var.calc]
seen = node.headwater.ds_1

[node.headwater]
loc = 0, 0
type = inflow
inflow = (sim.step + 1) * 10
ds_1 = outlet

[node.outlet]
loc = 0, 10
type = blackhole

[outputs]
var.calc.seen
";
    let mut model = IniModelIO::new().read_model_string(before)
        .expect("the before-position model still loads (validation is at run start)");
    model.configure().expect("configure should succeed");
    let err = model.run().expect_err("a this-step read of a node below the var must fail at run start");
    assert!(err.contains("computed later in the timestep"),
        "error should explain the forward-in-timestep read: {err}");
}

// ============================================================================
// 5. A node BELOW a var block reads the var's this-step value.
// ============================================================================

/// A gauge placed below `[var.calc]` reads `var.calc.x * 2` in its
/// `reference_flow`. x=[10..50] so reference_flow=[20,40,60,80,100]. (The gauge
/// only computes reference_flow when it is recorded, so it is in [outputs].)
#[test]
fn node_below_var_reads_var_this_step() {
    let ini = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[node.headwater]
loc = 0, 0
type = inflow
inflow = (sim.step + 1) * 10
ds_1 = gauge

[var.calc]
x = node.headwater.ds_1

[node.gauge]
loc = 0, 10
type = gauge
reference_flow = var.calc.x * 2
ds_1 = sink

[node.sink]
loc = 0, 20
type = blackhole

[outputs]
node.gauge.reference_flow
";
    let model = run_model(ini);
    assert_series(&series(&model, "node.gauge.reference_flow"),
        &[20.0, 40.0, 60.0, 80.0, 100.0], "node.gauge.reference_flow");
}

// ============================================================================
// 6. The accounting shape: blocks + fn + stateful inside var values.
// ============================================================================

/// The reference smoke model pinned by hand. `fn.new_wy()` is a water-year
/// boundary (first step of February). Over the ramp [10,20,30,40,50]:
///   inflow_wy = sum_since(node.headwater.ds_1, fn.new_wy())
///     step0 Jan30: run-start reset, sum = 10
///     step1 Jan31: 10+20 = 30
///     step2 Feb01: new_wy resets, sum = its own 30
///     step3 Feb02: 30+40 = 70
///     step4 Feb03: 70+50 = 120        => [10,30,30,70,120]
///   headroom = { cap=100; assert(cap>0); cap - inflow_wy } => [90,70,70,30,-20]
///   prev_headroom = headroom[-1, -999]                     => [-999,90,70,70,30]
#[test]
fn accounting_block_with_fn_and_stateful() {
    let ini = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[node.headwater]
loc = 0, 0
type = inflow
inflow = (sim.step + 1) * 10
ds_1 = outlet

[var.accounting]
inflow_wy = sum_since(node.headwater.ds_1, fn.new_wy())
headroom = {
    cap = 100;
    assert(cap > 0);
    cap - var.accounting.inflow_wy
    }
prev_headroom = var.accounting.headroom[-1, -999]

[node.outlet]
loc = 0, 10
type = blackhole

[fn]
new_wy() = sim.new_month && sim.month == 2

[outputs]
var.accounting.inflow_wy
var.accounting.headroom
var.accounting.prev_headroom
";
    let model = run_model(ini);
    assert_series(&series(&model, "var.accounting.inflow_wy"),
        &[10.0, 30.0, 30.0, 70.0, 120.0], "inflow_wy");
    assert_series(&series(&model, "var.accounting.headroom"),
        &[90.0, 70.0, 70.0, 30.0, -20.0], "headroom");
    assert_series(&series(&model, "var.accounting.prev_headroom"),
        &[-999.0, 90.0, 70.0, 70.0, 30.0], "prev_headroom");
}

// ============================================================================
// 7. Phase handling.
// ============================================================================

/// `phase = order` is designed but unimplemented — rejected at load.
#[test]
fn phase_order_is_rejected() {
    let ini = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[var.calc]
phase = order
x = 1
";
    let err = load_err(ini);
    assert!(err.contains("not yet implemented"),
        "phase = order should be rejected as unimplemented: {err}");
}

/// A phase value that is neither `flow` nor `order` is an invalid-phase error.
#[test]
fn phase_bogus_is_rejected() {
    let ini = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[var.calc]
phase = banana
x = 1
";
    let err = load_err(ini);
    assert!(err.contains("invalid phase"),
        "a bogus phase should be an invalid-phase error: {err}");
}

/// `phase = flow` (the default) loads and runs.
#[test]
fn phase_flow_loads() {
    let ini = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[node.headwater]
loc = 0, 0
type = inflow
inflow = (sim.step + 1) * 10
ds_1 = outlet

[var.calc]
phase = flow
x = node.headwater.ds_1

[node.outlet]
loc = 0, 10
type = blackhole

[outputs]
var.calc.x
";
    let model = run_model(ini);
    assert_series(&series(&model, "var.calc.x"), &[10.0, 20.0, 30.0, 40.0, 50.0], "var.calc.x");
}

// ============================================================================
// 8. Load errors: invalid block name, invalid key name.
// ============================================================================

/// A block name containing a dot (`[var.has.dot]`) is rejected — the segment
/// after `var.` is the namespace and must be a single bare name.
#[test]
fn invalid_block_name_with_dot_is_rejected() {
    let ini = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[var.has.dot]
x = 1
";
    let err = load_err(ini);
    assert!(err.contains("Invalid var block name"),
        "a dotted block name should be rejected: {err}");
}

/// A dotted key name inside a var block is rejected — keys are bare.
#[test]
fn invalid_dotted_key_name_is_rejected() {
    let ini = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[var.calc]
bad.key = 1
";
    let err = load_err(ini);
    assert!(err.contains("Invalid var name"),
        "a dotted key name should be rejected: {err}");
}

// NOTE on duplicate-series collision (test-plan item 8): no constructible case
// was found. Two keys `x` in one `[var.a]` section are silently collapsed by
// the INI parser's IndexMap before the var arm ever sees them (keys are unique
// per section). Two *different* blocks cannot collide because their series are
// namespaced by block name (`var.a.x` vs `var.b.x`). A var series could only
// collide with a pre-existing series of the identical `var.<block>.<key>` name,
// which nothing else in the model produces. The load arm's duplicate guard
// (`get_existing_series_idx(...).is_some()`) is therefore real but unreachable
// from INI; no test asserts it.

// ============================================================================
// 9. Forward offset on a var is a load error.
// ============================================================================

/// A positive offset on a var reference (`var.calc.x[1, 0]`) looks forward to a
/// value not yet computed — rejected at load, same as node outputs.
#[test]
fn forward_offset_on_var_is_rejected() {
    let ini = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[var.calc]
x = sim.step
fwd = var.calc.x[1, 0]
";
    let err = load_err(ini);
    assert!(err.contains("Forward lookup not supported for computed series"),
        "a forward offset on a var should be rejected: {err}");
}

// ============================================================================
// 10. Bare var reference is a series variable, not a call.
// ============================================================================

/// `var.calc.x + 1` in a downstream node treats `var.calc.x` as a series
/// reference (not a function call) and adds 1. Over x=[10..50] the gauge's
/// reference_flow is [11,21,31,41,51].
#[test]
fn bare_var_reference_is_a_variable_not_a_call() {
    let ini = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[node.headwater]
loc = 0, 0
type = inflow
inflow = (sim.step + 1) * 10
ds_1 = gauge

[var.calc]
x = node.headwater.ds_1

[node.gauge]
loc = 0, 10
type = gauge
reference_flow = var.calc.x + 1
ds_1 = sink

[node.sink]
loc = 0, 20
type = blackhole

[outputs]
node.gauge.reference_flow
";
    let model = run_model(ini);
    assert_series(&series(&model, "node.gauge.reference_flow"),
        &[11.0, 21.0, 31.0, 41.0, 51.0], "node.gauge.reference_flow");
}

// ============================================================================
// 11. Round-trip: [var.*] re-emitted at its file position, phase + exprs kept.
// ============================================================================

/// `model_to_string` re-emits `[var.accounting]` between the two node sections
/// it sat between (position is part of a var's meaning), preserving `phase` and
/// the original expression text. The re-emitted model re-loads and runs to the
/// identical outputs.
#[test]
fn round_trip_reemits_var_at_file_position() {
    let io = IniModelIO::new();
    let ini = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[node.headwater]
loc = 0, 0
type = inflow
inflow = (sim.step + 1) * 10
ds_1 = outlet

[var.accounting]
phase = flow
inflow_wy = sum_since(node.headwater.ds_1, sim.new_month)

[node.outlet]
loc = 0, 10
type = blackhole

[outputs]
var.accounting.inflow_wy
";
    let m1 = io.read_model_string(ini).expect("model should load");
    let serialised = io.model_to_string(&m1);

    // Position: the var header sits between the two node headers, in file order.
    let hw = serialised.find("[node.headwater]").expect("headwater header present");
    let var = serialised.find("[var.accounting]").expect("var header present");
    let out = serialised.find("[node.outlet]").expect("outlet header present");
    assert!(hw < var && var < out,
        "var block must be re-emitted between its neighbouring nodes:\n{serialised}");

    // Fidelity: phase and the original expression text survive.
    assert!(serialised.contains("phase") && serialised.contains("flow"),
        "phase = flow should survive the round-trip:\n{serialised}");
    assert!(serialised.contains("sum_since(node.headwater.ds_1, sim.new_month)"),
        "the original expression should survive verbatim:\n{serialised}");

    // Behaviour: re-loading the emitted text runs to identical outputs.
    let expected = series(&run_model(ini), "var.accounting.inflow_wy");
    let round_tripped = series(&run_model(&serialised), "var.accounting.inflow_wy");
    assert_series(&round_tripped, &expected, "round-tripped inflow_wy");
    // Sanity: sum_since over the ramp with a Feb-1 month reset = [10,30,30,70,120].
    assert_series(&expected, &[10.0, 30.0, 30.0, 70.0, 120.0], "inflow_wy sanity");
}

// ============================================================================
// 12. Two runs of the same model give identical var outputs (state reset).
// ============================================================================

/// Running the same model twice produces byte-identical var series — the
/// stateful `sum_since` and its advance guards reset at the start of each run.
#[test]
fn two_runs_produce_identical_var_outputs() {
    let ini = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[node.headwater]
loc = 0, 0
type = inflow
inflow = (sim.step + 1) * 10
ds_1 = outlet

[var.accounting]
inflow_wy = sum_since(node.headwater.ds_1, sim.new_month)

[node.outlet]
loc = 0, 10
type = blackhole

[outputs]
var.accounting.inflow_wy
";
    let mut model = IniModelIO::new().read_model_string(ini).expect("model should load");
    model.configure().expect("configure should succeed");

    model.run().expect("first run should succeed");
    let first = series(&model, "var.accounting.inflow_wy");

    model.run().expect("second run should succeed");
    let second = series(&model, "var.accounting.inflow_wy");

    assert_eq!(first, second, "two runs must produce identical var outputs");
    assert_series(&first, &[10.0, 30.0, 30.0, 70.0, 120.0], "inflow_wy hand-check");
}

// ============================================================================
// 13. Every key in a block is evaluated each step, even if unreferenced.
// ============================================================================

/// A block with three keys none of which is read elsewhere: all three are
/// recorded series with a full-length value at every step. a=[10..50],
/// b=3*a=[30..150], c=42 constant.
#[test]
fn all_keys_evaluate_every_step_even_if_unreferenced() {
    let ini = "\
[kalix]
start = 2020-01-30
end = 2020-02-03

[node.headwater]
loc = 0, 0
type = inflow
inflow = (sim.step + 1) * 10
ds_1 = outlet

[var.multi]
a = node.headwater.ds_1
b = node.headwater.ds_1 * 3
c = 42

[node.outlet]
loc = 0, 10
type = blackhole

[outputs]
var.multi.a
var.multi.b
var.multi.c
";
    let model = run_model(ini);
    assert_series(&series(&model, "var.multi.a"), &[10.0, 20.0, 30.0, 40.0, 50.0], "a");
    assert_series(&series(&model, "var.multi.b"), &[30.0, 60.0, 90.0, 120.0, 150.0], "b");
    assert_series(&series(&model, "var.multi.c"), &[42.0, 42.0, 42.0, 42.0, 42.0], "c");
}
