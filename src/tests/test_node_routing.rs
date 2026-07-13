use crate::model::Model;
use crate::nodes::routing_node::RoutingNode;
use crate::nodes::inflow_node::InflowNode;
use crate::nodes::{Node, NodeEnum};
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::numerical::opt::optimisable_component::OptimisableComponent;


/// Create an inflow node, add it to a model, and drive the inflow
/// node manually using timeseries data read from a CSV file.
#[test]
fn test_inflow_node_with_timeseries() {

    //Creat a new inflow node
    let mut n = InflowNode::new();
    n.name = "Node_inflow".to_string();

    //Create a new routing node
    let mut r = RoutingNode::new();
    r.set_routing_table(vec![0.0, 1e1, 1e2, 1e3, 1e4, 1e5], 
                        vec![5.0, 2.0, 3.0, 1.5, 1.0, 0.0]);
    r.set_divs(10);
    r.set_x(0.5);
    r.set_lag(2);
    r.name = "Node_routing".to_string();

    // Create a model and add the nodes
    let mut m = Model::new();
    let n_idx = m.add_node(NodeEnum::InflowNode(n));
    let r_idx = m.add_node(NodeEnum::RoutingNode(r));

    // Link the nodes using the new centralized link management
    println!("Linking inflow node (idx {}) to routing node (idx {})", n_idx, r_idx);
    m.add_link(n_idx, r_idx, 0, 0);

    // TODO: Configure inflow data
    // let timeseries_vec = crate::io::csv_io::read_ts("./src/tests/example_data/test3.csv").expect("Error");
    // let inflow_ts = timeseries_vec[0].clone();
    // m <---- add data here and tell the inflow node how to find it.

    // Now run the model
    //m.run();

    ////////////////////////////////////////////////
    // 
    // //Run it
    // n.initialise();
    // let mut result_dsflow_ts = Timeseries::new();
    // for i in 0..len {
    //     n.run_flow_phase();
    //     result_dsflow_ts.push(i as u64,n.ds_flow);
    //     println!("ds_flow => {} {}", i, n.ds_flow);
    // }
    // 
    // //Check the results
    // assert_eq!(result_dsflow_ts.len(), 6);
    // assert_eq!(result_dsflow_ts.sum(), 38.1);
}

// ============================================================================
// OptimisableComponent tests
// ============================================================================

fn pwl_node() -> RoutingNode {
    let mut r = RoutingNode::new();
    r.name = "pwl_reach".to_string();
    // 4 points -> pwl_segs = 3 -> params pwl_tt_0 to pwl_tt_3
    r.set_routing_table(vec![0.0, 10.0, 100.0, 1000.0],
                        vec![5.0, 3.0, 2.0, 1.0]);
    r
}

fn nlm_node() -> RoutingNode {
    let mut r = RoutingNode::new();
    r.name = "nlm_reach".to_string();
    r.set_k(100.0);
    r.set_m(0.8);
    r
}

/// A PWL node advertises exactly its travel-time parameters, one per table point.
#[test]
fn test_pwl_node_lists_travel_times_only() {
    let r = pwl_node();
    assert_eq!(r.list_params(), vec!["pwl_tt_0", "pwl_tt_1", "pwl_tt_2", "pwl_tt_3"]);
}

/// An NLM node (and a lag-only node) advertises the Muskingum pair.
#[test]
fn test_nlm_node_lists_muskingum_params() {
    assert_eq!(nlm_node().list_params(), vec!["nlm_k", "nlm_m"]);
    // Lag-only (no table, k = 0) lands in the NLM bucket: calibrating k onto
    // it is how a modeller gives it NLM routing.
    let mut lag_only = RoutingNode::new();
    lag_only.set_lag(3);
    assert_eq!(lag_only.list_params(), vec!["nlm_k", "nlm_m"]);
}

/// Every advertised parameter must round-trip through set_param/get_param —
/// the contract the optimiser relies on.
#[test]
fn test_advertised_params_roundtrip() {
    for mut node in [pwl_node(), nlm_node()] {
        for (i, name) in node.list_params().iter().enumerate() {
            let value = 1.5 + i as f64;
            node.set_param(name, value).unwrap();
            assert_eq!(node.get_param(name).unwrap(), value, "param '{}'", name);
        }
    }
}

/// set_param on a PWL travel time updates the table the routing actually uses.
#[test]
fn test_pwl_tt_set_param_updates_routing_table() {
    let mut r = pwl_node();
    r.set_param("pwl_tt_2", 2.5).unwrap();
    // Table serialises interleaved (q, tt) per row; tt of row 2 is element 5.
    assert_eq!(r.get_routing_table_as_vec()[5], 2.5);
}

/// Parameters of the inactive mode are rejected, as are malformed and
/// out-of-range names.
#[test]
fn test_inactive_mode_and_invalid_params_are_rejected() {
    let mut pwl = pwl_node();
    assert!(pwl.set_param("nlm_k", 50.0).unwrap_err().contains("uses PWL routing"));
    assert!(pwl.get_param("nlm_m").unwrap_err().contains("uses PWL routing"));
    assert!(pwl.set_param("pwl_tt_4", 1.0).unwrap_err().contains("out of range"));
    assert!(pwl.set_param("pwl_tt_x", 1.0).unwrap_err().contains("invalid index"));
    assert!(pwl.set_param("bogus", 1.0).unwrap_err().contains("Unknown routing parameter"));

    let mut nlm = nlm_node();
    assert!(nlm.set_param("pwl_tt_0", 1.0).unwrap_err().contains("no PWL routing table"));
    assert!(nlm.get_param("pwl_tt_0").unwrap_err().contains("no PWL routing table"));
    assert!(nlm.set_param("bogus", 1.0).unwrap_err().contains("Unknown routing parameter"));
}

/// initialise() must reject a negative travel time: segment storage is the
/// integral of tt, so a negative tt breaks its monotonicity and the
/// reference-flow solver's root uniqueness. This is what makes a bad
/// optimisation candidate cleanly infeasible.
#[test]
fn test_initialise_rejects_negative_travel_time() {
    let mut r = pwl_node();
    r.set_param("pwl_tt_1", -0.5).unwrap();

    let mut data_cache = DataCache::new();
    let mut account_manager = AccountManager::new();
    let err = r.initialise(&mut data_cache, &mut account_manager).unwrap_err();
    assert!(err.contains("must be non-negative"), "got: {}", err);

    // And a clean table still initialises.
    let mut ok = pwl_node();
    ok.initialise(&mut data_cache, &mut account_manager).unwrap();
}