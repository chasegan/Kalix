// Systematic scenario battery for storage outlet behaviour under the
// rating-curve semantics (docs/storage_outlet_rating_prototype.md).
//
// Organised to mirror the scenario catalogue agreed 2026-09-02:
//   A. single outlet, step MOL          E. ds_1 spill interplay
//   B. single outlet, MOL + capacity    F. empty storage and the floor
//   C. single outlet, rating table      G. climate feedback
//   D. multi-outlet competition         H. forced releases
//   I. multi-day dynamics               J. configuration and IO
//   K. numerical edges
//
// Every single-step scenario asserts exact mass-balance closure through the
// harness; expected values are hand-derived closed forms, not snapshots.
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::nodes::storage_node::{OutletDefinition, StorageNode};
use crate::nodes::Node;
use crate::model_inputs::dynamic_input::DynamicInput;
use crate::numerical::table::Table;

// Level 0-10 m maps linearly to volume 0-1000 ML; no area, no spill.
const VDIMS: &str = "0, 0, 0, 0, \
                     10, 1000, 0, 0,";
// As VDIMS plus a spillway: spill ramps 0 -> 1000 ML/d across the top 100 ML
// (slope 10 ML/d per ML above FSL = 1,000 ML).
const SPILL_DIMS: &str = "0, 0, 0, 0, \
                          10, 1000, 0, 0, \
                          11, 1100, 0, 1000,";
// As VDIMS plus surface area ramping 0 -> 2 km2 (A(v) = 0.002*v).
const AREA_DIMS: &str = "0, 0, 0, 0, \
                         10, 1000, 2, 0,";

fn node(dims: &str) -> StorageNode {
    let mut n = StorageNode::new();
    n.name = "scenario".to_string();
    n.dimensions = Table::from_csv_string(dims, 4, false).unwrap();
    n
}

fn mol(level: f64) -> OutletDefinition {
    OutletDefinition::OutletWithMOL(level)
}
fn molcap(level: f64, cap: f64) -> OutletDefinition {
    OutletDefinition::OutletWithMOLAndCapacity(level, cap)
}
fn rating(points: &[(f64, f64)]) -> OutletDefinition {
    OutletDefinition::OutletWithRatingTable(points.to_vec())
}

struct StepResult {
    v: f64,
    flows: [f64; 4],
}

/// One flow-phase step with exact mass-balance closure asserted (for the
/// zero-area tables; climate tests account for area themselves).
fn step(node: &mut StorageNode, v0: f64, usflow: f64, orders: [f64; 4]) -> StepResult {
    let r = step_climate(node, v0, usflow, 0.0, 0.0, orders);
    let mbal = v0 + usflow - r.flows.iter().sum::<f64>() - r.v;
    assert!(mbal.abs() < 1e-9, "mass balance residual {mbal}");
    r
}

fn step_climate(node: &mut StorageNode, v0: f64, usflow: f64, rain_mm: f64, evap_mm: f64,
                orders: [f64; 4]) -> StepResult {
    let mut data_cache = DataCache::new();
    data_cache.step_size = 86400;
    let mut accounts = AccountManager::new();
    node.initialise(&mut data_cache, &mut accounts).unwrap();
    node.volume = v0;
    node.rain_mm_input = DynamicInput::Constant { value: rain_mm, original: rain_mm.to_string() };
    node.evap_mm_input = DynamicInput::Constant { value: evap_mm, original: evap_mm.to_string() };
    node.ds_orders_due = orders;
    node.add_usflow(usflow, 0);
    node.run_flow_phase(&mut data_cache, &mut accounts);
    let mut flows = [0.0; 4];
    for (i, f) in flows.iter_mut().enumerate() {
        *f = node.remove_dsflow(i as u8);
    }
    StepResult { v: node.volume, flows }
}

/// Multi-day runner: initialise once, then step with per-day inflows and
/// orders. Returns (volumes, flows) per day and asserts cumulative closure.
fn run_days(node: &mut StorageNode, v0: f64, days: &[(f64, [f64; 4])])
    -> Vec<(f64, [f64; 4])>
{
    let mut data_cache = DataCache::new();
    data_cache.step_size = 86400;
    let mut accounts = AccountManager::new();
    node.initialise(&mut data_cache, &mut accounts).unwrap();
    node.volume = v0;
    let mut out = Vec::new();
    let mut total_in = 0.0;
    let mut total_out = 0.0;
    for &(usflow, orders) in days {
        node.ds_orders_due = orders;
        node.add_usflow(usflow, 0);
        node.run_flow_phase(&mut data_cache, &mut accounts);
        let mut flows = [0.0; 4];
        for (i, f) in flows.iter_mut().enumerate() {
            *f = node.remove_dsflow(i as u8);
        }
        total_in += usflow;
        total_out += flows.iter().sum::<f64>();
        out.push((node.volume, flows));
    }
    let mbal = v0 + total_in - total_out - node.volume;
    assert!(mbal.abs() < 1e-8, "cumulative mass balance residual {mbal}");
    out
}

fn close(a: f64, b: f64) -> bool {
    (a - b).abs() < 1e-9 * (1.0 + b.abs())
}

// ---------------------------------------------------------------------------
// A. Single outlet, step MOL
// ---------------------------------------------------------------------------

/// A4: exactly ON the MOL, the outlet is shut ("0 at the MOL").
#[test]
fn a4_start_exactly_on_mol_releases_nothing() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(5.0); // volume 500
    let r = step(&mut n, 500.0, 0.0, [0.0, 50.0, 0.0, 0.0]);
    assert!(r.flows[1].abs() < 1e-12, "got {}", r.flows[1]);
    assert!(close(r.v, 500.0), "got {}", r.v);
}

