// A storage that orders upstream - to meet a target level, or by passing
// downstream orders through - reports the order it places (`order`) and, for
// a target level, what is on its way at the end of the step
// (`orders_en_route`: today's order included, the order arriving today not),
// the same two states a field reports.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;
use crate::model_inputs::DynamicInput;
use crate::nodes::NodeEnum;

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
node.weir.target_level
node.weir.target_volume
node.weir.forecast_volume
node.weir.forecast_adjustment
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

fn weir_target_level(model: &Model) -> &DynamicInput {
    model.nodes.iter().find_map(|n| match n {
        NodeEnum::StorageNode(s) if s.name == "weir" => Some(&s.target_level),
        _ => None,
    }).expect("weir storage")
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
fn test_constant_target_level_matches_the_same_level_evaluated_each_step() {
    // A constant level is converted to a volume once at initialise; an expression is
    // converted every step. 1.7 m falls between table rows, so this exercises the
    // interpolation, and the two must agree to the bit.
    let mut fixed = run(&rig(3, "target_level = 1.7"));
    let mut per_step = run(&rig(3, "target_level = if(sim.step >= 0, 1.7, 0)"));
    assert!(matches!(weir_target_level(&fixed), DynamicInput::Constant { .. }));
    assert!(!matches!(weir_target_level(&per_step),
        DynamicInput::Constant { .. } | DynamicInput::DirectConstantReference { .. }),
        "the expression must not fold to a constant, or both runs take the same path");
    for name in ["node.weir.order", "node.weir.orders_en_route", "node.weir.volume", "node.weir.target_level"] {
        assert_eq!(series(&mut fixed, name), series(&mut per_step, name), "{}", name);
    }
    assert!(series(&mut fixed, "node.weir.target_level").iter().all(|&v| v == 1.7));
}

#[test]
fn test_target_level_from_a_constant_follows_the_constant_between_runs() {
    // A constant reference is converted once per run, so a constant changed between runs
    // (as the optimiser does) must be picked up. 1.5 m orders 1500 - 990 = 510 on day 1;
    // 2.0 m orders 2000 - 990 = 1010.
    let ini = format!("{}\n[const]\nconst.target = 1.5\n", rig(3, "target_level = const.target"));
    let mut model = run(&ini);
    assert!(matches!(weir_target_level(&model), DynamicInput::DirectConstantReference { .. }));
    assert_eq!(series(&mut model, "node.weir.order")[0], 510.0);
    model.data_cache.constants.set_value("const.target", 2.0);
    model.run().expect("second run should succeed");
    assert_eq!(series(&mut model, "node.weir.order")[0], 1010.0);
    assert_eq!(series(&mut model, "node.weir.target_level")[0], 2.0);
}

#[test]
fn test_target_volume_orders_as_the_target_level_it_corresponds_to() {
    // 1.7 m is 1700 ML on the weir's table, so both storages order and fill identically.
    let mut by_level = run(&rig(3, "target_level = 1.7"));
    let mut by_volume = run(&rig(3, "target_volume = 1700"));
    for name in ["node.weir.order", "node.weir.orders_en_route", "node.weir.volume"] {
        assert_eq!(series(&mut by_level, name), series(&mut by_volume, name), "{}", name);
    }
    assert_eq!(series(&mut by_volume, "node.weir.order")[..2], [710.0, 10.0]);
}

#[test]
fn test_target_level_and_target_volume_are_both_recorded_whichever_is_given() {
    // The input is recorded as given and the other is derived from the dimension table.
    for (property, given, derived) in [
        ("target_level = 1.7", "node.weir.target_level", "node.weir.target_volume"),
        ("target_volume = 1700", "node.weir.target_volume", "node.weir.target_level"),
    ] {
        let mut model = run(&rig(3, property));
        let (g, d) = (series(&mut model, given), series(&mut model, derived));
        let (expect_g, expect_d) = if given.ends_with("level") { (1.7, 1700.0) } else { (1700.0, 1.7) };
        assert!(g.iter().all(|&v| v == expect_g), "{}: {} {:?}", property, given, g);
        assert!(d.iter().all(|&v| (v - expect_d).abs() < 1e-9), "{}: {} {:?}", property, derived, d);
    }
}

#[test]
fn test_target_level_and_target_volume_together_are_refused() {
    let mut model = IniModelIO::read_model_string(&rig(3, "target_level = 1.5\ntarget_volume = 1500"))
        .expect("model should load");
    let err = model.configure().expect_err("both targets must be refused");
    assert!(err.contains("'weir'") && err.contains("target_volume"), "got: {}", err);
}

#[test]
fn test_forecast_volume_is_what_each_target_order_is_based_on() {
    // Day 1: 1000 now, nothing en route, 10 released today: 990, so 510 is ordered. Day 2:
    // 990 now plus the 510 en route, less 10: 1490, so 10 is ordered.
    let mut model = run(&rig(3, "target_level = 1.5"));
    let forecast = series(&mut model, "node.weir.forecast_volume");
    let order = series(&mut model, "node.weir.order");
    let target = series(&mut model, "node.weir.target_volume");
    assert_eq!(forecast[..2], [990.0, 1490.0]);
    for i in 0..order.len() {
        assert_eq!(order[i], (target[i] - forecast[i]).max(0.0), "step {}", i);
    }
}

#[test]
fn test_forecast_adjustment_adds_to_the_forecast() {
    // Day 1 forecast without adjustment is 990 against a 1500 target. Expected inflow
    // lowers the order, expected outflow raises it, and enough inflow orders nothing.
    for (adjustment, order) in [("200", 310.0), ("-200", 710.0), ("600", 0.0)] {
        let mut model = run(&rig(3, &format!("target_volume = 1500\nforecast_adjustment = {}", adjustment)));
        let a: f64 = adjustment.parse().unwrap();
        assert_eq!(series(&mut model, "node.weir.order")[0], order, "adjustment {}", adjustment);
        assert_eq!(series(&mut model, "node.weir.forecast_volume")[0], 990.0 + a);
        assert_eq!(series(&mut model, "node.weir.forecast_adjustment")[0], a);
    }
}

#[test]
fn test_forecast_adjustment_applies_to_a_level_target_alike() {
    let mut by_level = run(&rig(3, "target_level = 1.5\nforecast_adjustment = 200"));
    let mut by_volume = run(&rig(3, "target_volume = 1500\nforecast_adjustment = 200"));
    for name in ["node.weir.order", "node.weir.forecast_volume", "node.weir.volume"] {
        assert_eq!(series(&mut by_level, name), series(&mut by_volume, name), "{}", name);
    }
}

#[test]
fn test_forecast_adjustment_is_not_a_critical_input() {
    // A forecast series may be shorter than the driving data and must not set the run period.
    let model = IniModelIO::read_model_string(
        &rig(3, "target_volume = 1500\nforecast_adjustment = data.fc_csv.by_name.inflow"))
        .expect("model should load");
    let critical = model.data_cache.get_critical_input_names();
    assert!(!critical.contains(&"data.fc_csv.by_name.inflow"), "got {:?}", critical);
}

#[test]
fn test_forecast_adjustment_that_changes_is_applied_on_the_step_it_is_evaluated() {
    // Each step's forecast is the volume and orders en route left by the step before, less
    // the 10 released today, plus that step's adjustment.
    let mut model = run(&rig(3, "target_volume = 1500\nforecast_adjustment = if(sim.step >= 2, 200, 0)"));
    let adjustment = series(&mut model, "node.weir.forecast_adjustment");
    let forecast = series(&mut model, "node.weir.forecast_volume");
    let order = series(&mut model, "node.weir.order");
    let volume = series(&mut model, "node.weir.volume");
    let en_route = series(&mut model, "node.weir.orders_en_route");
    assert_eq!((adjustment[0], adjustment[adjustment.len() - 1]), (0.0, 200.0));
    for i in 0..order.len() {
        let (v, e) = if i == 0 { (1000.0, 0.0) } else { (volume[i - 1], en_route[i - 1]) };
        assert_eq!(forecast[i], v + e - 10.0 + adjustment[i], "forecast, step {}", i);
        assert_eq!(order[i], (1500.0 - forecast[i]).max(0.0), "order, step {}", i);
    }
}

#[test]
fn test_forecast_adjustment_without_a_target_is_refused() {
    let mut model = IniModelIO::read_model_string(&rig(3, "forecast_adjustment = 200"))
        .expect("model should load");
    let err = model.configure().expect_err("an adjustment with nothing to adjust must be refused");
    assert!(err.contains("'weir'") && err.contains("forecast_adjustment"), "got: {}", err);
}

#[test]
fn test_order_through_overrides_a_target_volume() {
    let mut model = run(&rig(2, "order_through = true\ntarget_volume = 1700\nforecast_adjustment = 200"));
    assert_eq!(series(&mut model, "node.weir.order")[0], 10.0, "orders pass through unchanged");
    for name in ["node.weir.target_level", "node.weir.target_volume", "node.weir.forecast_volume",
                 "node.weir.forecast_adjustment"] {
        let s = series(&mut model, name);
        assert!(s.is_empty() || s.iter().all(|v| v.is_nan()), "{} got {:?}", name, s);
    }
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
