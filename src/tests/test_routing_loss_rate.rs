//! The routing node's optional `loss_rate` property: a reach loss [ML/timestep]
//! split equally across the divisions and taken inside each division's
//! backward Euler balance, bounded so no division's outflow goes negative.
//! Covers the INI reader and writer, the `loss_rate` recorder (raw expression
//! value; all-NaN when unset), the `loss` recorder (what was actually taken),
//! both bounds, and mass closure under PWL and NLM routing.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;
use crate::model_inputs::DynamicInput;
use crate::nodes::NodeEnum;

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

fn loss_rate_of<'a>(model: &'a Model, node_name: &str) -> &'a DynamicInput {
    match model.get_node(node_name).expect("node not found") {
        NodeEnum::RoutingNode(n) => &n.loss_rate,
        other => panic!("node '{}' is not a routing node: {}", node_name, other.get_type_as_string()),
    }
}

/// `inflow` ML/day into a reach configured by `reach_lines`, over `days` days.
fn model_ini(inflow: &str, reach_lines: &str, days: u32) -> String {
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
node.reach.loss_rate
node.reach.loss
")
}

/// Mass closure over the run: everything that came in either went out, was
/// lost, or is still in the reach.
fn assert_closure(model: &Model) {
    let us: f64 = series(model, "node.reach.usflow").iter().sum();
    let ds: f64 = series(model, "node.reach.dsflow").iter().sum();
    let lost: f64 = series(model, "node.reach.loss").iter().sum();
    let vol = *series(model, "node.reach.volume").last().unwrap();
    assert!((us - ds - lost - vol).abs() < 1e-9,
        "closure: usflow {us} - dsflow {ds} - loss {lost} - volume {vol} = {}", us - ds - lost - vol);
}

#[test]
fn absent_loss_rate_leaves_the_input_unset_and_loses_nothing() {
    let model = run_model(&model_ini("100", "", 3));
    assert!(matches!(loss_rate_of(&model, "reach"), DynamicInput::None { .. }));
    assert!(series(&model, "node.reach.loss_rate").iter().all(|v| v.is_nan()),
        "no loss_rate property: the recorder must say 'no value', not fabricate one");
    assert_eq!(series(&model, "node.reach.loss"), vec![0.0; 3]);
    assert_eq!(series(&model, "node.reach.dsflow"), vec![100.0; 3]);
}

#[test]
fn loss_rate_is_taken_from_a_pass_through_reach() {
    // lag 0, no table: the single division holds nothing, so loss = min(30, 100).
    let model = run_model(&model_ini("100", "loss_rate = 30", 3));
    assert!(matches!(loss_rate_of(&model, "reach"), DynamicInput::Constant { .. }));
    assert_eq!(series(&model, "node.reach.loss_rate"), vec![30.0; 3]);
    assert_eq!(series(&model, "node.reach.loss"), vec![30.0; 3]);
    assert_eq!(series(&model, "node.reach.dsflow"), vec![70.0; 3]);
    assert_closure(&model);
}

#[test]
fn loss_is_bounded_so_outflow_cannot_go_negative() {
    // Asking for 150 from a reach receiving 100 with nothing stored: the bound
    // is everything present, outflow lands at exactly zero, never below.
    let model = run_model(&model_ini("100", "loss_rate = 150", 3));
    assert_eq!(series(&model, "node.reach.loss_rate"), vec![150.0; 3], "recorded raw, before bounding");
    assert_eq!(series(&model, "node.reach.loss"), vec![100.0; 3]);
    assert_eq!(series(&model, "node.reach.dsflow"), vec![0.0; 3]);
    assert_closure(&model);
}

#[test]
fn a_dry_reach_loses_nothing() {
    let model = run_model(&model_ini("0", "loss_rate = 30", 3));
    assert_eq!(series(&model, "node.reach.loss"), vec![0.0; 3]);
    assert_eq!(series(&model, "node.reach.dsflow"), vec![0.0; 3]);
}

#[test]
fn nan_loss_rate_loses_nothing() {
    // A gap in a loss series arrives as NaN; the reach treats it as no loss.
    let model = run_model(&model_ini("100", "loss_rate = 0 / 0", 3));
    assert_eq!(series(&model, "node.reach.loss"), vec![0.0; 3]);
    assert_eq!(series(&model, "node.reach.dsflow"), vec![100.0; 3]);
}

#[test]
fn negative_loss_rate_loses_nothing() {
    let model = run_model(&model_ini("100", "loss_rate = 0 - 5", 3));
    assert_eq!(series(&model, "node.reach.loss"), vec![0.0; 3]);
    assert_eq!(series(&model, "node.reach.dsflow"), vec![100.0; 3]);
}

#[test]
fn loss_is_split_across_pwl_divisions_and_mass_closes() {
    // Three divisions, a real travel-time table, x < 1: each division takes a
    // third, the total is the reach's loss_rate, and the balance closes.
    let reach = "n_divs = 3\nx = 0.2\npwl = Flow [ML], Travel Time [steps],\n      0,   2,\n      100, 1,\n      1000, 0.5,\nloss_rate = 30";
    let model = run_model(&model_ini("100", reach, 10));
    let loss = series(&model, "node.reach.loss");
    assert!(loss.iter().all(|l| (l - 30.0).abs() < 1e-9), "loss each step should be the full 30: {loss:?}");
    assert!(series(&model, "node.reach.dsflow").iter().all(|q| *q >= 0.0));
    assert_closure(&model);
}

#[test]
fn loss_is_split_across_nlm_divisions_and_mass_closes() {
    let reach = "n_divs = 2\nx = 0.3\nnlm = 0.5, 0.8\nloss_rate = 30";
    let model = run_model(&model_ini("100", reach, 10));
    let loss = series(&model, "node.reach.loss");
    assert!(loss.iter().all(|l| (l - 30.0).abs() < 1e-9), "loss each step should be the full 30: {loss:?}");
    assert!(series(&model, "node.reach.dsflow").iter().all(|q| *q >= 0.0));
    assert_closure(&model);
}

#[test]
fn lag_delays_the_water_and_the_loss_waits_for_it() {
    // lag = 2: nothing reaches the division for two steps, so nothing is lost;
    // from step 2 the division sees 100 and loses 30.
    let model = run_model(&model_ini("100", "lag = 2\nloss_rate = 30", 5));
    assert_eq!(series(&model, "node.reach.loss"), vec![0.0, 0.0, 30.0, 30.0, 30.0]);
    assert_eq!(series(&model, "node.reach.dsflow"), vec![0.0, 0.0, 70.0, 70.0, 70.0]);
    assert_closure(&model);
}

#[test]
fn absent_loss_rate_is_not_emitted() {
    let m = IniModelIO::read_model_string(&model_ini("100", "", 3)).unwrap();
    let written = IniModelIO::model_to_string(&m);
    // The property form - the [outputs] section legitimately names node.reach.loss_rate.
    assert!(!written.contains("loss_rate = "), "absent loss_rate should not be serialised as a property, got:\n{written}");
}

#[test]
fn loss_rate_survives_a_full_round_trip() {
    let m1 = IniModelIO::read_model_string(&model_ini("100", "loss_rate = 30", 3)).unwrap();
    let written = IniModelIO::model_to_string(&m1);
    assert!(written.contains("loss_rate = "), "the writer must emit the loss_rate property:\n{written}");
    let m2 = IniModelIO::read_model_string(&written).expect("resaved model should load");
    assert_eq!(loss_rate_of(&m1, "reach").to_string(), loss_rate_of(&m2, "reach").to_string());
}
