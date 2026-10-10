// Tests for the field's crops (Phase 3): crop slots planted from the fallow
// and harvested back to it, each ordering for its own deficit, over a soil
// kept in layers so that water below one crop's roots is there for the next.
//
// Working in mm over each partition's area: 1 mm x 1 km2 = 1 ML. The field is
// 4 km2 of 100 mm/m soil.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

/// A dam supplies the field with no travel time. Two crops: shallow roots to
/// 500 mm (50 mm bucket) and deep to 1000 mm (100 mm bucket); the fallow is
/// bare ground rooted with the shallow crop. `{crops}` are the crop sections'
/// extra lines, `{field}` the field's properties after the soil.
fn rig(crops: &str, field: &str) -> String {
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-12

[crop.bare]
root_depth = 500
kc = 0

[crop.shallow]
root_depth = 500
kc = 1

[crop.deep]
root_depth = 1000
kc = 1
{crops}

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
ds_1 = paddock

[node.paddock]
type = field
loc = 0, 20
area = 4
available_water = 100
interception = 0
fallow = bare
{field}
ds_1 = outlet

[node.outlet]
type = gauge
loc = 0, 30

[outputs]
node.paddock.crop_1_area
node.paddock.crop_1_days
node.paddock.crop_1_depletion
node.paddock.crop_1_ks
node.paddock.crop_1_order
node.paddock.crop_1_order_due
node.paddock.crop_1_orders_en_route
node.paddock.crop_2_area
node.paddock.crop_2_days
node.paddock.crop_2_depletion
node.paddock.crop_2_ks
node.paddock.crop_2_order
node.paddock.crop_1_plant
node.paddock.crop_1_viable_area
node.paddock.crop_1_kc_multiplier
node.paddock.crop_1_kc
node.paddock.crop_2_plant
node.paddock.fallow_depletion
node.paddock.soil_moisture
node.paddock.volume
node.paddock.usflow
node.paddock.et
node.paddock.et_vol
node.paddock.rain_vol
node.paddock.intercepted
node.paddock.excess
node.paddock.supply
node.paddock.escape
node.paddock.bypass
node.paddock.ds_1
node.dam.ds_1_order
"#)
}

fn run(ini: &str) -> Model {
    let mut model = IniModelIO::read_model_string(ini).expect("model should load");
    model.configure().expect("model should configure");
    model.run().expect("simulation should run");
    model
}

fn s(model: &mut Model, output: &str) -> Vec<f64> {
    let name = format!("node.paddock.{output}");
    let idx = model.data_cache.get_series_idx(&name, false).unwrap_or_else(|| panic!("missing series {name}"));
    model.data_cache.series[idx].values.clone()
}

fn assert_close(a: f64, b: f64, what: &str) {
    assert!((a - b).abs() <= 1e-9 * b.abs().max(1.0), "{what}: {a} vs {b}");
}

/// The field's water balance, every step, from its results alone: the change in the
/// water the profile holds is what came in less what went out. volume counts
/// every bucket and every layer, so this holds through planting, harvest, abandonment,
/// overflow into the layers, storm runoff and the pour.
fn field_balance_closes(model: &mut Model) {
    let n = s(model, "usflow").len();
    let area = 4.0;
    for t in 1..n {
        let d_held = s(model, "volume")[t] - s(model, "volume")[t - 1];
        let inflow = s(model, "rain_vol")[t] - s(model, "intercepted")[t] * area + s(model, "supply")[t] - s(model, "escape")[t];
        let outflow = s(model, "et_vol")[t] + s(model, "excess")[t];
        assert_close(inflow - outflow, d_held, &format!("water balance on step {t}"));
        assert_close(s(model, "usflow")[t], s(model, "supply")[t] + s(model, "bypass")[t], &format!("usflow on step {t}"));
        assert_close(s(model, "soil_moisture")[t] * area, s(model, "volume")[t], &format!("soil_moisture is volume over the field on step {t}"));
    }
}

#[test]
fn test_planting_takes_area_from_the_fallow_with_its_water_and_harvest_gives_it_back() {
    // The whole profile starts 20 mm below full (over 1 m: 10 in the top 500 mm, 10 below;
    // the deep crop in slot 2, never planted, is what gives the profile its second layer).
    // Day 3: 1 km2 of the shallow crop is planted; the fallow is bare so nothing has
    // dried, and the crop's bucket is 10 mm down, like the fallow's. It dries 4 mm a day
    // for 3 days (days 0, 1, 2 since planting) and is harvested on day 6 as days reaches 3.
    let ini = rig("", "initial_depletion = 20\nevap = 4\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 3, 1, 0)\ncrop_2 = deep\ncrop_2_plant = 0")
        .replace("[crop.shallow]\nroot_depth = 500\nkc = 1\n", "[crop.shallow]\nroot_depth = 500\nkc = 1\nseason_len = 3\n");
    let mut model = run(&ini);
    let area = s(&mut model, "crop_1_area");
    let days = s(&mut model, "crop_1_days");
    let dep = s(&mut model, "crop_1_depletion");
    assert_eq!(area[..7], [0.0, 0.0, 1.0, 1.0, 1.0, 0.0, 0.0]);
    assert!(days[1].is_nan() && dep[1].is_nan() && s(&mut model, "crop_1_ks")[1].is_nan(), "no state before planting");
    assert_eq!(days[2..5], [0.0, 1.0, 2.0]);
    assert_eq!(dep[2..5], [14.0, 18.0, 22.0], "10 mm down at planting, then 4 mm a day");
    assert!(days[5].is_nan(), "harvested at the start of day 6");
    assert_eq!(s(&mut model, "et_vol")[2..6], [4.0, 4.0, 4.0, 0.0], "1 km2 x 4 mm while the crop stands");
    // The fallow's bucket: 10 mm down throughout, until the harvest brings back 1 km2 at 22 mm
    // down in the top 500 mm mixed with 3 km2 at 10: 13 mm
    let fallow = s(&mut model, "fallow_depletion");
    assert_eq!(fallow[..5], [10.0; 5]);
    assert_eq!(fallow[5], 13.0);
    field_balance_closes(&mut model);
}

