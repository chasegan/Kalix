use std::collections::HashMap;
use crate::model::Model;
use crate::nodes::inflow_node::InflowNode;
use crate::nodes::sacramento_node::SacramentoNode;
use crate::nodes::gr4j_node::Gr4jNode;
use crate::nodes::storage_node::StorageNode;
use crate::numerical::table::Table;
use crate::timeseries::Timeseries;
use crate::nodes::{Node, NodeEnum};
use crate::data_cache::DataCache;
use crate::nodes::user_node::UserNode;

#[test]
fn test_model_with_all_node_types() {

    //Create model
    let mut model = Model::new();
    let mut regression_results: HashMap<String, (usize, f64, f64)> = HashMap::new();

    //Add rainfall, evap, and flow data
    let _ = model.load_input_data("./src/tests/example_models/1/flows.csv");
    let _ = model.load_input_data("./src/tests/example_models/1/rex_mpot.csv");
    let _ = model.load_input_data("./src/tests/example_models/1/rex_rain.csv");
    let _ = model.load_input_data("./src/tests/example_models/1/constants.csv");

    //Add an inflow node
    let node1_idx: usize;
    {
        //Node
        let mut n = InflowNode::new();
        n.name = "node1_inflow".to_string();
        n.inflow_def.name = "data.flows_csv.by_index.1".to_string();
        node1_idx = model.add_node(NodeEnum::InflowNode(n));

        //Node results
        let result_name = "node.node1_inflow.usflow".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 0.0, 0.0));

        let result_name = "node.node1_inflow.inflow".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 126.79567788251778, 189.52350495319962));

        let result_name = "node.node1_inflow.dsflow".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 126.79567788251778, 189.52350495319962));
    }

    //Add a sacramento node
    let node2_idx: usize;
    {
        //Node
        let mut n = SacramentoNode::new();
        n.name = "node2_sacramento".to_string();
        n.rain_mm_def.name = "data.rex_rain_csv.by_index.1".to_string();
        n.evap_mm_def.name = "data.rex_mpot_csv.by_index.1".to_string();
        n.area_km2 = 80.0;
        //n.sacramento_model.set_params();
        node2_idx = model.add_node(NodeEnum::SacramentoNode(n));

        //Node results
        let result_name = "node.node2_sacramento.usflow".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 126.79567788251778, 189.52350495319962));

        let result_name = "node.node2_sacramento.runoff_volume".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 264.37366169710083, 1249.115576264033));

        let result_name = "node.node2_sacramento.dsflow".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 391.16933957961965, 1376.5048361232634));
    }
    model.add_link(node1_idx, node2_idx, 0, 0);

    //Add an gr4j node
    let node3_idx: usize;
    {
        //Node
        let mut n = Gr4jNode::new();
        n.name = "node3_gr4j".to_string();
        n.rain_mm_def.name = "data.rex_rain_csv.by_index.1".to_string();
        n.evap_mm_def.name = "data.rex_mpot_csv.by_index.1".to_string();
        n.area_km2 = 80.0;
        node3_idx = model.add_node(NodeEnum::Gr4jNode(n));

        //Node results
        let result_name = "node.node3_gr4j.usflow".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 391.16933957961965, 1376.5048361232634));

        let result_name = "node.node3_gr4j.runoff_volume".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 251.7564530253888, 1010.8535625355022));

        let result_name = "node.node3_gr4j.dsflow".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 642.925792605013, 2236.5833607731133));
    }
    model.add_link(node2_idx, node3_idx, 0, 0);

    //Add a user node
    let node4_idx: usize;
    {
        //Node
        let mut n = UserNode::new();
        n.name = "node4_user".to_string();
        n.demand_def.name = "data.constants_csv.by_name.const_20".to_string();
        node4_idx = model.add_node(NodeEnum::UserNode(n));

        //Node results
        let result_name = "node.node4_user.usflow".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 642.925792605013, 2236.5833607731133));

        let result_name = "node.node4_user.demand".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 20.0, 0.0));

        let result_name = "node.node4_user.diversion".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 19.984550376890894, 0.5523837133339123));

        let result_name = "node.node4_user.dsflow".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 622.9412422281207, 2236.578989471308));
    }
    model.add_link(node3_idx, node4_idx, 0, 0);

    //Run the model
    model.configure().expect("Configuration error");
    model.run().expect("Simulation error");

    //Assess the results
    for key in regression_results.keys() {
        let ds_idx = model.data_cache.get_existing_series_idx(key).unwrap();
        let len = model.data_cache.series[ds_idx].len();
        let mean = model.data_cache.series[ds_idx].mean();
        let std_dev = model.data_cache.series[ds_idx].std_dev();

        let new_answer = (len, mean, std_dev);
        let old_answer = &regression_results[key];
        println!("\n{}", key);
        println!("new_answer: {:?}", new_answer);
        println!("old_answer: {:?}", old_answer);
        assert_eq!(new_answer, *old_answer);
    }
}



