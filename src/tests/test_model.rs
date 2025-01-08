use crate::model::Model;
use crate::nodes::inflow_node::InflowNode;
use crate::nodes::storage_node::StorageNode;
use crate::numerical::table::Table;
use crate::timeseries::Timeseries;
use crate::nodes::Node;
use crate::data_cache::DataCache;


/// Create a model with 1 storage node. Configure the storage dimensions
/// using a CSV file and running 10 timesteps with inflows causing the
/// storage to fill and spill.
///
/// NOTE: The timestepping is done manually here because the model doesn't
/// have the ability to really coordinate a model run and collect results
/// at the time of writing.
#[test]
fn test_create_and_run_model_with_storage_node() {

    let mut m = Model::new();
    let mut st1 = StorageNode::new();
    st1.d = Table::from_csv("./src/tests/example_tables/test_4_dim_table.csv");
    //t1.d.print();
    let mut data_cache = DataCache::new();
    st1.initialise(&mut data_cache);
    println!("Initial vol = {}", st1.v_initial);
    println!("Area 0 = {}", st1.area0);

    let mut spill = Timeseries::new_daily();
    let mut volume = Timeseries::new_daily();
    for i in 0..10 {
        let mut flow = 0_f64;
        if i < 4 { flow = 100_f64 }
        st1.add(flow, 0);
        st1.run_flow_phase(&mut data_cache);
        spill.push(i as u64, st1.spill);
        volume.push(i as u64, st1.v);
        println!("Vol = {}, Spill = {}", st1.v, st1.spill);
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
    //in1.inflow_ts = Some(inflow_ts1);

    let mut in2 = InflowNode::new();
    //in2.inflow_ts = Some(inflow_ts2);

    let id1 = in1.get_id();
    let id2 = in2.get_id();

    // Now create a model and put the nodes into the model
    let mut m = Model::new();
    m.nodes.push(Box::new(in1));
    m.nodes.push(Box::new(in2));

    // Link the nodes
    println!("Upstream={id1}, Downstream={id2}");
    m.add_link(id1, id2);

    // Now run the model
    m.run();

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
    //in1.inflow_ts = Some(inflow_ts1);

    let mut in2 = InflowNode::new();
    //in2.inflow_ts = Some(inflow_ts2);

    let id1 = in1.get_id();
    let id2 = in2.get_id();

    // Now create a model and put the nodes into the model
    let mut m = Model::new();
    m.nodes.push(Box::new(in2));
    m.nodes.push(Box::new(in1));

    // Link the nodes
    println!("Upstream={id1}, Downstream={id2}");
    m.add_link(id1, id2);

    // Now run the model
    m.run();

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
    m.add_node(Box::new(n));

    //Specify some outputs
    m.outputs.push("node.my_inflow_node.usflow".to_owned());
    m.outputs.push("node.my_inflow_node.dsflow".to_owned());

    //Configure the model, clone it, run both, and compare the results.
    m.configure();
    let mut m2 = m.clone();

    //Check the results of m
    m.run();
    let ds_idx = m.data_cache.get_series_idx("node.my_inflow_node.dsflow", false).unwrap();
    let ans = m.data_cache.series[ds_idx].clone();
    assert_eq!(ans.len(), 6);
    assert_eq!(ans.sum(), 38.1);

    //Check the results of m2
    m2.run();
    let ds_idx = m2.data_cache.get_series_idx("node.my_inflow_node.dsflow", false).unwrap();
    let ans = m2.data_cache.series[ds_idx].clone();
    assert_eq!(ans.len(), 6);
    assert_eq!(ans.sum(), 38.1);
}