#[test]
fn test_each_crop_orders_for_its_own_deficit_and_takes_only_its_own_order() {
    // Both crops planted on day 1, 1 km2 each, over a full profile, evap 5 mm. Each orders
    // yesterday's closing deficit. Day 1: nothing to read, no orders; each closes 5 down.
    // Day 2: each orders 5 ML; each takes its own 5 with 0.5 mm... no: 5 ML over 1 km2 is
    // 5 mm, refilling exactly; then dries 5 again. The dam sees the sum.
    let mut model = run(&rig("", "evap = 5\ncrop_1 = shallow\ncrop_1_plant = 1\ncrop_1_order = this.crop_1_area[-1, 0] * this.crop_1_depletion[-1, 0]\ncrop_2 = deep\ncrop_2_plant = 1\ncrop_2_order = this.crop_2_area[-1, 0] * this.crop_2_depletion[-1, 0]"));
    assert_eq!(s(&mut model, "crop_1_order")[..3], [0.0, 5.0, 5.0]);
    assert_eq!(s(&mut model, "crop_2_order")[..3], [0.0, 5.0, 5.0]);
    let dam_order = {
        let idx = model.data_cache.get_series_idx("node.dam.ds_1_order", false).unwrap();
        model.data_cache.series[idx].values.clone()
    };
    assert_eq!(dam_order[..3], [0.0, 10.0, 10.0], "the field places the sum");
    assert_eq!(s(&mut model, "usflow")[1], 10.0);
    assert_eq!(s(&mut model, "supply")[1], 10.0);
    assert_eq!(s(&mut model, "crop_1_depletion")[..3], [5.0, 5.0, 5.0], "refilled then dried again, every day");
    assert_eq!(s(&mut model, "crop_2_depletion")[..3], [5.0, 5.0, 5.0]);
    assert_eq!(s(&mut model, "bypass")[1], 0.0);
    // The fallow, 2 km2 of bare ground, neither dries nor is watered
    assert_eq!(s(&mut model, "fallow_depletion")[5], 0.0);
}

#[test]
fn test_water_beyond_the_orders_is_poured_over_the_driest_crop_first() {
    // Two crops of 1 km2, both drying 5 mm a day; crop_2 never orders, crop_1 orders 30 from
    // day 2. Day 2: both open 5 down and dry to 10 before the water is applied. crop_1 takes
    // min(30, its room of 10) = 10. The other 20 is a forced watering: crop_2, now the drier,
    // gets 10 to level the two at 0, and the last 10, with no room anywhere, is bypass.
    let mut model = run(&rig("", "evap = 5\ncrop_1 = shallow\ncrop_1_plant = 1\ncrop_1_order = if(var.day.n >= 2, 30, 0)\ncrop_2 = deep\ncrop_2_plant = 1"));
    assert_eq!(s(&mut model, "usflow")[1], 30.0);
    assert_eq!(s(&mut model, "supply")[1], 20.0);
    assert_eq!(s(&mut model, "bypass")[1], 10.0);
    assert_eq!(s(&mut model, "crop_1_depletion")[1], 0.0, "watered to full");
    assert_eq!(s(&mut model, "crop_2_depletion")[1], 0.0, "the same, from the pour");
    // With unequal deficits the pour levels from the driest down. 20 mm down over the metre
    // at the start: 10 in the shallow crop's bucket, 20 in the deep one's. Day 1 closes at
    // 15 and 25; day 2 dries them to 20 and 30, crop_1 takes its 20, and the other 10 brings
    // crop_2 from 30 to 20: nothing bypasses.
    let mut model = run(&rig("", "initial_depletion = 20\nevap = 5\ncrop_1 = shallow\ncrop_1_plant = 1\ncrop_1_order = if(var.day.n >= 2, 30, 0)\ncrop_2 = deep\ncrop_2_plant = 1"));
    assert_eq!(s(&mut model, "crop_1_depletion")[..2], [15.0, 0.0]);
    assert_eq!(s(&mut model, "crop_2_depletion")[..2], [25.0, 20.0]);
    assert_eq!(s(&mut model, "supply")[1], 30.0);
    assert_eq!(s(&mut model, "bypass")[1], 0.0);
}

#[test]
fn test_the_fallow_is_never_irrigated_and_forced_water_with_no_crop_is_bypass() {
    // Slots that never plant: all fallow, over a profile of two layers (the deep crop's
    // roots give it the second). A field with no slot in the ground places no order and
    // takes nothing; the fallow is never irrigated.
    let mut model = run(&rig("", "initial_depletion = 20\nevap = 5\ncrop_1 = shallow\ncrop_1_plant = 0\ncrop_1_order = 10\ncrop_2 = deep\ncrop_2_plant = 0"));
    assert_eq!(s(&mut model, "crop_1_order")[3], 0.0, "not in the ground, not evaluated");
    assert_eq!(s(&mut model, "usflow")[3], 0.0);
    assert_eq!(s(&mut model, "fallow_depletion")[3], 10.0, "bare ground with kc 0 never dries");
    // Rain on the fallow overflows into the layer below before it leaves as excess: the top
    // 500 mm is 10 down and the layer below 10 down; 15 mm fills the top and 5 goes below
    let mut model = run(&rig("", "initial_depletion = 20\nrain = if(var.day.n == 2, 15, if(var.day.n == 3, 30, 0))\ncrop_1 = shallow\ncrop_1_plant = 0\ncrop_2 = deep\ncrop_2_plant = 0"));
    assert_eq!(s(&mut model, "fallow_depletion")[1], 0.0);
    assert_eq!(s(&mut model, "excess")[1], 0.0, "5 mm went to the layer below");
    assert_eq!(s(&mut model, "excess")[2], (30.0 - 5.0) * 4.0, "day 3: 5 more fills the layer, 25 mm over 4 km2 leaves");
}