/// Create a model with 1 storage node. Configure the storage dimensions
/// using a CSV file and running 10 timesteps with inflows causing the
/// storage to fill and spill.
///
/// NOTE: The timestepping is done manually here because the model doesn't
/// have the ability to really coordinate a model run and collect results
/// at the time of writing.
#[test]
fn test_create_and_run_model_with_storage_node() {

    let mut st1 = StorageNode::new();
    st1.d = Table::from_csv("./src/tests/example_tables/test_4_dim_table.csv");
    let mut data_cache = DataCache::new();

    st1.initialise(&mut data_cache).expect("Initialisation error");
    println!("Initial vol = {}", st1.v_initial);
    println!("Area 0 = {}", st1.area0);

    let _spill = Timeseries::new_daily();
    let _volume = Timeseries::new_daily();
    for i in 0..10 {
        let mut flow = 0_f64;
        if i < 4 { flow = 100_f64 }
        st1.add_usflow(flow, 0);
        st1.run_flow_phase(&mut data_cache);
        // Note: spill and v are now private - we need public getter methods or to make them public for testing
        // For now, let's comment out these lines and test basic functionality
        // spill.push(i as u64, st1.spill);
        // volume.push(i as u64, st1.v);
        // println!("Vol = {}, Spill = {}", st1.v, st1.spill);
    }
}


/// Create a model with 2 inflow nodes, but these nodes are not linked.
/// Then we run the model... but at the time of writing it's not
/// actually executing the nodes because of a borrow-checker error.
#[test]
fn test_create_and_run_model_with_nodes() {
    // Fake some data
    let mut inflow_ts1 = Timeseries::new_daily();
    inflow_ts1.push(1, 100.0);
    inflow_ts1.push(2, 100.0);

    let mut inflow_ts2 = Timeseries::new_daily();
    inflow_ts2.push(1, 20.0);
    inflow_ts2.push(2, 20.0);

    // Create two inflow nodes
    let mut in1 = InflowNode::new();
    in1.name = "inflow_node_1".to_string();
    let mut in2 = InflowNode::new();
    in2.name = "inflow_node_2".to_string();

    // Create a model and add the nodes
    let mut m = Model::new();
    let in1_idx = m.add_node(NodeEnum::InflowNode(in1));
    let in2_idx = m.add_node(NodeEnum::InflowNode(in2));

    // Add a link from in1 to in2 using the new centralized link management
    m.add_link(in1_idx, in2_idx, 0, 0);

    // Link the nodes
    //println!("Upstream={id1}, Downstream={id2}");
    //m.add_link(id1, id2);

    // Now run the model
    m.run().expect("Simulation error");

    // assert_eq!(what2.sum(), 38.1);
}


#[test]
fn test_create_and_run_model_with_nodes_reverse_order() {
    // Fake some data
    let mut inflow_ts1 = Timeseries::new_daily();
    inflow_ts1.push(1, 100.0);
    inflow_ts1.push(2, 100.0);

    let mut inflow_ts2 = Timeseries::new_daily();
    inflow_ts2.push(1, 20.0);
    inflow_ts2.push(2, 20.0);

    // Create two inflow nodes
    let mut in1 = InflowNode::new();
    in1.name = "Node1".to_string();

    let mut in2 = InflowNode::new();
    in2.name = "Node2".to_string();

    // Create a model and add nodes
    let mut m = Model::new();
    let in2_idx = m.add_node(NodeEnum::InflowNode(in2));
    let in1_idx = m.add_node(NodeEnum::InflowNode(in1));

    // Link the nodes using the new centralized link management
    m.add_link(in1_idx, in2_idx, 0, 0);

    // Now run the model
    m.run().expect("Simulation error");

    // assert_eq!(what2.sum(), 38.1);
}


#[test]
fn test_clone_model() {

    //Creat a new model. Add some data.
    let mut m = Model::new();
    m.load_input_data("./src/tests/example_data/test.csv").expect("TODO: panic message");

    //Create an inflow node and add it to the model
    let mut n = InflowNode::new();
    n.name = "my_inflow_node".to_owned();
    n.inflow_def.name = "data.test_csv.by_name.value".to_owned();
    m.add_node(NodeEnum::InflowNode(n));

    //Specify some outputs
    m.outputs.push("node.my_inflow_node.usflow".to_owned());
    m.outputs.push("node.my_inflow_node.dsflow".to_owned());

    //Configure the model, clone it, run both, and compare the results.
    m.configure().expect("Configuration error");
    let mut m2 = m.clone();

    //Check the results of m
    m.run().expect("Simulation error");
    let ds_idx = m.data_cache.get_series_idx("node.my_inflow_node.dsflow", false).unwrap();
    let ans = m.data_cache.series[ds_idx].clone();
    assert_eq!(ans.len(), 6);
    assert_eq!(ans.sum(), 38.1);

    //Check the results of m2
    m2.run().expect("Simulation error");
    let ds_idx = m2.data_cache.get_series_idx("node.my_inflow_node.dsflow", false).unwrap();
    let ans = m2.data_cache.series[ds_idx].clone();
    assert_eq!(ans.len(), 6);
    assert_eq!(ans.sum(), 38.1);
}