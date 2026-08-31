// Tests for storage outlet minimum operating levels (MOLs) under the
// access-based semantics (docs/storage_mol_semantics.md): an outlet may only
// be supplied from water stored above its own MOL, contention resolves by
// outlet priority, and mass balance closes by construction.
//
// The two-way scenario mirrors the TwoWayStorage model reported by Aaron Trim
// (2026-08-18): a big order pinned at a high MOL must not clamp a small order
// on another outlet (starvation), nor set the storage to the small order's
// MOL without a corresponding release (mass balance error).
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::nodes::storage_node::{OutletDefinition, StorageNode};
use crate::nodes::Node;
use crate::model_inputs::dynamic_input::DynamicInput;
use crate::numerical::table::Table;

// Simple linear storage: level 0-10 m, volume 0-100 ML, no area, no spill.
const LINEAR_DIMS: &str = "0, 0, 0, 0, \
                           10, 100, 0, 0,";

// The TwoWayStorage dimensions: FSL at 7,000 ML, spill ramping above it.
const TWO_WAY_DIMS: &str = "0,  0,    0, 0, \
                            10, 7000, 1, 0, \
                            11, 7100, 1, 1000,";

fn storage(dims: &str, outlets: &[(usize, f64)]) -> StorageNode {
    let mut n = StorageNode::new();
    n.name = "test_storage".to_string();
    n.dimensions = Table::from_csv_string(dims, 4, false).unwrap();
    for &(i_outlet, mol_level) in outlets {
        n.outlet_definition[i_outlet] = OutletDefinition::OutletWithMOL(mol_level);
    }
    n
}

/// Runs one flow-phase step. Returns (v_final, ds_flows[4], spill).
fn run_step(node: &mut StorageNode,
            v_start: f64,
            usflow: f64,
            rain_mm: f64,
            evap_mm: f64,
            orders_due: [f64; 4]) -> (f64, [f64; 4], f64) {
    let mut data_cache = DataCache::new();
    data_cache.step_size = 86400;
    let mut accounts = AccountManager::new();
    node.initialise(&mut data_cache, &mut accounts).unwrap();
    node.volume = v_start;
    node.rain_mm_input = DynamicInput::Constant { value: rain_mm, original: rain_mm.to_string() };
    node.evap_mm_input = DynamicInput::Constant { value: evap_mm, original: evap_mm.to_string() };
    node.ds_orders_due = orders_due;
    node.add_usflow(usflow, 0);
    node.run_flow_phase(&mut data_cache, &mut accounts);
    let mut flows = [0.0; 4];
    for (i, f) in flows.iter_mut().enumerate() {
        *f = node.remove_dsflow(i as u8);
    }
    // ds_1 total minus its controlled component is the spill; recompute the
    // spill from the dimensions instead of poking private state.
    let spill = node.dimensions.interpolate(1, 3, node.volume).max(0.0);
    (node.volume, flows, spill)
}

/// TwoWayStorage, net-evap day: ds_1 (MOL 700 ML) orders 8, ds_2 (MOL 7,000
/// ML, i.e. at FSL) orders 1e6. The huge ds_2 order must neither clamp ds_1's
/// release to ds_2's MOL, nor crash the volume to ds_1's MOL with no release.
#[test]
fn test_two_way_big_order_does_not_starve_small_order() {
    let mut node = storage(TWO_WAY_DIMS, &[(0, 1.0), (1, 10.0)]);
    let (v, flows, _) = run_step(&mut node, 7000.0, 0.0, 0.0, 4.6, [8.0, 1e6, 0.0, 0.0]);

    assert!((flows[0] - 8.0).abs() < 1e-9,
            "ds_1's small order must be met from below ds_2's MOL, got {}", flows[0]);
    assert!(flows[1].abs() < 1e-9,
            "no water stands above ds_2's MOL on a net-evap day, got {}", flows[1]);
    // Exact solution of v = 7000 - 4.6*(v/7000) - 8:
    let v_expected = 6992.0 / (1.0 + 4.6 / 7000.0);
    assert!((v - v_expected).abs() < 1e-6, "expected {v_expected}, got {v}");
    // Mass balance: nothing may vanish (the original bug lost 6,292 ML in a day).
    let area = v / 7000.0;
    let mbal = 7000.0 - 4.6 * area - flows.iter().sum::<f64>() - v;
    assert!(mbal.abs() < 1e-6, "mass balance must close, residual {mbal}");
}

