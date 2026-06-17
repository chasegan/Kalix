#[test]
fn test_load() {
    use crate::timeseries_input::TimeseriesInput;
    let vts = match TimeseriesInput::load("./src/tests/example_models/1/rex_mpot.csv", None) {
        Ok(v) => v,
        Err(e) => panic!("{}", e),
    };

    //Print
    println!("vts[0].timeseries.len() = {}", vts[0].timeseries.len());
    println!("vts[0].len() = {}", vts[0].len());

    // Assert
    let sum = vts[0].timeseries.sum();
    assert!((sum - 251302.61119047567).abs() < 0.00001);
}


#[test]
fn test_load_2() {
    use crate::timeseries_input::TimeseriesInput;
    let vts = match TimeseriesInput::load("./src/tests/example_models/1/rex_rain.csv", None) {
        Ok(v) => v,
        Err(e) => panic!("{}", e),
    };

    //Print
    println!("vts[0].timeseries.len() = {}", vts[0].timeseries.len());
    println!("vts[0].len() = {}", vts[0].len());

    // Assert
    let sum = vts[0].timeseries.sum();
    println!("sum = {}", sum);
    assert!((sum - 310683.1999999939).abs() < 0.00001);
}

#[test]
fn test_name_sanitization() {
    use crate::timeseries_input::TimeseriesInput;

    // Load a CSV file
    let vts = match TimeseriesInput::load("./src/tests/example_models/1/rex_rain.csv", None) {
        Ok(v) => v,
        Err(e) => panic!("{}", e),
    };

    // Check that filename is sanitized (dots converted to underscores)
    // "rex_rain.csv" should become "rex_rain_csv"
    assert_eq!(vts[0].source_name, "rex_rain_csv");

    // Check that paths are constructed correctly with sanitization
    // The paths should only contain lowercase a-z, 0-9, underscores, and dots (as delimiters)
    assert!(vts[0].full_colname_path.chars().all(|c|
        c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '.'
    ));
    assert!(vts[0].full_colindex_path.chars().all(|c|
        c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '.'
    ));

    // Print for debugging
    println!("source_name: {}", vts[0].source_name);
    println!("full_colname_path: {}", vts[0].full_colname_path);
    println!("full_colindex_path: {}", vts[0].full_colindex_path);
}

#[test]
fn test_timeseries_input_with_alias() {
    use crate::timeseries_input::TimeseriesInput;

    // Load a file with an alias
    let inputs = TimeseriesInput::load("./src/tests/example_data/test.csv", Some("mydata"))
        .expect("Failed to load with alias");

    assert_eq!(inputs.len(), 1);
    let input = &inputs[0];

    // Check that alias is set
    assert_eq!(input.alias, Some("mydata".to_string()));

    // Check auto-generated paths still exist
    assert_eq!(input.full_colname_path, "data.test_csv.by_name.value");
    assert_eq!(input.full_colindex_path, "data.test_csv.by_index.1");

    // Check alias paths are generated
    assert_eq!(
        input.alias_colname_path,
        Some("data.mydata.by_name.value".to_string())
    );
    assert_eq!(
        input.alias_colindex_path,
        Some("data.mydata.by_index.1".to_string())
    );
}

/// Create a model and load data with an alias
#[test]
fn test_model_with_aliased_data() {
    use crate::model::Model;
    use crate::model_inputs::DynamicInput;
    use crate::nodes::inflow_node::InflowNode;
    use crate::nodes::NodeEnum;
    
    let mut m = Model::new();
    m.load_input_data("./src/tests/example_data/test.csv", Some("flow_data"))
        .expect("Failed to load input data with alias");

    // Create an inflow node using the aliased reference
    let mut n = InflowNode::new();
    n.name = "my_inflow".to_string();
    n.inflow_input = DynamicInput::from_string(
        "data.flow_data.by_name.value",
        &mut m.data_cache,
        true,
        None,
    )
    .expect("Failed to parse aliased reference");
    m.add_node(NodeEnum::InflowNode(n));

    // Specify outputs
    m.outputs.push("node.my_inflow.dsflow".to_string());

    // Configure and run
    m.configure().expect("Configuration error");
    m.run().expect("Simulation error");

    // Check results
    let ds_idx = m
        .data_cache
        .get_series_idx("node.my_inflow.dsflow", false)
        .unwrap();
    let ans = m.data_cache.series[ds_idx].clone();
    assert_eq!(ans.len(), 6);
    assert_eq!(ans.sum(), 38.1);
}