/// A5: continuity at the park/clear boundary — demand a hair below, equal
/// to, and above the surplus over the MOL.
#[test]
fn a5_park_boundary_is_continuous() {
    for (d, rel_exp, v_exp) in [(99.9, 99.9, 500.1), (100.0, 100.0, 500.0), (100.1, 100.0, 500.0)] {
        let mut n = node(VDIMS);
        n.outlet_definition[1] = mol(5.0);
        let r = step(&mut n, 600.0, 0.0, [0.0, d, 0.0, 0.0]);
        assert!(close(r.flows[1], rel_exp), "demand {d}: got {}", r.flows[1]);
        assert!(close(r.v, v_exp), "demand {d}: got {}", r.v);
    }
}

/// A6: a tiny demand far above the MOL is met exactly.
#[test]
fn a6_tiny_demand_met() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(5.0);
    let r = step(&mut n, 800.0, 0.0, [0.0, 0.001, 0.0, 0.0]);
    assert!(close(r.flows[1], 0.001), "got {}", r.flows[1]);
}

/// A7: a MOL at the table floor is no constraint at all.
#[test]
fn a7_mol_at_floor_is_unconstrained() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(0.0);
    let r = step(&mut n, 100.0, 0.0, [0.0, 200.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 100.0), "got {}", r.flows[1]);
    assert!(r.v.abs() < 1e-12, "got {}", r.v);
}

/// A9a: moderate evap on a parking day — evap is served from the surplus
/// first and the outlet takes what keeps the level on the MOL.
/// W(500) = 520 - 10mm * A(500)=1km2 = 510, so the outlet gets 10.
#[test]
fn a9_evap_reduces_park_residual() {
    let mut n = node(AREA_DIMS);
    n.outlet_definition[1] = mol(5.0);
    let r = step_climate(&mut n, 520.0, 0.0, 0.0, 10.0, [0.0, 30.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 10.0), "got {}", r.flows[1]);
    assert!(close(r.v, 500.0), "got {}", r.v);
}

/// A9b: heavy evap drags the level below the MOL on its own — the outlet is
/// cut entirely and the equilibrium solves v = 520 - 100mm*0.002v.
#[test]
fn a9_heavy_evap_cuts_outlet() {
    let mut n = node(AREA_DIMS);
    n.outlet_definition[1] = mol(5.0);
    let r = step_climate(&mut n, 520.0, 0.0, 0.0, 100.0, [0.0, 30.0, 0.0, 0.0]);
    assert!(r.flows[1].abs() < 1e-12, "got {}", r.flows[1]);
    assert!(close(r.v, 520.0 / 1.2), "got {}", r.v);
}

// ---------------------------------------------------------------------------
// B. Single outlet, MOL + capacity
// ---------------------------------------------------------------------------

/// B12: the two regimes either side of "surplus vs capacity": with more
/// surplus than capacity the release is capacity-bound and the level ends
/// ABOVE the MOL; with less, the volume parks on the MOL.
#[test]
fn b12_capacity_vs_surplus_regimes() {
    // Surplus 100 > capacity 40: capacity-bound, no park.
    let mut n = node(VDIMS);
    n.outlet_definition[1] = molcap(5.0, 40.0);
    let r = step(&mut n, 600.0, 0.0, [0.0, 200.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 40.0), "got {}", r.flows[1]);
    assert!(close(r.v, 560.0), "got {}", r.v);

    // Surplus 30 < capacity 40: parks with the residual.
    let mut n = node(VDIMS);
    n.outlet_definition[1] = molcap(5.0, 40.0);
    let r = step(&mut n, 530.0, 0.0, [0.0, 200.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 30.0), "got {}", r.flows[1]);
    assert!(close(r.v, 500.0), "got {}", r.v);
}

/// B11: demand under the capacity is met exactly.
#[test]
fn b11_demand_under_capacity_met() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = molcap(5.0, 40.0);
    let r = step(&mut n, 800.0, 0.0, [0.0, 25.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 25.0), "got {}", r.flows[1]);
}

/// B13: capacity zero shuts the outlet permanently.
#[test]
fn b13_zero_capacity_never_flows() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = molcap(5.0, 0.0);
    let r = step(&mut n, 800.0, 0.0, [0.0, 100.0, 0.0, 0.0]);
    assert!(r.flows[1].abs() < 1e-12, "got {}", r.flows[1]);
    assert!(close(r.v, 800.0), "got {}", r.v);
}

/// B14: capacity far above the storage behaves as a bare MOL.
#[test]
fn b14_huge_capacity_equals_bare_mol() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = molcap(5.0, 1e6);
    let r = step(&mut n, 800.0, 0.0, [0.0, 1000.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 300.0), "got {}", r.flows[1]);
    assert!(close(r.v, 500.0), "got {}", r.v);
}

// ---------------------------------------------------------------------------
// C. Single outlet, rating table
// ---------------------------------------------------------------------------

/// C18: a rating segment spanning dimension rows with NONLINEAR level-volume:
/// capacity must interpolate linearly in LEVEL, not volume. Table: level 5 at
/// 100 ML then level 10 at 1,000 ML; rating ramps 0 -> 90 across levels
/// 0 -> 10. Capacity-bound solve on the upper stretch:
/// v = 550 - (45 + 0.05*(v-100))  =>  v = 3600/7... solved: 1.05v = 510.
#[test]
fn c18_rating_composes_through_nonlinear_dims() {
    const BENT_DIMS: &str = "0, 0, 0, 0, 5, 100, 0, 0, 10, 1000, 0, 0,";
    // Demand-bound, far from the curve: released in full.
    let mut n = node(BENT_DIMS);
    n.outlet_definition[1] = rating(&[(0.0, 0.0), (10.0, 90.0)]);
    let r = step(&mut n, 550.0, 0.0, [0.0, 30.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 30.0), "got {}", r.flows[1]);

    // Capacity-bound: release rides cap(level(v)) = 9 * level(v).
    let mut n = node(BENT_DIMS);
    n.outlet_definition[1] = rating(&[(0.0, 0.0), (10.0, 90.0)]);
    let r = step(&mut n, 550.0, 0.0, [0.0, 200.0, 0.0, 0.0]);
    let v_exp = 510.0 / 1.05;
    let level = 5.0 + (v_exp - 100.0) / 180.0;
    assert!(close(r.v, v_exp), "got {}", r.v);
    assert!(close(r.flows[1], 9.0 * level), "got {}", r.flows[1]);
}