#[test]
fn test_water_below_one_crops_roots_is_there_for_the_next() {
    // A deep crop is planted over the whole field on day 1 into a full profile and dries it
    // 10 mm a day for 4 days: 40 down over the metre, which its harvest on day 5 spreads: 20
    // in the top 500 mm, 20 below. The fallow (rooted to 500) then holds 20 down on top, and
    // the layer below remembers its 20. Day 7 a shallow crop is planted into that: it starts
    // 20 down and dries to 30. Day 8 it opens past p x 50 = 25, so ks = 20 / 25 = 0.8 and it
    // dries 8 to 38; 50 mm of rain fills it with 12 to spare, which the layer below takes
    // (20 down to 8): no excess. Day 9, 60 mm: the bucket takes 10, the layer its last 8,
    // and 42 mm over 4 km2 leaves. Without the layer's memory the whole 12 would have left
    // on day 8.
    let ini = rig("", "evap = 10\ncrop_1 = deep\ncrop_1_plant = if(var.day.n == 1, 4, 0)\ncrop_2 = shallow\ncrop_2_plant = if(var.day.n == 7, 4, 0)\nrain = if(var.day.n == 8, 50, if(var.day.n == 9, 60, 0))")
        .replace("[crop.deep]\nroot_depth = 1000\nkc = 1\n", "[crop.deep]\nroot_depth = 1000\nkc = 1\nseason_len = 4\n");
    let mut model = run(&ini);
    assert_eq!(s(&mut model, "crop_1_depletion")[..4], [10.0, 20.0, 30.0, 40.0]);
    assert!(s(&mut model, "crop_1_days")[4].is_nan(), "harvested on day 5");
    assert_eq!(s(&mut model, "fallow_depletion")[4], 20.0, "the top half of 40 over the metre");
    assert_eq!(s(&mut model, "crop_2_depletion")[6], 30.0, "planted 20 down on day 7 and dried 10");
    assert_eq!(s(&mut model, "crop_2_ks")[7], 0.8);
    assert_eq!(s(&mut model, "crop_2_depletion")[7], 0.0);
    assert_eq!(s(&mut model, "excess")[7], 0.0, "the 12 to spare went to the layer below");
    assert_eq!(s(&mut model, "crop_2_depletion")[8], 0.0);
    assert_eq!(s(&mut model, "excess")[8], 168.0, "42 mm over 4 km2 once the layer is full");
    // The profile's water shows the layer the buckets hide: at the end of day 5, after the
    // harvest, the fallow's bucket is 20 down and the layer below 20, over the whole field:
    // 100 mm of capacity less 40, over 4 km2
    assert_eq!(s(&mut model, "soil_moisture")[4], 60.0);
    assert_eq!(s(&mut model, "volume")[4], 240.0);
    field_balance_closes(&mut model);
}

#[test]
fn test_a_stressed_crop_dies_by_the_built_in_rule_and_a_written_rule_replaces_it() {
    // p = 0.5 so ks = (cap - dep) / (0.5 cap); the built-in rule kills a crop whose ks opens
    // at 0.05 or below: for the shallow crop (50 mm) that is 48.75 mm down or more. Bone dry
    // start: the profile is the 500 mm the shallow crop and the fallow root to, 50 mm down.
    // ks opens at 0, the crop is planted on day 1 and dies on day 2 at the start of the day,
    // its area back to the fallow. (A trigger that is always true would plant it again the
    // same morning: the rule plants whenever no crop is in the ground.)
    let mut model = run(&rig("", "initial_depletion = 50\nevap = 5\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 1, 1, 0)"));
    let area = s(&mut model, "crop_1_area");
    assert_eq!(area[..3], [1.0, 0.0, 0.0], "planted on day 1, dead on day 2");
    assert_eq!(s(&mut model, "crop_1_ks")[0], 0.0);
    // A written rule replaces the built-in one entirely: this crop keeps 0.5 km2 whatever
    // its stress, and never dies
    let mut model = run(&rig("", "initial_depletion = 50\nevap = 5\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 1, 1, 0)\ncrop_1_viable_area = 0.5"));
    let area = s(&mut model, "crop_1_area");
    assert_eq!(area[..3], [1.0, 0.5, 0.5], "half the area abandoned on day 2, the rest stays");
    assert_eq!(s(&mut model, "crop_1_ks")[5], 0.0, "stressed but alive");
}

#[test]
fn test_field_with_crops_round_trips() {
    let ini = rig("season_len = 120", "evap = 5\ncrop_1 = shallow\ncrop_1_plant = if(sim.month == 10, 1.5, 0)\ncrop_1_order = 10\ncrop_1_viable_area = 2\ncrop_2 = deep\ncrop_2_plant = 0");
    let model = IniModelIO::read_model_string(&ini).expect("model should load");
    let rendered = IniModelIO::model_to_string(&model);
    for line in ["fallow = bare", "crop_1 = shallow", "crop_1_plant = if(sim.month == 10, 1.5, 0)", "crop_1_order = 10", "crop_1_viable_area = 2", "crop_2 = deep", "crop_2_plant = 0", "season_len = 120"] {
        assert!(rendered.contains(line), "'{line}' survives save:\n{rendered}");
    }
    assert!(!rendered.contains("crop_2_order ="), "an unset rule is not written");
    let reloaded = IniModelIO::read_model_string(&rendered).expect("canonical render should re-load");
    assert_eq!(IniModelIO::model_to_string(&reloaded), rendered);
}


