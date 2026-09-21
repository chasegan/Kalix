// Tests for regulated orders on a splitter's effluent (ds_2) link.
//
// The splitter sends orders from both outlets upstream, and when the ordered
// water arrives it diverts the effluent's share down ds_2. "When it arrives"
// is the supply travel time, so the splitter must delay effluent orders by
// exactly the travel time the effluent's users assume for themselves.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

const DAM_DIMENSIONS: &str = "dimensions = 0,  0,     0,  0,
             10, 12000, 10, 0,
             11, 12100, 10, 1000,";

/// A rising order pattern (1, 2, 3, ...), so a timing error of any number of
/// steps shows up as a different value rather than hiding in a constant.
const HEADER: &str = "
[kalix]
start = 2020-01-01
end = 2020-01-12

[var.patterns]
phase = ras
p1 = var.patterns.p1[-1, 0] + 1
";

/// The splitter, a gauge on the main channel, and a regulated user on the
/// effluent.
const SPLITTER_AND_BELOW: &str = "
[node.splitter_1]
type = splitter
loc = 5, 20
table = 0,   0,
        100, 0,
ds_1 = gauge_1
ds_2 = user_2

[node.gauge_1]
type = gauge
loc = 5, 30

[node.user_2]
type = regulated_user
loc = 15, 30
order = var.patterns.p1

[outputs]
node.splitter_1.ds_2
node.splitter_1.ds_2_order_due
node.user_2.order_due
node.user_2.diversion
";

