// The accounts' `committed` forecast: a regulated user may only order what its
// accounts hold less the orders it has already placed and not yet received.
//
// Without it, a user below a travel time of T steps orders against the same
// balance T times over - nothing has been debited yet - and the supply releases
// water that cannot be delivered. The contract:
// 1. CAP BY THE FORECAST: the order cap is sum(balance - committed) over the
//    regular accounts, floored at zero per account, plus the order_accounts.
// 2. COMMIT ON ACCEPT: the accepted order is added to the accounts' committed
//    tally at order time, cascading in list order.
// 3. RELEASE ON DUE, DEBIT ON DIVERSION: on the step an order falls due the
//    whole order leaves the tally, delivered or not. Only the water actually
//    diverted is debited, so a short delivery leaves the balance free to be
//    ordered again from that step.
// 4. The tally is a forecast, never a debit: balances, debits, use and
//    allocation never see it. It is published as acc.<name>.committed as each
//    day's ordering begins, so opening_balance - committed is the headroom
//    that day's orders are capped by.
//
// These semantics belong to the accounts, not the node, so that users sharing
// an account see each other's orders en route.
use crate::io::ini_model_io::IniModelIO;

fn load(ini: &str) -> crate::model::Model {
    let mut model = IniModelIO::read_model_string(ini).expect("model should load");
    model.configure().expect("model should configure");
    model
}

fn run(ini: &str) -> crate::model::Model {
    let mut model = load(ini);
    model.run().expect("simulation should run");
    model
}

fn series(model: &mut crate::model::Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_series_idx(name, false)
        .unwrap_or_else(|| panic!("no series '{}'", name));
    model.data_cache.series[idx].values.clone()
}

fn balance(model: &crate::model::Model, name: &str) -> f64 {
    let idx = model.account_manager.get_account_idx(name).unwrap();
    model.account_manager.get_account_balance(idx)
}

/// One user two steps below its supply, ordering 100 a day against an account
/// holding 100. `dam_volume` is what the dam holds at the start, so the test can
/// choose how much of the order is deliverable.
fn lagged_model(dam_volume: f64, order: &str, extra: &str) -> String {
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-07

[acc.entitlements]
accounts = name, size, initial,
           a,    1000, 100,

[node.dam]
type = storage
loc = 0, 0
initial_volume = {dam_volume}
dimensions = 0,  0,     0,  0,
             10, 12000, 10, 0,
             11, 12100, 10, 1000,
ds_1 = reach

[node.reach]
type = routing
loc = 0, 10
lag = 2
ds_1 = user

[node.user]
type = regulated_user
loc = 0, 20
order = {order}
accounts = a
{extra}
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 30

[outputs]
node.user.order
node.user.order_due
node.user.diversion
node.dam.ds_1
acc.a.committed
acc.a.closing_balance
acc.a.debits
"#)
}

/// Contracts 1-3 with a full delivery. The first order is placed against the
/// whole balance; while it is en route the account is spoken for and the user
/// orders nothing more; the dam releases 100 once, not three times over.
#[test]
fn test_orders_en_route_cap_further_orders() {
    let mut m = run(&lagged_model(12000.0, "100", ""));
    assert_eq!(series(&mut m, "node.user.order"), vec![100.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "node.dam.ds_1"), vec![100.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "node.user.order_due"), vec![0.0, 0.0, 100.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "node.user.diversion"), vec![0.0, 0.0, 100.0, 0.0, 0.0, 0.0, 0.0]);
    // committed is written as each day's ordering begins: the order placed on day 1 is en
    // route on days 2 and 3, and released before day 4's ordering
    assert_eq!(series(&mut m, "acc.a.committed"), vec![0.0, 100.0, 100.0, 0.0, 0.0, 0.0, 0.0]);
    // The balance moves only with the diversion
    assert_eq!(series(&mut m, "acc.a.closing_balance"), vec![100.0, 100.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "acc.a.debits"), vec![0.0, 0.0, 100.0, 0.0, 0.0, 0.0, 0.0]);
}

