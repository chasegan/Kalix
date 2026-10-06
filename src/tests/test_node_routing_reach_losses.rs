//! Routing node reach losses: `evap` + `loss_table` (flow, dead storage volume, area).
//!
//! Notation, per division: V_i + Q_i = V_f + Q_f + E * A_f. The dead pool is part of
//! the routing balance, so there is outflow only when the pool is full.

use crate::model::Model;

const ROUTINGS: [(&str, &str); 4] = [
    ("NLM x = 1", "x = 1\nnlm = 2.0, 0.8"),
    ("NLM x < 1", "x = 0.3\nnlm = 2.0, 0.8"),
    ("PWL x = 1", "x = 1\npwl = 0, 2,\n      100, 2,\n      1000, 1,"),
    ("PWL x < 1", "x = 0.3\npwl = 0, 2,\n      100, 2,\n      1000, 1,"),
];
const PWL_X_UNITY: &str = ROUTINGS[2].1;

/// Dead pool of 100 ML with area 0 to 1 km2; flowing area 1 km2 at no flow to 2 km2 at 100 ML/d.
const LOSS_TABLE: &str = "0, 0, 0,\n    0, 100, 1,\n    100, 100, 2,";
const DEAD: f64 = 100.0;

/// A model of `days` days (up to 91) from 2020-01-01.
fn ini(inflow: &str, routing: &str, n_divs: usize, reach_losses: &str, days: usize) -> String {
    let (month, day) = match days { 1..=31 => (1, days), 32..=60 => (2, days - 31), _ => (3, days - 60) };
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-{month:02}-{day:02}

[node.src]
type = inflow
loc = 0, 0
inflow = {inflow}
ds_1 = reach

[node.reach]
type = routing
loc = 0, 10
n_divs = {n_divs}
{routing}
{reach_losses}
ds_1 = sink

[node.sink]
type = gauge
loc = 0, 20

[outputs]
node.reach.usflow
node.reach.dsflow
node.reach.volume
node.reach.area
node.reach.loss
"#)
}

fn losses(evap: &str, table: &str) -> String {
    format!("evap = {evap}\nloss_table = {table}")
}

fn try_run(ini: &str) -> Result<Model, String> {
    let mut model = crate::io::ini_model_io::IniModelIO::read_model_string(ini).map_err(|e| e.to_string())?;
    model.configure()?;
    model.run()?;
    Ok(model)
}

fn series(model: &mut Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_series_idx(name, false)
        .unwrap_or_else(|| panic!("no series '{}'", name));
    model.data_cache.series[idx].values.clone()
}

fn assert_close(got: &[f64], want: &[f64], what: &str) {
    assert_eq!(got.len(), want.len(), "{what}: length");
    for (i, (g, w)) in got.iter().zip(want).enumerate() {
        assert!((g - w).abs() < 1e-9, "{what}, step {}: got {g}, want {w}", i + 1);
    }
}

// ============================================================================
// Hand-computed cases
// ============================================================================

/// PWL, x = 1, one division: V(q) = 2q. Inflow 50, evap 10 mm.
/// Flowing: V_f = 100 + V(50) = 200, A(50) = 1.5, loss 15.
/// Days 1-2 cannot reach 200 and hold their water less the full-pool loss (10 * 1);
/// day 3 fills, and from day 4 the outflow is inflow less loss.
#[test]
fn reach_fills_before_it_flows() {
    let mut model = try_run(&ini("50", PWL_X_UNITY, 1, &losses("10", LOSS_TABLE), 5)).unwrap();
    assert_close(&series(&mut model, "node.reach.dsflow"), &[0.0, 0.0, 15.0, 35.0, 35.0], "dsflow");
    assert_close(&series(&mut model, "node.reach.volume"), &[140.0, 180.0, 200.0, 200.0, 200.0], "volume");
    assert_close(&series(&mut model, "node.reach.area"), &[1.0, 1.0, 1.5, 1.5, 1.5], "area");
    assert_close(&series(&mut model, "node.reach.loss"), &[10.0, 10.0, 15.0, 15.0, 15.0], "loss");
}