/// C19: multi-piece curve (ramp - flat - ramp): equilibria on each piece.
#[test]
fn c19_multi_piece_curve() {
    let curve = [(0.0, 0.0), (2.0, 20.0), (5.0, 20.0), (8.0, 80.0)];
    // Capacity-bound on the flat middle (cap 20 between 200 and 500 ML).
    let mut n = node(VDIMS);
    n.outlet_definition[1] = rating(&curve);
    let r = step(&mut n, 400.0, 0.0, [0.0, 50.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 20.0), "got {}", r.flows[1]);
    assert!(close(r.v, 380.0), "got {}", r.v);

    // Demand-bound on the upper ramp.
    let mut n = node(VDIMS);
    n.outlet_definition[1] = rating(&curve);
    let r = step(&mut n, 900.0, 0.0, [0.0, 70.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 70.0), "got {}", r.flows[1]);
    assert!(close(r.v, 830.0), "got {}", r.v);

    // Capacity-bound above the curve's top: flat 80 governs, v = 900 - 80.
    let mut n = node(VDIMS);
    n.outlet_definition[1] = rating(&curve);
    let r = step(&mut n, 900.0, 0.0, [0.0, 200.0, 0.0, 0.0]);
    assert!(close(r.v, 820.0), "got {}", r.v);
    assert!(close(r.flows[1], 80.0), "got {}", r.flows[1]);

    // Capacity-bound INSIDE the upper ramp: v = 840 - (20 + 0.2*(v-500)),
    // equilibrium at 920/1.2 = 766.67, release = cap at that level.
    let mut n = node(VDIMS);
    n.outlet_definition[1] = rating(&curve);
    let r = step(&mut n, 840.0, 0.0, [0.0, 200.0, 0.0, 0.0]);
    let v_exp = 920.0 / 1.2;
    assert!(close(r.v, v_exp), "got {}", r.v);
    assert!(close(r.flows[1], 840.0 - v_exp), "got {}", r.flows[1]);
}

/// C20: nonzero capacity at the floor: drains fully to empty at a capped rate.
#[test]
fn c20_rating_nonzero_at_floor() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = rating(&[(0.0, 30.0), (10.0, 30.0)]);
    let r = step(&mut n, 20.0, 0.0, [0.0, 100.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 20.0), "got {}", r.flows[1]);
    assert!(r.v.abs() < 1e-12, "got {}", r.v);

    let mut n = node(VDIMS);
    n.outlet_definition[1] = rating(&[(0.0, 30.0), (10.0, 30.0)]);
    let r = step(&mut n, 100.0, 0.0, [0.0, 100.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 30.0), "got {}", r.flows[1]);
    assert!(close(r.v, 70.0), "got {}", r.v);
}

// ---------------------------------------------------------------------------
// D. Multi-outlet competition
// ---------------------------------------------------------------------------

/// D23: equal MOLs whose combined demand fits the shared surplus: both met.
#[test]
fn d23_equal_mols_both_met() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(5.0);
    n.outlet_definition[2] = mol(5.0);
    let r = step(&mut n, 800.0, 0.0, [0.0, 100.0, 150.0, 0.0]);
    assert!(close(r.flows[1], 100.0) && close(r.flows[2], 150.0),
            "got {} / {}", r.flows[1], r.flows[2]);
    assert!(close(r.v, 550.0), "got {}", r.v);
}

/// D25: park on the HIGHER threshold — the lower-MOL outlet is fully met and
/// the higher-MOL outlet takes the residual.
#[test]
fn d25_park_on_higher_threshold() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(6.0); // 600
    n.outlet_definition[2] = mol(3.0); // 300
    let r = step(&mut n, 800.0, 0.0, [0.0, 500.0, 50.0, 0.0]);
    assert!(close(r.flows[2], 50.0), "ds_3 met, got {}", r.flows[2]);
    assert!(close(r.flows[1], 150.0), "ds_2 residual, got {}", r.flows[1]);
    assert!(close(r.v, 600.0), "got {}", r.v);
}

/// D26: park on the LOWER threshold — the higher-MOL outlet gets nothing and
/// the lower-MOL outlet takes the residual.
#[test]
fn d26_park_on_lower_threshold() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(6.0); // 600
    n.outlet_definition[2] = mol(3.0); // 300
    let r = step(&mut n, 800.0, 0.0, [0.0, 100.0, 1000.0, 0.0]);
    assert!(r.flows[1].abs() < 1e-12, "ds_2 cut, got {}", r.flows[1]);
    assert!(close(r.flows[2], 500.0), "ds_3 residual, got {}", r.flows[2]);
    assert!(close(r.v, 300.0), "got {}", r.v);
}

