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

// ============================================================================
// Out-of-table reference flows (PWL saturation)
// ============================================================================

fn run(ini: &str) -> Model {
    let mut model = crate::io::ini_model_io::IniModelIO::read_model_string(ini)
        .expect("model should load");
    model.configure().expect("model should configure");
    model.run().expect("simulation should run");
    model
}

fn series(model: &mut Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_series_idx(name, false)
        .unwrap_or_else(|| panic!("no series '{}'", name));
    model.data_cache.series[idx].values.clone()
}

/// A model whose routing table tops out at q = 100 (V(q) = 2q, so V_max = 200
/// for one division) fed a flood of 1000 on day 3. The table for building the
/// day-by-day expectation: tt is a flat 2 days, so in-range storage is exactly
/// 2 x the reference flow.
fn out_of_table_ini(x: f64, n_divs: usize) -> String {
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-05

[node.src]
type = inflow
loc = 0, 0
inflow = if(sim.day == 3, 1000, 50)
ds_1 = reach

[node.reach]
type = routing
loc = 0, 10
x = {x}
n_divs = {n_divs}
pwl = 0, 2,
      100, 2,
ds_1 = sink

[node.sink]
type = gauge
loc = 0, 20

[outputs]
node.reach.usflow
node.reach.dsflow
node.reach.volume
"#)
}

/// x = 1 path: a reference flow above the table top must saturate the
/// division at V(q_max) and release the balance downstream — previously this
/// fall-through zeroed the division's storage without releasing it (a genuine
/// mass loss). Hand-computed expectation, all values exact in f64.
#[test]
fn test_pwl_flow_above_table_releases_storage_x_unity() {
    let mut model = run(&out_of_table_ini(1.0, 1));
    // Days 1-2 fill toward steady state (qout clamps at 0 while V(50) = 100
    // exceeds what is in the reach); day 3 crosses the table top: storage
    // saturates at V_max = 200 and qout = 100 + 1000 - 200 = 900.
    assert_eq!(series(&mut model, "node.reach.dsflow"), vec![0.0, 0.0, 900.0, 150.0, 50.0]);
    assert_eq!(series(&mut model, "node.reach.volume"), vec![50.0, 100.0, 200.0, 100.0, 100.0]);
}

/// General-x path (quadratic per-segment solve) has the same fall-through:
/// verify saturation on the crossing day and that mass balances over the run.
#[test]
fn test_pwl_flow_above_table_releases_storage_general_x() {
    let mut model = run(&out_of_table_ini(0.5, 2));
    let usflow = series(&mut model, "node.reach.usflow");
    let dsflow = series(&mut model, "node.reach.dsflow");
    let volume = series(&mut model, "node.reach.volume");

    // On the crossing day both divisions saturate: V_max = 100 each.
    assert_eq!(volume[2], 200.0, "storage saturates at V(q_max) on the flood day");

    // Mass balance: inflow - outflow = storage still in the reach. The old
    // fall-through failed this by the pre-flood storage volume.
    let stored: f64 = usflow.iter().sum::<f64>() - dsflow.iter().sum::<f64>();
    assert!((stored - volume[4]).abs() < 1e-9,
            "mass leak: inflow - outflow = {stored} but reach holds {}", volume[4]);
}

/// A lag-only node (no PWL table) takes the same fall-through path by design;
/// it must remain pure pass-through with a step of lag.
#[test]
fn test_lag_only_routing_is_pass_through() {
    let ini = r#"
[kalix]
start = 2020-01-01
end = 2020-01-03

[node.src]
type = inflow
loc = 0, 0
inflow = 50
ds_1 = reach

[node.reach]
type = routing
loc = 0, 10
lag = 1
ds_1 = sink

[node.sink]
type = gauge
loc = 0, 20

[outputs]
node.reach.dsflow
"#;
    let mut model = run(ini);
    assert_eq!(series(&mut model, "node.reach.dsflow"), vec![0.0, 50.0, 50.0]);
}