/// Contract 3 with a short delivery: the dam holds 40, so 40 of the 100 due
/// arrives. The whole 100 leaves the forecast that step, only the 40 is
/// debited, and the 60 left in the account is ordered again next step.
#[test]
fn test_short_delivery_frees_the_balance_for_reordering() {
    let mut m = run(&lagged_model(40.0, "100", ""));
    assert_eq!(series(&mut m, "node.user.order"), vec![100.0, 0.0, 0.0, 60.0, 0.0, 0.0, 60.0]);
    assert_eq!(series(&mut m, "node.user.diversion"), vec![0.0, 0.0, 40.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "acc.a.committed"), vec![0.0, 100.0, 100.0, 0.0, 60.0, 60.0, 0.0]);
    assert_eq!(series(&mut m, "acc.a.debits"), vec![0.0, 0.0, 40.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(balance(&m, "a"), 60.0);
}

/// With no travel time the forecast is a within-step affair: commit in the
/// order phase, release in the flow phase. Results are as they were.
#[test]
fn test_no_lag_is_unchanged() {
    let ini = lagged_model(12000.0, "30", "").replace("lag = 2", "lag = 0");
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "node.user.order"), vec![30.0, 30.0, 30.0, 10.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "node.user.diversion"), vec![30.0, 30.0, 30.0, 10.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "acc.a.committed"), vec![0.0; 7]);
}

/// Two users on one account, no travel time. The downstream user is visited
/// first in the order phase and commits 80 of the 100; the upstream user sees
/// 20 left. The dam releases 100, not 160. At flow time each user plans to
/// divert its whole demand, not the order it could place: the upstream user
/// finds 100 in the river and 100 in the pool and takes its 80, and the
/// downstream user, whose order most of that water was released for, gets the
/// 20 left. The forecast gates orders, never takes - so sharers upstream are
/// served first whatever was ordered.
#[test]
fn test_shared_account_sees_the_other_users_order() {
    let mut m = run(r#"
[kalix]
start = 2020-01-01
end = 2020-01-01

[acc.scheme]
accounts = name, size, initial,
           pool, 1000, 100,

[node.dam]
type = storage
loc = 0, 0
initial_volume = 12000
dimensions = 0,  0,     0,  0,
             10, 12000, 10, 0,
             11, 12100, 10, 1000,
ds_1 = upstream_user

[node.upstream_user]
type = regulated_user
loc = 0, 10
order = 80
accounts = pool
ds_1 = downstream_user

[node.downstream_user]
type = regulated_user
loc = 0, 20
order = 80
accounts = pool
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 30

[outputs]
node.upstream_user.order
node.downstream_user.order
node.upstream_user.diversion
node.downstream_user.diversion
node.dam.ds_1
node.sink.usflow
acc.scheme.committed
"#);
    assert_eq!(series(&mut m, "node.downstream_user.order"), vec![80.0]);
    assert_eq!(series(&mut m, "node.upstream_user.order"), vec![20.0]);
    assert_eq!(series(&mut m, "node.dam.ds_1"), vec![100.0]);
    assert_eq!(series(&mut m, "node.upstream_user.diversion"), vec![80.0]);
    assert_eq!(series(&mut m, "node.downstream_user.diversion"), vec![20.0]);
    assert_eq!(series(&mut m, "node.sink.usflow"), vec![0.0]);
    // The group aggregate is published too; nothing was en route as the day began
    assert_eq!(series(&mut m, "acc.scheme.committed"), vec![0.0]);
}

/// Orders from a supply outlet are the user's orders: they are committed and
/// released with its own. A field on ds_2 ordering 30 a day plus the user's
/// own 30: day 1 commits 60, day 2 has 40 left and serves the outlet first
/// (30 to the field, 10 of the user's own), and nothing more is ordered until
/// the deliveries free the balance.
#[test]
fn test_supply_outlet_orders_are_committed_too() {
    let ini = lagged_model(12000.0, "30", "ds_2 = paddock")
        .replace("[node.dam]", "[crop.bare]\nroot_depth = 1000\nkc = 0\n\n[crop.thirsty]\nroot_depth = 1000\nkc = 0\n\n[node.dam]")
        .replace("[node.sink]", r#"[node.paddock]
type = field
loc = 10, 25
area = 1
available_water = 1000
initial_depletion = 1000
fallow = bare
crop_1 = thirsty
crop_1_plant = 1
crop_1_viable_area = this.area
crop_1_order = 30
ds_1 = sink

[node.sink]"#)
        .replace("[outputs]", "[outputs]\nnode.user.ds_2_order\nnode.user.ds_2_order_due");
    let mut m = run(&ini);
    // ds_2_order is the order as it arrived; the accepted order shows up as ds_2_order_due
    assert_eq!(series(&mut m, "node.user.ds_2_order"), vec![30.0; 7]);
    assert_eq!(series(&mut m, "node.user.ds_2_order_due"), vec![0.0, 0.0, 30.0, 30.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "node.user.order"), vec![30.0, 10.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "node.user.order_due"), vec![0.0, 0.0, 30.0, 10.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "acc.a.committed"), vec![0.0, 60.0, 100.0, 40.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "node.user.diversion"), vec![0.0, 0.0, 60.0, 40.0, 0.0, 0.0, 0.0]);
    assert_eq!(balance(&m, "a"), 0.0);
}

/// order_accounts authorise what the regular accounts cannot cover, and "cannot
/// cover" is measured net of the forecast. Account a holds 20 and the user
/// orders 20 a day one step below the dam: day 1 is covered by a; on day 2 a's
/// 20 is en route, so the bridge pays for the whole order.
#[test]
fn test_order_account_excess_is_measured_net_of_the_forecast() {
    let ini = lagged_model(12000.0, "20", "order_accounts = bridge")
        .replace("lag = 2", "lag = 1")
        .replace("a,    1000, 100,", "a,    1000, 20,\n\n[acc.bridges]\naccounts = name, size, initial,\n           bridge, 1000, 100,")
        .replace("end = 2020-01-07", "end = 2020-01-02");
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "node.user.order"), vec![20.0, 20.0]);
    assert_eq!(balance(&m, "bridge"), 80.0, "day 2's order is authorised by the bridge");
    assert_eq!(balance(&m, "a"), 0.0, "day 1's order arrived on day 2 and was debited");
}