/// D27: three distinct MOLs (700/500/300). Three demand mixes: equilibrium
/// between thresholds, park on the middle one, and a deep cascade where only
/// the lowest outlet flows.
#[test]
fn d27_three_outlet_cascade() {
    let defs = |n: &mut StorageNode| {
        n.outlet_definition[1] = mol(7.0); // 700
        n.outlet_definition[2] = mol(5.0); // 500
        n.outlet_definition[3] = mol(3.0); // 300
    };
    // Equilibrium at 610, between 500 and 700: top outlet cut, others met.
    let mut n = node(VDIMS);
    defs(&mut n);
    let r = step(&mut n, 800.0, 0.0, [0.0, 80.0, 90.0, 100.0]);
    assert!(r.flows[1].abs() < 1e-12 && close(r.flows[2], 90.0) && close(r.flows[3], 100.0),
            "got {:?}", r.flows);
    assert!(close(r.v, 610.0), "got {}", r.v);

    // Park on the middle threshold: lowest met, middle takes the residual.
    let mut n = node(VDIMS);
    defs(&mut n);
    let r = step(&mut n, 800.0, 0.0, [0.0, 80.0, 90.0, 220.0]);
    assert!(r.flows[1].abs() < 1e-12 && close(r.flows[2], 80.0) && close(r.flows[3], 220.0),
            "got {:?}", r.flows);
    assert!(close(r.v, 500.0), "got {}", r.v);

    // Deep cascade: only the lowest outlet still flows at the equilibrium.
    let mut n = node(VDIMS);
    defs(&mut n);
    let r = step(&mut n, 800.0, 0.0, [0.0, 80.0, 90.0, 400.0]);
    assert!(r.flows[1].abs() < 1e-12 && r.flows[2].abs() < 1e-12 && close(r.flows[3], 400.0),
            "got {:?}", r.flows);
    assert!(close(r.v, 400.0), "got {}", r.v);
}

/// D28: three-outlet continuity sweep — sweeping the lowest outlet's demand
/// must move every flow and the volume continuously.
#[test]
fn d28_three_outlet_continuity_sweep() {
    let mut prev: Option<(f64, [f64; 4])> = None;
    for d4_tenths in 0..=420 {
        let d4 = d4_tenths as f64;
        let mut n = node(VDIMS);
        n.outlet_definition[1] = mol(7.0);
        n.outlet_definition[2] = mol(5.0);
        n.outlet_definition[3] = mol(3.0);
        let r = step(&mut n, 800.0, 0.0, [0.0, 80.0, 90.0, d4]);
        if let Some((pv, pf)) = prev {
            assert!((pv - r.v).abs() <= 1.0 + 1e-9,
                    "volume jumped {pv} -> {} at d4={d4}", r.v);
            for i in 0..4 {
                assert!((pf[i] - r.flows[i]).abs() <= 1.0 + 1e-9,
                        "flow {i} jumped {} -> {} at d4={d4}", pf[i], r.flows[i]);
            }
        }
        prev = Some((r.v, r.flows));
    }
}

/// D30: priority at a shared threshold — swapping the demands between ds_2
/// and ds_3 documents that ds_2 is always served first from the residual.
#[test]
fn d30_shared_threshold_priority_swap() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(5.0);
    n.outlet_definition[2] = mol(5.0);
    let r = step(&mut n, 750.0, 0.0, [0.0, 200.0, 100.0, 0.0]);
    assert!(close(r.flows[1], 200.0) && close(r.flows[2], 50.0), "got {:?}", r.flows);

    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(5.0);
    n.outlet_definition[2] = mol(5.0);
    let r = step(&mut n, 750.0, 0.0, [0.0, 100.0, 200.0, 0.0]);
    assert!(close(r.flows[1], 100.0) && close(r.flows[2], 150.0), "got {:?}", r.flows);
}

/// D31: capacities, not MOLs, as the binding constraints on both outlets.
#[test]
fn d31_capacity_sum_binds() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = molcap(1.0, 40.0);
    n.outlet_definition[2] = molcap(1.0, 60.0);
    let r = step(&mut n, 800.0, 0.0, [0.0, 100.0, 100.0, 0.0]);
    assert!(close(r.flows[1], 40.0) && close(r.flows[2], 60.0), "got {:?}", r.flows);
    assert!(close(r.v, 700.0), "got {}", r.v);
}

/// D29: a step MOL parked outlet alongside a rating-taper outlet whose
/// equilibrium sits inside the taper band.
#[test]
fn d29_step_and_taper_interact() {
    // ds_2: bare MOL at 600. ds_3: taper 0 -> 100 across levels 2 -> 4
    // (volumes 200 -> 400). Start 650: ds_2 takes its 30 above the MOL...
    // combined: solve v = 650 - min(30, above-600 stuff)...
    // With d2=30, d3=200: guess v in (400, 600): ds_2 cut (below 600 at end),
    // ds_3 flat cap 100: v = 650 - 100 = 550. Check: ds_2's MOL gate — level
    // ends below 600 so ds_2 released 0; ds_3 capacity-bound at 100.
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(6.0);
    n.outlet_definition[2] = rating(&[(2.0, 0.0), (4.0, 100.0)]);
    let r = step(&mut n, 650.0, 0.0, [0.0, 30.0, 200.0, 0.0]);
    assert!(r.flows[1].abs() < 1e-12, "ds_2 cut, got {}", r.flows[1]);
    assert!(close(r.flows[2], 100.0), "ds_3 at flat cap, got {}", r.flows[2]);
    assert!(close(r.v, 550.0), "got {}", r.v);

    // Deeper draw: equilibrium inside ds_3's taper (below its top at 400):
    // v = 380 - 0.5*(v-200) => 1.5v = 480, release 60 = cap(320).
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(6.0);
    n.outlet_definition[2] = rating(&[(2.0, 0.0), (4.0, 100.0)]);
    let r = step(&mut n, 380.0, 0.0, [0.0, 30.0, 500.0, 0.0]);
    assert!(close(r.v, 320.0), "got {}", r.v);
    assert!(close(r.flows[2], 60.0), "got {}", r.flows[2]);
}

// ---------------------------------------------------------------------------
// E. ds_1 spill interplay
// ---------------------------------------------------------------------------

