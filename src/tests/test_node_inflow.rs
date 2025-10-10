use crate::model::Model;
use crate::nodes::inflow_node::InflowNode;
use crate::nodes::NodeEnum;
use crate::model_inputs::DynamicInput;


/// Create an inflow node, add it to a model, and drive the inflow
/// node manually using timeseries data read from a CSV file.
#[test]
fn test_inflow_node_with_timeseries() {

    //Creat a new model. Add some data.
    let mut m = Model::new();
    m.load_input_data("./src/tests/example_data/test.csv").expect("TODO: panic message");

    //Create an inflow node and add it to the model
    let mut n = InflowNode::new();
    n.name = "my_inflow_node".to_owned();
    n.inflow_def = DynamicInput::from_string("data.test_csv.by_name.value", &mut m.data_cache, true)
        .expect("Failed to parse inflow expression");
    m.add_node(NodeEnum::InflowNode(n));

    //Specify some outputs
    m.outputs.push("node.my_inflow_node.usflow".to_owned());
    m.outputs.push("node.my_inflow_node.dsflow".to_owned());

    //Configure and run the model
    m.configure().expect("Configuration error");
    println!("Configuration:\n{:#?}", &m.configuration);
    m.run().expect("Simulation error");

    //Check the results
    let ds_idx = m.data_cache.get_series_idx("node.my_inflow_node.dsflow", false).unwrap();
    let ans = m.data_cache.series[ds_idx].clone();
    assert_eq!(ans.len(), 6);
    assert_eq!(ans.sum(), 38.1);
}