/// A supply storage feeding the splitter through a pure lag.
fn supply(name: &str, lag: usize) -> String {
    format!("
[node.dam_{name}]
type = storage
loc = 0, 0
initial_volume = 12000
{DAM_DIMENSIONS}
ds_1 = lag_{name}

[node.lag_{name}]
type = routing
loc = 0, 10
lag = {lag}
ds_1 = splitter_1
")
}

fn load(ini: &str) -> Model {
    let mut model = IniModelIO::read_model_string(ini).expect("model should load");
    model.configure().expect("model should configure");
    model
}

fn series(model: &mut Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_series_idx(name, false)
        .unwrap_or_else(|| panic!("missing series {}", name));
    model.data_cache.series[idx].values.clone()
}

/// One supply path, two steps away: the effluent user's order is diverted
/// down ds_2 on the step it falls due, and the user takes all of it.
#[test]
fn effluent_order_is_diverted_on_the_step_it_is_due() {
    let ini = format!("{HEADER}{}{SPLITTER_AND_BELOW}", supply("a", 2));
    let mut model = load(&ini);
    model.run().expect("simulation should run");

    let due = series(&mut model, "node.user_2.order_due");
    assert_eq!(&due[..5], &[0.0, 0.0, 1.0, 2.0, 3.0], "user travel time should be the 2-step lag");
    assert_eq!(series(&mut model, "node.splitter_1.ds_2_order_due"), due);
    assert_eq!(series(&mut model, "node.splitter_1.ds_2"), due);
    assert_eq!(series(&mut model, "node.user_2.diversion"), due);
}

/// Two supply paths of different length into one splitter. The effluent user
/// assumes the longest travel time, so the splitter must too - whichever
/// order the two branches are defined in. (Sizing the delay from each
/// incoming link in turn let the last-defined branch win.)
#[test]
fn delay_follows_the_longest_supply_path_in_either_definition_order() {
    for (first, second) in [(("a", 3), ("b", 1)), (("a", 1), ("b", 3))] {
        let ini = format!("{HEADER}{}{}{SPLITTER_AND_BELOW}",
                          supply(first.0, first.1), supply(second.0, second.1));
        let mut model = load(&ini);
        model.run().expect("simulation should run");

        let due = series(&mut model, "node.user_2.order_due");
        assert_eq!(&due[..5], &[0.0, 0.0, 0.0, 1.0, 2.0],
                   "user travel time should be the 3-step branch (lags {} then {})", first.1, second.1);
        assert_eq!(series(&mut model, "node.splitter_1.ds_2_order_due"), due,
                   "splitter delay should match the user's (lags {} then {})", first.1, second.1);
    }
}

/// Running the same model object twice gives the same effluent diversions:
/// no orders from the end of the first run leak into the start of the second.
#[test]
fn rerun_starts_with_an_empty_order_buffer() {
    let ini = format!("{HEADER}{}{SPLITTER_AND_BELOW}", supply("a", 2));
    let mut model = load(&ini);

    model.run().expect("first run");
    let first_due = series(&mut model, "node.splitter_1.ds_2_order_due");
    let first_ds_2 = series(&mut model, "node.splitter_1.ds_2");

    model.run().expect("second run");
    assert_eq!(series(&mut model, "node.splitter_1.ds_2_order_due"), first_due);
    assert_eq!(series(&mut model, "node.splitter_1.ds_2"), first_ds_2);
}

/// A storage directly above a splitter whose table sends 10% of the inflow
/// down the effluent, with constant orders on both outlets. `{ds_2_order}`
/// sets the effluent user's order; the main channel user always orders 100.
fn ten_percent_rig(ds_2_order: f64) -> String {
    format!("
[kalix]
start = 2020-01-01
end = 2020-01-05

[node.dam]
type = storage
loc = 0, 0
initial_volume = 12000
{DAM_DIMENSIONS}
ds_1 = splitter_1

[node.splitter_1]
type = splitter
loc = 0, 20
table = 0,    0,
        1000, 100,
ds_1 = user_1
ds_2 = user_2

[node.user_1]
type = regulated_user
loc = 0, 30
order = 100

[node.user_2]
type = regulated_user
loc = 10, 30
order = {ds_2_order}

[outputs]
node.splitter_1.usflow
node.splitter_1.ds_1
node.splitter_1.ds_2
node.user_1.diversion
node.user_2.diversion
")
}

fn assert_all_close(values: &[f64], expected: f64, what: &str) {
    for (i, v) in values.iter().enumerate() {
        assert!((v - expected).abs() < 1e-9, "{what}: step {i} was {v}, expected {expected}");
    }
}

/// The table takes more than the effluent ordered. The order sent upstream
/// must allow for it, as a loss node's does: 100 on ds_1 needs an inflow of
/// 100 / 0.9, not the plain sum of 105 that would leave ds_1 with 94.5.
#[test]
fn upstream_order_allows_for_the_flow_the_table_diverts() {
    let mut model = load(&ten_percent_rig(5.0));
    model.run().expect("simulation should run");

    assert_all_close(&series(&mut model, "node.splitter_1.usflow"), 100.0 / 0.9, "splitter inflow");
    assert_all_close(&series(&mut model, "node.splitter_1.ds_1"), 100.0, "main channel flow");
    assert_all_close(&series(&mut model, "node.user_1.diversion"), 100.0, "main channel diversion");
    assert_all_close(&series(&mut model, "node.user_2.diversion"), 5.0, "effluent diversion");
}

/// The effluent ordered more than the table would send it. The order then
/// displaces the table flow, nothing more is diverted than was ordered, and
/// the plain sum of the two orders is exactly enough.
#[test]
fn upstream_order_is_the_sum_when_the_effluent_order_exceeds_the_table_flow() {
    let mut model = load(&ten_percent_rig(20.0));
    model.run().expect("simulation should run");

    assert_all_close(&series(&mut model, "node.splitter_1.usflow"), 120.0, "splitter inflow");
    assert_all_close(&series(&mut model, "node.splitter_1.ds_2"), 20.0, "effluent flow");
    assert_all_close(&series(&mut model, "node.user_1.diversion"), 100.0, "main channel diversion");
    assert_all_close(&series(&mut model, "node.user_2.diversion"), 20.0, "effluent diversion");
}

/// A high-flow breakout: nothing is diverted below an inflow of 200, and half
/// of everything above it. Orders beneath the threshold pass through
/// unchanged (the ordering phase skips the table lookup there); an order
/// above it is raised through the table. 300 on ds_1 needs an inflow of 400:
/// the table takes (400 - 200) / 2 = 100 and leaves 300.
#[test]
fn breakout_table_passes_small_orders_unchanged_and_raises_large_ones() {
    for (order, expected_inflow) in [(150.0, 150.0), (200.0, 200.0), (300.0, 400.0)] {
        let ini = ten_percent_rig(0.0)
            .replace("table = 0,    0,\n        1000, 100,", "table = 0,    0,\n        200,  0,\n        1000, 400,")
            .replace("order = 100", &format!("order = {order}"));
        assert!(ini.contains("200,  0,"), "the rig's table should have been replaced");
        let mut model = load(&ini);
        model.run().expect("simulation should run");

        assert_all_close(&series(&mut model, "node.splitter_1.usflow"), expected_inflow, "splitter inflow");
        assert_all_close(&series(&mut model, "node.user_1.diversion"), order, "main channel diversion");
    }
}

/// Above an inflow of 200 this table sends every additional megalitre down
/// the effluent, so the main channel can never receive more than 200. An
/// order of 300 on ds_1 cannot be met by any inflow; ordering 300 would only
/// send 100 down the effluent for nothing. The order is held to what can be
/// delivered, as it is at a loss node - and an effluent order still adds.
#[test]
fn ds_1_order_is_held_to_what_the_table_can_pass() {
    for (ds_2_order, expected_inflow, expected_ds_2) in [(0.0, 200.0, 0.0), (50.0, 250.0, 50.0)] {
        let ini = ten_percent_rig(ds_2_order)
            .replace("table = 0,    0,\n        1000, 100,", "table = 0,    0,\n        200,  0,\n        1000, 800,")
            .replace("order = 100", "order = 300");
        assert!(ini.contains("1000, 800,"), "the rig's table should have been replaced");
        let mut model = load(&ini);
        model.run().expect("simulation should run");

        assert_all_close(&series(&mut model, "node.splitter_1.usflow"), expected_inflow, "splitter inflow");
        assert_all_close(&series(&mut model, "node.splitter_1.ds_1"), 200.0, "main channel flow");
        assert_all_close(&series(&mut model, "node.splitter_1.ds_2"), expected_ds_2, "effluent flow");
    }
}

/// The table is optional. A splitter without one diverts nothing of its own
/// accord, so the effluent receives exactly what is ordered down it - a
/// regulated offtake - and the order sent upstream is the plain sum.
#[test]
fn splitter_without_a_table_is_a_regulated_offtake() {
    let ini = ten_percent_rig(20.0).replace("table = 0,    0,\n        1000, 100,\n", "");
    assert!(!ini.contains("table ="), "the rig's table should have been removed");
    let mut model = load(&ini);
    model.run().expect("a splitter with no table should run");

    assert_all_close(&series(&mut model, "node.splitter_1.usflow"), 120.0, "splitter inflow");
    assert_all_close(&series(&mut model, "node.splitter_1.ds_2"), 20.0, "effluent flow");
    assert_all_close(&series(&mut model, "node.user_1.diversion"), 100.0, "main channel diversion");
    assert_all_close(&series(&mut model, "node.user_2.diversion"), 20.0, "effluent diversion");
}

/// With no table and no orders, everything stays in the main channel.
#[test]
fn splitter_without_a_table_or_orders_sends_everything_down_ds_1() {
    let mut model = load("
[kalix]
start = 2020-01-01
end = 2020-01-03

[node.headwater]
type = inflow
loc = 0, 0
inflow = 10
ds_1 = splitter_1

[node.splitter_1]
type = splitter
loc = 0, 10
ds_1 = main_gauge
ds_2 = effluent_gauge

[node.main_gauge]
type = gauge
loc = 0, 20

[node.effluent_gauge]
type = gauge
loc = 10, 20

[outputs]
node.splitter_1.ds_1
node.splitter_1.ds_2
");
    model.run().expect("a splitter with no table should run");
    assert_all_close(&series(&mut model, "node.splitter_1.ds_1"), 10.0, "main channel flow");
    assert_all_close(&series(&mut model, "node.splitter_1.ds_2"), 0.0, "effluent flow");
}

/// What you read is what runs, and what you wrote is what is saved: the
/// table that stands in for a missing one is the engine's working copy, and
/// a model written back after a run must not gain a table nobody wrote.
#[test]
fn saving_after_a_run_does_not_invent_a_table() {
    let ini = ten_percent_rig(20.0).replace("table = 0,    0,\n        1000, 100,\n", "");
    let mut model = load(&ini);
    model.run().expect("simulation should run");

    let saved = IniModelIO::model_to_string(&model);
    assert!(saved.contains("[node.splitter_1]"), "the splitter should be written");
    assert!(!saved.contains("table"), "no table should be written for a splitter that has none:\n{saved}");
}