/// E34: ds_1 with a high MOL near FSL: on a moderate day the outlet takes
/// only the residual above its MOL and no spill develops.
#[test]
fn e34_ds1_high_mol_parks_below_spillway() {
    let mut n = node(SPILL_DIMS);
    n.outlet_definition[0] = mol(9.0); // volume 900
    let r = step(&mut n, 1005.0, 0.0, [200.0, 0.0, 0.0, 0.0]);
    assert!(close(r.flows[0], 105.0), "residual above the MOL, got {}", r.flows[0]);
    assert!(close(r.v, 900.0), "got {}", r.v);
}

/// E34b: same outlet on a big-inflow day: spill covers the order entirely
/// and flows regardless of the MOL. v solves v = 1150 - 10*(v-1000).
#[test]
fn e34_spill_flows_despite_mol() {
    let mut n = node(SPILL_DIMS);
    n.outlet_definition[0] = mol(9.0);
    let r = step(&mut n, 1000.0, 150.0, [50.0, 0.0, 0.0, 0.0]);
    let v_exp = 11150.0 / 11.0;
    assert!(close(r.v, v_exp), "got {}", r.v);
    assert!(close(r.flows[0], 1150.0 - v_exp), "got {}", r.flows[0]);
}

/// E36: capacity-limited top-up alongside spill: ds_1 total is spill plus
/// its capacity when the order exceeds both. v = 1100 - 10*(v-1000).
#[test]
fn e36_capacity_limited_topup_with_spill() {
    let mut n = node(SPILL_DIMS);
    n.outlet_definition[0] = molcap(0.0, 100.0);
    let r = step(&mut n, 1000.0, 200.0, [500.0, 0.0, 0.0, 0.0]);
    let v_exp = 11100.0 / 11.0;
    assert!(close(r.v, v_exp), "got {}", r.v);
    // spill = 10*(v-1000), ds_1 = spill + 100
    assert!(close(r.flows[0], 10.0 * (v_exp - 1000.0) + 100.0), "got {}", r.flows[0]);
}

/// E37: all three outlets demanding over the spillway: total outflow is
/// spill + the capped releases, closing exactly. v = 1170 - 10*(v-1000).
#[test]
fn e37_three_outlets_plus_spill() {
    let mut n = node(SPILL_DIMS);
    n.outlet_definition[1] = mol(5.0);
    n.outlet_definition[2] = molcap(0.0, 30.0);
    let r = step(&mut n, 1000.0, 300.0, [50.0, 100.0, 80.0, 0.0]);
    let v_exp = 11170.0 / 11.0;
    assert!(close(r.v, v_exp), "got {}", r.v);
    let spill = 10.0 * (v_exp - 1000.0);
    assert!(close(r.flows[0], spill), "spill covers ds_1's 50, got {}", r.flows[0]);
    assert!(close(r.flows[1], 100.0) && close(r.flows[2], 30.0), "got {:?}", r.flows);
}

/// E38: a rating table on ds_1 itself. Below FSL the release rides the
/// taper (v = 2170/2.2); over FSL it adds to spill at the flat capacity
/// (v = 11170/11).
#[test]
fn e38_rating_on_ds1_with_spill() {
    let mut n = node(SPILL_DIMS);
    n.outlet_definition[0] = rating(&[(9.0, 0.0), (10.0, 120.0)]);
    let r = step(&mut n, 990.0, 100.0, [300.0, 0.0, 0.0, 0.0]);
    let v_exp = 2170.0 / 2.2;
    assert!(close(r.v, v_exp), "got {}", r.v);
    assert!(close(r.flows[0], 1.2 * (v_exp - 900.0)), "got {}", r.flows[0]);

    let mut n = node(SPILL_DIMS);
    n.outlet_definition[0] = rating(&[(9.0, 0.0), (10.0, 120.0)]);
    let r = step(&mut n, 990.0, 300.0, [300.0, 0.0, 0.0, 0.0]);
    let v_exp = 11170.0 / 11.0;
    assert!(close(r.v, v_exp), "got {}", r.v);
    assert!(close(r.flows[0], 10.0 * (v_exp - 1000.0) + 120.0), "got {}", r.flows[0]);
}

// ---------------------------------------------------------------------------
// F. Empty storage and the floor
// ---------------------------------------------------------------------------

/// F40: two unrestricted outlets jointly overdrawing: priority greedy at the
/// floor, releases sum to exactly the available water.
#[test]
fn f40_joint_overdraw_priority_at_floor() {
    let mut n = node(VDIMS);
    let r = step(&mut n, 600.0, 0.0, [0.0, 300.0, 500.0, 0.0]);
    assert!(close(r.flows[1], 300.0), "ds_2 met first, got {}", r.flows[1]);
    assert!(close(r.flows[2], 300.0), "ds_3 gets what's left, got {}", r.flows[2]);
    assert!(r.v.abs() < 1e-12, "got {}", r.v);
}

/// F41: a free outlet drains past a sibling's MOL toward empty; the MOL
/// outlet releases nothing once the level passes its sill.
#[test]
fn f41_free_outlet_drains_past_sibling_mol() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(5.0);
    let r = step(&mut n, 800.0, 0.0, [0.0, 100.0, 700.0, 0.0]);
    assert!(r.flows[1].abs() < 1e-12, "ds_2 cut, got {}", r.flows[1]);
    assert!(close(r.flows[2], 700.0), "got {}", r.flows[2]);
    assert!(close(r.v, 100.0), "got {}", r.v);

    // Deeper: past the MOL to a bone-dry floor.
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(5.0);
    let r = step(&mut n, 800.0, 0.0, [0.0, 100.0, 900.0, 0.0]);
    assert!(r.flows[1].abs() < 1e-12 && close(r.flows[2], 800.0), "got {:?}", r.flows);
    assert!(r.v.abs() < 1e-12, "got {}", r.v);
}

