// Tests for the regulated user's opportunistic_demand: a flow-phase demand for
// water above the arriving order (e.g. off-allocation access announced on flow
// conditions). Opportunistic take is supplied from what the regulated delivery
// leaves behind and is debited to the same accounts, but recorded separately
// (diversion_regulated / diversion_opportunistic) so a resource assessment can
// count regulated usage only.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

/// Minimal regulated rig: a supplied storage defines the ordering zone and
/// releases the user's orders; a tributary joins below it so surplus water
/// (beyond the ordered release) is available for opportunistic extraction.
/// `{TRIB}` is the tributary inflow, `{USER}` the regulated user's properties,
/// `{HEAD}` optional sections (accounts), `{OUT}` the outputs.
fn rig(head: &str, trib: &str, user: &str, outputs: &str) -> String {
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-04
{head}

[node.src]
type = inflow
loc = 0, 0
inflow = 100
ds_1 = dam

[node.dam]
type = storage
loc = 0, 10
initial_volume = 500
dimensions = Level [m], Volume [ML], Area [km2], Spill [ML],
             0.0      , 0.0        , 0.0       , 0.0,
             1.0      , 1000.0     , 0.1       , 0.0,
             2.0      , 2000.0     , 0.1       , 1.0E9,
ds_1_outlet = 0, 10000
ds_1 = conf

[node.trib]
type = inflow
loc = 10, 10
inflow = {trib}
ds_1 = conf

[node.conf]
type = confluence
loc = 5, 20
harmony_fraction = 1
ds_1 = u1

[node.u1]
type = regulated_user
loc = 5, 30
{user}

[outputs]
{outputs}
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

const ALL_OUT: &str = "node.u1.diversion\nnode.u1.diversion_regulated\nnode.u1.diversion_opportunistic\nnode.u1.opportunistic_demand\nnode.u1.dsflow\nnode.u1.usflow";

#[test]
fn test_opportunistic_take_above_order() {
    // Tributary 30 on top of the ordered release of 5: the order is delivered
    // and the opportunistic demand of 10 is taken from the surplus.
    let ini = rig("", "30", "order = 5\nopportunistic_demand = 10", ALL_OUT);
    let mut model = run(&ini);
    assert_eq!(series(&mut model, "node.u1.usflow")[1], 35.0, "release 5 + tributary 30 arrives");
    assert_eq!(series(&mut model, "node.u1.diversion_regulated")[1], 5.0);
    assert_eq!(series(&mut model, "node.u1.diversion_opportunistic")[1], 10.0);
    assert_eq!(series(&mut model, "node.u1.opportunistic_demand")[1], 10.0);
    assert_eq!(series(&mut model, "node.u1.diversion")[1], 15.0, "total diversion is the sum of both paths");
    assert_eq!(series(&mut model, "node.u1.dsflow")[1], 20.0, "mass balance holds");
}

#[test]
fn test_opportunistic_yields_to_regulated_delivery() {
    // Scarce surplus: the arriving order has first claim on availability; the
    // opportunistic take only gets the tributary's leftover 7.
    let ini = rig("", "7", "order = 5\nopportunistic_demand = 10", ALL_OUT);
    let mut model = run(&ini);
    assert_eq!(series(&mut model, "node.u1.diversion_regulated")[1], 5.0);
    assert_eq!(series(&mut model, "node.u1.diversion_opportunistic")[1], 7.0, "only the leftover 7 is available");
}

#[test]
fn test_pump_capacity_caps_combined_take() {
    // The pump limits the combined extraction, not each path separately.
    let ini = rig("", "30", "order = 5\npump = 8\nopportunistic_demand = 10", ALL_OUT);
    let mut model = run(&ini);
    assert_eq!(series(&mut model, "node.u1.diversion_regulated")[1], 5.0);
    assert_eq!(series(&mut model, "node.u1.diversion_opportunistic")[1], 3.0, "pump 8 leaves 3 above the order");
}

#[test]
fn test_opportunistic_debits_accounts_after_regulated() {
    // Balance 20, order 5/day + opportunistic 10/day. Day 1 takes 5 + 10
    // (balance 20 -> 5). Day 2 the regulated delivery has first claim on the
    // remaining 5, leaving nothing opportunistic. Day 3 the account is dry:
    // ordering is balance-capped to 0 and the opportunistic take is too.
    let head = "\n[acc.g1]\naccounts = name, size, initial,\n           a1, 100, 20,\n";
    let ini = rig(head, "30", "order = 5\nopportunistic_demand = 10\naccounts = a1", ALL_OUT);
    let mut model = run(&ini);
    let reg = series(&mut model, "node.u1.diversion_regulated");
    let opp = series(&mut model, "node.u1.diversion_opportunistic");
    assert_eq!((reg[0], opp[0]), (5.0, 10.0), "day 1: both paths supplied");
    assert_eq!((reg[1], opp[1]), (5.0, 0.0), "day 2: regulated has first claim on the last 5");
    assert_eq!((reg[2], opp[2]), (0.0, 0.0), "day 3: account empty");
    let a1 = model.account_manager.get_account_idx("a1").unwrap();
    assert_eq!(model.account_manager.get_account_balance(a1), 0.0, "both paths debit the account");
}

#[test]
fn test_negative_opportunistic_demand_is_clamped() {
    // A negative expression value must not become a negative diversion.
    let ini = rig("", "30", "order = 5\nopportunistic_demand = -3", ALL_OUT);
    let mut model = run(&ini);
    assert_eq!(series(&mut model, "node.u1.diversion_opportunistic")[1], 0.0);
    assert_eq!(series(&mut model, "node.u1.diversion")[1], 5.0, "regulated delivery unaffected");
}

#[test]
fn test_without_opportunistic_demand_behaviour_unchanged() {
    // A plain regulated user records zero on the new series and diverts as before.
    let ini = rig("", "30", "order = 5", ALL_OUT);
    let mut model = run(&ini);
    assert_eq!(series(&mut model, "node.u1.diversion")[1], 5.0);
    assert_eq!(series(&mut model, "node.u1.diversion_regulated")[1], 5.0);
    assert_eq!(series(&mut model, "node.u1.diversion_opportunistic")[1], 0.0);
    assert_eq!(series(&mut model, "node.u1.opportunistic_demand")[1], 0.0);
}

#[test]
fn test_opportunistic_demand_round_trips() {
    let ini = rig("", "30", "order = 5\nopportunistic_demand = 10", "node.u1.diversion");
    let model = IniModelIO::read_model_string(&ini).expect("model should load");
    let rendered = IniModelIO::model_to_string(&model);
    assert!(rendered.contains("opportunistic_demand = 10"),
            "property survives save:\n{}", rendered);
    IniModelIO::read_model_string(&rendered).expect("canonical render should re-load");
}
