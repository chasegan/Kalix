use crate::model::Model;
use crate::nodes::routing_node::RoutingNode;
use crate::nodes::inflow_node::InflowNode;
use crate::nodes::{Link, Node, NodeEnum};


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

    // Link the nodes
    println!("Inflow={}, Routing={}", n.name, r.name);
    n.ds_link_primary = Link::new_named_link(&r.name);

    // Now create a model and put the nodes into the model
    let mut m = Model::new();
    m.nodes.push(NodeEnum::InflowNode(n));
    m.nodes.push(NodeEnum::RoutingNode(r));

    // TODO: Configure inflow data
    // let timeseries_vec = crate::io::csv_io::read_ts("./src/tests/example_data/test3.csv").expect("Error");
    // let inflow_ts = timeseries_vec[0].clone();
    // m <---- add data here and tell the inflow node how to find it.


    // Now run the model
    m.run();

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