/// F42: an already-empty storage with demands releases nothing and never
/// goes negative.
#[test]
fn f42_empty_storage_stays_empty() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(5.0);
    let r = step(&mut n, 0.0, 0.0, [0.0, 100.0, 100.0, 0.0]);
    assert!(r.flows.iter().all(|f| f.abs() < 1e-12), "got {:?}", r.flows);
    assert!(r.v.abs() < 1e-12, "got {}", r.v);
}

/// F43: an empty storage releases from same-day inflow, MOL respected.
#[test]
fn f43_empty_plus_inflow_releases_same_day() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(1.0); // 100
    let r = step(&mut n, 0.0, 250.0, [0.0, 100.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 100.0), "got {}", r.flows[1]);
    assert!(close(r.v, 150.0), "got {}", r.v);
}

/// F44: the Talgai degenerate floor (dVol ~ net_rain*dArea) with a MOL at
/// the standing level: the huge order takes only the day's surplus, parks on
/// the MOL, and nothing explodes.
#[test]
fn f44_talgai_floor_with_mol() {
    const TALGAI_DIMS: &str = "0, 0, 0, 0, \
                               406.5, 0.001, 0.01, 0, \
                               407, 1, 0.01, 0, \
                               408, 25, 0.05, 0, \
                               409.1, 92, 0.09, 0, \
                               410, 215, 0.15, 0, \
                               411, 395, 0.21, 0, \
                               412, 640, 0.28, 0, \
                               413, 985, 0.42, 1.00E+09,";
    let mut n = node(TALGAI_DIMS);
    n.outlet_definition[0] = mol(409.1); // volume 92
    let r = step_climate(&mut n, 92.0, 0.891536, 4.3, 4.2, [1683.72, 0.0, 0.0, 0.0]);
    assert!(r.v.is_finite() && r.flows[0].is_finite(), "must not explode");
    assert!(close(r.v, 92.0), "parks on the MOL, got {}", r.v);
    assert!(r.flows[0] > 0.8 && r.flows[0] < 1.0,
            "releases the day's surplus (~0.9), got {}", r.flows[0]);
}

// ---------------------------------------------------------------------------
// G. Climate feedback (and the pond ruling)
// ---------------------------------------------------------------------------

/// G45: rain adds to the park residual: W(500) = 700 + 50mm * A(500)=1km2.
#[test]
fn g45_rain_adds_park_headroom() {
    let mut n = node(AREA_DIMS);
    n.outlet_definition[1] = mol(5.0);
    let r = step_climate(&mut n, 700.0, 0.0, 50.0, 0.0, [0.0, 300.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 250.0), "got {}", r.flows[1]);
    assert!(close(r.v, 500.0), "got {}", r.v);
}

/// G47: rain on an empty zero-area storage creates no phantom water.
#[test]
fn g47_rain_on_empty_zero_area() {
    let mut n = node(AREA_DIMS);
    n.outlet_definition[1] = mol(5.0);
    let r = step_climate(&mut n, 0.0, 0.0, 100.0, 0.0, [0.0, 50.0, 0.0, 0.0]);
    assert!(r.v.abs() < 1e-9 && r.flows.iter().all(|f| f.abs() < 1e-12),
            "got v={} flows={:?}", r.v, r.flows);
}

/// G48 (ruled intentional 2026-09-02): pond diversion has absolute priority
/// and may draw the storage below every outlet MOL; the outlets then see
/// only what remains.
#[test]
fn g48_pond_priority_over_mols() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(5.0);
    n.pond_demand_input = DynamicInput::Constant { value: 400.0, original: "400".to_string() };
    let mut data_cache = DataCache::new();
    data_cache.step_size = 86400;
    let mut accounts = AccountManager::new();
    n.initialise(&mut data_cache, &mut accounts).unwrap();
    n.volume = 600.0;
    n.ds_orders_due = [0.0, 100.0, 0.0, 0.0];
    n.run_flow_phase(&mut data_cache, &mut accounts);
    let ds2 = n.remove_dsflow(1);
    assert!(ds2.abs() < 1e-12, "outlet below its MOL after the pond took 400, got {ds2}");
    assert!(close(n.volume, 200.0), "got {}", n.volume);
}

// ---------------------------------------------------------------------------
// H. Forced releases
// ---------------------------------------------------------------------------

/// H50: a forced release is capped by the outlet capacity.
#[test]
fn h50_forced_release_capped_by_capacity() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = molcap(2.0, 60.0);
    n.ds_force_release_input[1] = DynamicInput::Constant { value: 200.0, original: "200".to_string() };
    let r = step(&mut n, 800.0, 0.0, [0.0, 0.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 60.0), "got {}", r.flows[1]);
    assert!(close(r.v, 740.0), "got {}", r.v);
}

/// H51: spill counts toward a ds_1 forced release exactly as it does toward
/// an order: when spill exceeds the forced amount, the controlled outlet
/// contributes nothing. v = 1200 - 10*(v-1000).
#[test]
fn h51_spill_counts_toward_forced_release() {
    let mut n = node(SPILL_DIMS);
    n.ds_force_release_input[0] = DynamicInput::Constant { value: 30.0, original: "30".to_string() };
    let r = step(&mut n, 1000.0, 200.0, [0.0, 0.0, 0.0, 0.0]);
    let v_exp = 11200.0 / 11.0;
    assert!(close(r.v, v_exp), "got {}", r.v);
    assert!(close(r.flows[0], 1200.0 - v_exp),
            "ds_1 is spill only, not spill + forced, got {}", r.flows[0]);
}

