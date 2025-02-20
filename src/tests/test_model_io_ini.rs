use crate::io::ini_model_io::IniModelIO;

#[test]
fn test_model_io_ini_read() {
    let ini_reader = IniModelIO::new();
    let mut m = ini_reader.read_model("./src/tests/example_models/2/model.ini").unwrap();
    m.configure();
    m.run();

    //Check the number of nodes
    assert_eq!(m.nodes.len(), 1);

    //Check the results
    let ds_idx = m.data_cache.get_series_idx("node.my_inflow_node.dsflow", false).unwrap();
    let ans = m.data_cache.series[ds_idx].clone();
    assert_eq!(ans.len(), 6);
    assert_eq!(ans.sum(), 38.1);
    println!("Timestamps: {:?}", ans.timestamps);

    //Write the results
    m.write_outputs("./src/tests/example_models/2/output.csv").expect("Csv write failed");
}