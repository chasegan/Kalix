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
node.paddock.fallow_depletion
node.paddock.usflow
node.paddock.et
node.paddock.et_vol
node.paddock.rain_vol
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

/// Water in the field at the end of a step, from what it records: only the root
/// buckets show, so this holds when the profile is one layer (see the layer test
/// for the rest)
fn field_balance_closes(model: &mut Model, held: impl Fn(&mut Model, usize) -> f64) {
    let n = s(model, "usflow").len();
    for t in 1..n {
        let d_held = held(model, t) - held(model, t - 1);
        let inflow = s(model, "rain_vol")[t] + s(model, "supply")[t] - s(model, "escape")[t];
        let outflow = s(model, "et_vol")[t] + s(model, "excess")[t];
        assert_close(inflow - outflow, d_held, &format!("water balance on step {t}"));
        assert_close(s(model, "usflow")[t], s(model, "supply")[t] + s(model, "bypass")[t], &format!("usflow on step {t}"));
    }
}

#[test]
fn test_planting_takes_area_from_the_fallow_with_its_water_and_harvest_gives_it_back() {
    // The whole profile starts 20 mm below full (over 1 m: 10 in the top 500 mm, 10 below;
    // the deep crop in slot 2, never planted, is what gives the profile its second layer).
    // Day 3: 1 km2 of the shallow crop is planted; the fallow is bare so nothing has
    // dried, and the crop's bucket is 10 mm down, like the fallow's. It dries 4 mm a day
    // for 3 days (days 0, 1, 2 since planting) and is harvested on day 6 as days reaches 3.
    let ini = rig("", "initial_depletion = 20\nevap = 4\ncrop_1 = shallow\ncrop_1_plant = var.day.n == 3\ncrop_1_plant_area = 1\ncrop_2 = deep\ncrop_2_plant = 0\ncrop_2_plant_area = 1")
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
    let held = |m: &mut Model, t: usize| {
        let (a, d, f) = (s(m, "crop_1_area")[t], s(m, "crop_1_depletion")[t], s(m, "fallow_depletion")[t]);
        // The profile below 500 mm is untouched (10 mm down over 4 km2) and one layer deep, so
        // the root buckets account for every change
        -(a * if d.is_nan() { 0.0 } else { d } + (4.0 - a) * f)
    };
    field_balance_closes(&mut model, held);
}

#[test]
fn test_each_crop_orders_for_its_own_deficit_and_takes_only_its_own_order() {
    // Both crops planted on day 1, 1 km2 each, over a full profile, evap 5 mm. Each orders
    // yesterday's closing deficit. Day 1: nothing to read, no orders; each closes 5 down.
    // Day 2: each orders 5 ML; each takes its own 5 with 0.5 mm... no: 5 ML over 1 km2 is
    // 5 mm, refilling exactly; then dries 5 again. The dam sees the sum.
    let mut model = run(&rig("", "evap = 5\ncrop_1 = shallow\ncrop_1_plant = 1\ncrop_1_plant_area = 1\ncrop_1_order = this.crop_1_area[-1, 0] * this.crop_1_depletion[-1, 0]\ncrop_2 = deep\ncrop_2_plant = 1\ncrop_2_plant_area = 1\ncrop_2_order = this.crop_2_area[-1, 0] * this.crop_2_depletion[-1, 0]"));
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
    let mut model = run(&rig("", "evap = 5\ncrop_1 = shallow\ncrop_1_plant = 1\ncrop_1_plant_area = 1\ncrop_1_order = if(var.day.n >= 2, 30, 0)\ncrop_2 = deep\ncrop_2_plant = 1\ncrop_2_plant_area = 1"));
    assert_eq!(s(&mut model, "usflow")[1], 30.0);
    assert_eq!(s(&mut model, "supply")[1], 20.0);
    assert_eq!(s(&mut model, "bypass")[1], 10.0);
    assert_eq!(s(&mut model, "crop_1_depletion")[1], 0.0, "watered to full");
    assert_eq!(s(&mut model, "crop_2_depletion")[1], 0.0, "the same, from the pour");
    // With unequal deficits the pour levels from the driest down. 20 mm down over the metre
    // at the start: 10 in the shallow crop's bucket, 20 in the deep one's. Day 1 closes at
    // 15 and 25; day 2 dries them to 20 and 30, crop_1 takes its 20, and the other 10 brings
    // crop_2 from 30 to 20: nothing bypasses.
    let mut model = run(&rig("", "initial_depletion = 20\nevap = 5\ncrop_1 = shallow\ncrop_1_plant = 1\ncrop_1_plant_area = 1\ncrop_1_order = if(var.day.n >= 2, 30, 0)\ncrop_2 = deep\ncrop_2_plant = 1\ncrop_2_plant_area = 1"));
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
    let mut model = run(&rig("", "initial_depletion = 20\nevap = 5\ncrop_1 = shallow\ncrop_1_plant = 0\ncrop_1_plant_area = 1\ncrop_1_order = 10\ncrop_2 = deep\ncrop_2_plant = 0\ncrop_2_plant_area = 1"));
    assert_eq!(s(&mut model, "crop_1_order")[3], 0.0, "not in the ground, not evaluated");
    assert_eq!(s(&mut model, "usflow")[3], 0.0);
    assert_eq!(s(&mut model, "fallow_depletion")[3], 10.0, "bare ground with kc 0 never dries");
    // Rain on the fallow overflows into the layer below before it leaves as excess: the top
    // 500 mm is 10 down and the layer below 10 down; 15 mm fills the top and 5 goes below
    let mut model = run(&rig("", "initial_depletion = 20\nrain = if(var.day.n == 2, 15, if(var.day.n == 3, 30, 0))\ncrop_1 = shallow\ncrop_1_plant = 0\ncrop_1_plant_area = 1\ncrop_2 = deep\ncrop_2_plant = 0\ncrop_2_plant_area = 1"));
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
    let ini = rig("", "evap = 10\ncrop_1 = deep\ncrop_1_plant = var.day.n == 1\ncrop_1_plant_area = 4\ncrop_2 = shallow\ncrop_2_plant = var.day.n == 7\ncrop_2_plant_area = 4\nrain = if(var.day.n == 8, 50, if(var.day.n == 9, 60, 0))")
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
}

