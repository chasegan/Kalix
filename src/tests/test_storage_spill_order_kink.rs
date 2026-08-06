// Regression tests for the storage-solver mass leak found in the Proserpine
// conversion (Peter Faust Dam, 2011-06-05 and 350 sibling days).
//
// Setup that triggered it: the dam just above FSL, drawing down under net
// evaporation, with ds_1 orders due slightly ABOVE the interpolated spill.
// The equilibrium outflow max(spill(v), order) kinks where the spill line
// crosses the order; interpolating the error straight across the segment
// found a volume consistent with the linearized (larger) outflow while the
// allocation released only the order — the difference vanished from the
// mass balance (~45 ML/d, 6.4 GL over the 130-y run).
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::nodes::storage_node::StorageNode;
use crate::nodes::Node;
use crate::model_inputs::dynamic_input::DynamicInput;
use crate::numerical::table::Table;

// Peter Faust Dam, abridged to the rows around FSL (85.6 m). The 85.6->85.7
// segment carries the kink: spill 0 -> 194 ML/d over 4,391 ML of volume.
const PFD_DIMS: &str = "44   , 0      , 0     , 0, \
                        53.1 , 970    , 0.35  , 0, \
                        85.6 , 491400 , 43.25 , 0, \
                        85.7 , 495791 , 43.47 , 194, \
                        95   , 999098 , 65.17 , 1e9,";

fn pfd_node() -> StorageNode {
    let mut n = StorageNode::new();
    n.name = "pfd".to_string();
    n.dimensions = Table::from_csv_string(PFD_DIMS, 4, false).unwrap();
    n
}

fn run_one_step(node: &mut StorageNode,
                v_start: f64,
                usflow: f64,
                evap_mm: f64,
                order_due: f64) -> (f64, f64) {
    let mut data_cache = DataCache::new();
    data_cache.step_size = 86400;
    let mut accounts = AccountManager::new();
    node.initialise(&mut data_cache, &mut accounts).unwrap();
    node.volume = v_start;
    node.evap_mm_input = DynamicInput::Constant { value: evap_mm, original: evap_mm.to_string() };
    node.ds_orders_due[0] = order_due;
    node.add_usflow(usflow, 0);
    node.run_flow_phase(&mut data_cache, &mut accounts);
    let dsflow: f64 = (0..4).map(|i| node.remove_dsflow(i)).sum();
    (node.volume, dsflow)
}

/// Area on the 85.6->85.7 segment, linearly interpolated (mirrors the solver).
fn area_at(v: f64) -> f64 {
    43.25 + (43.47 - 43.25) * (v - 491400.0) / (495791.0 - 491400.0)
}

/// The exact 2011-06-05 conditions: order_due (95.928) just above the
/// interpolated spill. The solution sits on the constant-outflow side of the
/// kink: outflow = order, and the volume must satisfy mass balance with THAT
/// outflow (the old code returned 493,508 — 47 ML low).
#[test]
fn test_order_just_above_spill_conserves_mass() {
    let mut node = pfd_node();
    let (volume, dsflow) = run_one_step(&mut node, 493592.065, 226.29, 3.8609, 95.928);

    assert!((dsflow - 95.928).abs() < 1e-6,
            "release must equal the order (spill below it), got {dsflow}");
    // Exact solution of v = W - 3.8609*area(v) - 95.928, W = 493818.355:
    assert!((volume - 493555.0).abs() < 0.2,
            "expected the kink-aware volume ~493555.0, got {volume}");
    // And the round-trip invariant that actually failed in production:
    let mbal = 493592.065 + 226.29 - 3.8609 * area_at(volume) - dsflow - volume;
    assert!(mbal.abs() < 1e-6, "mass balance must close, residual {mbal}");
}

/// Spill comfortably above the order (2011-06-09 shape): the spill-limited
/// pass handles it and was always exact — guard it stays that way.
#[test]
fn test_spill_covers_order_conserves_mass() {
    let mut node = pfd_node();
    let (volume, dsflow) = run_one_step(&mut node, 493592.065, 226.29, 3.8609, 49.178);

    assert!(dsflow > 49.178, "spill exceeds the order, got {dsflow}");
    let mbal = 493592.065 + 226.29 - 3.8609 * area_at(volume) - dsflow - volume;
    assert!(mbal.abs() < 1e-3, "mass balance must close, residual {mbal}");
}

/// Order far above any spill this segment can produce: outflow is constant
/// across the whole segment (no kink in play) and behaviour must match the
/// plain interpolation.
#[test]
fn test_order_far_above_spill_conserves_mass() {
    let mut node = pfd_node();
    let (volume, dsflow) = run_one_step(&mut node, 493592.065, 226.29, 3.8609, 800.0);

    assert!((dsflow - 800.0).abs() < 1e-6, "storage can meet the order, got {dsflow}");
    let mbal = 493592.065 + 226.29 - 3.8609 * area_at(volume) - dsflow - volume;
    assert!(mbal.abs() < 1e-6, "mass balance must close, residual {mbal}");
}

/// Below FSL with an order: no spill anywhere near the solution — the fix
/// must not disturb the ordinary release path.
#[test]
fn test_below_fsl_release_unchanged() {
    let mut node = pfd_node();
    let (volume, dsflow) = run_one_step(&mut node, 400000.0, 100.0, 2.0, 150.0);

    assert!((dsflow - 150.0).abs() < 1e-6, "expected the plain order released, got {dsflow}");
    assert!(volume < 400100.0 && volume > 399000.0, "volume in a sane range, got {volume}");
}