/// H52: a forced release exceeding the storage drains it exactly to empty.
#[test]
fn h52_forced_release_exceeding_storage() {
    let mut n = node(VDIMS);
    n.ds_force_release_input[1] = DynamicInput::Constant { value: 1000.0, original: "1000".to_string() };
    let r = step(&mut n, 300.0, 0.0, [0.0, 0.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 300.0), "got {}", r.flows[1]);
    assert!(r.v.abs() < 1e-12, "got {}", r.v);
}

/// H53: a NaN forced release releases nothing (defensive guard).
#[test]
fn h53_nan_forced_release_is_zero() {
    let mut n = node(VDIMS);
    n.ds_force_release_input[1] = DynamicInput::Constant { value: f64::NAN, original: "nan".to_string() };
    let r = step(&mut n, 800.0, 0.0, [0.0, 0.0, 0.0, 0.0]);
    assert!(r.flows[1].abs() < 1e-12, "got {}", r.flows[1]);
    assert!(close(r.v, 800.0), "got {}", r.v);
}

// ---------------------------------------------------------------------------
// I. Multi-day dynamics
// ---------------------------------------------------------------------------

/// I54: draw down to the MOL and hold there — no drift once parked.
#[test]
fn i54_drawdown_then_hold_at_park() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(5.0);
    let orders = [0.0, 100.0, 0.0, 0.0];
    let days = vec![(0.0, orders); 5];
    let out = run_days(&mut n, 700.0, &days);
    let expect = [(600.0, 100.0), (500.0, 100.0), (500.0, 0.0), (500.0, 0.0), (500.0, 0.0)];
    for (day, (&(v, flows), &(ve, fe))) in out.iter().zip(expect.iter()).enumerate() {
        assert!(close(v, ve), "day {day}: volume {v}, expected {ve}");
        assert!(close(flows[1], fe), "day {day}: flow {}, expected {fe}", flows[1]);
    }
}

/// I55: pulse inflows across the MOL every other day: clean alternation,
/// no chattering, warm start lands the right answer every day.
#[test]
fn i55_pulse_inflow_across_mol() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(5.0);
    let orders = [0.0, 100.0, 0.0, 0.0];
    let days: Vec<(f64, [f64; 4])> = (0..8)
        .map(|i| (if i % 2 == 0 { 100.0 } else { 0.0 }, orders))
        .collect();
    let out = run_days(&mut n, 500.0, &days);
    for (day, &(v, flows)) in out.iter().enumerate() {
        let (ve, fe) = if day % 2 == 0 { (500.0, 100.0) } else { (500.0, 0.0) };
        assert!(close(v, ve) && close(flows[1], fe),
                "day {day}: v={v} flow={}, expected v={ve} flow={fe}", flows[1]);
    }
}

/// I56: refill from below the MOL up through it: the outlet resumes as soon
/// as (and only as far as) water stands above the sill.
#[test]
fn i56_refill_through_mol() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(5.0);
    let orders = [0.0, 50.0, 0.0, 0.0];
    let out = run_days(&mut n, 300.0, &vec![(150.0, orders); 3]);
    let expect = [(450.0, 0.0), (550.0, 50.0), (650.0, 50.0)];
    for (day, (&(v, flows), &(ve, fe))) in out.iter().zip(expect.iter()).enumerate() {
        assert!(close(v, ve), "day {day}: volume {v}, expected {ve}");
        assert!(close(flows[1], fe), "day {day}: flow {}, expected {fe}", flows[1]);
    }
}

/// I57: a 200-day pseudo-random workout over spill, MOL, and capacity —
/// cumulative closure to 1e-8 (asserted by the runner) and no negatives.
#[test]
fn i57_long_run_closure() {
    let mut n = node(SPILL_DIMS);
    n.outlet_definition[0] = mol(2.0);
    n.outlet_definition[1] = molcap(5.0, 80.0);
    n.outlet_definition[2] = rating(&[(1.0, 0.0), (3.0, 40.0)]);
    // Deterministic pseudo-random inflows/orders (LCG).
    let mut seed: u64 = 42;
    let mut rnd = move || {
        seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        ((seed >> 33) % 1000) as f64
    };
    let days: Vec<(f64, [f64; 4])> = (0..200)
        .map(|_| {
            let inflow = rnd() * 0.4;
            let orders = [rnd() * 0.1, rnd() * 0.15, rnd() * 0.08, 0.0];
            (inflow, orders)
        })
        .collect();
    let out = run_days(&mut n, 600.0, &days);
    for (day, &(v, flows)) in out.iter().enumerate() {
        assert!(v >= 0.0 && v.is_finite(), "day {day}: bad volume {v}");
        assert!(flows.iter().all(|f| *f >= 0.0 && f.is_finite()),
                "day {day}: bad flows {flows:?}");
    }
}

// ---------------------------------------------------------------------------
// J. Configuration and IO
// ---------------------------------------------------------------------------

/// J58: the MOL and MOL+capacity forms survive an INI round trip.
#[test]
fn j58_mol_forms_round_trip() {
    use crate::io::ini_model_io::IniModelIO;
    use crate::nodes::NodeEnum;
    for (line, expected) in [
        ("ds_2_outlet = 1.5\n", mol(1.5)),
        ("ds_2_outlet = 1.5, 120\n", molcap(1.5, 120.0)),
    ] {
        let ini = format!(
            "[kalix]\n\n[node.dam]\ntype = storage\nloc = 0, 0\ninitial_volume = 100\n\
             dimensions = 0, 0, 0, 0,\n\x2010, 1000, 1, 0,\n{line}");
        let outlet_of = |m: &crate::model::Model| match m.get_node("dam").unwrap() {
            NodeEnum::StorageNode(n) => n.outlet_definition[1].clone(),
            _ => panic!("not a storage"),
        };
        let m1 = IniModelIO::read_model_string(&ini).unwrap();
        assert_eq!(outlet_of(&m1), expected);
        let m2 = IniModelIO::read_model_string(&IniModelIO::model_to_string(&m1)).unwrap();
        assert_eq!(outlet_of(&m2), expected, "round trip failed for {line:?}");
    }
}

