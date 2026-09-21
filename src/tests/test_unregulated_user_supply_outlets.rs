// Tests for the unregulated user's supply outlets (ds_2 to ds_4). A node on one
// of them - a field, here - places its orders with the user, which is the
// supply at the top of that node's regulated zone. The user adds the orders to
// its demand as they arrive, takes what the river and its own limits allow,
// and sends the outlet's share down the outlet the same step. It orders
// nothing upstream: it is an unregulated user.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

/// river -> user -> gauge, with a field on the user's ds_2 through a channel of
/// `channel_lag` steps, draining to the gauge. `{RIVER}` is the nodes above the
/// user (the last of them named `river`), `{USER}` the user's properties.
fn rig(head: &str, river: &str, channel_lag: usize, user: &str, field_order: &str) -> String {
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-10
{head}
{river}

[node.pump]
type = unregulated_user
loc = 0, 30
{user}
ds_1 = outlet
ds_2 = channel

[node.channel]
type = routing
loc = 10, 40
lag = {channel_lag}
ds_1 = paddock

[node.paddock]
type = field
loc = 10, 50
order = {field_order}
ds_1 = outlet

[node.outlet]
type = gauge
loc = 0, 60

[outputs]
node.pump.usflow
node.pump.demand
node.pump.demand_carryover
node.pump.ds_2_order
node.pump.ds_2
node.pump.ds_1
node.pump.dsflow
node.pump.diversion
node.paddock.usflow
node.paddock.order_due
node.paddock.supply
"#)
}

