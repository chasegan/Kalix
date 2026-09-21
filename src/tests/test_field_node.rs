// Tests for the field node SKELETON: a water demand that places orders
// upstream (as a regulated user does), takes its order from what arrives, and
// passes the rest to ds_1. There is no soil store yet.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

/// A supplied storage releases the field's orders on ds_1, with zero travel
/// time. `{FIELD}` is the field's properties.
fn rig(field: &str) -> String {
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-04

[node.dam]
type = storage
loc = 0, 10
initial_volume = 500
dimensions = Level [m], Volume [ML], Area [km2], Spill [ML],
             0.0      , 0.0        , 0.0       , 0.0,
             1.0      , 1000.0     , 0.1       , 0.0,
             2.0      , 2000.0     , 0.1       , 1.0E9,
ds_1_outlet = 0, 10000
ds_1 = paddock

[node.paddock]
type = field
loc = 0, 20
{field}
ds_1 = outlet

[node.outlet]
type = gauge
loc = 0, 30

[outputs]
node.dam.ds_1_order
node.dam.volume
node.paddock.usflow
node.paddock.order
node.paddock.order_due
node.paddock.supply
node.paddock.dsflow
"#)
}

fn run(ini: &str) -> Model {
    let mut model = IniModelIO::read_model_string(ini).expect("model should load");
    model.configure().expect("model should configure");
    model.run().expect("simulation should run");
    model
}

fn series(model: &mut Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_series_idx(name, false)
        .unwrap_or_else(|| panic!("missing series {}", name));
    model.data_cache.series[idx].values.clone()
}

#[test]
fn test_field_orders_from_storage_and_takes_its_order() {
    let mut model = run(&rig("order = 5"));
    for step in 0..4 {
        assert_eq!(series(&mut model, "node.dam.ds_1_order")[step], 5.0, "the storage sees the field's order");
        assert_eq!(series(&mut model, "node.paddock.order")[step], 5.0);
        assert_eq!(series(&mut model, "node.paddock.order_due")[step], 5.0, "zero travel time: due on the day it is placed");
        assert_eq!(series(&mut model, "node.paddock.usflow")[step], 5.0, "the storage releases the order");
        assert_eq!(series(&mut model, "node.paddock.supply")[step], 5.0);
        assert_eq!(series(&mut model, "node.paddock.dsflow")[step], 0.0);
    }
    // 4 steps x 5 ML leave the storage and leave the model through the field
    assert_eq!(series(&mut model, "node.dam.volume")[3], 480.0);
}

#[test]
fn test_field_without_an_order_takes_nothing() {
    let mut model = run(&rig(""));
    assert_eq!(series(&mut model, "node.paddock.order")[1], 0.0);
    assert_eq!(series(&mut model, "node.paddock.supply")[1], 0.0);
    assert_eq!(series(&mut model, "node.dam.volume")[3], 500.0);
}

#[test]
fn test_negative_order_is_no_order() {
    let mut model = run(&rig("order = -5"));
    assert_eq!(series(&mut model, "node.paddock.order")[1], 0.0);
    assert_eq!(series(&mut model, "node.dam.ds_1_order")[1], 0.0);
}

#[test]
fn test_field_does_not_pass_downstream_orders_upstream() {
    // A regulated user below the field orders 7. The field records the order
    // arriving on its ds_1 and drops it: the storage sees the field's 5 alone.
    let ini = rig("order = 5")
        .replace("type = gauge\nloc = 0, 30", "type = regulated_user\nloc = 0, 30\norder = 7")
        .replace("[outputs]", "[outputs]\nnode.paddock.ds_1_order");
    let mut model = run(&ini);
    assert_eq!(series(&mut model, "node.paddock.ds_1_order")[1], 7.0, "the arriving order is visible");
    assert_eq!(series(&mut model, "node.dam.ds_1_order")[1], 5.0, "and is not sent on");
}

#[test]
fn test_field_round_trips() {
    let ini = rig("order = 5");
    let model = IniModelIO::read_model_string(&ini).expect("model should load");
    let rendered = IniModelIO::model_to_string(&model);
    assert!(rendered.contains("type = field"), "type survives save:\n{}", rendered);
    assert!(rendered.contains("order = 5"), "property survives save:\n{}", rendered);
    IniModelIO::read_model_string(&rendered).expect("canonical render should re-load");
}