/// TwoWayStorage, inflow day: ds_2 releases exactly the surplus above its
/// MOL, ds_1 still gets its order from deeper water.
#[test]
fn test_two_way_inflow_day_releases_surplus_above_mol() {
    let mut node = storage(TWO_WAY_DIMS, &[(0, 1.0), (1, 10.0)]);
    let (v, flows, _) = run_step(&mut node, 7000.0, 30.0, 0.0, 0.0, [8.0, 1e6, 0.0, 0.0]);

    assert!((flows[0] - 8.0).abs() < 1e-9, "ds_1 order met, got {}", flows[0]);
    assert!((flows[1] - 30.0).abs() < 1e-6,
            "ds_2 releases the surplus above its MOL, got {}", flows[1]);
    assert!((v - 6992.0).abs() < 1e-6, "expected 6992, got {v}");
    let mbal = 7030.0 - flows.iter().sum::<f64>() - v;
    assert!(mbal.abs() < 1e-9, "mass balance must close, residual {mbal}");
}

/// An unrestricted outlet must not be starved by a sibling's MOL clamp, and
/// a MOL outlet starting above its MOL must not be zeroed because the end-of-
/// step volume lands below it (both failure modes of the active-set solver).
#[test]
fn test_mol_outlet_and_unrestricted_outlet_both_served() {
    // ds_2: MOL at level 5 (volume 50), order 30. ds_3: no MOL, order 40.
    let mut node = storage(LINEAR_DIMS, &[(1, 5.0)]);
    let (v, flows, _) = run_step(&mut node, 80.0, 0.0, 0.0, 0.0, [0.0, 30.0, 40.0, 0.0]);

    assert!((flows[1] - 30.0).abs() < 1e-9,
            "ds_2 draws its full order from above its MOL, got {}", flows[1]);
    assert!((flows[2] - 40.0).abs() < 1e-9,
            "unrestricted ds_3 must not be starved, got {}", flows[2]);
    assert!((v - 10.0).abs() < 1e-9, "expected 10, got {v}");
}

/// Releases must be continuous in the sibling's order: sweeping ds_3's order
/// must never flip ds_2's release (the active-set solver jumped it 30 -> 0
/// when ds_3's order crossed 30).
#[test]
fn test_release_continuous_in_sibling_order() {
    let mut prev_v = f64::NAN;
    for ds3_order_tenths in 0..=600 {
        let ds3_order = ds3_order_tenths as f64 * 0.1;
        let mut node = storage(LINEAR_DIMS, &[(1, 5.0)]);
        let (v, flows, _) = run_step(&mut node, 80.0, 0.0, 0.0, 0.0, [0.0, 30.0, ds3_order, 0.0]);

        // ds_2's access (80 - 50 = 30 ML above its MOL) never shrinks.
        assert!((flows[1] - 30.0).abs() < 1e-9,
                "ds_2 must stay at 30 for ds_3 order {ds3_order}, got {}", flows[1]);
        // ds_3 gets its order until only ds_2-inaccessible water remains.
        let ds3_expected = ds3_order.min(50.0);
        assert!((flows[2] - ds3_expected).abs() < 1e-9,
                "ds_3 expected {ds3_expected} at order {ds3_order}, got {}", flows[2]);
        // Volume moves continuously (never jumps more than the order step).
        if prev_v.is_finite() {
            assert!((prev_v - v) <= 0.1 + 1e-9,
                    "volume jumped from {prev_v} to {v} at ds_3 order {ds3_order}");
        }
        prev_v = v;
        let mbal = 80.0 - flows.iter().sum::<f64>() - v;
        assert!(mbal.abs() < 1e-9, "mass balance residual {mbal} at ds_3 order {ds3_order}");
    }
}

/// A single MOL outlet drains exactly to its MOL, no further.
#[test]
fn test_single_outlet_clamps_at_mol() {
    let mut node = storage(LINEAR_DIMS, &[(1, 5.0)]);
    let (v, flows, _) = run_step(&mut node, 80.0, 0.0, 0.0, 0.0, [0.0, 60.0, 0.0, 0.0]);
    assert!((flows[1] - 30.0).abs() < 1e-9, "got {}", flows[1]);
    assert!((v - 50.0).abs() < 1e-9, "expected the MOL volume 50, got {v}");
}

/// Below its MOL an outlet releases nothing.
#[test]
fn test_outlet_below_mol_releases_nothing() {
    let mut node = storage(LINEAR_DIMS, &[(1, 5.0)]);
    let (v, flows, _) = run_step(&mut node, 40.0, 0.0, 0.0, 0.0, [0.0, 10.0, 0.0, 0.0]);
    assert!(flows[1].abs() < 1e-12, "got {}", flows[1]);
    assert!((v - 40.0).abs() < 1e-9, "volume must be untouched, got {v}");
}

