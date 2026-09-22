// A storage that orders upstream - to meet a target level, or by passing
// downstream orders through - reports the order it places (`order`) and, for
// a target level, what is on its way at the end of the step
// (`orders_en_route`: today's order included, the order arriving today not),
// the same two states a field reports.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

/// A supply dam, `lag` steps of routing, then a weir with `{WEIR}` properties
/// and a regulated user below it ordering 10.
fn rig(lag: usize, weir: &str) -> String {
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-10

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
lag = {lag}
ds_1 = weir

[node.weir]
type = storage
loc = 0, 20
initial_volume = 1000
dimensions = 0, 0, 0, 0,
             1, 1000, 0.1, 0,
             3, 3000, 0.1, 0,
             4, 4000, 0.1, 1e9,
ds_1_outlet = 0, 10000
{weir}
ds_1 = user

[node.user]
type = regulated_user
loc = 0, 30
order = 10

[outputs]
node.weir.order
node.weir.orders_en_route
node.dam.ds_1_order
node.weir.volume
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
fn test_target_level_storage_reports_its_order_and_what_is_en_route() {
    // Held at 1.5 m (1500 ML) from 1000 ML, 3 steps below its supply, releasing 10 a day.
    // Day 1 orders the whole gap plus the day's release: 1500 - (1000 - 10) = 510. On days 2
    // and 3 that 510 is en route and covers the gap, so only the day's 10 is ordered. Day 4
    // the 510 arrives: it leaves the en-route figure and lands in the weir (970 + 510 - 10).
    let mut model = run(&rig(3, "target_level = 1.5"));
    let order = series(&mut model, "node.weir.order");
    let en_route = series(&mut model, "node.weir.orders_en_route");
    assert_eq!(order[..4], [510.0, 10.0, 10.0, 10.0]);
    assert_eq!(en_route[..4], [510.0, 520.0, 530.0, 30.0], "today's order in, today's arrival out");
    assert_eq!(series(&mut model, "node.dam.ds_1_order")[0], 510.0, "and that is what the dam sees");
    assert_eq!(series(&mut model, "node.weir.volume")[3], 1470.0);
}

#[test]
fn test_order_through_storage_reports_the_order_it_passes_on() {
    let mut model = run(&rig(2, "order_through = true"));
    assert_eq!(series(&mut model, "node.weir.order")[0], 10.0);
    assert_eq!(series(&mut model, "node.dam.ds_1_order")[0], 10.0);
    assert_eq!(series(&mut model, "node.weir.orders_en_route")[0], 0.0, "nothing is en route to a storage that is not the supply");
}

#[test]
fn test_a_plain_supply_storage_records_no_order() {
    // Neither target_level nor order_through: the storage orders nothing upstream and the
    // series are not written, as for any node that does not take part in ordering.
    let mut model = run(&rig(2, ""));
    let order = series(&mut model, "node.weir.order");
    assert!(order.is_empty() || order.iter().all(|v| v.is_nan()), "got {:?}", order);
}
