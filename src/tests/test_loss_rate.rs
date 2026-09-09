//! End-to-end tests for the loss node's optional `rate` property: a dynamic
//! expression that overrides the loss table for computing loss (the table, if
//! present, still shapes ordering). Covers the override, both clamps, the
//! `rate` recorder (raw pre-clamp value; all-NaN when unset), and the
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
/// so any test below can tell the table's answer (50) from the rate's.
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
node.reach_loss.rate
")
}

#[test]
fn without_rate_the_table_governs_and_the_rate_recorder_is_all_nan() {
    let model = run_model(&model_ini("table = 0, 0, 200, 100"));
    let loss = series(&model, "node.reach_loss.loss");
    assert_eq!(loss, vec![50.0; loss.len()], "the table halves a 100 inflow");
    let rate = series(&model, "node.reach_loss.rate");
    assert!(rate.iter().all(|v| v.is_nan()),
        "no rate property: the recorder must say 'no value', not fabricate one");
}

#[test]
fn rate_overrides_the_loss_table() {
    let model = run_model(&model_ini("table = 0, 0, 200, 100\nrate = 30"));
    assert_eq!(series(&model, "node.reach_loss.loss"), vec![30.0; 3],
        "rate wins over the table's 50");
    assert_eq!(series(&model, "node.reach_loss.dsflow"), vec![70.0; 3]);
    assert_eq!(series(&model, "node.reach_loss.rate"), vec![30.0; 3]);
}

#[test]
fn rate_is_clamped_to_available_flow_but_recorded_raw() {
    let model = run_model(&model_ini("rate = 150"));
    assert_eq!(series(&model, "node.reach_loss.loss"), vec![100.0; 3],
        "cannot lose more than arrives");
    assert_eq!(series(&model, "node.reach_loss.dsflow"), vec![0.0; 3]);
    assert_eq!(series(&model, "node.reach_loss.rate"), vec![150.0; 3],
        "the recorder reports the expression's value, not the clamped loss");
}

#[test]
fn negative_rate_loses_nothing() {
    let model = run_model(&model_ini("rate = 0 - 5"));
    assert_eq!(series(&model, "node.reach_loss.loss"), vec![0.0; 3]);
    assert_eq!(series(&model, "node.reach_loss.dsflow"), vec![100.0; 3]);
}

#[test]
fn rate_survives_a_resave() {
    let model = IniModelIO::read_model_string(&model_ini("table = 0, 0, 200, 100\nrate = 30"))
        .expect("model should load");
    let written = IniModelIO::model_to_string(&model);
    assert!(written.contains("rate"), "the writer must emit the rate property:\n{written}");
    // And the round trip behaves identically.
    let mut reread = IniModelIO::read_model_string(&written).expect("resaved model should load");
    reread.configure().unwrap();
    reread.run().unwrap();
    assert_eq!(series(&reread, "node.reach_loss.loss"), vec![30.0; 3]);
}