/// An unregulated river carrying `inflow`
fn unregulated_river(inflow: &str) -> String {
    format!("[node.river]\ntype = inflow\nloc = 0, 0\ninflow = {inflow}\nds_1 = pump")
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
fn test_outlet_order_joins_the_demand_and_is_delivered_the_same_step() {
    // No storage anywhere: the user is the supply for the field.
    let mut model = run(&rig("", &unregulated_river("100"), 0, "demand = 3", "5"));
    let t = 0;
    assert_eq!(series(&mut model, "node.pump.ds_2_order")[t], 5.0, "the field's order arrives on ds_2");
    assert_eq!(series(&mut model, "node.pump.demand")[t], 3.0, "`demand` stays the user's own demand");
    assert_eq!(series(&mut model, "node.pump.diversion")[t], 8.0, "the metered take is the whole diversion");
    assert_eq!(series(&mut model, "node.pump.ds_2")[t], 5.0);
    assert_eq!(series(&mut model, "node.pump.ds_1")[t], 92.0);
    assert_eq!(series(&mut model, "node.pump.dsflow")[t], 97.0, "dsflow is the total down all outlets");
    assert_eq!(series(&mut model, "node.paddock.supply")[t], 5.0);
}

#[test]
fn test_mass_balance_counts_only_the_users_own_use() {
    let model = run(&rig("", &unregulated_river("100"), 0, "demand = 3", "5"));
    let mbal: std::collections::HashMap<String, f64> = model.get_mass_balance_data().into_iter()
        .map(|(name, _, v)| (name, v)).collect();
    assert_eq!(mbal["pump"], -3.0);
    assert_eq!(mbal["paddock"], -5.0);
}

#[test]
fn test_supply_outlets_are_served_before_the_users_own_demand() {
    // The river carries 6 and 8 is wanted: the outlet's 5 is met and the user keeps 1
    let mut model = run(&rig("", &unregulated_river("6"), 0, "demand = 3", "5"));
    assert_eq!(series(&mut model, "node.pump.ds_2")[0], 5.0);
    assert_eq!(series(&mut model, "node.pump.diversion")[0], 6.0, "so the user keeps 1");
    assert_eq!(series(&mut model, "node.pump.ds_1")[0], 0.0);
}

#[test]
fn test_limits_on_the_take_apply_to_the_whole_diversion() {
    // pump: 8 wanted, 6 can be lifted
    let mut model = run(&rig("", &unregulated_river("100"), 0, "demand = 3\npump = 6", "5"));
    assert_eq!(series(&mut model, "node.pump.ds_2")[0], 5.0);
    assert_eq!(series(&mut model, "node.pump.diversion")[0], 6.0);
    // flow_threshold: 100 in the river, 96 to be left in it
    let mut model = run(&rig("", &unregulated_river("100"), 0, "demand = 3\nflow_threshold = 96", "5"));
    assert_eq!(series(&mut model, "node.pump.ds_2")[0], 4.0);
    assert_eq!(series(&mut model, "node.pump.diversion")[0], 4.0);
    // annual_cap: 20 for the year, 8 a day
    let mut model = run(&rig("", &unregulated_river("100"), 0, "demand = 3\nannual_cap = 20, 7", "5"));
    assert_eq!(series(&mut model, "node.pump.diversion")[..4], [8.0, 8.0, 4.0, 0.0], "the outlet's share counts against the cap");
    assert_eq!(series(&mut model, "node.pump.ds_2")[..4], [5.0, 5.0, 4.0, 0.0]);
}

#[test]
fn test_the_whole_take_is_debited_to_the_users_accounts() {
    let head = "[acc.licences]\naccounts = name, size, initial,\n           licence, 1000, 12,";
    let mut model = run(&rig(head, &unregulated_river("100"), 0, "demand = 3\naccounts = licence", "5")
        .replace("[outputs]", "[outputs]\nacc.licence.closing_balance"));
    assert_eq!(series(&mut model, "node.pump.diversion")[..3], [8.0, 4.0, 0.0]);
    assert_eq!(series(&mut model, "node.pump.ds_2")[..3], [5.0, 4.0, 0.0], "outlets first");
    assert_eq!(series(&mut model, "acc.licence.closing_balance")[..3], [4.0, 0.0, 0.0]);
}

#[test]
fn test_outlet_orders_take_no_part_in_carryover() {
    // The river is dry on the first day. The user's own unmet 3 is carried over. The field's
    // unmet 5 is not: the field orders again the next day, and carrying its order over as
    // well would deliver it twice.
    let head = "[var.count]\nphase = ras\nn = var.count.n[-1, 0] + 1";
    let mut model = run(&rig(head, &unregulated_river("if(var.count.n > 1, 100, 0)"), 0, "demand = 3\ndemand_carryover = true", "5"));
    assert_eq!(series(&mut model, "node.pump.diversion")[0], 0.0);
    assert_eq!(series(&mut model, "node.pump.demand_carryover")[0], 3.0, "the user's own demand alone");
    assert_eq!(series(&mut model, "node.pump.ds_2")[1], 5.0, "one day's order, not two");
    assert_eq!(series(&mut model, "node.pump.diversion")[1], 11.0, "5 down the outlet, and the user's 3 + 3");
    assert_eq!(series(&mut model, "node.pump.demand_carryover")[1], 0.0);
}

#[test]
fn test_travel_time_is_counted_from_the_user() {
    // The user sits 3 steps below a dam, in the dam's regulated zone, and the field 2 steps
    // below the user. The field's zone starts at the user: its order falls due after 2 steps,
    // not 5; the user diverts the step the order arrives; and none of it is ordered from the dam.
    let river = r#"[node.dam]
type = storage
loc = 0, 0
initial_volume = 5000
dimensions = Level [m], Volume [ML], Area [km2], Spill [ML],
             0.0      , 0.0        , 0.0       , 0.0,
             1.0      , 10000.0    , 0.1       , 0.0,
             2.0      , 20000.0    , 0.1       , 1.0E9,
ds_1_outlet = 0, 10000
ds_1 = trib

[node.trib]
type = inflow
loc = 0, 5
inflow = 100
ds_1 = river

[node.river]
type = routing
loc = 0, 10
lag = 3
ds_1 = pump"#;
    let mut model = run(&rig("", river, 2, "demand = 0", "5").replace("[outputs]", "[outputs]\nnode.dam.ds_1_order"));
    assert_eq!(series(&mut model, "node.paddock.order_due")[..4], [0.0, 0.0, 5.0, 5.0]);
    assert_eq!(series(&mut model, "node.pump.ds_2")[3..6], [5.0, 5.0, 5.0], "diverted as ordered, once the river is flowing");
    assert_eq!(series(&mut model, "node.paddock.supply")[5..8], [5.0, 5.0, 5.0], "and received 2 steps later, as it falls due");
    assert_eq!(series(&mut model, "node.dam.ds_1_order")[..4], [0.0; 4], "an unregulated user orders nothing upstream");
}

#[test]
fn test_supply_outlet_round_trips() {
    let ini = rig("", &unregulated_river("100"), 0, "demand = 3", "5");
    let model = IniModelIO::read_model_string(&ini).expect("model should load");
    let rendered = IniModelIO::model_to_string(&model);
    assert!(rendered.contains("ds_2 = channel"), "the supply link survives save:\n{}", rendered);
    let reloaded = IniModelIO::read_model_string(&rendered).expect("canonical render should re-load");
    assert_eq!(IniModelIO::model_to_string(&reloaded), rendered);
}