/// No inflow: the pool drains by evaporation alone. Area is V / 100 below full, so
/// V_f = V_i - 10 * V_f / 100, i.e. V_f = V_i / 1.1.
#[test]
fn pool_drains_by_evaporation_only() {
    for (name, routing) in ROUTINGS {
        let mut model = try_run(&ini("0", routing, 1, &losses("10", LOSS_TABLE), 4)).unwrap();
        let want: Vec<f64> = (1..=4).map(|t| DEAD / 1.1_f64.powi(t)).collect();
        assert_close(&series(&mut model, "node.reach.volume"), &want, &format!("{name}: volume"));
        assert_close(&series(&mut model, "node.reach.dsflow"), &[0.0; 4], &format!("{name}: dsflow"));
        let area: Vec<f64> = want.iter().map(|v| v / 100.0).collect();
        assert_close(&series(&mut model, "node.reach.area"), &area, &format!("{name}: area"));
    }
}

/// A NaN evap is no evap that step, on every solver path, and the next step carries on.
/// Left unguarded, one path emptied the division into `loss` and another filled it to the
/// top of its routing table, both with every output finite.
#[test]
fn nan_evap_is_no_evap_that_step() {
    for (name, routing) in ROUTINGS {
        let mut model = try_run(&ini("0", routing, 1, &losses("if(sim.day == 3, 0 / 0, 5)", LOSS_TABLE), 4)).unwrap();
        let volume = series(&mut model, "node.reach.volume");
        let loss = series(&mut model, "node.reach.loss");
        assert!(volume.iter().chain(&loss).all(|v| v.is_finite()), "{name}: NaN reached the outputs");
        assert!((volume[0] - DEAD / 1.05).abs() < 1e-9, "{name}: day 1 volume {}", volume[0]);
        assert!((volume[2] - volume[1]).abs() < 1e-9 && loss[2] == 0.0, "{name}: day 3 should lose nothing");
        assert!((volume[3] - volume[2] / 1.05).abs() < 1e-9, "{name}: day 4 volume {}", volume[3]);
    }
}

/// A dry reach stays at exactly zero, with a table that has no pool and so no water to
/// hold: the NLM x < 1 path chased a root at 0 and reported outflow of +-1e-13.
#[test]
fn dry_reach_with_no_pool_is_exactly_zero() {
    for (name, routing) in ROUTINGS {
        let mut model = try_run(&ini("0", routing, 3, &losses("5", "0, 0, 0,\n    100, 0, 1,"), 10)).unwrap();
        for what in ["dsflow", "volume", "loss"] {
            for (t, v) in series(&mut model, &format!("node.reach.{what}")).iter().enumerate() {
                assert_eq!(v.to_bits(), 0.0f64.to_bits(), "{name}: {what} on day {} is {v:e}", t + 1);
            }
        }
    }
}

/// The table is for the whole reach: three divisions draining hold the same total as one.
#[test]
fn table_is_shared_across_divisions() {
    for (name, routing) in ROUTINGS {
        let mut model = try_run(&ini("0", routing, 3, &losses("10", LOSS_TABLE), 4)).unwrap();
        let want: Vec<f64> = (1..=4).map(|t| DEAD / 1.1_f64.powi(t)).collect();
        assert_close(&series(&mut model, "node.reach.volume"), &want, &format!("{name}: volume"));
    }
}

// ============================================================================
// Every solver case
// ============================================================================

/// A dry spell, a flood above the PWL table, then a recession to nothing.
const VARIED_INFLOW: &str = "if(sim.day < 4, 0, if(sim.day < 8, 30, if(sim.day == 8, 1500, if(sim.day < 16, 60, 0))))";

