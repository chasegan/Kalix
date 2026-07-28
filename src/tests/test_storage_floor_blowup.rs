// Regression test for the storage-solver floor blowup found in the Upper
// Condamine conversion (Talgai Weir, 1931-09-09).
//
// Setup that triggered it: storage sitting at its ds_1 MOL volume (92 ML at
// level 409.1), a pass-through order (1,683.7 ML) far exceeding everything the
// storage holds, and net rain of +0.1 mm/d. Between dimension-table rows 0 and
// 1 (volume 0 -> 0.001, area 0 -> 0.01) the equilibrium error difference is
// dVol - net_rain * dArea = 0.001 - 0.1 * 0.01 = exactly zero, and with the
// demanded outflow unmeetable there is no sign change anywhere in the table.
// The old code missed the floor (istop lands at 1, not 0) and interpolated
// across the degenerate row pair: x = ~1591 / ~0, landing the volume at
// 6.9965e12 ML and the level at 2.8e18 m.
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::nodes::storage_node::StorageNode;
use crate::nodes::{Node, NodeEnum};
use crate::model_inputs::dynamic_input::DynamicInput;
use crate::numerical::table::Table;

const TALGAI_DIMS: &str = "0        , 0          , 0         , 0, \
                           406.5    , 0.001      , 0.01      , 0, \
                           407      , 1          , 0.01      , 0, \
                           408      , 25         , 0.05      , 0, \
                           409.1    , 92         , 0.09      , 0, \
                           410      , 215        , 0.15      , 0, \
                           411      , 395        , 0.21      , 0, \
                           412      , 640        , 0.28      , 0, \
                           413      , 985        , 0.42      , 1.00E+09,";

fn talgai_node() -> StorageNode {
    let mut n = StorageNode::new();
    n.name = "talgai".to_string();
    n.dimensions = Table::from_csv_string(TALGAI_DIMS, 4, false).unwrap();
    n
}

fn run_one_step(node: &mut StorageNode,
                v_start: f64,
                usflow: f64,
                rain_mm: f64,
                evap_mm: f64,
                order_due: f64) -> (f64, f64) {
    let mut data_cache = DataCache::new();
    data_cache.step_size = 86400;
    let mut accounts = AccountManager::new();
    node.initialise(&mut data_cache, &mut accounts).unwrap();
    node.volume = v_start;
    node.rain_mm_input = DynamicInput::Constant { value: rain_mm, original: rain_mm.to_string() };
    node.evap_mm_input = DynamicInput::Constant { value: evap_mm, original: evap_mm.to_string() };
    node.ds_orders_due[0] = order_due;
    node.add_usflow(usflow, 0);
    node.run_flow_phase(&mut data_cache, &mut accounts);
    let dsflow: f64 = (0..4).map(|i| node.remove_dsflow(i)).sum();
    (node.volume, dsflow)
}

/// The exact Talgai 1931-09-09 conditions: the storage must drain toward the
/// floor, not explode.
#[test]
fn test_floor_with_degenerate_row_pair_does_not_explode() {
    let mut node = talgai_node();
    let (volume, dsflow) = run_one_step(&mut node, 92.0, 0.891536, 4.3, 4.2, 1683.72);

    // The demanded release exceeds all stored water: the storage drains to the
    // table floor and releases what mass balance allows.
    assert!(volume.is_finite(), "volume must be finite, got {volume}");
    assert!(volume >= 0.0 && volume <= 92.0 + 1.0,
            "volume must drain toward the floor, got {volume}");
    assert!(dsflow.is_finite() && dsflow >= 0.0 && dsflow <= 92.0 + 0.9 + 1.0,
            "dsflow can release at most stored volume + inflow + net rain, got {dsflow}");
}

/// Same day without the degenerate cancellation (net rain zero): behaviour must
/// be the same well-behaved drain — guards the fix against regressing the
/// ordinary drain-dry case.
#[test]
fn test_overdraw_drains_to_floor() {
    let mut node = talgai_node();
    let (volume, dsflow) = run_one_step(&mut node, 92.0, 0.5, 0.0, 4.2, 1683.72);
    assert!(volume.is_finite() && volume >= 0.0 && volume < 92.5);
    assert!(dsflow.is_finite() && dsflow <= 92.5 + 1.0);
}

/// Sanity: a modest order the storage CAN meet must still be delivered.
#[test]
fn test_meetable_order_still_delivered() {
    let mut node = talgai_node();
    let (volume, dsflow) = run_one_step(&mut node, 92.0, 10.0, 0.0, 0.0, 5.0);
    assert!((dsflow - 5.0).abs() < 0.5, "expected ~5 ML released, got {dsflow}");
    assert!(volume > 92.0, "inflow beyond the order should be retained, got {volume}");
}

// keep NodeEnum import used
#[allow(dead_code)]
fn _touch(n: StorageNode) -> NodeEnum { NodeEnum::StorageNode(n) }
