use crate::model::Model;
use crate::io::csv_io;
use crate::nodes::sacramento_node::SacramentoNode;
use crate::nodes::NodeEnum;


/// Create a Sacramento node, add it to a model, and test results
/// against a data read from a CSV file.
#[test]
fn test_sacramento_node_with_timeseries() {

    //Creat a new model. Add some data.
    let mut m = Model::new();
    m.load_input_data("./src/tests/example_data/fors/rain_infilled.csv").expect("TODO: panic message");
    m.load_input_data("./src/tests/example_data/fors/mpot_rolled.csv").expect("TODO: panic message");

    //Create a node and add it to the model
    let mut n = SacramentoNode::new();
    n.name = "my_sac_node".to_owned();
    n.area_km2 = 228.0;
    n.rain_mm_def.name = "data.rain_infilled_csv.by_name.value".to_owned(); //This is what the inflow node wants to look at
    n.evap_mm_def.name = "data.mpot_rolled_csv.by_name.value".to_owned();
    n.sacramento_model.set_params(0.0,45.0,60.0,0.01,
                 0.01,150.0,0.0,0.11,
                 1.5,0.0,0.2,0.01,
                 25.0,0.2,47.0,15.0,0.1);
    m.add_node(NodeEnum::SacramentoNode(n));

    //Specify some outputs
    m.outputs.push("node.my_sac_node.dsflow".to_owned());

    //Configure and run the model
    m.configure().expect("Configuration error");
    m.run().expect("Simulation error");

    //Check the results
    let dsflow_idx = m.data_cache.get_series_idx("node.my_sac_node.dsflow", false).unwrap();
    let total_runoff = m.data_cache.series[dsflow_idx].sum();
    let correct_answer = &csv_io::read_ts("./src/tests/example_data/fors/modelled_flow.csv").unwrap()[0];
    let correct_answer_total_runoff = correct_answer.sum();
    //println!("total_runoff: {}", total_runoff);
    //println!("correct_answer: {}", correct_answer_total_runoff);
    assert!((total_runoff - correct_answer_total_runoff).abs() < 0.00001);
}



/// Create a Sacramento node, add it to a model, and test results
/// against a data read from a CSV file.
#[test]
fn test_sacramento_node_with_timeseries_by_index() {

    //Creat a new model. Add some data.
    let mut m = Model::new();
    m.load_input_data("./src/tests/example_data/fors/rain_infilled.csv").expect("TODO: panic message");
    m.load_input_data("./src/tests/example_data/fors/mpot_rolled.csv").expect("TODO: panic message");

    //Create a node and add it to the model
    let mut n = SacramentoNode::new();
    n.name = "my_sac_node".to_owned();
    n.area_km2 = 228.0;
    n.rain_mm_def.name = "data.rain_infilled_csv.by_index.1".to_owned(); //This is what the inflow node wants to look at
    n.evap_mm_def.name = "data.mpot_rolled_csv.by_index.1".to_owned();
    n.sacramento_model.set_params(0.0,45.0,60.0,0.01,
                                  0.01,150.0,0.0,0.11,
                                  1.5,0.0,0.2,0.01,
                                  25.0,0.2,47.0,15.0,0.1);
    m.add_node(NodeEnum::SacramentoNode(n));

    //Specify some outputs
    m.outputs.push("node.my_sac_node.dsflow".to_owned());

    //Configure and run the model
    m.configure().expect("Configuration error");
    m.run().expect("Simulation error");

    //Check the results
    let dsflow_idx = m.data_cache.get_series_idx("node.my_sac_node.dsflow", false).unwrap();
    let total_runoff = m.data_cache.series[dsflow_idx].sum();
    let correct_answer = &csv_io::read_ts("./src/tests/example_data/fors/modelled_flow.csv").unwrap()[0];
    let correct_answer_total_runoff = correct_answer.sum();
    //println!("total_runoff: {}", total_runoff);
    //println!("correct_answer: {}", correct_answer_total_runoff);
    assert!((total_runoff - correct_answer_total_runoff).abs() < 0.00001);
}