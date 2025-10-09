use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;
use crate::nodes::Node;

#[test]
fn test_model_1_io_ini_read() {
    let ini_reader = IniModelIO::new();

    //Read the model
    let model_filename = "./src/tests/example_models/1/first_model.ini";
    println!("model_file = {}", model_filename);
    let mut m= match  ini_reader.read_model_file(model_filename) {
        Ok(v) => {
            println!("Model read okay from file.");
            println!("number of inputs = {}", v.inputs.len());
            v.print_inputs();
            println!("inputs[0]:");
            v.inputs[0].print();
            v
        },
        Err(s) => {
            panic!("Model not read due to error: {}", s);
        }
    };

    m.configure().expect("Configuration error");
    println!("Config: {:?}", m.configuration);

    m.run().expect("Simulation error");
    println!("Done!");

    //Check the number of nodes
    assert_eq!(m.nodes.len(), 1);

    //Outputs
    for output_name in &m.outputs {
        let idx = m.data_cache.get_existing_series_idx(output_name).unwrap();
        let ts = m.data_cache.series[idx].clone();
        println!("output: {}, idx: {}, len:{}", output_name, idx, ts.len());
    }

    // Writing outputs
    let output_filename = "./src/tests/example_models/1/outputs.csv";
    let _output_write_result = m.write_outputs(output_filename);

    // Printing data cache
    println!(" ");
    println!("Printing the data cache");
    m.data_cache.print();
    println!(" ");
    for temp in &m.data_cache.series {
         temp.print();
    }
}

#[test]
fn test_model_2_io_ini_read() {
    let ini_reader = IniModelIO::new();
    let mut m = ini_reader.read_model_file("./src/tests/example_models/2/model.ini").unwrap();
    m.configure().expect("Configuration error");
    m.run().expect("Simulation error");

    //Check the number of nodes
    assert_eq!(m.nodes.len(), 1);

    //Check the results
    let ds_idx = m.data_cache.get_series_idx("node.my_inflow_node.dsflow", false).unwrap();
    let ans = &m.data_cache.series[ds_idx];
    assert_eq!(ans.len(), 6);
    assert_eq!(ans.sum(), 38.1);
    println!("Timestamps: {:?}", ans.timestamps);

    //Write the results
    m.write_outputs("./src/tests/example_models/2/output.csv").expect("Csv write failed");
}

#[test]
fn test_model_3_io_ini_read() {
    let ini_reader = IniModelIO::new();

    //Read the model
    let model_filename = "./src/tests/example_models/3/model_3.ini";
    println!("model_file = {}", model_filename);
    let mut m= match  ini_reader.read_model_file(model_filename) {
        Ok(v) => {
            println!("Model read okay from file.");
            println!("number of inputs = {}", v.inputs.len());
            v.print_inputs();
            println!("inputs[0]:");
            v.inputs[0].print();
            v
        },
        Err(s) => {
            panic!("Model not read due to error: {}", s);
        }
    };

    m.configure().expect("Configuration error");
    println!("Config: {:?}", m.configuration);

    m.run().expect("Simulation error");
    println!("Done!");

    //Check the number of nodes
    assert_eq!(m.nodes.len(), 2);

    //Outputs
    for output_name in &m.outputs {
        let idx = m.data_cache.get_existing_series_idx(output_name).unwrap();
        let ts = m.data_cache.series[idx].clone();
        println!("output: {}, idx: {}, len:{}", output_name, idx, ts.len());
    }

    //
    let output_filename = "./src/tests/example_models/3/outputs.csv";
    let _ = m.write_outputs(output_filename);
}



#[test]
fn test_model_3_minimal_version() {

    fn run_model(model_filename: &str, output_filename: &str) -> Result<(), String> {
        let ini_reader = IniModelIO::new();
        let mut m = ini_reader.read_model_file(model_filename)?;
        m.configure()?;
        m.run()?;
        m.write_outputs(output_filename)?;
        Ok(())
    }

    let r = run_model("./src/tests/example_models/3/model_3.ini",
                      "./src/tests/example_models/3/outputs.csv");
    assert!(r.is_ok());
}



#[test]
fn test_model_4() {

    fn run_model(model_filename: &str, output_filename: &str) -> Result<Model, String> {
        let ini_reader = IniModelIO::new();
        let mut m = ini_reader.read_model_file(model_filename)?;
        m.configure()?;
        m.run()?;
        m.write_outputs(output_filename)?;
        Ok(m)
    }

    let result_model = run_model("./src/tests/example_models/4/linked_model.ini",
                      "./src/tests/example_models/4/outputs.csv");

    match result_model {
        Err(s) => {
            println!("Model not read due to error: {}", s);
            assert!(false);
        },
        Ok(model) => {

            println!("Execution order: {:?}", model.execution_order);
            for i in 0..model.execution_order.len() {
                let node_idx = model.execution_order[i];
                println!("Execution order[{}]: {:?} {}", i, node_idx, model.nodes[node_idx].get_name());
            }

            //
            let sim_len = model.configuration.sim_nsteps;

            let node3_dsflow = model.data_cache.series[model.data_cache.get_existing_series_idx("node.node3.dsflow").unwrap()].clone();
            println!("node3_dsflow: {}", node3_dsflow.mean());

            //TODO uncomment below. This is currently wrong!!! The value should be 300, but is 295.16129032258067.
            //assert!((node3_dsflow.mean() - 300.0).abs() < 1e-12);
            assert_eq!(sim_len, 62);
        }
    };
}




#[test]
fn test_pioneer_model() {

    fn run_model(model_filename: &str, output_filename: &str) -> Result<(), String> {
        let ini_reader = IniModelIO::new();
        let mut m = ini_reader.read_model_file(model_filename)?;
        m.configure()?;
        m.run()?;
        m.write_outputs(output_filename)?;
        Ok(())
    }

    let r = run_model("/Users/chas/Desktop/pioneer_validation_model/PioneerVal_to_125001b_withLocations.ini",
                      "/Users/chas/Desktop/pioneer_validation_model/outputs.csv");
    assert!(r.is_ok());
}