#[test]
fn test_a_curve_number_sheds_storm_rain_by_the_paddocks_wetness() {
    // All fallow (no slots), 4 km2 rooted to 500 mm at 100 mm/m: a 50 mm bucket. CN2 = 85, so
    // the dry curve is 85 / (2.334 − 0.01334 × 85) = 70.83 and the wet one
    // 85 / (0.4036 + 0.005964 × 85) = 93.35. 50 mm of rain on day 2.
    let dry = "initial_depletion = 50\nrain = if(var.day.n == 2, 50, 0)\ncurve_number = 85";
    let mut model = run(&rig("", dry));
    // Bone dry: wetness 0, CN 70.83, S = 254 (100/70.83 − 1) = 104.6, initial abstraction 20.9,
    // runoff (50 − 20.9)² / (50 + 83.7) = 6.32 mm. The other 43.68 mm go into the 50 mm room.
    assert_close(s(&mut model, "excess")[1], 6.323638637769316 * 4.0, "storm runoff from a dry paddock, ML");
    assert_close(s(&mut model, "fallow_depletion")[1], 50.0 - (50.0 - 6.323638637769316), "the rest infiltrated");
    // Full: wetness 1, CN 93.35, S = 18.1, runoff 33.37 mm; the rest overflows a full profile,
    // so all 50 mm leave
    let wet = "rain = if(var.day.n == 2, 50, 0)\ncurve_number = 85";
    let mut model = run(&rig("", wet));
    assert_close(s(&mut model, "excess")[1], 50.0 * 4.0, "runoff and overflow together");
    assert_close(s(&mut model, "fallow_depletion")[1], 0.0, "still full");
    // Below the initial abstraction nothing runs off: 10 mm on the dry paddock all infiltrates
    let small = "initial_depletion = 50\nrain = if(var.day.n == 2, 10, 0)\ncurve_number = 85";
    let mut model = run(&rig("", small));
    assert_eq!(s(&mut model, "excess")[1], 0.0);
    assert_close(s(&mut model, "fallow_depletion")[1], 40.0, "all 10 mm in");
    // Without a curve number the same dry paddock takes all 50 mm
    let mut model = run(&rig("", "initial_depletion = 50\nrain = if(var.day.n == 2, 50, 0)"));
    assert_eq!(s(&mut model, "excess")[1], 0.0);
}

#[test]
fn test_irrigation_never_runs_through_the_curve_number_and_wet_crops_shed_more() {
    // crop_1, 1 km2, kept full by a standing order; the 3 km2 fallow starts 50 mm down and
    // nothing dries (evap 0). Day 3: 50 mm of rain. The crop sheds by the wet curve, the
    // fallow by the dry one, and the irrigation delivered on day 2 shed nothing at all.
    // (viable_area = 1 keeps the crop alive through its bone-dry first day.)
    let field = "initial_depletion = 50\nevap = 0\nrain = if(var.day.n == 3, 50, 0)\ncurve_number = 85\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 1, 1, 0)\ncrop_1_order = if(var.day.n == 2, 50, 0)\ncrop_1_viable_area = 1";
    let mut model = run(&rig("", field));
    assert_eq!(s(&mut model, "usflow")[1], 50.0);
    assert_eq!(s(&mut model, "supply")[1], 50.0, "1 km2 x 50 mm of room, all taken");
    assert_eq!(s(&mut model, "excess")[1], 0.0, "irrigation does not run off");
    assert_eq!(s(&mut model, "crop_1_depletion")[1], 0.0);
    // Day 3: the crop is full, so 33.37 mm run off it and the rest overflows: 50 mm x 1 km2.
    // The fallow is dry: 6.32 mm x 3 km2.
    assert_close(s(&mut model, "excess")[2], 50.0 * 1.0 + 6.323638637769316 * 3.0, "wet crop and dry fallow");
    assert_close(s(&mut model, "fallow_depletion")[2], 6.323638637769316, "the fallow took the rest");
    // The balance still closes
    field_balance_closes(&mut model);
}

#[test]
fn test_curve_number_validation_and_round_trip() {
    let mut bad = IniModelIO::read_model_string(&rig("", "curve_number = 0")).expect("loads");
    let err = bad.configure().map(|_| ()).and_then(|_| bad.run()).err().map(|e| e.to_string()).unwrap_or_default();
    assert!(err.contains("curve_number must be greater than 0 and at most 100"), "got: {err}");
    let ini = rig("", "curve_number = 78");
    let model = IniModelIO::read_model_string(&ini).expect("model should load");
    let rendered = IniModelIO::model_to_string(&model);
    assert!(rendered.contains("curve_number = 78"), "survives save:\n{rendered}");
    let plain = IniModelIO::model_to_string(&IniModelIO::read_model_string(&rig("", "")).unwrap());
    assert!(!plain.contains("curve_number"), "absent stays absent");
}

#[test]
fn test_a_slot_number_with_a_leading_zero_is_not_a_slot() {
    let ini = rig("", "crop_01 = shallow\ncrop_01_plant = 1");
    let err = IniModelIO::read_model_string(&ini).err().map(|e| e.to_string()).unwrap_or_default();
    assert!(err.contains("Unexpected parameter 'crop_01'"), "got: {err}");
}

#[test]
fn test_a_fallow_with_a_kc_table_is_refused() {
    let ini = rig("", "").replace("[crop.bare]\nroot_depth = 500\nkc = 0\n", "[crop.bare]\nroot_depth = 500\nkc = 0, 0.2, 100, 0.5\n");
    let mut model = IniModelIO::read_model_string(&ini).expect("loads");
    let err = model.configure().map(|_| ()).and_then(|_| model.run()).err().map(|e| e.to_string()).unwrap_or_default();
    assert!(err.contains("The fallow's kc must be a number"), "got: {err}");
}