/// J59: malformed outlet definitions are rejected at parse.
#[test]
fn j59_malformed_outlet_definitions_rejected() {
    use crate::io::ini_model_io::IniModelIO;
    for bad in [
        "ds_2_outlet = 1, 2, 3\n",           // odd value count
        "ds_2_outlet = 5, 10, 4, 20\n",      // decreasing levels
        "ds_2_outlet = 1, 10, 2, -5\n",      // negative capacity
        "ds_2_outlet = 1, 100, 2, 50\n",     // decreasing capacity
    ] {
        let ini = format!(
            "[kalix]\n\n[node.dam]\ntype = storage\nloc = 0, 0\ninitial_volume = 100\n\
             dimensions = 0, 0, 0, 0,\n\x2010, 1000, 1, 0,\n{bad}");
        assert!(IniModelIO::read_model_string(&ini).is_err(),
                "expected a parse error for {bad:?}");
    }
}

/// J61: an outlet definition with no demand on its link is a harmless no-op.
#[test]
fn j61_outlet_without_demand_is_noop() {
    let mut n = node(VDIMS);
    n.outlet_definition[2] = mol(5.0); // ds_3, never ordered from
    let r = step(&mut n, 800.0, 0.0, [0.0, 100.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 100.0) && r.flows[2].abs() < 1e-12, "got {:?}", r.flows);
    assert!(close(r.v, 700.0), "got {}", r.v);
}

/// J63: exists = 0 dumps everything through ds_1, ignoring every outlet
/// curve (documented pass-through semantics).
#[test]
fn j63_exists_zero_ignores_outlets() {
    let mut n = node(VDIMS);
    n.outlet_definition[0] = mol(9.0);
    n.outlet_definition[1] = mol(5.0);
    n.exists = DynamicInput::Constant { value: 0.0, original: "0".to_string() };
    let r = step(&mut n, 800.0, 50.0, [10.0, 100.0, 0.0, 0.0]);
    assert!(close(r.flows[0], 850.0), "full dump through ds_1, got {}", r.flows[0]);
    assert!(r.flows[1].abs() < 1e-12 && r.v.abs() < 1e-12, "got {:?} v={}", r.flows, r.v);
}

// ---------------------------------------------------------------------------
// K. Numerical edges
// ---------------------------------------------------------------------------

/// K64: gigalitre-scale volumes park to sub-millilitre precision.
#[test]
fn k64_huge_volume_parking_precision() {
    const BIG_DIMS: &str = "0, 0, 0, 0, 100, 1e9, 0, 0,";
    let mut n = node(BIG_DIMS);
    n.outlet_definition[1] = mol(50.0); // volume 5e8
    let r = step(&mut n, 6e8, 0.0, [0.0, 2e8, 0.0, 0.0]);
    assert!((r.v - 5e8).abs() < 1e-3, "got {}", r.v);
    assert!((r.flows[1] - 1e8).abs() < 1e-3, "got {}", r.flows[1]);
}

/// K65: a MOL sitting exactly on a dimension row (bracket boundary).
#[test]
fn k65_mol_on_dimension_row() {
    const ROWED_DIMS: &str = "0, 0, 0, 0, 5, 500, 0, 0, 10, 1000, 0, 0,";
    let mut n = node(ROWED_DIMS);
    n.outlet_definition[1] = mol(5.0); // exactly the middle row
    let r = step(&mut n, 800.0, 0.0, [0.0, 400.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 300.0), "got {}", r.flows[1]);
    assert!(close(r.v, 500.0), "got {}", r.v);
}

/// K66: the jump sits exactly at the solution segment's lower bound (the
/// walk's first park check).
#[test]
fn k66_jump_at_segment_lower_bound() {
    const ROWED_DIMS: &str = "0, 0, 0, 0, 5, 500, 0, 0, 10, 1000, 0, 0,";
    let mut n = node(ROWED_DIMS);
    n.outlet_definition[1] = mol(5.0);
    let r = step(&mut n, 700.0, 0.0, [0.0, 250.0, 0.0, 0.0]);
    assert!(close(r.flows[1], 200.0), "got {}", r.flows[1]);
    assert!(close(r.v, 500.0), "got {}", r.v);
}

/// K67: a MOL inside a 1e9-slope spill segment: the equilibrium sits a
/// whisker over FSL, below the MOL, so the outlet is cut while the spill
/// passes the inflow.
#[test]
fn k67_mol_inside_steep_spill_segment() {
    const STEEP_DIMS: &str = "0, 0, 0, 0, 10, 1000, 0, 0, 10.001, 1001, 0, 1e9,";
    let mut n = node(STEEP_DIMS);
    n.outlet_definition[1] = mol(10.0005); // volume 1000.5, mid spill segment
    let r = step(&mut n, 1000.0, 100.0, [0.0, 50.0, 0.0, 0.0]);
    assert!(r.flows[1].abs() < 1e-12, "outlet cut below its MOL, got {}", r.flows[1]);
    assert!((r.flows[0] - 100.0).abs() < 0.01, "spill passes ~the inflow, got {}", r.flows[0]);
    assert!(r.v >= 1000.0 && r.v < 1000.001, "got {}", r.v);
}

/// K68: a negative order due (bad upstream data) is treated as zero.
#[test]
fn k68_negative_order_is_zero() {
    let mut n = node(VDIMS);
    n.outlet_definition[1] = mol(5.0);
    let r = step(&mut n, 800.0, 0.0, [0.0, -50.0, 0.0, 0.0]);
    assert!(r.flows[1].abs() < 1e-12, "got {}", r.flows[1]);
    assert!(close(r.v, 800.0), "got {}", r.v);
}
