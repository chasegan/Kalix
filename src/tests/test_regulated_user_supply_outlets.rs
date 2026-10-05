// Tests for the regulated user's supply outlets (ds_2 to ds_4). A node on one
// of them - a field, here - places its orders with the user. The user adds
// them to its own order before order_factor, places the total into the
// network, and when the ordered water arrives diverts it and sends the
// outlet's share down the outlet.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

/// dam -> [reach of `river_lag` steps] -> user -> gauge, with a field on the
/// user's ds_2 through a channel of `channel_lag` steps, draining to the gauge.
/// `{HEAD}` is optional sections (accounts), `{USER}` the user's properties.
fn rig(head: &str, river_lag: usize, channel_lag: usize, user: &str, field_order: &str) -> String {
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-10
{head}

[crop.bare]
root_depth = 1000
kc = 0

[crop.thirsty]
root_depth = 1000
kc = 0

[node.dam]
type = storage
loc = 0, 10
initial_volume = 5000
dimensions = Level [m], Volume [ML], Area [km2], Spill [ML],
             0.0      , 0.0        , 0.0       , 0.0,
             1.0      , 10000.0    , 0.1       , 0.0,
             2.0      , 20000.0    , 0.1       , 1.0E9,
ds_1_outlet = 0, 10000
ds_1 = river

[node.river]
type = routing
loc = 0, 20
lag = {river_lag}
ds_1 = pump

[node.pump]
type = regulated_user
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
area = 1
available_water = 1000
initial_depletion = 1000
fallow = bare
crop_1 = thirsty
crop_1_plant = 1
crop_1_viable_area = this.area
loc = 10, 50
crop_1_order = {field_order}
ds_1 = outlet

[node.outlet]
type = gauge
loc = 0, 60

[outputs]
node.dam.ds_1_order
node.dam.ds_1
node.pump.usflow
node.pump.order
node.pump.order_due
node.pump.ds_2_order
node.pump.ds_2_order_due
node.pump.ds_2
node.pump.ds_1
node.pump.dsflow
node.pump.diversion
node.pump.diversion_regulated
node.paddock.usflow
node.paddock.crop_1_order_due
node.paddock.supply
node.outlet.usflow
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
fn test_outlet_order_joins_the_users_order_and_is_delivered() {
    // No travel time anywhere. The field orders 5 and the user 3 of its own.
    let mut model = run(&rig("", 0, 0, "order = 3", "5"));
    let t = 1;
    assert_eq!(series(&mut model, "node.pump.ds_2_order")[t], 5.0, "the field's order arrives on ds_2");
    assert_eq!(series(&mut model, "node.pump.order")[t], 3.0, "`order` stays the user's own order");
    assert_eq!(series(&mut model, "node.dam.ds_1_order")[t], 8.0, "the network sees the two together");
    assert_eq!(series(&mut model, "node.pump.usflow")[t], 8.0);
    assert_eq!(series(&mut model, "node.pump.diversion")[t], 8.0, "the metered take is the whole diversion");
    assert_eq!(series(&mut model, "node.pump.ds_2")[t], 5.0, "the field's share goes down the outlet");
    // The user's own share, diversion less ds_2, leaves the model here: see the mass balance test
    assert_eq!(series(&mut model, "node.pump.ds_1")[t], 0.0);
    assert_eq!(series(&mut model, "node.pump.dsflow")[t], 5.0, "dsflow is the total down all outlets");
    assert_eq!(series(&mut model, "node.paddock.supply")[t], 5.0);
    assert_eq!(series(&mut model, "node.outlet.usflow")[t], 0.0);
}

#[test]
fn test_mass_balance_counts_only_the_users_own_use() {
    let model = run(&rig("", 0, 0, "order = 3", "5"));
    let mbal: std::collections::HashMap<String, f64> = model.get_mass_balance_data().into_iter()
        .map(|(name, _, v)| (name, v)).collect();
    // Per timestep: the dam gives up 8, the user keeps 3, the field 5, nothing else moves
    assert_eq!(mbal["pump"], -3.0);
    assert_eq!(mbal["paddock"], -5.0);
    assert_eq!(mbal["dam"], 8.0);
    assert_eq!(mbal.values().sum::<f64>(), 0.0, "the model balances");
}

#[test]
fn test_outlet_order_is_held_for_the_users_travel_time() {
    // 2 steps from the dam to the user, 1 more down the channel to the field. The field's
    // order is timed from the dam like every other (3 steps). The user holds it for its own
    // 2, so that it diverts the water on the step it arrives, and the field receives it on
    // the step its order falls due.
    let mut model = run(&rig("", 2, 1, "order = 0", "5"));
    assert_eq!(series(&mut model, "node.dam.ds_1")[..5], [5.0, 5.0, 5.0, 5.0, 5.0], "released the step it is ordered");
    assert_eq!(series(&mut model, "node.pump.ds_2_order_due")[..5], [0.0, 0.0, 5.0, 5.0, 5.0]);
    assert_eq!(series(&mut model, "node.pump.usflow")[..5], [0.0, 0.0, 5.0, 5.0, 5.0]);
    assert_eq!(series(&mut model, "node.pump.ds_2")[..5], [0.0, 0.0, 5.0, 5.0, 5.0], "diverted as it arrives");
    assert_eq!(series(&mut model, "node.paddock.crop_1_order_due")[..5], [0.0, 0.0, 0.0, 5.0, 5.0]);
    assert_eq!(series(&mut model, "node.paddock.usflow")[..5], [0.0, 0.0, 0.0, 5.0, 5.0], "and received as it falls due");
    assert_eq!(series(&mut model, "node.paddock.supply")[..5], [0.0, 0.0, 0.0, 5.0, 5.0]);
    assert_eq!(series(&mut model, "node.pump.ds_1")[..5], [0.0; 5], "nothing passes the user undiverted");
}

#[test]
fn test_supply_outlets_are_served_before_the_users_own_use() {
    // 8 is ordered and arrives, but the pump can lift 6: the outlet's 5 is met and the user keeps 1
    let mut model = run(&rig("", 0, 0, "order = 3\npump = 6", "5"));
    let t = 1;
    assert_eq!(series(&mut model, "node.pump.usflow")[t], 8.0);
    assert_eq!(series(&mut model, "node.pump.ds_2")[t], 5.0);
    assert_eq!(series(&mut model, "node.pump.diversion")[t], 6.0, "so the user keeps 1");
    assert_eq!(series(&mut model, "node.pump.ds_1")[t], 2.0, "what the pump could not lift stays in the river");
}

#[test]
fn test_order_factor_scales_the_outlet_orders_with_the_users_own() {
    let mut model = run(&rig("", 0, 0, "order = 3\norder_factor = 1.5", "5"));
    let t = 1;
    assert_eq!(series(&mut model, "node.dam.ds_1_order")[t], 12.0, "1.5 x (3 + 5)");
    assert_eq!(series(&mut model, "node.pump.ds_2")[t], 5.0, "the delivery is the order as placed");
    assert_eq!(series(&mut model, "node.pump.diversion")[t], 8.0, "the user keeps its 3");
    assert_eq!(series(&mut model, "node.pump.ds_1")[t], 4.0, "the over-order passes downstream");
}

#[test]
fn test_account_cap_applies_to_the_whole_order_outlets_first() {
    // 6 ML in the account, 8 wanted: the outlet's 5 is accepted in full and the user's own 3 is cut to 1.
    let head = "[acc.licences]\naccounts = name, size, initial,\n           licence, 1000, 6,";
    let mut model = run(&rig(head, 0, 0, "order = 3\naccounts = licence", "5").replace("node.outlet.usflow", "node.outlet.usflow\nacc.licence.closing_balance"));
    assert_eq!(series(&mut model, "node.dam.ds_1_order")[0], 6.0, "the order is capped by what the user owns");
    assert_eq!(series(&mut model, "node.pump.ds_2_order")[0], 5.0, "ds_2_order is the order as it arrived");
    assert_eq!(series(&mut model, "node.pump.ds_2")[0], 5.0);
    assert_eq!(series(&mut model, "node.pump.diversion")[0], 6.0, "so the user keeps 1");
    assert_eq!(series(&mut model, "acc.licence.closing_balance")[0], 0.0, "the whole take is debited, the outlet's share included");
    assert_eq!(series(&mut model, "node.dam.ds_1_order")[1], 0.0, "nothing left to order with");
}

#[test]
fn test_supply_outlet_round_trips() {
    let ini = rig("", 0, 0, "order = 3", "5");
    let model = IniModelIO::read_model_string(&ini).expect("model should load");
    let rendered = IniModelIO::model_to_string(&model);
    assert!(rendered.contains("ds_2 = channel"), "the supply link survives save:\n{}", rendered);
    let reloaded = IniModelIO::read_model_string(&rendered).expect("canonical render should re-load");
    assert_eq!(IniModelIO::model_to_string(&reloaded), rendered);
}

#[test]
fn test_an_on_farm_storage_between_the_user_and_the_field() {
    // The in-line arrangement of a regulated farm: the user's supply outlet fills an on-farm
    // storage that holds a target level, and the storage supplies the field. The storage
    // orders up the outlet to reach its target, the user adds that to its own order, and the
    // dam releases it; the storage meanwhile supplies the field from what it holds. 2 steps
    // from the dam to the user, none from the storage to the field.
    let ini = r#"
[kalix]
start = 2020-01-01
end = 2020-01-08

[crop.bare]
root_depth = 1000
kc = 0

[crop.thirsty]
root_depth = 1000
kc = 0

[node.dam]
type = storage
loc = 0, 0
initial_volume = 50000
dimensions = 0, 0, 0, 0,
             1, 100000, 1, 0,
             2, 200000, 1, 1e9,
ds_1_outlet = 0, 10000
ds_1 = river

[node.river]
type = routing
loc = 0, 10
lag = 2
ds_1 = pump

[node.pump]
type = regulated_user
loc = 0, 20
order = 0
ds_1 = outlet
ds_2 = ofs

[node.ofs]
type = storage
loc = 10, 30
initial_volume = 1000
dimensions = 0, 0, 0, 0,
             1, 1000, 0.1, 0,
             3, 3000, 0.1, 0,
             4, 4000, 0.1, 1e9,
ds_1_outlet = 0, 10000
target_level = 1.5
ds_1 = paddock

[node.paddock]
type = field
loc = 10, 40
area = 1
available_water = 1000
initial_depletion = 1000
interception = 0
fallow = bare
crop_1 = thirsty
crop_1_plant = 1
crop_1_viable_area = this.area
crop_1_order = 10
ds_1 = outlet

[node.outlet]
type = gauge
loc = 0, 50

[outputs]
node.dam.ds_1_order
node.pump.ds_2_order
node.pump.ds_2
node.ofs.order
node.ofs.orders_en_route
node.ofs.usflow
node.ofs.volume
node.paddock.supply
"#;
    let mut model = run(ini);
    // Day 1: the field orders 10 of the storage; the storage, at 1000 against a 1500 target
    // and releasing 10 today, orders 510 up the outlet; the user passes it to the dam
    assert_eq!(series(&mut model, "node.ofs.order")[..3], [510.0, 10.0, 10.0]);
    assert_eq!(series(&mut model, "node.pump.ds_2_order")[..3], [510.0, 10.0, 10.0]);
    assert_eq!(series(&mut model, "node.dam.ds_1_order")[..3], [510.0, 10.0, 10.0]);
    // The storage counts the 510 as on its way and orders only the day's release after that
    assert_eq!(series(&mut model, "node.ofs.orders_en_route")[..3], [510.0, 520.0, 20.0]);
    // The field is supplied from the storage every day, while the 510 travels
    assert_eq!(series(&mut model, "node.paddock.supply")[..4], [10.0; 4]);
    assert_eq!(series(&mut model, "node.ofs.volume")[..2], [990.0, 980.0]);
    // Day 3: the 510 reaches the user, goes down the outlet and lands in the storage
    assert_eq!(series(&mut model, "node.pump.ds_2")[..3], [0.0, 0.0, 510.0]);
    assert_eq!(series(&mut model, "node.ofs.usflow")[2], 510.0);
    assert_eq!(series(&mut model, "node.ofs.volume")[2], 1480.0, "980 + 510 - 10");
}