#[test]
fn test_a_planting_rule_that_gives_no_number_stops_the_run() {
    // NaN is not an area, nor is a negative: both stop the run naming the field and the
    // crop, as a viable_area rule does, rather than planting the whole fallow
    let nan = rig("", "crop_1 = shallow\ncrop_1_plant = 0 / 0");
    let err = std::panic::catch_unwind(|| run(&nan)).err().map(|e| e.downcast_ref::<String>().cloned().unwrap_or_default()).unwrap_or_default();
    assert!(err.contains("Field 'paddock': crop plant rule for 'shallow' gave NaN"), "got: {err}");
    let negative = rig("", "crop_1 = shallow\ncrop_1_plant = -1");
    let err = std::panic::catch_unwind(|| run(&negative)).err().map(|e| e.downcast_ref::<String>().cloned().unwrap_or_default()).unwrap_or_default();
    assert!(err.contains("gave -1; it must be the area to plant"), "got: {err}");
    // Zero plants nothing and the rule is read again next day
    let mut model = run(&rig("", "crop_1 = shallow\ncrop_1_plant = if(var.day.n < 3, 0, 1)"));
    assert_eq!(s(&mut model, "crop_1_area")[..3], [0.0, 0.0, 1.0]);
}

#[test]
fn test_a_field_outside_every_regulated_zone_plants_irrigates_and_records_no_orders() {
    // An inflow feeds the field directly: no storage, no zone, no order phase. The crop is
    // planted all the same (at the start of the flow phase), takes what arrives up to its
    // room, and its order results are zero rather than missing.
    let ini = rig("", "evap = 5\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 2, 1, 0)\ncrop_1_order = 100")
        .replace("[node.dam]\ntype = storage\nloc = 0, 0\ninitial_volume = 5000\ndimensions = Level [m], Volume [ML], Area [km2], Spill [ML],\n             0.0      , 0.0        , 0.0       , 0.0,\n             1.0      , 10000.0    , 0.1       , 0.0,\n             2.0      , 20000.0    , 0.1       , 1.0E9,\nds_1_outlet = 0, 10000\nds_1 = paddock", "[node.dam]\ntype = inflow\nloc = 0, 0\ninflow = 20\nds_1 = paddock");
    let mut model = run(&ini);
    let n = s(&mut model, "usflow").len();
    assert_eq!(s(&mut model, "crop_1_area")[..3], [0.0, 1.0, 1.0], "planted on day 2 in the flow phase");
    assert_eq!(s(&mut model, "crop_1_order").len(), n, "the series exists and is as long as the rest");
    assert!(s(&mut model, "crop_1_order").iter().all(|v| *v == 0.0), "no order phase, no order");
    assert_eq!(s(&mut model, "crop_1_order_due").len(), n);
    assert_eq!(s(&mut model, "crop_1_orders_en_route").len(), n);
    // Day 2: planted full, dries 5; nothing due, so the 20 arriving is a forced watering
    // poured into the crop's 5 mm of room: 5 ML taken, 15 bypass
    assert_eq!(s(&mut model, "supply")[1], 5.0);
    assert_eq!(s(&mut model, "bypass")[1], 15.0);
}

#[test]
fn test_a_standing_trigger_replants_on_the_day_of_harvest() {
    // season_len 2 and a trigger that is always true: the crop is harvested at the start of
    // every third day and planted again the same morning, so its area never shows a gap and
    // days runs 0, 1, 0, 1, ...
    let ini = rig("", "evap = 0\ncrop_1 = shallow\ncrop_1_plant = 1")
        .replace("[crop.shallow]\nroot_depth = 500\nkc = 1\n", "[crop.shallow]\nroot_depth = 500\nkc = 1\nseason_len = 2\n");
    let mut model = run(&ini);
    assert_eq!(s(&mut model, "crop_1_days")[..5], [0.0, 1.0, 0.0, 1.0, 0.0]);
    assert_eq!(s(&mut model, "crop_1_area")[..5], [1.0; 5]);
}

#[test]
fn test_kc_is_read_from_the_crops_table_by_days_since_planting() {
    // kc 0 on the planting day, 1 the next, 1 after: ET is 0 on day 2 (days = 0) and
    // evap x area from day 3
    let ini = rig("", "evap = 4\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 2, 1, 0)")
        .replace("[crop.shallow]\nroot_depth = 500\nkc = 1\n", "[crop.shallow]\nroot_depth = 500\nkc = 0, 0, 1, 1, 2, 1\n");
    let mut model = run(&ini);
    assert_eq!(s(&mut model, "crop_1_days")[1..4], [0.0, 1.0, 2.0]);
    assert_eq!(s(&mut model, "et_vol")[1..4], [0.0, 4.0, 4.0]);
    assert_eq!(s(&mut model, "et")[2], 1.0, "4 mm over 1 of 4 km2: 1 mm over the field");
}

#[test]
fn test_two_slots_firing_the_same_day_plant_lower_first_and_the_fallow_caps_the_second() {
    let mut model = run(&rig("", "crop_1 = shallow\ncrop_1_plant = if(var.day.n == 2, 3, 0)\ncrop_2 = deep\ncrop_2_plant = if(var.day.n == 2, 3, 0)"));
    assert_eq!(s(&mut model, "crop_1_area")[1], 3.0);
    assert_eq!(s(&mut model, "crop_2_area")[1], 1.0, "what the fallow had left");
}

#[test]
fn test_interception_comes_off_what_the_storm_leaves() {
    // Dry fallow, CN 85, evap 10 with the default 0.2 interception: 50 mm of rain sheds
    // 6.32 mm, then 2 mm is intercepted from the remainder, and 41.68 mm infiltrates
    let ini = rig("", "initial_depletion = 50\nevap = 10\nrain = if(var.day.n == 2, 50, 0)\ncurve_number = 85").replace("interception = 0\n", "");
    let mut model = run(&ini);
    assert_close(s(&mut model, "intercepted")[1], 2.0, "mm, the same over the whole fallow");
    assert_close(s(&mut model, "fallow_depletion")[1], 50.0 - (50.0 - 6.323638637769316 - 2.0), "what reached the soil");
}

