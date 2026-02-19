use std::collections::HashMap;
use crate::model::Model;
use crate::nodes::inflow_node::InflowNode;
use crate::nodes::gr4j_node::Gr4jNode;
use crate::nodes::NodeEnum;
use crate::io::csv_io::csv_string_to_f64_vec;
use crate::model_inputs::DynamicInput;

#[test]
fn test_model_with_function() {

    //Create model
    let mut model = Model::new();
    let mut regression_results: HashMap<String, (usize, f64, f64)> = HashMap::new();

    //Add rainfall, evap, and flow data (matching INI file)
    let _ = model.load_input_data("./src/tests/example_models/1/flows.csv");
    let _ = model.load_input_data("./src/tests/example_models/4/rex_mpot.csv");
    let _ = model.load_input_data("./src/tests/example_models/4/rex_rain.csv");
    let _ = model.load_input_data("./src/tests/example_models/1/constants.csv");
    let _ = model.load_input_data("./src/tests/example_models/1/constants_1_2_3_4_5_6.csv");

    //Add node6_gr4j
    let _node6_idx: usize;
    {
        let mut n = Gr4jNode::new();
        n.name = "node6_gr4j".to_string();
        n.rain_mm_input = DynamicInput::from_string("data.rex_rain_csv.by_name.value", &mut model.data_cache, true, None)
            .expect("Failed to parse rain expression");
        // Test DynamicInput with a constant expression (evap data is constant 5.0)
        n.evap_mm_input = DynamicInput::from_string("2 + 3", &mut model.data_cache, true, None)
            .expect("Failed to parse evap expression");
        n.area_km2 = 80.0;
        let params = csv_string_to_f64_vec("350.0, 0.0, 90.0, 1.7").unwrap();
        n.gr4j_model.x1 = params[0];
        n.gr4j_model.x2 = params[1];
        n.gr4j_model.x3 = params[2];
        n.gr4j_model.x4 = params[3];
        _node6_idx = model.add_node(NodeEnum::Gr4jNode(n));

        //Node results
        let result_name = "node.node6_gr4j.runoff_depth".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 3.157898981745616, 12.68888593633923));

        let result_name = "node.node6_gr4j.ds_1".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 252.63191853964588, 1015.1108749071437));
    }

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


#[test]
fn test_model_with_changing_constant() {

    //Create model
    let mut model = Model::new();
    let mut regression_results: HashMap<String, (usize, f64, f64)> = HashMap::new();

    //Add file data
    let _ = model.load_input_data("./src/tests/example_models/1/constants.csv");

    //Add data_cache constants
    model.data_cache.constants.set_value("c.pi", 3.14);
    model.data_cache.constants.set_value("c.run_counter", 1.0);

    //Add node1
    let node1_idx: usize;
    {
        let mut n = InflowNode::new();
        n.name = "node1_inflow".to_string();
        n.inflow_input = DynamicInput::from_string("0 * data.constants_csv.by_index.1", &mut model.data_cache, true, None)
            .expect("Failed to parse expression");
        node1_idx = model.add_node(NodeEnum::InflowNode(n));

        //Node results
        let result_name = "node.node1_inflow.ds_1".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 0.0, 0.0));
    }

    //Add node2
    let node2_idx: usize;
    {
        let mut n = InflowNode::new();
        n.name = "node2_inflow".to_string();
        n.inflow_input = DynamicInput::from_string("c.run_counter * c.run_counter", &mut model.data_cache, true, None)
            .expect("Failed to parse expression");
        node2_idx = model.add_node(NodeEnum::InflowNode(n));

        //Node results
        let result_name = "node.node2_inflow.ds_1".to_string();
        model.outputs.push(result_name.clone());
        regression_results.insert(result_name, (48824, 1.0, 0.0)); //c.run_counter * c.run_counter = 1.0
    }
    model.add_link(node1_idx, node2_idx, 0, 0);

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

    /////////////////////////////////////////////////////////// Change constant
    /////////////////////////////////////////////////////////// Run again
    /////////////////////////////////////////////////////////// Check results

    model.data_cache.constants.set_value("c.run_counter", 2.0);
    model.run().expect("Simulation error");

    {
        let ds_idx = model.data_cache.get_existing_series_idx("node.node2_inflow.ds_1").unwrap();
        let len = model.data_cache.series[ds_idx].len();
        let mean = model.data_cache.series[ds_idx].mean();
        let std_dev = model.data_cache.series[ds_idx].std_dev();

        let new_answer = (len, mean, std_dev);
        let old_answer = (48824, 4.0, 0.0); //c.run_counter * c.run_counter = 4.0
        println!("\nWith changed constant value");
        println!("new_answer: {:?}", new_answer);
        println!("old_answer: {:?}", old_answer);
        assert_eq!(new_answer, old_answer);
    }
}