/// Water in = water out + loss + change in storage, and the reach starts with its pool full.
#[test]
fn mass_balance_closes() {
    for (name, routing) in ROUTINGS {
        for n_divs in [1, 4] {
            let mut model = try_run(&ini(VARIED_INFLOW, routing, n_divs, &losses("6", LOSS_TABLE), 30)).unwrap();
            let mut total = |s: &str| series(&mut model, s).iter().sum::<f64>();
            let (inflow, outflow, loss) = (total("node.reach.usflow"), total("node.reach.dsflow"), total("node.reach.loss"));
            let end_volume = *series(&mut model, "node.reach.volume").last().unwrap();
            let residual = DEAD + inflow - outflow - loss - end_volume;
            assert!(residual.abs() < 1e-7, "{name}, {n_divs} divisions: residual {residual}");
        }
    }
}

/// Runs a month of jumpy inflow (dry days, floods above the tables) and the given evap
/// through every solver case and a lag-only reach. Every step must close, with outflow
/// and volume never negative. Whole-run closure would miss a step that over-releases
/// and a later one that makes up for it.
fn assert_every_step_closes(evap_of: fn(u64) -> f64, net_rain: bool) {
    let mut state: u64 = 0x2545_F491_4F6C_DD1D;
    let mut next = || { state = state.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407); state >> 33 };
    let mut csv = String::from("timestamp,inflow,evap
");
    for day in 1..=31 {
        let (r, e) = (next(), next());
        let inflow = if r % 4 == 0 { 0.0 } else { (r % 1200) as f64 + 0.37 };
        csv.push_str(&format!("2020-01-{day:02},{inflow},{}
", evap_of(e)));
    }
    let dir = std::env::temp_dir().join("kalix_tests").join(uuid::Uuid::new_v4().to_string());
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join("series.csv");
    std::fs::write(&path, csv).unwrap();

    let laws = ROUTINGS.iter().copied().chain([("lag only", "lag = 2")]);
    for (name, routing) in laws {
        for n_divs in [1, 3] {
            let ini = ini("data.series_csv.by_name.inflow", routing, n_divs,
                          &losses("data.series_csv.by_name.evap", LOSS_TABLE), 31)
                .replace("[node.src]", &format!("[data]
{}

[node.src]", path.display()));
            let mut model = try_run(&ini).unwrap();
            let usflow = series(&mut model, "node.reach.usflow");
            let dsflow = series(&mut model, "node.reach.dsflow");
            let volume = series(&mut model, "node.reach.volume");
            let loss = series(&mut model, "node.reach.loss");
            assert!(dsflow.iter().any(|&q| q > 0.0) && loss.iter().any(|&l| l > 0.0), "{name}: case not exercised");
            assert_eq!(loss.iter().any(|&l| l < 0.0), net_rain, "{name}, {n_divs} divisions: sign of the loss");
            let mut prev = DEAD;
            for t in 0..usflow.len() {
                let residual = usflow[t] - dsflow[t] - loss[t] - (volume[t] - prev);
                assert!(residual.abs() < 1e-8, "{name}, {n_divs} divisions, step {}: residual {residual}", t + 1);
                assert!(dsflow[t] >= 0.0 && volume[t] >= 0.0, "{name}, {n_divs} divisions, step {}: negative value", t + 1);
                prev = volume[t];
            }
        }
    }
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn every_step_closes_on_a_random_series() {
    assert_every_step_closes(|e| if e % 3 == 0 { 0.0 } else { (e % 12) as f64 + 0.61 }, false);
}

/// Negative evap (net rain) is not the documented use, but a mild one must still close:
/// the loss goes negative and the reach gains water. Mild means 1 + E * slope stays
/// above zero (here the area slopes are 0.01 km2/ML, so E > -100 mm).
#[test]
fn every_step_closes_with_net_rain() {
    assert_every_step_closes(|e| if e % 3 == 0 { -((e % 9) as f64) - 0.4 } else { (e % 12) as f64 + 0.61 }, true);
}

/// With one division, outflow means the pool is full, and nothing is ever negative.
#[test]
fn no_outflow_until_pool_is_full() {
    for (name, routing) in ROUTINGS {
        let mut model = try_run(&ini(VARIED_INFLOW, routing, 1, &losses("6", LOSS_TABLE), 30)).unwrap();
        let dsflow = series(&mut model, "node.reach.dsflow");
        let volume = series(&mut model, "node.reach.volume");
        let loss = series(&mut model, "node.reach.loss");
        assert!(dsflow.iter().any(|&q| q > 0.0) && dsflow.iter().any(|&q| q == 0.0), "{name}: case not exercised");
        for t in 0..dsflow.len() {
            assert!(dsflow[t] >= 0.0 && volume[t] >= 0.0 && loss[t] >= 0.0, "{name}, step {}: negative value", t + 1);
            if dsflow[t] > 0.0 {
                assert!(volume[t] >= DEAD - 1e-9, "{name}, step {}: outflow {} with pool at {}", t + 1, dsflow[t], volume[t]);
            }
        }
    }
}

/// The loss is evap times the recorded area, and at steady flow the outflow is the
/// inflow less the loss, at the area for the reference flow.
#[test]
fn steady_flow_loses_evap_times_area() {
    for (name, routing) in ROUTINGS {
        let x: f64 = if routing.starts_with("x = 1") { 1.0 } else { 0.3 };
        let mut model = try_run(&ini("80", routing, 1, &losses("6", LOSS_TABLE), 90)).unwrap();
        let (dsflow, area, loss) = (series(&mut model, "node.reach.dsflow"), series(&mut model, "node.reach.area"), series(&mut model, "node.reach.loss"));
        let (q, a, l) = (dsflow[89], area[89], loss[89]);
        assert!((dsflow[88] - q).abs() < 1e-9, "{name}: not steady by day 90");
        assert!((l - 6.0 * a).abs() < 1e-9, "{name}: loss {l} is not 6 mm x {a} km2");
        assert!((q - (80.0 - l)).abs() < 1e-9, "{name}: outflow {q}, loss {l}");
        let q_ref = x * 80.0 + (1.0 - x) * q;
        assert!((a - (1.0 + q_ref / 100.0)).abs() < 1e-9, "{name}: area {a} at reference flow {q_ref}");
    }
}

/// With x < 1 the routing storage above the pool is the law at the reference flow,
/// V(q_ref), with the loss folded into the solve. Checked against a reach without
/// losses fed q_ref, whose steady storage is V(q_ref) by definition. Dropping the
/// evaporation term from the PWL quadratic, or the NLM residual, passes every other
/// test: at steady state outflow is inflow less loss whatever q_ref the solver found.
#[test]
fn flowing_storage_is_the_law_at_the_reference_flow() {
    for (name, routing) in ROUTINGS {
        if routing.starts_with("x = 1") {
            continue;
        }
        let mut with = try_run(&ini("80", routing, 1, &losses("6", LOSS_TABLE), 90)).unwrap();
        let (q, v) = (series(&mut with, "node.reach.dsflow")[89], series(&mut with, "node.reach.volume")[89]);
        let q_ref = 0.3 * 80.0 + 0.7 * q;
        let mut without = try_run(&ini(&format!("{q_ref:?}"), routing, 1, "", 90)).unwrap();
        let law = series(&mut without, "node.reach.volume")[89];
        assert!((v - DEAD - law).abs() < 1e-7, "{name}: storage above the pool {} is not V(q_ref) = {law}", v - DEAD);
    }
}

/// A loss table that reaches beyond the top of the routing table: the area still slopes
/// there, and the loss is evap times the area at the reference flow. Every other test
/// has a flat area above the routing table, where a wrong sign cannot show.
#[test]
fn loss_above_the_routing_table_follows_the_sloping_area() {
    const WIDE_TABLE: &str = "0, 0, 0,\n    0, 100, 1,\n    2000, 100, 3,";
    for (name, routing) in ROUTINGS.iter().filter(|(n, _)| n.starts_with("PWL")) {
        let x: f64 = if routing.starts_with("x = 1") { 1.0 } else { 0.3 };
        let mut model = try_run(&ini("1500", routing, 1, &losses("6", WIDE_TABLE), 90)).unwrap();
        let (dsflow, area, loss) = (series(&mut model, "node.reach.dsflow"), series(&mut model, "node.reach.area"), series(&mut model, "node.reach.loss"));
        let (q, a, l) = (dsflow[89], area[89], loss[89]);
        assert!((dsflow[88] - q).abs() < 1e-9, "{name}: not steady by day 90");
        let q_ref = x * 1500.0 + (1.0 - x) * q;
        assert!(q_ref > 1000.0, "{name}: q_ref {q_ref} is not above the routing table");
        assert!((a - (1.0 + 2.0 * q_ref / 2000.0)).abs() < 1e-9, "{name}: area {a} at reference flow {q_ref}");
        assert!((l - 6.0 * a).abs() < 1e-9, "{name}: loss {l} is not 6 mm x {a} km2");
    }
}

/// Flows above the loss table hold the last area.
#[test]
fn area_holds_above_the_table() {
    for (name, routing) in ROUTINGS {
        let mut model = try_run(&ini("5000", routing, 1, &losses("6", LOSS_TABLE), 31)).unwrap();
        let area = series(&mut model, "node.reach.area");
        assert!((area[30] - 2.0).abs() < 1e-9, "{name}: area {}", area[30]);
    }
}

/// A table of zero area and no dead storage leaves the routing as it is without the feature.
#[test]
fn zero_table_matches_no_reach_losses() {
    for (name, routing) in ROUTINGS {
        for n_divs in [1, 4] {
            let mut with = try_run(&ini(VARIED_INFLOW, routing, n_divs, &losses("6", "0, 0, 0,\n    1000, 0, 0,"), 30)).unwrap();
            let mut without = try_run(&ini(VARIED_INFLOW, routing, n_divs, "", 30)).unwrap();
            let (a, b) = (series(&mut with, "node.reach.dsflow"), series(&mut without, "node.reach.dsflow"));
            for t in 0..a.len() {
                assert!((a[t] - b[t]).abs() < 1e-8, "{name}, {n_divs} divisions, step {}: {} vs {}", t + 1, a[t], b[t]);
            }
        }
    }
}

// ============================================================================
// Table and property checks
// ============================================================================

#[test]
fn bad_loss_tables_are_rejected() {
    let cases = [
        ("0, 0, 1,\n 100, 0, 2,", "must begin with flow = 0, dead storage volume = 0, area = 0"),
        ("50, 0, 0,\n 100, 0, 2,", "must begin with flow = 0, dead storage volume = 0, area = 0"),
        ("0, 0, 0,\n 100, 0, -1,", "must be non-negative"),
        ("0, 0, 0,\n 100, 0, 1,\n 50, 0, 2,", "must not decrease"),
        ("0, 0, 0,\n 0, 100, 1,\n 0, 50, 2,\n 100, 50, 2,", "must not decrease"),
        ("0, 0, 0,\n 100, 0, 1,\n 100, 0, 2,", "must be strictly increasing"),
        ("0, 0, 0,\n 100, 0, 2,\n 200, 0, 1,", "areas must be non-decreasing"),
        ("0, 0, 0,\n 0, 100, 1,\n 100, 50, 2,", "must not decrease"),
        ("0, 0, 0,\n 0, 100, 1,\n 100, 100, 2,\n 200, 120, 2,", "must equal the largest zero-flow value"),
    ];
    for (table, want) in cases {
        let err = try_run(&ini("50", PWL_X_UNITY, 1, &losses("10", table), 3)).err()
            .unwrap_or_else(|| panic!("table should be rejected:\n{table}"));
        assert!(err.contains(want), "table:\n{table}\nwant '{want}', got: {err}");
    }
}

#[test]
fn zero_divisions_are_rejected() {
    let err = try_run(&ini("50", PWL_X_UNITY, 0, &losses("5", LOSS_TABLE), 5)).err().expect("n_divs = 0 must fail");
    assert!(err.contains("n_divs must be at least 1"), "{err}");
}

#[test]
fn evap_and_loss_table_must_come_together() {
    for alone in ["evap = 10".to_string(), format!("loss_table = {LOSS_TABLE}")] {
        let err = try_run(&ini("50", PWL_X_UNITY, 1, &alone, 3)).err().expect("should be rejected");
        assert!(err.contains("must be specified together"), "got: {err}");
    }
}

// ============================================================================
// Node variants
// ============================================================================

/// A node built in code must be the variant that matches what it is given.
#[test]
fn variant_must_match_evap_and_loss_table() {
    use crate::data_management::data_cache::DataCache;
    use crate::hydrology::accounts::account_manager::AccountManager;
    use crate::model_inputs::DynamicInput;
    use crate::nodes::routing_node::RoutingNode;
    use crate::nodes::Node;
    use crate::numerical::table::Table;

    fn configure<const U: bool>(evap: bool, table: bool) -> Result<(), String> {
        let mut n = RoutingNode::<U>::new();
        n.name = "reach".to_string();
        if evap { n.evap_mm_input = DynamicInput::Constant { value: 5.0, original: "5".to_string() }; }
        if table { n.loss_table = Table::from_csv_string(LOSS_TABLE, 3, false).unwrap(); }
        n.initialise(&mut DataCache::new(), &mut AccountManager::new())
    }

    assert!(configure::<false>(false, false).is_ok());
    assert!(configure::<true>(true, true).is_ok());
    for (evap, table) in [(true, false), (false, true)] {
        assert!(configure::<false>(evap, table).unwrap_err().contains("must be specified together"));
        assert!(configure::<true>(evap, table).unwrap_err().contains("must be specified together"));
    }
    assert!(configure::<false>(true, true).unwrap_err().contains("must be NodeEnum::RoutingNodeReachLosses"));
    assert!(configure::<true>(false, false).unwrap_err().contains("must be NodeEnum::RoutingNodeReachLosses"));
}

fn ordering_ini(reach_losses: &str) -> String {
    format!(r#"
[kalix]
start = 2022-01-01
end = 2022-03-31

[node.src]
type = inflow
loc = 0, 0
inflow = 0
ds_1 = dam

[node.dam]
type = storage
loc = 0, 10
initial_volume = 50000
dimensions = 0, 0, 0, 0,
             1, 100000, 1, 0,
             1.1, 110000, 1, 100000,
ds_1 = reach

[node.reach]
type = routing
loc = 0, 20
pwl = 0, 2,
      9999, 2,
{reach_losses}
ds_1 = user

[node.user]
type = regulated_user
loc = 0, 30
order = 50
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 40

[outputs]
node.dam.ds_1
node.reach.ds_1_order
node.reach.loss
node.user.diversion
"#)
}

/// Ordering treats both variants alike: the order passes up through the reach unchanged
/// and with the same travel time. The order is not raised for the reach loss, so the
/// delivery falls short of the order by the loss.
#[test]
fn orders_pass_through_a_reach_with_losses_unchanged() {
    let mut plain = try_run(&ordering_ini("")).unwrap();
    let mut lossy = try_run(&ordering_ini(&losses("5", LOSS_TABLE))).unwrap();
    for model in [&mut plain, &mut lossy] {
        assert!(series(model, "node.reach.ds_1_order").iter().all(|&o| o == 50.0));
        assert!(series(model, "node.dam.ds_1").iter().all(|&q| q == 50.0));
        let diversion = series(model, "node.user.diversion");
        assert_eq!(diversion.iter().position(|&d| d > 0.0), Some(2), "first delivery after the 2-day travel time");
    }
    let last = series(&mut plain, "node.user.diversion").len() - 1;
    let loss = series(&mut lossy, "node.reach.loss")[last];
    assert!(loss > 1.0);
    assert!((series(&mut plain, "node.user.diversion")[last] - 50.0).abs() < 1e-6);
    assert!((series(&mut lossy, "node.user.diversion")[last] - (50.0 - loss)).abs() < 1e-6);
}