#[test]
fn test_a_curve_number_needs_a_daily_step() {
    // The step is daily by construction today, so the guard is reached by setting the cache's
    // step directly and initialising the network as a run does
    let mut model = IniModelIO::read_model_string(&rig("", "curve_number = 85")).expect("loads");
    model.configure().expect("configures: the step is not known yet");
    model.data_cache.set_start_and_stepsize(model.configuration.sim_start_timestamp, 3600);
    let err = model.initialize_network().err().unwrap_or_default();
    assert!(err.contains("defined for daily rain totals"), "got: {err}");
}

#[test]
fn test_a_rerun_of_the_same_model_repeats_itself() {
    let ini = rig("", "evap = 5\nrain = if(var.day.n == 4, 30, 0)\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 2, 2, 0)\ncrop_1_order = this.crop_1_area[-1, 0] * this.crop_1_depletion[-1, 0]")
        .replace("[crop.shallow]\nroot_depth = 500\nkc = 1\n", "[crop.shallow]\nroot_depth = 500\nkc = 1\nseason_len = 5\n");
    let mut model = run(&ini);
    let first: Vec<Vec<f64>> = ["crop_1_area", "crop_1_depletion", "fallow_depletion", "excess", "supply"].iter().map(|k| s(&mut model, k)).collect();
    model.run().expect("second run");
    let second: Vec<Vec<f64>> = ["crop_1_area", "crop_1_depletion", "fallow_depletion", "excess", "supply"].iter().map(|k| s(&mut model, k)).collect();
    for (a, b) in first.iter().zip(second.iter()) {
        assert!(a.iter().zip(b.iter()).all(|(x, y)| x == y || (x.is_nan() && y.is_nan())), "a rerun differs");
    }
}

#[test]
fn test_a_negative_viable_area_stops_the_run() {
    let ini = rig("", "crop_1 = shallow\ncrop_1_plant = if(var.day.n == 1, 1, 0)\ncrop_1_viable_area = if(var.day.n == 3, -1, 1)");
    let err = std::panic::catch_unwind(|| run(&ini)).err().map(|e| e.downcast_ref::<String>().cloned().unwrap_or_default()).unwrap_or_default();
    assert!(err.contains("crop viable_area rule for 'shallow' gave -1"), "got: {err}");
}

#[test]
fn test_the_planting_and_viability_rules_are_recorded_as_read() {
    // crop_1 plants 1 km2 on day 2 and is abandoned by a rule on day 4; crop_2 never plants.
    // crop_N_plant is the rule's value on the days the slot is empty and not a number while
    // the crop stands; crop_N_viable_area is the rule's value while it stands, and the
    // built-in rule's area when no rule is written.
    let ini = rig("", "evap = 0\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 2, 1, 0)\ncrop_1_viable_area = if(var.day.n == 4, 0, 5)\ncrop_2 = deep\ncrop_2_plant = 0");
    let mut model = run(&ini);
    let plant = s(&mut model, "crop_1_plant");
    assert_eq!(plant[0], 0.0, "read and gave 0");
    assert_eq!(plant[1], 1.0, "read and gave 1: planted");
    assert!(plant[2].is_nan(), "not read while the crop stands");
    assert_eq!(plant[3], 0.0, "read again the day it was abandoned");
    let viable = s(&mut model, "crop_1_viable_area");
    assert!(viable[0].is_nan() && viable[1].is_nan(), "nothing to keep viable before the crop stands");
    assert_eq!(viable[2], 5.0, "the rule's value, uncapped");
    assert_eq!(viable[3], 0.0, "the day it was abandoned");
    assert!(viable[4].is_nan());
    assert_eq!(s(&mut model, "crop_2_plant")[..3], [0.0, 0.0, 0.0], "a slot that never plants is read every day");
    // With no rule written, the built-in rule's area is what is recorded: the crop's own
    // area while it lives, 0 the morning it dies
    let mut model = run(&rig("", "initial_depletion = 50\nevap = 5\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 1, 1, 0)"));
    let viable = s(&mut model, "crop_1_viable_area");
    assert!(viable[0].is_nan(), "planted this morning: the rule is read from tomorrow");
    assert_eq!(viable[1], 0.0, "the built-in rule: dead at ks 0");
}

#[test]
fn test_a_perennial_is_planted_once_and_stays() {
    // No season_len: planted on day 2 and never harvested; its plant rule is not read again
    // while it stands, and its kc table holds its last value past the end
    // (10 mm of rain a day keeps the bucket full, so stress never enters into it)
    let ini = rig("", "evap = 10\nrain = 10\ncrop_1 = deep\ncrop_1_plant = if(var.day.n == 2, 4, 0)")
        .replace("[crop.deep]\nroot_depth = 1000\nkc = 1\n", "[crop.deep]\nroot_depth = 1000\nkc = 0, 0.5, 2, 1.0\n");
    let mut model = run(&ini);
    let area = s(&mut model, "crop_1_area");
    assert!(area[1..].iter().all(|a| *a == 4.0), "in the ground from day 2 to the end: {area:?}");
    assert!(s(&mut model, "crop_1_plant")[2..].iter().all(|v| v.is_nan()), "the plant rule is not read while it stands");
    assert_eq!(s(&mut model, "et_vol")[1], 20.0, "kc 0.5 on its first day");
    assert_eq!(s(&mut model, "et_vol")[3], 40.0, "kc 1.0 from day 2 of its life");
    assert_eq!(s(&mut model, "et_vol")[8], 40.0, "and held there past the end of the table");
}