/// The tally is run state: a second run of the same model must start with it
/// empty, like the node's order buffer.
#[test]
fn test_rerun_starts_with_nothing_committed() {
    let mut m = load(&lagged_model(12000.0, "100", "").replace("end = 2020-01-07", "end = 2020-01-02"));
    m.run().expect("first run");
    let first = series(&mut m, "node.user.order");
    assert_eq!(first, vec![100.0, 0.0]);
    m.run().expect("second run");
    assert_eq!(series(&mut m, "node.user.order"), first);
}

/// Each commitment leaves the account it entered, on the step its order is due,
/// whatever has happened to the balances in between. User x draws on its own
/// account first and a pool second, two steps below the dam, ordering 80 a day.
/// Day 1 commits 50 on x_co and 30 on the pool. On day 2 a RAS credits x_co
/// 100, so day 2's 80 sits wholly on x_co; day 3 commits the 20 x_co has left
/// and 60 on the pool. Before day 4's ordering, day 1's order is released: 50
/// off x_co and 30 off the pool, exactly as committed. (A release walked across the
/// accounts by list order would have taken all 80 off x_co, leaving the pool
/// spoken for by an order that no longer exists.) The forecast and the debit
/// can still part company - day 2's 80 is debited 70 from x_co and 10 from the
/// pool on day 4, by the balances of the day - so the pool goes into day 5
/// with 100 committed against 90 held, which the cap then works off.
#[test]
fn test_release_comes_off_the_account_the_order_was_committed_to() {
    let mut m = run(r#"
[kalix]
start = 2020-01-01
end = 2020-01-04

[acc.own]
accounts = name, size, initial,
           x_co, 1000, 50,

[acc.shared]
accounts = name, size, initial,
           pool, 1000, 100,

[ras.top_up]
targets = acc.own
trigger = sim.day == 2
action  = credit(100)

[node.dam]
type = storage
loc = 0, 0
initial_volume = 12000
dimensions = 0,  0,     0,  0,
             10, 12000, 10, 0,
             11, 12100, 10, 1000,
ds_1 = reach

[node.reach]
type = routing
loc = 0, 10
lag = 2
ds_1 = x

[node.x]
type = regulated_user
loc = 0, 20
order = 80
accounts = x_co, pool
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 30

[outputs]
node.x.order
node.x.diversion
acc.x_co.committed
acc.pool.committed
acc.x_co.closing_balance
acc.pool.closing_balance
"#);
    assert_eq!(series(&mut m, "node.x.order"), vec![80.0, 80.0, 80.0, 40.0]);
    assert_eq!(series(&mut m, "acc.x_co.committed"), vec![0.0, 50.0, 130.0, 100.0]);
    assert_eq!(series(&mut m, "acc.pool.committed"), vec![0.0, 30.0, 30.0, 60.0]);
    // Deliveries are debited by list order over the balances of the day, as before
    assert_eq!(series(&mut m, "node.x.diversion"), vec![0.0, 0.0, 80.0, 80.0]);
    assert_eq!(series(&mut m, "acc.x_co.closing_balance"), vec![50.0, 150.0, 70.0, 0.0]);
    assert_eq!(series(&mut m, "acc.pool.closing_balance"), vec![100.0, 100.0, 100.0, 90.0]);
}

/// Two users with different travel times on one account. The downstream user
/// (visited first, three steps from the dam) commits 60 due in three steps;
/// the upstream user (one step from the dam) sees 40 left and commits it due
/// in one. Each commitment is released on its own due step: the upstream
/// user's on day 2, the downstream user's on day 4. The upstream user takes
/// its whole demand of 60 on day 2 (the water and the balance are there), so
/// the downstream user receives 40 on day 4.
#[test]
fn test_users_with_different_travel_times_share_one_ring() {
    let mut m = run(r#"
[kalix]
start = 2020-01-01
end = 2020-01-05

[acc.scheme]
accounts = name, size, initial,
           pool, 1000, 100,

[node.dam]
type = storage
loc = 0, 0
initial_volume = 12000
dimensions = 0,  0,     0,  0,
             10, 12000, 10, 0,
             11, 12100, 10, 1000,
ds_1 = reach_1

[node.reach_1]
type = routing
loc = 0, 10
lag = 1
ds_1 = near

[node.near]
type = regulated_user
loc = 0, 20
order = 60
accounts = pool
ds_1 = reach_2

[node.reach_2]
type = routing
loc = 0, 30
lag = 2
ds_1 = far

[node.far]
type = regulated_user
loc = 0, 40
order = 60
accounts = pool
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 50

[outputs]
node.near.order
node.far.order
node.near.diversion
node.far.diversion
node.dam.ds_1
acc.pool.committed
"#);
    assert_eq!(series(&mut m, "node.far.order"), vec![60.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "node.near.order"), vec![40.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "node.dam.ds_1"), vec![100.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "node.near.diversion"), vec![0.0, 60.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "node.far.diversion"), vec![0.0, 0.0, 0.0, 40.0, 0.0]);
    assert_eq!(series(&mut m, "acc.pool.committed"), vec![0.0, 100.0, 60.0, 60.0, 0.0]);
    assert_eq!(balance(&m, "pool"), 0.0);
}

/// The forecast gates what is ordered, never what is taken. The user plans to
/// divert its whole demand when the water arrives; the take is capped by the
/// water in the river and the balance of the day. Account a holds 40 against a
/// demand of 100, so day 1's order is 40. A tributary below the reach puts 100
/// in the river every day, and a RAS credits a 100 at the top of day 3, the day
/// the order arrives: the user takes its full 100, not the 40 it could order.
/// Day 2 could order nothing, but its demand of 100 still falls due on day 4,
/// and with the tributary there the 40 left in the account is taken too. Day
/// 3's order of 100 then arrives on day 5 to an empty account.
#[test]
fn test_planned_diversion_is_the_whole_demand() {
    let ini = lagged_model(12000.0, "100", "")
        .replace("a,    1000, 100,", "a,    1000, 40,")
        .replace("[node.user]", "[node.tributary]\ntype = inflow\nloc = 0, 15\ninflow = 100\nds_1 = user\n\n[node.user]")
        .replace("ds_1 = user\n\n[node.tributary]", "ds_1 = tributary\n\n[node.tributary]")
        .replace("[outputs]", "[ras.credit]\ntargets = acc.entitlements\ntrigger = sim.day == 3\naction  = credit(100)\n\n[outputs]\nnode.user.demand");
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "node.user.order"), vec![40.0, 0.0, 100.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "node.user.order_due"), vec![0.0, 0.0, 40.0, 0.0, 100.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "node.user.demand"), vec![0.0, 0.0, 100.0, 100.0, 100.0, 100.0, 100.0]);
    assert_eq!(series(&mut m, "node.user.diversion"), vec![0.0, 0.0, 100.0, 40.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "acc.a.closing_balance"), vec![40.0, 40.0, 40.0, 0.0, 0.0, 0.0, 0.0]);
}