#[test]
fn test_a_stressed_crop_dies_by_the_built_in_rule_and_a_written_rule_replaces_it() {
    // p = 0.5 so ks = (cap - dep) / (0.5 cap); the built-in rule kills a crop whose ks opens
    // at 0.05 or below: for the shallow crop (50 mm) that is 48.75 mm down or more. Bone dry
    // start: the profile is the 500 mm the shallow crop and the fallow root to, 50 mm down.
    // ks opens at 0, the crop is planted on day 1 and dies on day 2 at the start of the day,
    // its area back to the fallow. (A trigger that is always true would plant it again the
    // same morning: the rule plants whenever no crop is in the ground.)
    let mut model = run(&rig("", "initial_depletion = 50\nevap = 5\ncrop_1 = shallow\ncrop_1_plant = var.day.n == 1\ncrop_1_plant_area = 1"));
    let area = s(&mut model, "crop_1_area");
    assert_eq!(area[..3], [1.0, 0.0, 0.0], "planted on day 1, dead on day 2");
    assert_eq!(s(&mut model, "crop_1_ks")[0], 0.0);
    // A written rule replaces the built-in one entirely: this crop keeps 0.5 km2 whatever
    // its stress, and never dies
    let mut model = run(&rig("", "initial_depletion = 50\nevap = 5\ncrop_1 = shallow\ncrop_1_plant = var.day.n == 1\ncrop_1_plant_area = 1\ncrop_1_viable_area = 0.5"));
    let area = s(&mut model, "crop_1_area");
    assert_eq!(area[..3], [1.0, 0.5, 0.5], "half the area abandoned on day 2, the rest stays");
    assert_eq!(s(&mut model, "crop_1_ks")[5], 0.0, "stressed but alive");
}

#[test]
fn test_field_with_crops_round_trips() {
    let ini = rig("season_len = 120", "evap = 5\ncrop_1 = shallow\ncrop_1_plant = sim.month == 10\ncrop_1_plant_area = 1.5\ncrop_1_order = 10\ncrop_1_viable_area = 2\ncrop_2 = deep\ncrop_2_plant = 0\ncrop_2_plant_area = 1");
    let model = IniModelIO::read_model_string(&ini).expect("model should load");
    let rendered = IniModelIO::model_to_string(&model);
    for line in ["fallow = bare", "crop_1 = shallow", "crop_1_plant = sim.month == 10", "crop_1_plant_area = 1.5", "crop_1_order = 10", "crop_1_viable_area = 2", "crop_2 = deep", "crop_2_plant = 0", "season_len = 120"] {
        assert!(rendered.contains(line), "'{line}' survives save:\n{rendered}");
    }
    assert!(!rendered.contains("crop_2_order ="), "an unset rule is not written");
    let reloaded = IniModelIO::read_model_string(&rendered).expect("canonical render should re-load");
    assert_eq!(IniModelIO::model_to_string(&reloaded), rendered);
}

