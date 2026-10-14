use crate::model::Model;
use crate::io::csv_io;
use crate::nodes::sacramento_node::SacramentoNode;
use crate::hydrology::rainfall_runoff::sacramento::Sacramento;
use crate::nodes::NodeEnum;
use crate::model_inputs::DynamicInput;


/// Create a Sacramento node, add it to a model, and test results
/// against a data read from a CSV file.
#[test]
fn test_sacramento_node_with_timeseries() {

    //Creat a new model. Add some data.
    let mut m = Model::new();
    m.load_input_data("./src/tests/example_data/fors/rain_infilled.csv", None).expect("TODO: panic message");
    m.load_input_data("./src/tests/example_data/fors/mpot_rolled.csv", None).expect("TODO: panic message");

    //Create a node and add it to the model
    let mut n = SacramentoNode::new();
    n.name = "my_sac_node".to_owned();
    n.area_km2 = 228.0;
    n.rain_mm_input = DynamicInput::from_string("data.rain_infilled_csv.by_name.value", &mut m.data_cache, true, None)
        .expect("Failed to parse rain expression");
    n.evap_mm_input = DynamicInput::from_string("data.mpot_rolled_csv.by_name.value", &mut m.data_cache, true, None)
        .expect("Failed to parse evap expression");
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
    m.load_input_data("./src/tests/example_data/fors/rain_infilled.csv", None).expect("TODO: panic message");
    m.load_input_data("./src/tests/example_data/fors/mpot_rolled.csv", None).expect("TODO: panic message");

    //Create a node and add it to the model
    let mut n = SacramentoNode::new();
    n.name = "my_sac_node".to_owned();
    n.area_km2 = 228.0;
    n.rain_mm_input = DynamicInput::from_string("data.rain_infilled_csv.by_index.1", &mut m.data_cache, true, None)
        .expect("Failed to parse rain expression");
    n.evap_mm_input = DynamicInput::from_string("data.mpot_rolled_csv.by_index.1", &mut m.data_cache, true, None)
        .expect("Failed to parse evap expression");
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


/// Each soil moisture store output carries the right store: every recorded
/// step matches a standalone model driven by the same rain and evap, and stays
/// within the store's capacity.
#[test]
fn test_sacramento_node_store_outputs() {

    //Creat a new model. Add some data.
    let mut m = Model::new();
    m.load_input_data("./src/tests/example_data/fors/rain_infilled.csv", None).expect("TODO: panic message");
    m.load_input_data("./src/tests/example_data/fors/mpot_rolled.csv", None).expect("TODO: panic message");

    //Create a node and add it to the model
    let (lzfpm, lzfsm, lztwm, side, uzfwm, uztwm) = (45.0, 60.0, 150.0, 0.2, 25.0, 47.0);
    let set_params = |s: &mut Sacramento| {
        s.set_params(0.1,lzfpm,lzfsm,0.01,
                     0.01,lztwm,0.05,0.11,
                     1.5,0.0,side,0.01,
                     uzfwm,0.2,uztwm,15.0,0.1);
    };
    let mut n = SacramentoNode::new();
    n.name = "my_sac_node".to_owned();
    n.area_km2 = 228.0;
    n.rain_mm_input = DynamicInput::from_string("data.rain_infilled_csv.by_name.value", &mut m.data_cache, true, None)
        .expect("Failed to parse rain expression");
    n.evap_mm_input = DynamicInput::from_string("data.mpot_rolled_csv.by_name.value", &mut m.data_cache, true, None)
        .expect("Failed to parse evap expression");
    set_params(&mut n.sacramento_model);
    m.add_node(NodeEnum::SacramentoNode(n));

    //Specify some outputs
    let stores = ["uztw", "uzfw", "lztw", "lzfp", "lzfs"];
    for output in ["rain", "evap"].iter().chain(stores.iter()) {
        m.outputs.push(format!("node.my_sac_node.{}", output));
    }

    //Configure and run the model
    m.configure().expect("Configuration error");
    m.run().expect("Simulation error");

    //Read the results
    let mut series = |name: &str| -> Vec<f64> {
        let idx = m.data_cache.get_series_idx(&format!("node.my_sac_node.{}", name), false).unwrap();
        m.data_cache.series[idx].values.clone()
    };
    let rain = series("rain");
    let evap = series("evap");
    let recorded: Vec<Vec<f64>> = stores.iter().map(|s| series(s)).collect();
    let n_steps = rain.len();
    assert!(n_steps > 0);
    for (store, values) in stores.iter().zip(&recorded) {
        assert_eq!(values.len(), n_steps, "{}", store);
    }

    //Each output must match its own store in a standalone model, step by step.
    let mut reference = Sacramento::new();
    set_params(&mut reference);
    reference.initialize_state_empty();
    for i in 0..n_steps {
        reference.run_step(rain[i], evap[i]);
        let expected = [
            reference.get_uztw(),
            reference.get_uzfw(),
            reference.get_lztw(),
            reference.get_lzfp(),
            reference.get_lzfs(),
        ];
        for (j, store) in stores.iter().enumerate() {
            assert_eq!(recorded[j][i], expected[j], "step {}: {}", i, store);
        }
    }

    //The comparison above only pins each name to its store if the stores differ.
    for j in 0..stores.len() {
        for k in (j + 1)..stores.len() {
            assert!(recorded[j] != recorded[k], "{} and {} are identical series", stores[j], stores[k]);
        }
    }

    //Each store stays within its capacity. The lower zone free water stores
    //include the side component.
    let capacities = [uztwm, uzfwm, lztwm, lzfpm * (1.0 + side), lzfsm * (1.0 + side)];
    for (j, store) in stores.iter().enumerate() {
        let max = recorded[j].iter().cloned().fold(f64::MIN, f64::max);
        let min = recorded[j].iter().cloned().fold(f64::MAX, f64::min);
        assert!(min >= 0.0, "{}: min {}", store, min);
        assert!(max > 0.0 && max <= capacities[j] + 1e-9, "{}: max {} outside (0, {}]", store, max, capacities[j]);
    }
}
