// Tests for storage outlet minimum operating levels (MOLs).
//
// PROTOTYPE BRANCH: these expectations follow the rating-curve semantics —
// each outlet's capacity is a (step) function of the end-of-step level, zero
// at/below its MOL, with the volume parking exactly on a threshold when the
// root falls inside the step's jump. Tests whose outcomes differ from the
// access-based semantics on feat/storage-mol-redesign say so in their doc
// comments; the rest agree under both.
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

/// TwoWayStorage, inflow day. RATING SEMANTICS: the volume parks exactly on
/// ds_2's MOL (7,000) and ds_2 gets the residual after ds_1's release — 22,
/// not the full 30 surplus the access semantics granted (where ds_1's 8 was
/// booked against deeper water and the volume ended at 6,992).
#[test]
fn test_two_way_inflow_day_releases_surplus_above_mol() {
    let mut node = storage(TWO_WAY_DIMS, &[(0, 1.0), (1, 10.0)]);
    let (v, flows, _) = run_step(&mut node, 7000.0, 30.0, 0.0, 0.0, [8.0, 1e6, 0.0, 0.0]);

    assert!((flows[0] - 8.0).abs() < 1e-9, "ds_1 order met, got {}", flows[0]);
    assert!((flows[1] - 22.0).abs() < 1e-6,
            "ds_2 gets the residual that keeps the level on its MOL, got {}", flows[1]);
    assert!((v - 7000.0).abs() < 1e-6, "volume parks on ds_2's MOL, got {v}");
    let mbal = 7030.0 - flows.iter().sum::<f64>() - v;
    assert!(mbal.abs() < 1e-9, "mass balance must close, residual {mbal}");
}

/// RATING SEMANTICS: outlets couple through the solved level. ds_3's big
/// draw carries the end-of-step level below ds_2's MOL, so ds_2 releases
/// nothing — the level trace never contradicts "no flow below the MOL".
/// (Access semantics instead served both in full: ds_2 = 30, v = 10.)
#[test]
fn test_mol_outlet_and_unrestricted_outlet_both_served() {
    // ds_2: MOL at level 5 (volume 50), order 30. ds_3: no MOL, order 40.
    let mut node = storage(LINEAR_DIMS, &[(1, 5.0)]);
    let (v, flows, _) = run_step(&mut node, 80.0, 0.0, 0.0, 0.0, [0.0, 30.0, 40.0, 0.0]);

    assert!(flows[1].abs() < 1e-9,
            "level ends below ds_2's MOL, so ds_2 cannot have flowed, got {}", flows[1]);
    assert!((flows[2] - 40.0).abs() < 1e-9,
            "unrestricted ds_3 fully served, got {}", flows[2]);
    assert!((v - 40.0).abs() < 1e-9, "expected 40, got {v}");
}