#[test]
fn test_todays_area_is_readable_in_an_order_rule_because_planting_writes_it_first() {
    // Area, days and the two rules are written when planting runs, before any order rule,
    // so `this.crop_1_area` with no offset is today's area: the crop orders on the day it
    // goes in. Planted on day 2, 1 km2, 20 mm down: it orders 20 ML that morning.
    let mut model = run(&rig("", "initial_depletion = 20\nevap = 0\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 2, 1, 0)\ncrop_1_order = this.crop_1_area * this.crop_1_depletion[-1, 0]"));
    assert_eq!(s(&mut model, "crop_1_order")[1], 0.0, "day 2: the area is 1 but yesterday's depletion is not a number, read as 0");
    let mut model = run(&rig("", "initial_depletion = 20\nevap = 0\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 2, 1, 0)\ncrop_1_order = this.crop_1_area * 20"));
    assert_eq!(s(&mut model, "crop_1_order")[..3], [0.0, 20.0, 20.0], "ordered the morning it was planted");
    assert_eq!(s(&mut model, "crop_1_area")[..3], [0.0, 1.0, 1.0]);
    assert_eq!(s(&mut model, "crop_1_depletion")[1], 0.0, "and the order arrived the same day");
    // Outside a regulated zone planting runs in the flow phase and the series are still
    // written once a step, as long as every other
    let ini = rig("", "crop_1 = shallow\ncrop_1_plant = if(var.day.n == 2, 1, 0)")
        .replace("[node.dam]\ntype = storage\nloc = 0, 0\ninitial_volume = 5000\ndimensions = Level [m], Volume [ML], Area [km2], Spill [ML],\n             0.0      , 0.0        , 0.0       , 0.0,\n             1.0      , 10000.0    , 0.1       , 0.0,\n             2.0      , 20000.0    , 0.1       , 1.0E9,\nds_1_outlet = 0, 10000\nds_1 = paddock", "[node.dam]\ntype = inflow\nloc = 0, 0\ninflow = 0\nds_1 = paddock");
    let mut model = run(&ini);
    let n = s(&mut model, "usflow").len();
    assert_eq!(s(&mut model, "crop_1_area").len(), n);
    assert_eq!(s(&mut model, "crop_1_days").len(), n);
    assert_eq!(s(&mut model, "crop_1_plant").len(), n);
    assert_eq!(s(&mut model, "crop_1_area")[..3], [0.0, 1.0, 1.0]);
}

#[test]
fn test_todays_opening_stress_is_readable_in_a_viable_area_rule() {
    // ks is written the moment it is computed for the day, before the viability rule reads
    // it, so `this.crop_1_ks` with no offset is today's opening stress: a written rule at
    // the built-in threshold kills the crop on the same morning the built-in rule would.
    // 50 mm bucket, planted 48.5 down on day 1 (ks = 1.5/25 = 0.06), drying 0.06 x 5 that
    // day: ks opens at 0.048 on day 2, below 0.05.
    let mut built_in = run(&rig("", "initial_depletion = 48.5\nevap = 5\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 1, 1, 0)"));
    let mut written = run(&rig("", "initial_depletion = 48.5\nevap = 5\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 1, 1, 0)\ncrop_1_viable_area = if(this.crop_1_ks <= 0.05, 0, this.crop_1_area[-1, 0])"));
    assert_eq!(s(&mut built_in, "crop_1_area")[..3], [1.0, 0.0, 0.0]);
    assert_eq!(s(&mut written, "crop_1_area")[..3], [1.0, 0.0, 0.0], "same morning as the built-in rule");
    assert!((s(&mut written, "crop_1_ks")[0] - 0.06).abs() < 1e-12);
    assert!((s(&mut written, "crop_1_ks")[1] - 0.048).abs() < 1e-12, "the morning it dies, the stress that killed it is recorded");
    assert!(s(&mut written, "crop_1_ks")[2].is_nan(), "then nothing stands");
    // Yesterday's ks is a day late: on day 2 it reads 0.06 and the crop lives one more day
    let mut late = run(&rig("", "initial_depletion = 48.5\nevap = 5\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 1, 1, 0)\ncrop_1_viable_area = if(this.crop_1_ks[-1, 1] <= 0.05, 0, this.crop_1_area[-1, 0])"));
    assert_eq!(s(&mut late, "crop_1_area")[..4], [1.0, 1.0, 0.0, 0.0]);
    // A field outside every zone writes ks once a step too
    let ini = rig("", "evap = 5\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 2, 1, 0)")
        .replace("[node.dam]\ntype = storage\nloc = 0, 0\ninitial_volume = 5000\ndimensions = Level [m], Volume [ML], Area [km2], Spill [ML],\n             0.0      , 0.0        , 0.0       , 0.0,\n             1.0      , 10000.0    , 0.1       , 0.0,\n             2.0      , 20000.0    , 0.1       , 1.0E9,\nds_1_outlet = 0, 10000\nds_1 = paddock", "[node.dam]\ntype = inflow\nloc = 0, 0\ninflow = 0\nds_1 = paddock");
    let mut model = run(&ini);
    let ks = s(&mut model, "crop_1_ks");
    assert_eq!(ks.len(), s(&mut model, "usflow").len());
    assert!(ks[0].is_nan() && ks[1] == 1.0);
}

