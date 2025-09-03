use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

#[test]
fn test_model_1_io_ini_read() {
    let ini_reader = IniModelIO::new();

    //Read the model
    let model_filename = "./src/tests/example_models/1/first_model.ini";
    println!("model_file = {}", model_filename);
    let mut m= match  ini_reader.read_model(model_filename) {
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

    let config = m.configure();
    println!("Config: {:?}", config);

    m.run();
    println!("Done!");

    //Check the number of nodes
    assert_eq!(m.nodes.len(), 1);

    // //Check the results
    // let ds_idx = m.data_cache.get_series_idx("node.my_inflow_node.dsflow", false).unwrap();
    // let ans = &m.data_cache.series[ds_idx];
    // assert_eq!(ans.len(), 6);
    // assert_eq!(ans.sum(), 38.1);
    // println!("Timestamps: {:?}", ans.timestamps);
    //
    // //Write the results
    // m.write_outputs("./src/tests/example_models/2/output.csv").expect("Csv write failed");
}

#[test]
fn test_model_2_io_ini_read() {
    let ini_reader = IniModelIO::new();
    let mut m = ini_reader.read_model("./src/tests/example_models/2/model.ini").unwrap();
    m.configure();
    m.run();

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