//! The routing node's optional `dead_storage`: the volume a reach holds at
//! zero flow, split equally across its divisions and built into each
//! division's storage law as an offset. Covers the INI reader and writer,
//! the static output, starting at dead level, the filling regime, a loss
//! draining the pool once outflow is zero, and mass closure under PWL and NLM.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

fn run_model(ini: &str) -> Model {
    let mut model = IniModelIO::read_model_string(ini)
        .unwrap_or_else(|e| panic!("model should load: {e}"));
    model.configure().unwrap_or_else(|e| panic!("configure should succeed: {e}"));
    model.run().unwrap_or_else(|e| panic!("run should succeed: {e}"));
    model
}

fn series(model: &Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_existing_series_idx(name)
        .unwrap_or_else(|| panic!("series '{name}' should exist"));
    model.data_cache.series[idx].values.clone()
}

fn model_ini(inflow: &str, reach_lines: &str, days: u32, extra_outputs: &str) -> String {
    format!("\
[kalix]
start = 2020-01-01
end = 2020-01-{days:02}

[node.source]
type = inflow
loc = 0, 0
inflow = {inflow}
ds_1 = reach

[node.reach]
type = routing
loc = 0, 10
{reach_lines}
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 20

[outputs]
node.reach.usflow
node.reach.dsflow
node.reach.volume
node.reach.loss
{extra_outputs}
")
}

/// Everything that came in went out, was lost, or changed the reach's storage
/// - measured from the dead level the reach starts at.
fn assert_closure(model: &Model, initial_volume: f64) {
    let us: f64 = series(model, "node.reach.usflow").iter().sum();
    let ds: f64 = series(model, "node.reach.dsflow").iter().sum();
    let lost: f64 = series(model, "node.reach.loss").iter().sum();
    let vol = *series(model, "node.reach.volume").last().unwrap();
    let residual = us - ds - lost - (vol - initial_volume);
    assert!(residual.abs() < 1e-9, "closure: in {us} - out {ds} - lost {lost} - dV {} = {residual}", vol - initial_volume);
}

#[test]
fn absent_dead_storage_changes_nothing() {
    let model = run_model(&model_ini("100", "", 3, ""));
    assert_eq!(series(&model, "node.reach.dsflow"), vec![100.0; 3]);
    assert_eq!(series(&model, "node.reach.volume"), vec![0.0; 3]);
}

#[test]
fn reach_starts_at_dead_level_and_passes_flow_at_once() {
    // A full pool routes nothing extra: inflow goes straight through and the
    // pool stays at its level. The declared value is a static output.
    let model = run_model(&model_ini("30", "dead_storage = 100", 3, "node.reach.dead_storage"));
    assert_eq!(series(&model, "node.reach.dsflow"), vec![30.0; 3]);
    assert_eq!(series(&model, "node.reach.volume"), vec![100.0; 3]);
    assert_eq!(series(&model, "node.reach.dead_storage"), vec![100.0; 3]);
    assert_closure(&model, 100.0);
}

#[test]
fn a_loss_drives_outflow_to_zero_then_drains_the_pool() {
    // Day 1: 150 present (100 dead + 50 in). The bounded loss takes the 50 of
    // live water, outflow lands at zero, then the remaining 100 of the request
    // comes out of the pool. Every day after: below dead level, the pool takes
    // the inflow and the loss takes it back.
    let model = run_model(&model_ini("50", "dead_storage = 100\nloss_rate = 150", 3, ""));
    assert_eq!(series(&model, "node.reach.loss"), vec![150.0, 50.0, 50.0]);
    assert_eq!(series(&model, "node.reach.dsflow"), vec![0.0; 3]);
    assert_eq!(series(&model, "node.reach.volume"), vec![0.0; 3]);
    assert_closure(&model, 100.0);
}

#[test]
fn a_moderate_loss_leaves_the_pool_alone_while_live_water_covers_it() {
    let model = run_model(&model_ini("50", "dead_storage = 100\nloss_rate = 20", 3, ""));
    assert_eq!(series(&model, "node.reach.loss"), vec![20.0; 3]);
    assert_eq!(series(&model, "node.reach.dsflow"), vec![30.0; 3]);
    assert_eq!(series(&model, "node.reach.volume"), vec![100.0; 3]);
    assert_closure(&model, 100.0);
}

#[test]
fn pwl_reach_with_dead_storage_closes_and_holds_its_dead_level() {
    let reach = "n_divs = 3\nx = 0.2\ndead_storage = 90\npwl = Flow [ML], Travel Time [steps],\n      0,   2,\n      100, 1,\n      1000, 0.5,";
    let model = run_model(&model_ini("100", reach, 10, ""));
    assert!(series(&model, "node.reach.volume").iter().all(|v| *v >= 90.0 - 1e-9), "never below the dead level without a loss");
    assert!(series(&model, "node.reach.dsflow").iter().all(|q| *q > 0.0), "a full pool passes flow from the first step");
    assert_closure(&model, 90.0);
}

#[test]
fn nlm_reach_with_dead_storage_and_a_loss_closes() {
    let reach = "n_divs = 2\nx = 0.3\nnlm = 0.5, 0.8\ndead_storage = 60\nloss_rate = 20";
    let model = run_model(&model_ini("100", reach, 10, ""));
    let loss = series(&model, "node.reach.loss");
    assert!(loss.iter().all(|l| (l - 20.0).abs() < 1e-9), "live water covers the loss every step: {loss:?}");
    assert!(series(&model, "node.reach.volume").iter().all(|v| *v >= 60.0 - 1e-9));
    assert_closure(&model, 60.0);
}

#[test]
fn nlm_at_x_unity_with_dead_storage_and_a_loss_closes() {
    // x = 1 has its own filling test (the law's storage against what is
    // present) rather than the dead-level guard; both must hold the pool
    // while live water covers the loss, and drain it when it does not.
    let held = run_model(&model_ini("100", "x = 1\nnlm = 20000, 0.8\ndead_storage = 40\nloss_rate = 10", 6, ""));
    assert!(series(&held, "node.reach.loss").iter().all(|l| (l - 10.0).abs() < 1e-9));
    assert!(series(&held, "node.reach.volume").iter().all(|v| *v >= 40.0 - 1e-9), "live water covers the loss: {:?}", series(&held, "node.reach.volume"));
    assert_closure(&held, 40.0);
    let drained = run_model(&model_ini("100", "x = 1\nnlm = 20000, 0.8\ndead_storage = 40\nloss_rate = 500", 6, ""));
    assert_eq!(*series(&drained, "node.reach.volume").last().unwrap(), 0.0, "a loss that large empties the pool");
    assert_closure(&drained, 40.0);
}

#[test]
fn configuring_twice_does_not_double_the_dead_offset() {
    // The offset is added to the PWL segment constants at initialise; those
    // constants are rebuilt from scratch each time, so a second configure
    // must give the same run.
    let reach = "n_divs = 3\nx = 0.2\ndead_storage = 90\npwl = Flow [ML], Travel Time [steps],\n      0,   2,\n      100, 1,\n      1000, 0.5,";
    let mut model = IniModelIO::read_model_string(&model_ini("100", reach, 10, "")).unwrap();
    model.configure().unwrap();
    model.run().unwrap();
    let first = (series(&model, "node.reach.volume"), series(&model, "node.reach.dsflow"));
    model.configure().unwrap();
    model.run().unwrap();
    assert_eq!(series(&model, "node.reach.volume"), first.0);
    assert_eq!(series(&model, "node.reach.dsflow"), first.1);
}

#[test]
fn infinite_dead_storage_is_rejected() {
    let mut model = IniModelIO::read_model_string(&model_ini("100", "dead_storage = inf", 3, "")).expect("parses as a number");
    let err = model.configure().expect_err("configure must reject an infinite dead storage");
    assert!(err.contains("dead_storage"), "error should name the property: {err}");
}

#[test]
fn negative_dead_storage_is_rejected() {
    let mut model = IniModelIO::read_model_string(&model_ini("100", "dead_storage = -5", 3, "")).expect("parses as a number");
    let err = model.configure().expect_err("configure must reject a negative dead storage");
    assert!(err.contains("dead_storage"), "error should name the property: {err}");
}

#[test]
fn dead_storage_survives_a_round_trip_and_is_not_emitted_when_absent() {
    let m = IniModelIO::read_model_string(&model_ini("100", "", 3, "")).unwrap();
    assert!(!IniModelIO::model_to_string(&m).contains("dead_storage = "));
    let m1 = IniModelIO::read_model_string(&model_ini("100", "dead_storage = 100", 3, "")).unwrap();
    let written = IniModelIO::model_to_string(&m1);
    assert!(written.contains("dead_storage = 100"), "the writer must emit it:\n{written}");
    let m2 = run_model(&written);
    assert_eq!(series(&m2, "node.reach.volume"), vec![100.0; 3]);
}

#[test]
fn both_properties_survive_a_round_trip_together() {
    let ini = model_ini("100", "dead_storage = 90\nloss_rate = 5", 5, "");
    let original = run_model(&ini);
    let written = IniModelIO::model_to_string(&IniModelIO::read_model_string(&ini).unwrap());
    assert!(written.contains("dead_storage = 90") && written.contains("loss_rate = 5"), "both must be emitted:\n{written}");
    let reread = run_model(&written);
    for name in ["node.reach.dsflow", "node.reach.volume", "node.reach.loss"] {
        assert_eq!(series(&reread, name), series(&original, name), "{name} must survive the round trip");
    }
}
