// Tests for the field's soil water balance (Phase 1): one root-zone store
// that dries by evapotranspiration, fills with rain and irrigation, and orders
// water to meet its deficit. Every term is a recorded series, and the balance
// closes to machine precision on every step.
//
// Working in mm over the field's area: 1 mm x 1 km2 = 1 ML. The rigs use
// area = 2 km2, so 1 mm is 2 ML.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

const ALL: &str = "node.paddock.depletion\nnode.paddock.orders_en_route\nnode.paddock.order\nnode.paddock.order_due\nnode.paddock.usflow\nnode.paddock.ks\nnode.paddock.kc\nnode.paddock.et\nnode.paddock.rain\nnode.paddock.rain_vol\nnode.paddock.evap\nnode.paddock.excess\nnode.paddock.supply\nnode.paddock.escape\nnode.paddock.bypass\nnode.paddock.dsflow\nnode.paddock.ds_1";

/// A supply storage above the field, `river_lag` steps of routing between,
/// and a gauge below. `{FIELD}` is the field's properties after area and capacity.
fn rig(river_lag: usize, field: &str) -> String {
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-10

[var.day]
phase = ras
n = var.day.n[-1, 0] + 1

[node.dam]
type = storage
loc = 0, 0
initial_volume = 5000
dimensions = Level [m], Volume [ML], Area [km2], Spill [ML],
             0.0      , 0.0        , 0.0       , 0.0,
             1.0      , 10000.0    , 0.1       , 0.0,
             2.0      , 20000.0    , 0.1       , 1.0E9,
ds_1_outlet = 0, 10000
ds_1 = river

[node.river]
type = routing
loc = 0, 10
lag = {river_lag}
ds_1 = paddock

[node.paddock]
type = field
loc = 0, 20
area = 2
capacity = 100
{field}
ds_1 = outlet

[node.outlet]
type = gauge
loc = 0, 30

[outputs]
{ALL}
node.dam.ds_1_order
node.outlet.usflow
"#)
}

fn run(ini: &str) -> Model {
    let mut model = IniModelIO::read_model_string(ini).expect("model should load");
    model.configure().expect("model should configure");
    model.run().expect("simulation should run");
    model
}

fn load_err(ini: &str) -> String {
    let mut model = IniModelIO::read_model_string(ini).expect("model should load");
    model.configure().map(|_| ()).and_then(|_| model.run()).err().expect("should fail").to_string()
}

fn series(model: &mut Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_series_idx(name, false)
        .unwrap_or_else(|| panic!("missing series {}", name));
    model.data_cache.series[idx].values.clone()
}

fn s(model: &mut Model, output: &str) -> Vec<f64> { series(model, &format!("node.paddock.{output}")) }

fn assert_close(a: f64, b: f64, what: &str) {
    assert!((a - b).abs() <= 1e-9 * b.abs().max(1.0), "{what}: {a} vs {b}");
}

/// rain + (supply - escape) = et + excess + change in water held, every step,
/// and at the node usflow = supply + bypass, ds_1 = bypass + excess.
fn assert_balance_closes(model: &mut Model) {
    let (dep, rain, et, excess, supply, escape, bypass, usflow, ds_1) = (
        s(model, "depletion"), s(model, "rain_vol"), s(model, "et"), s(model, "excess"),
        s(model, "supply"), s(model, "escape"), s(model, "bypass"), s(model, "usflow"), s(model, "ds_1"));
    let area = 2.0;
    for t in 1..dep.len() {
        // depletion is recorded at the end of the step, so the change in water held over step t
        // is -(dep[t] - dep[t-1]) x area
        let d_held = -(dep[t] - dep[t - 1]) * area;
        assert_close(rain[t] + supply[t] - escape[t], et[t] + excess[t] + d_held, &format!("soil balance on step {t}"));
        assert_close(usflow[t], supply[t] + bypass[t], &format!("usflow on step {t}"));
        assert_close(ds_1[t], bypass[t] + excess[t], &format!("ds_1 on step {t}"));
    }
}

#[test]
fn test_a_rain_fed_field_dries_fills_and_sheds_excess() {
    // No order, so no supply. p = 0.5, kc = 1, evap = 4 mm. Rain 30 mm on day 3 and 80 mm on day 5.
    let mut model = run(&rig(0, "evap = 4\nkc = 1\np = 0.5\ninitial_depletion = 20\nrain = if(var.day.n == 3, 30, if(var.day.n == 5, 80, 0))"));
    let dep = s(&mut model, "depletion");
    let ks = s(&mut model, "ks");
    let et = s(&mut model, "et");
    let excess = s(&mut model, "excess");
    // depletion is the value at the end of each step.
    // Day 1: opening depletion 20; ks = (100 - 20) / 50 = 1.6 -> 1; et = 4 mm = 8 ML; closes at 24
    assert_eq!(ks[0], 1.0);
    assert_eq!(et[0], 8.0);
    assert_eq!(dep[0], 24.0);
    // Day 2: 24 -> 28. Day 3: et 4 then rain 30: 28 + 4 - 30 = 2
    assert_eq!(dep[1], 28.0);
    assert_eq!(excess[2], 0.0);
    assert_eq!(dep[2], 2.0);
    // Day 4: 2 -> 6. Day 5: et 4 then rain 80: 6 + 4 - 80 = -70, so excess 70 mm = 140 ML, closes full
    assert_eq!(dep[3], 6.0);
    assert_eq!(excess[4], 140.0);
    assert_eq!(dep[4], 0.0);
    assert_eq!(s(&mut model, "ds_1")[4], 140.0, "excess leaves down ds_1");
    // A result named for a property reports the property: rain and evap in mm, rain_vol in ML
    assert_eq!(s(&mut model, "rain")[4], 80.0);
    assert_eq!(s(&mut model, "rain_vol")[4], 160.0);
    assert_eq!(s(&mut model, "evap")[4], 4.0);
    assert_eq!(s(&mut model, "supply").iter().sum::<f64>(), 0.0, "rain-fed: never irrigated");
    assert_balance_closes(&mut model);
}