/// Today's inflow counts as accessible water: a storage below an outlet's MOL
/// that receives inflow lifting it above can release today.
#[test]
fn test_inflow_lifting_above_mol_is_accessible() {
    let mut node = storage(LINEAR_DIMS, &[(1, 5.0)]);
    let (v, flows, _) = run_step(&mut node, 40.0, 30.0, 0.0, 0.0, [0.0, 10.0, 0.0, 0.0]);
    assert!((flows[1] - 10.0).abs() < 1e-9, "got {}", flows[1]);
    assert!((v - 60.0).abs() < 1e-9, "expected 60, got {v}");
}

/// Outlets sharing a MOL threshold resolve by priority (ds_2 before ds_3).
#[test]
fn test_shared_mol_resolves_by_priority() {
    let mut node = storage(LINEAR_DIMS, &[(1, 5.0), (2, 5.0)]);
    let (v, flows, _) = run_step(&mut node, 80.0, 0.0, 0.0, 0.0, [0.0, 30.0, 30.0, 0.0]);
    assert!((flows[1] - 30.0).abs() < 1e-9, "ds_2 has priority, got {}", flows[1]);
    assert!(flows[2].abs() < 1e-9, "ds_3 yields to ds_2, got {}", flows[2]);
    assert!((v - 50.0).abs() < 1e-9, "expected 50, got {v}");
}

/// Overdraw with no MOL drains exactly to empty and releases exactly what was
/// available — never more, and nothing vanishes.
#[test]
fn test_overdraw_drains_exactly_to_empty() {
    let mut node = storage(LINEAR_DIMS, &[]);
    let (v, flows, _) = run_step(&mut node, 30.0, 0.0, 0.0, 0.0, [100.0, 0.0, 0.0, 0.0]);
    assert!((flows[0] - 30.0).abs() < 1e-12, "got {}", flows[0]);
    assert!(v.abs() < 1e-12, "expected empty, got {v}");
}

/// Spill consumes headroom too: with the storage pushed over FSL, an outlet
/// whose MOL sits at FSL only accesses the surplus net of spill.
#[test]
fn test_spill_counts_against_mol_headroom() {
    // Inflow 100 onto a full storage; ds_2 (MOL at FSL = 7,000) orders 200.
    // Equilibrium rests exactly on the MOL: ds_2 takes the whole surplus, no
    // spill develops.
    let mut node = storage(TWO_WAY_DIMS, &[(1, 10.0)]);
    let (v, flows, spill) = run_step(&mut node, 7000.0, 100.0, 0.0, 0.0, [0.0, 200.0, 0.0, 0.0]);
    assert!((flows[1] - 100.0).abs() < 1e-6, "ds_2 takes the surplus, got {}", flows[1]);
    assert!(spill.abs() < 1e-6, "no spill at FSL, got {spill}");
    assert!((v - 7000.0).abs() < 1e-6, "expected FSL volume 7000, got {v}");
    let mbal = 7100.0 - flows.iter().sum::<f64>() - v;
    assert!(mbal.abs() < 1e-9, "mass balance residual {mbal}");

    // Same day but a small ds_2 order: the storage rises over FSL and spills;
    // ds_2's access is the surplus net of that spill, which its order fits
    // inside. Solve v = 7100 - spill(v) - 20 with spill = 10*(v - 7000).
    let mut node = storage(TWO_WAY_DIMS, &[(1, 10.0)]);
    let (v, flows, spill) = run_step(&mut node, 7000.0, 100.0, 0.0, 0.0, [0.0, 20.0, 0.0, 0.0]);
    let v_expected = 77080.0 / 11.0;
    assert!((flows[1] - 20.0).abs() < 1e-6, "ds_2 order met, got {}", flows[1]);
    assert!((v - v_expected).abs() < 1e-6, "expected {v_expected}, got {v}");
    assert!((spill - 10.0 * (v_expected - 7000.0)).abs() < 1e-6, "got spill {spill}");
    let mbal = 7100.0 - flows.iter().sum::<f64>() - v;
    assert!(mbal.abs() < 1e-6, "mass balance residual {mbal}");
}

/// A forced release follows the same access rule: it cannot pull from below
/// its outlet's MOL.
#[test]
fn test_forced_release_respects_mol() {
    let mut node = storage(LINEAR_DIMS, &[(1, 5.0)]);
    node.ds_force_release_input[1] = DynamicInput::Constant { value: 60.0, original: "60".to_string() };
    let (v, flows, _) = run_step(&mut node, 80.0, 0.0, 0.0, 0.0, [0.0, 0.0, 0.0, 0.0]);
    assert!((flows[1] - 30.0).abs() < 1e-9,
            "forced release capped at water above the MOL, got {}", flows[1]);
    assert!((v - 50.0).abs() < 1e-9, "expected 50, got {v}");
}
