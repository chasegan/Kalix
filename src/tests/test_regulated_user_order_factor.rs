//! The regulated user's optional `order_factor`: a factor on the node's own
//! order as it goes upstream. The order placed, the order due, the account
//! cap and the delivery are untouched; only the network sees the scaled
//! value. Absent, it is 1 and the model is unchanged.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

fn run(ini: &str) -> Model {
    let mut model = IniModelIO::read_model_string(ini).expect("model should load");
    model.configure().expect("model should configure");
    model.run().expect("simulation should run");
    model
}

fn series(model: &Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_existing_series_idx(name)
        .unwrap_or_else(|| panic!("no series '{name}'"));
    model.data_cache.series[idx].values.clone()
}

/// A dam releasing to a user, a second user below it whose order passes
/// through the first, and a sink. `user_lines` goes into the upper user.
fn model(order: &str, user_lines: &str, lower_order: &str) -> String {
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-04

[node.src]
type = inflow
loc = 0, 0
inflow = 0
ds_1 = dam

[node.dam]
type = storage
loc = 0, 10
initial_volume = 5000
dimensions = Level [m], Volume [ML], Area [km2], Spill [ML],
             0        , 0          , 0         , 0,
             10       , 10000      , 0         , 0,
             10.1     , 10100      , 0         , 1e9,
ds_1 = user

[node.user]
type = regulated_user
loc = 0, 20
order = {order}
{user_lines}
ds_1 = lower

[node.lower]
type = regulated_user
loc = 0, 30
order = {lower_order}
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 40

[outputs]
node.dam.ds_1_order
node.dam.dsflow
node.user.ds_1_order
node.user.order
node.user.order_due
node.user.demand
node.user.diversion
node.user.dsflow
node.lower.diversion
"#)
}

#[test]
fn absent_order_factor_is_one_and_changes_nothing() {
    let plain = run(&model("100", "", "0"));
    let explicit = run(&model("100", "order_factor = 1", "0"));
    for name in ["node.dam.ds_1_order", "node.dam.dsflow", "node.user.order", "node.user.diversion"] {
        assert_eq!(series(&plain, name), series(&explicit, name), "{name}");
    }
    assert_eq!(series(&plain, "node.dam.ds_1_order"), vec![100.0; 4]);
}

#[test]
fn the_network_sees_the_scaled_order_while_the_node_does_not() {
    let m = run(&model("100", "order_factor = 1.1", "0"));
    let dam_order = series(&m, "node.dam.ds_1_order");
    assert!(dam_order.iter().all(|o| (o - 110.0).abs() < 1e-9), "the dam is asked for 110: {dam_order:?}");
    assert_eq!(series(&m, "node.user.order"), vec![100.0; 4], "the order placed is 100");
    assert_eq!(series(&m, "node.user.order_due"), vec![100.0; 4], "the order due is 100 (no travel time)");
    assert_eq!(series(&m, "node.user.demand"), vec![100.0; 4]);
    assert_eq!(series(&m, "node.user.diversion"), vec![100.0; 4], "the user takes what it ordered");
    let passed = series(&m, "node.user.dsflow");
    assert!(passed.iter().all(|q| (q - 10.0).abs() < 1e-9), "the extra 10 passes downstream: {passed:?}");
}

#[test]
fn downstream_orders_pass_through_unscaled() {
    // The lower user orders 50. The upper user sends 50 + 1.5 * 100 = 200 up,
    // and its ds_1_order shows the 50 it received.
    let m = run(&model("100", "order_factor = 1.5", "50"));
    assert_eq!(series(&m, "node.user.ds_1_order"), vec![50.0; 4]);
    let dam_order = series(&m, "node.dam.ds_1_order");
    assert!(dam_order.iter().all(|o| (o - 200.0).abs() < 1e-9), "{dam_order:?}");
    assert_eq!(series(&m, "node.lower.diversion"), vec![50.0; 4], "the lower user is served");
}

#[test]
fn a_factor_below_one_under_orders() {
    let m = run(&model("100", "order_factor = 0.5", "0"));
    let dam_order = series(&m, "node.dam.ds_1_order");
    assert!(dam_order.iter().all(|o| (o - 50.0).abs() < 1e-9), "{dam_order:?}");
    assert_eq!(series(&m, "node.user.order"), vec![100.0; 4]);
    assert_eq!(series(&m, "node.user.diversion"), vec![50.0; 4], "only what arrives can be taken");
}

#[test]
fn the_factor_applies_after_the_account_cap() {
    // The account holds 40, so the order is capped to 40 and the network is
    // asked for 1.1 * 40; the account, not the factor, is debited.
    let ini = model("100", "order_factor = 1.1\naccounts = a1", "0")
        .replace("[node.src]", "[acc.regular]\naccounts = name, size, initial,\n           a1, 1000, 40,\n\n[node.src]");
    let m = run(&ini);
    let dam_order = series(&m, "node.dam.ds_1_order");
    assert!((dam_order[0] - 44.0).abs() < 1e-9, "{dam_order:?}");
    assert_eq!(series(&m, "node.user.order")[0], 40.0);
}

#[test]
fn order_factor_is_a_static_output() {
    let m = run(&model("100", "order_factor = 1.25", "0").replace("[outputs]", "[outputs]\nnode.user.order_factor"));
    assert_eq!(series(&m, "node.user.order_factor"), vec![1.25; 4]);
}

#[test]
fn bad_values_are_rejected() {
    for bad in ["-0.5", "inf", "nan"] {
        let mut m = IniModelIO::read_model_string(&model("100", &format!("order_factor = {bad}"), "0")).expect("parses as a number");
        let err = m.configure().expect_err(&format!("configure must reject order_factor = {bad}"));
        assert!(err.contains("order_factor") && err.contains("user"), "{err}");
    }
    match IniModelIO::read_model_string(&model("100", "order_factor = lots", "0")) {
        Ok(_) => panic!("'lots' must not parse as an order_factor"),
        Err(e) => assert!(format!("{e:?}").contains("order_factor"), "{e:?}"),
    }
}

#[test]
fn survives_a_round_trip_and_is_not_written_when_default() {
    let plain = IniModelIO::model_to_string(&IniModelIO::read_model_string(&model("100", "", "0")).unwrap());
    assert!(!plain.contains("order_factor"), "default must not be written:\n{plain}");
    let written = IniModelIO::model_to_string(&IniModelIO::read_model_string(&model("100", "order_factor = 1.1", "0")).unwrap());
    assert!(written.contains("order_factor = 1.1"), "{written}");
    let m = run(&written);
    assert!((series(&m, "node.dam.ds_1_order")[0] - 110.0).abs() < 1e-9);
}