#[test]
fn test_stress_reduces_evapotranspiration_as_the_soil_dries() {
    // Bone dry start, no rain: ks falls from (100 - 60)/50 = 0.8 towards 0 and et with it
    let mut model = run(&rig(0, "evap = 10\nkc = 1\np = 0.5\ninitial_depletion = 60"));
    let ks = s(&mut model, "ks");
    let et = s(&mut model, "et");
    let dep = s(&mut model, "depletion");
    assert_eq!(ks[0], 0.8);
    assert_eq!(et[0], 16.0, "0.8 x 1 x 10 mm x 2 km2");
    assert_eq!(dep[0], 68.0, "at the end of day 1");
    assert_close(ks[1], 0.64, "ks on day 2");
    assert!(ks.iter().zip(ks.iter().skip(1)).all(|(a, b)| b < a), "ks falls every day");
    assert!(dep.last().unwrap() < &100.0, "and the soil never quite empties");
    assert_balance_closes(&mut model);
}

#[test]
fn test_irrigation_fills_the_room_left_after_rain_and_bypasses_the_rest() {
    // Ordered 100 ML (50 mm) every day. Day 1: opening 40, et 2 mm -> 42, no rain: room 42 mm = 84 ML
    // taken, 16 bypassed, closes full. Day 2: et 2 -> 2, rain 10 -> 0 with 8 mm excess, room 0: all 100 bypassed.
    let mut model = run(&rig(0, "evap = 2\nkc = 1\np = 0.5\ninitial_depletion = 40\nrain = if(var.day.n == 2, 10, 0)\norder = 100"));
    assert_eq!(s(&mut model, "usflow")[0], 100.0);
    assert_eq!(s(&mut model, "supply")[0], 84.0);
    assert_eq!(s(&mut model, "bypass")[0], 16.0);
    assert_eq!(s(&mut model, "depletion")[0], 0.0, "full after irrigation");
    assert_eq!(s(&mut model, "excess")[1], 16.0, "8 mm of rain on a nearly full profile");
    assert_eq!(s(&mut model, "supply")[1], 0.0, "no room left after the rain");
    assert_eq!(s(&mut model, "bypass")[1], 100.0);
    assert_eq!(s(&mut model, "ds_1")[1], 116.0);
    assert_balance_closes(&mut model);
}

#[test]
fn test_escape_leaves_the_model_and_the_take_allows_for_it() {
    // efficiency 0.8: room 40 mm = 80 ML at the soil needs 100 ML at the pump, of which 20 escapes
    let mut model = run(&rig(0, "evap = 0\nkc = 1\ninitial_depletion = 40\nefficiency = 0.8\norder = 150"));
    assert_eq!(s(&mut model, "supply")[0], 100.0);
    assert_close(s(&mut model, "escape")[0], 20.0, "escape");
    assert_eq!(s(&mut model, "bypass")[0], 50.0);
    assert_eq!(s(&mut model, "depletion")[0], 0.0);
    assert_balance_closes(&mut model);
    let mbal: std::collections::HashMap<String, f64> = model.get_mass_balance_data().into_iter()
        .map(|(name, _, v)| (name, v)).collect();
    // Over 10 steps: 80 ML into the soil and 20 ML of escape leave the model at the field,
    // and the rest of what the dam released passes the outlet gauge
    assert_close(mbal["paddock"] * 10.0, -100.0, "the field's mass balance");
    let passed_outlet: f64 = series(&mut model, "node.outlet.usflow").iter().sum();
    assert_close(mbal.values().sum::<f64>() * 10.0, passed_outlet, "the model balances");
}