#[test]
fn test_the_profiles_water_is_the_one_state_whose_change_is_the_water_balance() {
    // Everything at once over twelve days: two crops of different root depths planted and one
    // harvested, a curve number, interception, irrigation with a forced pour, rain that
    // overflows into the layers. The balance closes every step from the results alone.
    let ini = rig("", "initial_depletion = 30\nevap = 5\nrain = if(var.day.n == 4, 40, if(var.day.n == 9, 70, 0))\ncurve_number = 80\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 2, 2, 0)\ncrop_1_order = if(var.day.n == 6, 60, this.crop_1_area * clamp(this.crop_1_depletion[-1, 0] - 10, 0, 50))\ncrop_2 = deep\ncrop_2_plant = if(var.day.n == 3, 1, 0)\ncrop_2_order = this.crop_2_area * clamp(this.crop_2_depletion[-1, 0] - 20, 0, 50)")
        .replace("interception = 0\n", "")
        .replace("[crop.shallow]\nroot_depth = 500\nkc = 1\n", "[crop.shallow]\nroot_depth = 500\nkc = 1\nseason_len = 6\n");
    let mut model = run(&ini);
    field_balance_closes(&mut model);
    // With the whole field under one crop on a one-layer profile of 50 mm, the field's
    // moisture is the capacity less the crop's depletion
    let mut one = run(&rig("", "initial_depletion = 20\nevap = 5\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 1, 4, 0)").replace("[crop.deep]\nroot_depth = 1000\nkc = 1\n", "[crop.deep]\nroot_depth = 500\nkc = 1\n"));
    for t in 0..5 {
        assert_close(s(&mut one, "soil_moisture")[t], 50.0 - s(&mut one, "crop_1_depletion")[t], "one crop, one layer, whole field");
    }
}

#[test]
fn test_the_kc_multiplier_scales_the_crops_water_use_and_nothing_else() {
    // A crop at kc 1 on evap 5 dries 5 mm a day; with a multiplier of 0.6 it dries 3, with 0
    // nothing, and the multiplier never reaches the interception, which still takes 0.2 x evap
    let base = "evap = 5\nrain = 2\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 1, 4, 0)";
    let mut plain = run(&rig("", base).replace("interception = 0\n", ""));
    let mut skip = run(&rig("", &format!("{base}\ncrop_1_kc_multiplier = 0.6")).replace("interception = 0\n", ""));
    let mut none = run(&rig("", &format!("{base}\ncrop_1_kc_multiplier = 0")).replace("interception = 0\n", ""));
    assert_eq!(s(&mut plain, "et")[1], 5.0);
    assert_close(s(&mut skip, "et")[1], 3.0, "0.6 of the water use");
    assert_eq!(s(&mut none, "et")[1], 0.0, "a multiplier of 0 is no evapotranspiration");
    for m in [&mut plain, &mut skip, &mut none] {
        assert_eq!(s(m, "intercepted")[1], 1.0, "interception reads evap as it is: 0.2 x 5");
    }
    // Recorded: the multiplier as read (1 when none is written) and the coefficient used
    assert_eq!(s(&mut plain, "crop_1_kc_multiplier")[1], 1.0);
    assert_eq!(s(&mut plain, "crop_1_kc")[1], 1.0);
    assert_close(s(&mut skip, "crop_1_kc_multiplier")[1], 0.6, "the multiplier as read");
    assert_close(s(&mut skip, "crop_1_kc")[1], 0.6, "the curve's 1 times 0.6");
    // Not a number when nothing stands
    let mut later = run(&rig("", "evap = 5\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 3, 4, 0)\ncrop_1_kc_multiplier = 0.8"));
    assert!(s(&mut later, "crop_1_kc_multiplier")[0].is_nan() && s(&mut later, "crop_1_kc")[0].is_nan());
    assert_close(s(&mut later, "crop_1_kc")[2], 0.8, "from the planting day");
}

#[test]
fn test_the_kc_multiplier_can_follow_the_season_and_the_curve() {
    // The multiplier reads the slot's own series without an offset, since area and days are
    // written at planting: a single skip whose canopy closes over the skip after day 2.
    // The kc curve is 0.5 on day 0 rising to 1 by day 2, so the coefficient used is the
    // product on every day.
    let ini = rig("", "evap = 10\nrain = 10\ncrop_1 = deep\ncrop_1_plant = if(var.day.n == 1, 4, 0)\ncrop_1_kc_multiplier = if(this.crop_1_days < 2, 0.7, 0.9)")
        .replace("[crop.deep]\nroot_depth = 1000\nkc = 1\n", "[crop.deep]\nroot_depth = 1000\nkc = 0, 0.5, 2, 1.0\n");
    let mut model = run(&ini);
    let kc = s(&mut model, "crop_1_kc");
    assert_close(kc[0], 0.5 * 0.7, "day 0 of the crop");
    assert_close(kc[1], 0.75 * 0.7, "day 1");
    assert_close(kc[2], 1.0 * 0.9, "day 2: the canopy has closed");
    assert_close(s(&mut model, "et")[2], 9.0, "kc 0.9 x evap 10 over the whole field");
    // A multiplier that gives no number, or a negative one, stops the run
    let bad = rig("", "evap = 5\ncrop_1 = shallow\ncrop_1_plant = if(var.day.n == 1, 4, 0)\ncrop_1_kc_multiplier = -0.5");
    let err = std::panic::catch_unwind(|| run(&bad)).err().map(|e| e.downcast_ref::<String>().cloned().unwrap_or_default()).unwrap_or_default();
    assert!(err.contains("crop kc_multiplier rule for 'shallow' gave -0.5"), "got: {err}");
    // And it round-trips
    let ini = rig("", "crop_1 = shallow\ncrop_1_plant = 0\ncrop_1_kc_multiplier = 0.85");
    let model = IniModelIO::read_model_string(&ini).expect("loads");
    let rendered = IniModelIO::model_to_string(&model);
    assert!(rendered.contains("crop_1_kc_multiplier = 0.85"), "survives save:\n{rendered}");
    let plain = IniModelIO::model_to_string(&IniModelIO::read_model_string(&rig("", "crop_1 = shallow\ncrop_1_plant = 0")).unwrap());
    assert!(!plain.contains("crop_1_kc_multiplier ="), "absent stays absent:\n{plain}");
}
