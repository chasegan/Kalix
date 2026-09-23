// Tests for the field node SKELETON: a water demand that places orders
// upstream (as a regulated user does), takes its order from what arrives, and
// passes the rest to ds_1, which is a drain: the links leaving a field are
// not regulated. There is no soil store yet.

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
area = 1
capacity = 1000
initial_depletion = 1000
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
fn test_links_leaving_a_field_are_not_regulated() {
    // A regulated user below the field orders 7. The field's ds_1 is a drain,
    // not a delivery path, so the link below it is not regulated: the user is
    // outside any zone, its order goes nowhere, and the storage sees the
    // field's 5 alone.
    let ini = rig("order = 5")
        .replace("type = gauge\nloc = 0, 30", "type = regulated_user\nloc = 0, 30\norder = 7")
        .replace("[outputs]", "[outputs]\nnode.outlet.diversion\nnode.outlet.order");
    let mut model = run(&ini);
    assert_eq!(series(&mut model, "node.dam.ds_1_order")[1], 5.0);
    assert_eq!(series(&mut model, "node.paddock.supply")[1], 5.0);
    assert_eq!(series(&mut model, "node.outlet.diversion")[1], 0.0, "no order falls due outside a regulated zone");
    // The order phase never visits a node outside every zone, so its order is never evaluated
    let order = series(&mut model, "node.outlet.order");
    assert!(order.iter().all(|v| v.is_nan()) || order.is_empty(), "the user below the field is outside the zone, got {:?}", order);
}

#[test]
fn test_a_field_adds_nothing_to_the_travel_time_of_the_reach_below_it() {
    // The river: dam -> 2-step reach -> junction -> user. A field hangs off the
    // dam through a 5-step supply channel and drains to the same junction. The
    // user's travel time is the river's 2 steps; the field's 5 is no part of it.
    let ini = r#"
[kalix]
start = 2020-01-01
end = 2020-01-10

[node.dam]
type = storage
loc = 0, 10
initial_volume = 5000
dimensions = Level [m], Volume [ML], Area [km2], Spill [ML],
             0.0      , 0.0        , 0.0       , 0.0,
             1.0      , 10000.0    , 0.1       , 0.0,
             2.0      , 20000.0    , 0.1       , 1.0E9,
ds_1_outlet = 0, 10000
ds_2_outlet = 0, 10000
ds_1 = river
ds_2 = channel

[node.river]
type = routing
loc = 0, 20
lag = 2
ds_1 = junction

[node.channel]
type = routing
loc = 10, 20
lag = 5
ds_1 = paddock

[node.paddock]
type = field
area = 1
capacity = 1000
initial_depletion = 1000
loc = 10, 30
order = 3
ds_1 = junction

[node.junction]
type = gauge
loc = 0, 40
ds_1 = user

[node.user]
type = regulated_user
loc = 0, 50
order = 7

[outputs]
node.user.order_due
node.paddock.order_due
node.dam.ds_1_order
node.dam.ds_2_order
"#;
    let mut model = run(ini);
    assert_eq!(series(&mut model, "node.user.order_due")[..4], [0.0, 0.0, 7.0, 7.0], "2 steps, by the river");
    assert_eq!(series(&mut model, "node.paddock.order_due")[..7], [0.0, 0.0, 0.0, 0.0, 0.0, 3.0, 3.0], "5 steps, by the channel");
    assert_eq!(series(&mut model, "node.dam.ds_1_order")[0], 7.0, "the user's order goes up the river alone");
    assert_eq!(series(&mut model, "node.dam.ds_2_order")[0], 3.0, "and the field's up the channel");
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
