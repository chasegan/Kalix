//! End-to-end tests for the loss node's optional `loss_rate` property: a dynamic
//! expression that overrides the loss table for computing loss (the table, if
//! present, still shapes ordering). Covers the override, both clamps, the
//! `loss_rate` recorder (raw pre-clamp value; all-NaN when unset), and the
//! write-side round trip.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

/// Load, configure and run a model from INI, panicking with context on any failure.
fn run_model(ini: &str) -> Model {
    let mut model = IniModelIO::read_model_string(ini)
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

/// A constant 100 ML/day into a loss node whose table would take half —
/// so any test below can tell the table's answer (50) from the loss_rate's.
fn model_ini(loss_lines: &str) -> String {
    format!("\
[kalix]
start = 2020-01-30
end = 2020-02-01

[node.source]
type = inflow
loc = 0, 0
inflow = 100
ds_1 = reach_loss

[node.reach_loss]
type = loss
loc = 0, 10
{loss_lines}
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 20

[outputs]
node.reach_loss.loss
node.reach_loss.dsflow
node.reach_loss.loss_rate
")
}

#[test]
fn without_loss_rate_the_table_governs_and_the_loss_rate_recorder_is_all_nan() {
    let model = run_model(&model_ini("table = 0, 0, 200, 100"));
    let loss = series(&model, "node.reach_loss.loss");
    assert_eq!(loss, vec![50.0; loss.len()], "the table halves a 100 inflow");
    let loss_rate = series(&model, "node.reach_loss.loss_rate");
    assert!(loss_rate.iter().all(|v| v.is_nan()),
        "no loss_rate property: the recorder must say 'no value', not fabricate one");
}

#[test]
fn loss_rate_overrides_the_loss_table() {
    let model = run_model(&model_ini("table = 0, 0, 200, 100\nloss_rate = 30"));
    assert_eq!(series(&model, "node.reach_loss.loss"), vec![30.0; 3],
        "loss_rate wins over the table's 50");
    assert_eq!(series(&model, "node.reach_loss.dsflow"), vec![70.0; 3]);
    assert_eq!(series(&model, "node.reach_loss.loss_rate"), vec![30.0; 3]);
}

#[test]
fn loss_rate_is_clamped_to_available_flow_but_recorded_raw() {
    let model = run_model(&model_ini("loss_rate = 150"));
    assert_eq!(series(&model, "node.reach_loss.loss"), vec![100.0; 3],
        "cannot lose more than arrives");
    assert_eq!(series(&model, "node.reach_loss.dsflow"), vec![0.0; 3]);
    assert_eq!(series(&model, "node.reach_loss.loss_rate"), vec![150.0; 3],
        "the recorder reports the expression's value, not the clamped loss");
}

#[test]
fn negative_loss_rate_loses_nothing() {
    let model = run_model(&model_ini("loss_rate = 0 - 5"));
    assert_eq!(series(&model, "node.reach_loss.loss"), vec![0.0; 3]);
    assert_eq!(series(&model, "node.reach_loss.dsflow"), vec![100.0; 3]);
}

#[test]
fn loss_rate_survives_a_resave() {
    let model = IniModelIO::read_model_string(&model_ini("table = 0, 0, 200, 100\nloss_rate = 30"))
        .expect("model should load");
    let written = IniModelIO::model_to_string(&model);
    assert!(written.contains("loss_rate = "), "the writer must emit the loss_rate property:\n{written}");
    assert!(!written.contains("\nrate = "), "the writer must not emit the old name:\n{written}");
    // And the round trip behaves identically.
    let mut reread = IniModelIO::read_model_string(&written).expect("resaved model should load");
    reread.configure().unwrap();
    reread.run().unwrap();
    assert_eq!(series(&reread, "node.reach_loss.loss"), vec![30.0; 3]);
}

/// The flow phase skips the table lookup beneath the inflow where the table
/// starts to lose water. The loss must be the same as the table gives on both
/// sides of that threshold and exactly on it: nothing at 150 and at 200, then
/// 75% of the excess up to 400, then the last segment extended.
#[test]
fn table_loss_is_unchanged_either_side_of_the_zero_loss_threshold() {
    for (inflow, expected_loss) in [(0.0, 0.0), (150.0, 0.0), (200.0, 0.0), (300.0, 75.0), (400.0, 150.0), (600.0, 300.0)] {
        let ini = format!("
[kalix]
start = 2020-01-01
end = 2020-01-03

[node.headwater]
type = inflow
loc = 0, 0
inflow = {inflow}
ds_1 = reach_loss

[node.reach_loss]
type = loss
loc = 0, 10
table = 0,   0,
        200, 0,
        400, 150,
ds_1 = outlet

[node.outlet]
type = gauge
loc = 0, 20

[outputs]
node.reach_loss.loss
node.reach_loss.dsflow
");
        let mut model = crate::io::ini_model_io::IniModelIO::read_model_string(&ini).expect("model should load");
        model.configure().expect("model should configure");
        model.run().expect("simulation should run");
        for (name, expected) in [("node.reach_loss.loss", expected_loss), ("node.reach_loss.dsflow", inflow - expected_loss)] {
            let idx = model.data_cache.get_series_idx(name, false).expect("series should exist");
            for v in &model.data_cache.series[idx].values {
                assert!((v - expected).abs() < 1e-9, "{name} at an inflow of {inflow}: got {v}, expected {expected}");
            }
        }
    }
}

/// The ordering phase skips the order translation beneath the same threshold.
/// The numbers are the Ordering page's worked example: no loss up to 200, then
/// 150 lost by 400. Orders of 100 and 200 reach the dam unchanged; an order of
/// 300 is raised to 450, because 450 - 150 = 300.
#[test]
fn orders_are_unchanged_beneath_the_zero_loss_threshold_and_raised_above_it() {
    for (order, expected_release) in [(100.0, 100.0), (200.0, 200.0), (300.0, 450.0)] {
        let ini = format!("
[kalix]
start = 2020-01-01
end = 2020-01-03

[node.dam]
type = storage
loc = 0, 0
initial_volume = 5000
dimensions = 0, 0, 0, 0, 1, 10000, 0, 0, 1.1, 10001, 0, 10000, 1.2, 10002, 0, 10000
ds_1 = reach_loss

[node.reach_loss]
type = loss
loc = 0, 10
table = Flow [ML], Loss [ML],
        0        , 0,
        200      , 0,
        400      , 150,
        1000     , 150,
ds_1 = user

[node.user]
type = regulated_user
loc = 0, 20
order = {order}

[outputs]
node.dam.ds_1
node.user.diversion
");
        let mut model = crate::io::ini_model_io::IniModelIO::read_model_string(&ini).expect("model should load");
        model.configure().expect("model should configure");
        model.run().expect("simulation should run");
        for (name, expected) in [("node.dam.ds_1", expected_release), ("node.user.diversion", order)] {
            let idx = model.data_cache.get_series_idx(name, false).expect("series should exist");
            for v in &model.data_cache.series[idx].values {
                assert!((v - expected).abs() < 1e-9, "{name} for an order of {order}: got {v}, expected {expected}");
            }
        }
    }
}