#[test]
fn test_the_default_rule_and_what_it_reads() {
    // The IDE template's rule, with a target of 20 mm: top the soil up to 20 mm depletion, at
    // most 120 mm a day, grossed up for escape, less what is already on its way. No travel time.
    // The soil starts at 45 mm, inside the readily available water (p x capacity = 50), so
    // ks = 1 and it dries 2 mm a day. The rule reads yesterday's closing depletion: on day 1
    // there is none and it reads the fallback 0, so nothing is ordered and the soil closes at
    // 47. Day 2 reads 47 and orders 2 km2 x 27 mm / 0.8 = 67.5 ML, of which 54 reach the soil:
    // 47 + 2 - 27 = 22. From then on it orders the day's drying, 2 x 2 / 0.8 = 5.
    let mut model = run(&rig(0, "evap = 2\nkc = 1\ninitial_depletion = 45\nefficiency = 0.8\norder = this.area * clamp(this.depletion[-1, 0] - 20, 0, 120) / this.efficiency - this.orders_en_route[-1, 0]"));
    assert_eq!(s(&mut model, "order")[..4], [0.0, 67.5, 5.0, 5.0]);
    assert_eq!(s(&mut model, "depletion")[..4], [47.0, 22.0, 22.0, 22.0], "held at the target from day 2");
    assert_eq!(s(&mut model, "orders_en_route")[1], 0.0, "no travel time: an order arrives the day it is placed, nothing is ever en route");
    assert_balance_closes(&mut model);
}

#[test]
fn test_orders_en_route_stop_the_deficit_being_ordered_again_while_water_travels() {
    // 3 steps of travel time, drying 1 mm a day. Day 1 reads the fallback and orders nothing.
    // Day 2 reads 46 and orders 52. Without the en-route term days 3 and 4 would order the
    // whole deficit again; with it they order the day's drying only, and the 52 arrives on
    // day 5.
    let mut model = run(&rig(3, "evap = 1\nkc = 1\ninitial_depletion = 45\norder = this.area * clamp(this.depletion[-1, 0] - 20, 0, 120) - this.orders_en_route[-1, 0]"));
    assert_eq!(s(&mut model, "order")[..5], [0.0, 52.0, 2.0, 2.0, 2.0]);
    // On its way at the end of each day: today's order included, the one arriving today not
    assert_eq!(s(&mut model, "orders_en_route")[..5], [0.0, 52.0, 54.0, 56.0, 6.0]);
    assert_eq!(s(&mut model, "order_due")[..5], [0.0, 0.0, 0.0, 0.0, 52.0]);
    assert_eq!(s(&mut model, "usflow")[4], 52.0, "the first order arrives on day 5");
    assert_eq!(s(&mut model, "depletion")[4], 24.0, "49 at the start of day 5, + 1 of et, - 26 delivered");
    assert_balance_closes(&mut model);
}

#[test]
fn test_depletion_is_the_end_of_step_state() {
    // depletion is reported at the end of the step, as a storage's volume is, so a rule reads
    // yesterday's value with an offset: today's does not exist yet when the order is placed.
    let mut model = run(&rig(0, "evap = 5\nkc = 1\ninitial_depletion = 10\norder = this.depletion[-1, 0]"));
    assert_eq!(s(&mut model, "order")[0], 0.0, "nothing to read on the first step");
    assert_eq!(s(&mut model, "order")[1], s(&mut model, "depletion")[0]);
    let err = load_err(&rig(0, "evap = 5\nkc = 1\norder = this.depletion"));
    assert!(err.contains("no value yet") && err.contains("this.depletion[-1, 0.0]") || err.contains("[-1, 0.0]"), "got: {err}");
}

#[test]
fn test_field_validation() {
    assert!(load_err(&rig(0, "capacity = 0")).contains("capacity must be a positive number"));
    assert!(load_err(&rig(0, "efficiency = 0")).contains("efficiency must be greater than 0"));
    assert!(load_err(&rig(0, "efficiency = 1.2")).contains("efficiency must be greater than 0 and at most 1"));
    assert!(load_err(&rig(0, "p = 1")).contains("p must be at least 0 and less than 1"));
    assert!(load_err(&rig(0, "initial_depletion = 150")).contains("initial_depletion must be between 0 and capacity"));
    let no_area = rig(0, "").replace("area = 2\n", "");
    assert!(load_err(&no_area).contains("area must be a positive number"));
}

#[test]
fn test_field_round_trips_every_property() {
    let ini = rig(0, "evap = 4\nrain = 1\nkc = 0.9\np = 0.6\ninitial_depletion = 20\nefficiency = 0.8\norder = 5");
    let model = IniModelIO::read_model_string(&ini).expect("model should load");
    let rendered = IniModelIO::model_to_string(&model);
    for line in ["area = 2", "capacity = 100", "evap = 4", "rain = 1", "kc = 0.9", "p = 0.6", "initial_depletion = 20", "efficiency = 0.8", "order = 5"] {
        assert!(rendered.contains(line), "'{line}' survives save:\n{rendered}");
    }
    let reloaded = IniModelIO::read_model_string(&rendered).expect("canonical render should re-load");
    assert_eq!(IniModelIO::model_to_string(&reloaded), rendered);
    // Defaults are not written
    let plain = IniModelIO::model_to_string(&IniModelIO::read_model_string(&rig(0, "")).unwrap());
    for key in ["p =", "efficiency =", "initial_depletion ="] {
        assert!(!plain.contains(key), "default '{key}' is not written:\n{plain}");
    }
}