/// Releases must be continuous in the sibling's order — the crucial property
/// the old active-set solver lacked (it flipped ds_2 from 30 to 0 as ds_3's
/// order crossed 30). RATING SEMANTICS: while ds_3's order is below 30 the
/// volume parks on ds_2's MOL and ds_2 tapers linearly (30 - ds_3); beyond
/// that ds_2 is 0 and the volume falls away — continuous throughout.
#[test]
fn test_release_continuous_in_sibling_order() {
    let mut prev_v = f64::NAN;
    for ds3_order_tenths in 0..=600 {
        let ds3_order = ds3_order_tenths as f64 * 0.1;
        let mut node = storage(LINEAR_DIMS, &[(1, 5.0)]);
        let (v, flows, _) = run_step(&mut node, 80.0, 0.0, 0.0, 0.0, [0.0, 30.0, ds3_order, 0.0]);

        let ds2_expected = (30.0 - ds3_order).max(0.0);
        assert!((flows[1] - ds2_expected).abs() < 1e-9,
                "ds_2 expected {ds2_expected} at ds_3 order {ds3_order}, got {}", flows[1]);
        assert!((flows[2] - ds3_order).abs() < 1e-9,
                "ds_3 expected {ds3_order}, got {}", flows[2]);
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

/// Outlet capacity is enforced (finally): `ds_N_outlet = MOL, capacity`
/// caps the release even with the storage far above the MOL.
#[test]
fn test_outlet_capacity_enforced() {
    let mut node = storage(LINEAR_DIMS, &[]);
    node.outlet_definition[1] = OutletDefinition::OutletWithMOLAndCapacity(2.0, 10.0);
    let (v, flows, _) = run_step(&mut node, 80.0, 0.0, 0.0, 0.0, [0.0, 30.0, 0.0, 0.0]);
    assert!((flows[1] - 10.0).abs() < 1e-9,
            "release capped at the outlet capacity, got {}", flows[1]);
    assert!((v - 70.0).abs() < 1e-9, "expected 70, got {v}");
}

/// A capacity-limited outlet parked on its MOL still respects its capacity:
/// with 30 ML standing above the MOL but capacity 10, only 10 is released
/// and the volume ends above the MOL (no parking needed).
#[test]
fn test_capacity_limits_release_above_mol() {
    let mut node = storage(LINEAR_DIMS, &[]);
    node.outlet_definition[1] = OutletDefinition::OutletWithMOLAndCapacity(5.0, 10.0);
    let (v, flows, _) = run_step(&mut node, 80.0, 0.0, 0.0, 0.0, [0.0, 60.0, 0.0, 0.0]);
    assert!((flows[1] - 10.0).abs() < 1e-9, "got {}", flows[1]);
    assert!((v - 70.0).abs() < 1e-9, "expected 70, got {v}");
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

// ---------------------------------------------------------------------------
// Rating-table outlets: ds_N_outlet = level, capacity, level, capacity, ...
// Capacity interpolates linearly in level between points and holds flat
// beyond the ends; a repeated level is an explicit step.
// ---------------------------------------------------------------------------

// Level 0-10 m maps linearly to volume 0-1000 ML, so the rating levels
// 0 / 8 / 8.5 / 9 sit at volumes 0 / 800 / 850 / 900.
#[allow(dead_code)] // used by #[test] fns only; silences the non-test lib pass
const RATING_DIMS: &str = "0, 0, 0, 0, \
                           10, 1000, 0, 0,";

#[allow(dead_code)] // used by #[test] fns only; silences the non-test lib pass
fn rating_node() -> StorageNode {
    let mut n = storage(RATING_DIMS, &[]);
    n.outlet_definition[1] = OutletDefinition::OutletWithRatingTable(
        vec![(0.0, 0.0), (8.0, 0.0), (8.5, 100.0), (9.0, 100.0)]);
    n
}

/// In the taper band the release settles where the demand-limited draw meets
/// the falling capacity: v = 870 - cap(v) with cap = 2*(v - 800), giving
/// v = 2470/3 and a release of 140/3.
#[test]
fn test_rating_taper_partial_release() {
    let mut node = rating_node();
    let (v, flows, _) = run_step(&mut node, 870.0, 0.0, 0.0, 0.0, [0.0, 200.0, 0.0, 0.0]);
    let v_expected = 2470.0 / 3.0;
    assert!((v - v_expected).abs() < 1e-9, "expected {v_expected}, got {v}");
    assert!((flows[1] - 140.0 / 3.0).abs() < 1e-9, "got {}", flows[1]);
    let mbal = 870.0 - flows.iter().sum::<f64>() - v;
    assert!(mbal.abs() < 1e-9, "mass balance residual {mbal}");
}

/// On the flat top of the curve a demand under the capacity is met exactly.
#[test]
fn test_rating_flat_region_meets_demand() {
    let mut node = rating_node();
    let (v, flows, _) = run_step(&mut node, 950.0, 0.0, 0.0, 0.0, [0.0, 50.0, 0.0, 0.0]);
    assert!((flows[1] - 50.0).abs() < 1e-9, "got {}", flows[1]);
    assert!((v - 900.0).abs() < 1e-9, "expected 900, got {v}");
}

/// Beyond the last rating point the capacity holds flat: a big demand from
/// high storage is capacity-bound at 100 and the equilibrium sits on the
/// flat stretch of the curve.
#[test]
fn test_rating_capacity_bound_above_curve() {
    let mut node = rating_node();
    let (v, flows, _) = run_step(&mut node, 980.0, 0.0, 0.0, 0.0, [0.0, 200.0, 0.0, 0.0]);
    assert!((flows[1] - 100.0).abs() < 1e-9, "got {}", flows[1]);
    assert!((v - 880.0).abs() < 1e-9, "expected 880, got {v}");
}

/// Deep in the taper a huge demand rides the capacity curve down:
/// v = 830 - 2*(v - 800) gives v = 810, release 20.
#[test]
fn test_rating_taper_big_demand() {
    let mut node = rating_node();
    let (v, flows, _) = run_step(&mut node, 830.0, 0.0, 0.0, 0.0, [0.0, 500.0, 0.0, 0.0]);
    assert!((v - 810.0).abs() < 1e-9, "expected 810, got {v}");
    assert!((flows[1] - 20.0).abs() < 1e-9, "got {}", flows[1]);
}

/// Below the zero-capacity end of the ramp the outlet is shut.
#[test]
fn test_rating_below_ramp_releases_nothing() {
    let mut node = rating_node();
    let (v, flows, _) = run_step(&mut node, 700.0, 0.0, 0.0, 0.0, [0.0, 50.0, 0.0, 0.0]);
    assert!(flows[1].abs() < 1e-12, "got {}", flows[1]);
    assert!((v - 700.0).abs() < 1e-9, "volume untouched, got {v}");
}

/// A repeated level in the table is an explicit step, with the same parking
/// behaviour as a MOL: capacity 80 above level 5, zero below.
#[test]
fn test_rating_explicit_step() {
    // Comfortably above the step: capacity-bound at 80.
    let mut node = storage(RATING_DIMS, &[]);
    node.outlet_definition[1] = OutletDefinition::OutletWithRatingTable(
        vec![(5.0, 0.0), (5.0, 80.0)]);
    let (v, flows, _) = run_step(&mut node, 600.0, 0.0, 0.0, 0.0, [0.0, 200.0, 0.0, 0.0]);
    assert!((flows[1] - 80.0).abs() < 1e-9, "got {}", flows[1]);
    assert!((v - 520.0).abs() < 1e-9, "expected 520, got {v}");

    // Just above the step: the root falls inside the jump, so the volume
    // parks exactly on it and the outlet takes the residual.
    let mut node = storage(RATING_DIMS, &[]);
    node.outlet_definition[1] = OutletDefinition::OutletWithRatingTable(
        vec![(5.0, 0.0), (5.0, 80.0)]);
    let (v, flows, _) = run_step(&mut node, 510.0, 0.0, 0.0, 0.0, [0.0, 200.0, 0.0, 0.0]);
    assert!((flows[1] - 10.0).abs() < 1e-9, "got {}", flows[1]);
    assert!((v - 500.0).abs() < 1e-9, "expected to park on the step at 500, got {v}");
}

/// A rating level outside the dimensions table's level range is a
/// configuration error, reported at initialise.
#[test]
fn test_rating_level_outside_dimensions_errors() {
    let mut node = storage(RATING_DIMS, &[]);
    node.outlet_definition[1] = OutletDefinition::OutletWithRatingTable(
        vec![(0.0, 0.0), (20.0, 100.0)]);
    let mut data_cache = crate::data_management::data_cache::DataCache::new();
    let mut accounts = AccountManager::new();
    let err = node.initialise(&mut data_cache, &mut accounts).unwrap_err();
    assert!(err.contains("outside"), "unexpected error message: {err}");
}

/// The rating table survives INI parse -> serialise -> parse unchanged,
/// including a repeated-level step.
#[test]
fn test_rating_table_ini_round_trip() {
    use crate::io::ini_model_io::IniModelIO;
    use crate::nodes::NodeEnum;

    let ini = "[kalix]\n\
               \n\
               [node.dam]\n\
               type = storage\n\
               loc = 0, 0\n\
               initial_volume = 100\n\
               dimensions = 0, 0, 0, 0,\n\
               \x2010, 1000, 1, 0,\n\
               ds_2_outlet = 0.0, 0,\n\
               \x208.0, 0,\n\
               \x208.5, 100,\n\
               \x209.0, 100,\n";

    let outlet_of = |m: &crate::model::Model| -> OutletDefinition {
        match m.get_node("dam").expect("node not found") {
            NodeEnum::StorageNode(n) => n.outlet_definition[1].clone(),
            _ => panic!("not a storage"),
        }
    };

    let m1 = IniModelIO::read_model_string(ini).unwrap();
    let expected = OutletDefinition::OutletWithRatingTable(
        vec![(0.0, 0.0), (8.0, 0.0), (8.5, 100.0), (9.0, 100.0)]);
    assert_eq!(outlet_of(&m1), expected, "parse must yield the rating table");

    let serialised = IniModelIO::model_to_string(&m1);
    let m2 = IniModelIO::read_model_string(&serialised).unwrap();
    assert_eq!(outlet_of(&m2), expected,
               "rating table must survive a round trip, serialised as:\n{serialised}");
}

/// Decreasing capacity (e.g. gate submergence) is rejected at parse: it can
/// create multiple equilibria in the storage solve.
#[test]
fn test_rating_decreasing_capacity_rejected() {
    use crate::io::ini_model_io::IniModelIO;
    let ini = "[kalix]\n\
               \n\
               [node.dam]\n\
               type = storage\n\
               loc = 0, 0\n\
               initial_volume = 100\n\
               dimensions = 0, 0, 0, 0,\n\
               \x2010, 1000, 1, 0,\n\
               ds_2_outlet = 5.0, 100, 8.0, 50,\n";
    let err = match IniModelIO::read_model_string(ini) {
        Ok(_) => panic!("decreasing capacity must be rejected"),
        Err(e) => format!("{e:?}"),
    };
    assert!(err.contains("non-decreasing"),
            "expected a non-decreasing-capacity error, got: {err}");
}

/// A bare MOL (or MOL+capacity) level outside the dimensions table's level
/// range is a loud config error at initialise, matching the rating-table
/// form — previously it interpolated to NaN and the constraint silently
/// vanished.
#[test]
fn test_mol_level_outside_dimensions_errors() {
    for def in [OutletDefinition::OutletWithMOL(15.0),
                OutletDefinition::OutletWithMOLAndCapacity(15.0, 100.0)] {
        let mut node = storage(RATING_DIMS, &[]);
        node.outlet_definition[0] = def;
        let mut data_cache = crate::data_management::data_cache::DataCache::new();
        let mut accounts = AccountManager::new();
        let err = node.initialise(&mut data_cache, &mut accounts).unwrap_err();
        assert!(err.contains("outside"), "unexpected error message: {err}");
    }
}
