use crate::model::Model;
use crate::nodes::inflow_node::InflowNode;
use crate::nodes::blackhole_node::BlackholeNode;
use crate::nodes::NodeEnum;
use crate::model_inputs::DynamicInput;
use crate::io::ini_model_io::IniModelIO;

/// Test that a typo in a data reference (using ".csv" instead of "_csv" in the path)
/// produces a helpful error message at configure time, rather than a runtime panic.
///
/// This test uses two real model files:
/// - picnic_sacr_working.ini: uses correct path `data.formatted_11000A_csv.by_index.1`
/// - picnic_sacr_broken.ini: uses incorrect path `data.formatted_11000A.csv.by_index.1`
#[test]
fn test_broken_model_file_invalid_data_reference() {
    let io = IniModelIO::new();

    // The broken model should fail at configure time with a helpful error
    let mut broken_model = io.read_model_file("./src/tests/example_models/8/picnic_sacr_broken.ini")
        .expect("Should be able to parse the model file");

    let result = broken_model.configure();

    assert!(result.is_err(),
        "Expected configure() to return an error for invalid data reference");

    let error_message = result.unwrap_err();
    assert!(
        error_message.to_lowercase().contains("data.formatted_11000a.csv.by_index.1"),
        "Error message should contain the invalid reference name. Got: {}",
        error_message
    );
    assert!(
        error_message.contains("not found"),
        "Error message should indicate the reference was not found. Got: {}",
        error_message
    );
}

/// Test that the working model file configures and runs successfully
#[test]
fn test_working_model_file_runs_successfully() {
    let io = IniModelIO::new();

    let mut working_model = io.read_model_file("./src/tests/example_models/8/picnic_sacr_working.ini")
        .expect("Should be able to parse the model file");

    working_model.configure().expect("Working model should configure successfully");
    working_model.run().expect("Working model should run successfully");
}


/// Test that referencing a non-existent data series (e.g. due to a typo) produces
/// a helpful error message at configure time, rather than a runtime panic.
#[test]
fn test_invalid_data_reference_caught_at_configure() {
    // Create a model and load some real data
    let mut model = Model::new();
    model.load_input_data("./src/tests/example_data/test.csv")
        .expect("Failed to load test data");

    // Create an inflow node with a TYPO in the data reference
    // The correct path would be "data.test_csv.by_name.value"
    // We intentionally use "data.test_csv.by_name.valuee" (extra 'e')
    let mut n = InflowNode::new();
    n.name = "my_inflow_node".to_string();
    n.inflow_input = DynamicInput::from_string(
        "data.test_csv.by_name.valuee",  // <-- typo: "valuee" instead of "value"
        &mut model.data_cache,
        true, None
    ).expect("Failed to parse inflow expression");
    let n_idx = model.add_node(NodeEnum::InflowNode(n));

    // Add a blackhole to complete the network
    let mut bh = BlackholeNode::new();
    bh.name = "blackhole".to_string();
    let bh_idx = model.add_node(NodeEnum::BlackholeNode(bh));
    model.add_link(n_idx, bh_idx, 0, 0);

    // Configure should fail with a helpful error message
    let result = model.configure();

    assert!(result.is_err(), "Expected configure() to return an error for invalid data reference");

    let error_message = result.unwrap_err();
    assert!(
        error_message.contains("data.test_csv.by_name.valuee"),
        "Error message should contain the invalid reference name. Got: {}",
        error_message
    );
    assert!(
        error_message.contains("Could not find input data"),
        "Error message should indicate the reference was not found. Got: {}",
        error_message
    );
}


/// Test that a model with valid data references configures and runs successfully
#[test]
fn test_valid_data_reference_works() {
    let mut model = Model::new();
    model.load_input_data("./src/tests/example_data/test.csv")
        .expect("Failed to load test data");

    // Create an inflow node with a CORRECT data reference
    let mut n = InflowNode::new();
    n.name = "my_inflow_node".to_string();
    n.inflow_input = DynamicInput::from_string(
        "data.test_csv.by_name.value",  // Correct reference
        &mut model.data_cache,
        true, None
    ).expect("Failed to parse inflow expression");
    let n_idx = model.add_node(NodeEnum::InflowNode(n));

    // Add a blackhole to complete the network
    let mut bh = BlackholeNode::new();
    bh.name = "blackhole".to_string();
    let bh_idx = model.add_node(NodeEnum::BlackholeNode(bh));
    model.add_link(n_idx, bh_idx, 0, 0);

    // Specify outputs
    model.outputs.push("node.my_inflow_node.dsflow".to_string());

    // Configure and run should succeed
    model.configure().expect("Configure should succeed with valid references");
    model.run().expect("Run should succeed with valid references");

    // Verify we got results
    let ds_idx = model.data_cache.get_existing_series_idx("node.my_inflow_node.dsflow")
        .expect("Should have dsflow output");
    assert!(model.data_cache.series[ds_idx].len() > 0, "Should have output data");
}


/// Test that multiple invalid references are caught (first one reported)
#[test]
fn test_multiple_invalid_references_caught() {
    let mut model = Model::new();
    model.load_input_data("./src/tests/example_data/test.csv")
        .expect("Failed to load test data");

    // Create an inflow node with an invalid reference
    let mut n1 = InflowNode::new();
    n1.name = "inflow1".to_string();
    n1.inflow_input = DynamicInput::from_string(
        "data.nonexistent_file.by_name.col1",
        &mut model.data_cache,
        true, None
    ).expect("Failed to parse expression");
    let n1_idx = model.add_node(NodeEnum::InflowNode(n1));

    // Create another inflow node with a different invalid reference
    let mut n2 = InflowNode::new();
    n2.name = "inflow2".to_string();
    n2.inflow_input = DynamicInput::from_string(
        "data.another_missing.by_index.0",
        &mut model.data_cache,
        true, None
    ).expect("Failed to parse expression");
    let n2_idx = model.add_node(NodeEnum::InflowNode(n2));

    // Add blackholes to complete the network
    let mut bh1 = BlackholeNode::new();
    bh1.name = "blackhole1".to_string();
    let bh1_idx = model.add_node(NodeEnum::BlackholeNode(bh1));
    model.add_link(n1_idx, bh1_idx, 0, 0);

    let mut bh2 = BlackholeNode::new();
    bh2.name = "blackhole2".to_string();
    let bh2_idx = model.add_node(NodeEnum::BlackholeNode(bh2));
    model.add_link(n2_idx, bh2_idx, 0, 0);

    // Configure should fail
    let result = model.configure();
    assert!(result.is_err(), "Expected configure() to return an error");

    let error_message = result.unwrap_err();
    // Should report at least one of the invalid references
    assert!(
        error_message.contains("Could not find input data"),
        "Error message should indicate reference was not found. Got: {}",
        error_message
    );
}