/// ...and without the credit the balance of the day still caps the take: the
/// same 100 flows past on day 3, but the account holds 40, and 40 is taken.
#[test]
fn test_planned_diversion_is_still_capped_by_the_balance_of_the_day() {
    let ini = lagged_model(12000.0, "100", "")
        .replace("a,    1000, 100,", "a,    1000, 40,")
        .replace("[node.user]", "[node.tributary]\ntype = inflow\nloc = 0, 15\ninflow = 100\nds_1 = user\n\n[node.user]")
        .replace("ds_1 = user\n\n[node.tributary]", "ds_1 = tributary\n\n[node.tributary]")
        .replace("[outputs]", "[outputs]\nnode.user.demand");
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "node.user.demand"), vec![0.0, 0.0, 100.0, 100.0, 100.0, 100.0, 100.0]);
    assert_eq!(series(&mut m, "node.user.diversion"), vec![0.0, 0.0, 40.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(balance(&m, "a"), 0.0);
}

/// A supply outlet is planned the same way: the delivery down it is the order
/// as it arrived, not the part the user could order. The field asks for 30 a
/// day against an account holding 10; with the river and the balance replenished
/// on day 3 the user sends the field its 30, against an order due of 10.
#[test]
fn test_supply_outlet_delivery_is_planned_at_the_arriving_order() {
    let ini = lagged_model(12000.0, "0", "ds_2 = paddock")
        .replace("a,    1000, 100,", "a,    1000, 10,")
        .replace("[node.dam]", "[crop.bare]\nroot_depth = 1000\nkc = 0\n\n[crop.thirsty]\nroot_depth = 1000\nkc = 0\n\n[node.dam]")
        .replace("[node.user]", "[node.tributary]\ntype = inflow\nloc = 0, 15\ninflow = 100\nds_1 = user\n\n[node.user]")
        .replace("ds_1 = user\n\n[node.tributary]", "ds_1 = tributary\n\n[node.tributary]")
        .replace("[node.sink]", r#"[node.paddock]
type = field
loc = 10, 25
area = 1
available_water = 1000
initial_depletion = 1000
fallow = bare
crop_1 = thirsty
crop_1_plant = 1
crop_1_viable_area = this.area
crop_1_order = 30
ds_1 = sink

[node.sink]"#)
        .replace("[outputs]", "[ras.credit]\ntargets = acc.entitlements\ntrigger = sim.day == 3\naction  = credit(100)\n\n[outputs]\nnode.user.ds_2\nnode.user.ds_2_order_due")
        .replace("end = 2020-01-07", "end = 2020-01-03");
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "node.user.ds_2_order_due"), vec![0.0, 0.0, 10.0]);
    assert_eq!(series(&mut m, "node.user.ds_2"), vec![0.0, 0.0, 30.0]);
    assert_eq!(series(&mut m, "node.user.diversion"), vec![0.0, 0.0, 30.0]);
    assert_eq!(balance(&m, "a"), 80.0);
}
