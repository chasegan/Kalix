//! The routing node's optional `loss_rate` property - plumbing only, for now.
//!
//! These pin what exists today: the INI reader and writer, the `loss_rate`
//! recorder (the raw expression value; all-NaN when unset), and the fact that
//! the flow is NOT yet affected. The last test is expected to change when the
//! loss semantics land; it is here so that change is deliberate.

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

/// A constant 100 ML/day through a lag-only reach (pass-through), so any
/// effect of loss_rate on the flow would be visible as dsflow != 100.
fn model_ini(reach_lines: &str) -> String {
    format!("\
[kalix]
start = 2020-01-30
end = 2020-02-01

[node.source]
type = inflow
loc = 0, 0
inflow = 100
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
node.reach.loss_rate
")
}

#[test]
fn absent_loss_rate_leaves_the_input_unset_and_the_recorder_all_nan() {
    let model = run_model(&model_ini(""));
    assert!(matches!(loss_rate_of(&model, "reach"), DynamicInput::None { .. }));
    let lr = series(&model, "node.reach.loss_rate");
    assert!(lr.iter().all(|v| v.is_nan()),
        "no loss_rate property: the recorder must say 'no value', not fabricate one");
}

#[test]
fn loss_rate_is_evaluated_and_recorded() {
    let model = run_model(&model_ini("loss_rate = 30"));
    assert!(matches!(loss_rate_of(&model, "reach"), DynamicInput::Constant { .. }));
    assert_eq!(series(&model, "node.reach.loss_rate"), vec![30.0; 3]);
}

/// Plumbing only: the value is recorded but does not touch the flow. Expected
/// to change - deliberately - when the loss semantics are implemented.
#[test]
fn loss_rate_does_not_yet_affect_the_flow() {
    let model = run_model(&model_ini("loss_rate = 30"));
    assert_eq!(series(&model, "node.reach.dsflow"), vec![100.0; 3],
        "plumbing-only: dsflow must equal usflow until the loss is applied");
}

#[test]
fn absent_loss_rate_is_not_emitted() {
    let m = IniModelIO::read_model_string(&model_ini("")).unwrap();
    let written = IniModelIO::model_to_string(&m);
    // The property form - the [outputs] section legitimately names node.reach.loss_rate.
    assert!(!written.contains("loss_rate = "), "absent loss_rate should not be serialised as a property, got:\n{written}");
}

#[test]
fn loss_rate_survives_a_full_round_trip() {
    let m1 = IniModelIO::read_model_string(&model_ini("loss_rate = 30")).unwrap();
    let written = IniModelIO::model_to_string(&m1);
    assert!(written.contains("loss_rate = "), "the writer must emit the loss_rate property:\n{written}");
    let m2 = IniModelIO::read_model_string(&written).expect("resaved model should load");
    assert_eq!(loss_rate_of(&m1, "reach").to_string(), loss_rate_of(&m2, "reach").to_string());
}
