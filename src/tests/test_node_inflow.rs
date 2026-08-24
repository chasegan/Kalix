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
    m.load_input_data("./src/tests/example_data/test.csv", None).expect("TODO: panic message");

    //Create an inflow node and add it to the model
    let mut n = InflowNode::new();
    n.name = "my_inflow_node".to_owned();
    n.inflow_input = DynamicInput::from_string("data.test_csv.by_name.value", &mut m.data_cache, true, None)
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
/// `expected_inflow` is an ordering forecast, not driving data, so it must NOT
/// be flagged as a critical input: critical inputs are what
/// `Model::auto_determine_simulation_period` derives the run period from, and a
/// forecast has no business setting the simulation's start and end. `inflow`,
/// which IS driving data, stays critical.
///
/// Pins the 2026-08 fix. The failure mode it guards is quiet: a model with no
/// explicit `start`/`end` whose only data-backed reference is an
/// `expected_inflow` would silently take its period from the forecast series,
/// or — once that stops counting — fail with "There is no critical input data".
#[test]
fn test_expected_inflow_is_not_a_critical_input() {
    // No [data] section and no CSV on disk: the series are registered (and
    // flagged) at parse, which is the step under test. Loading the values
    // would need configure(), which this test deliberately does not reach.
    let ini = r#"
[kalix]
start = 2020-01-01
end = 2020-01-04

[node.inf]
type = inflow
loc = 0, 0
inflow = data.src_csv.by_name.driver
expected_inflow = data.src_csv.by_name.forecast
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 10
"#;
    let model = crate::io::ini_model_io::IniModelIO::read_model_string(ini)
        .expect("model should load");
    let critical = model.data_cache.get_critical_input_names();

    assert!(critical.contains(&"data.src_csv.by_name.driver"),
        "`inflow` is driving data and must stay critical, got {:?}", critical);
    assert!(!critical.contains(&"data.src_csv.by_name.forecast"),
        "`expected_inflow` is a forecast and must not set the simulation period, got {:?}", critical);
}

/// The confluence node's `expected_inflow` follows the same rule as the inflow
/// node's — it is the same ordering adjustment, so it must not become a
/// critical input either.
#[test]
fn test_confluence_expected_inflow_is_not_a_critical_input() {
    let ini = r#"
[kalix]
start = 2020-01-01
end = 2020-01-04

[node.a]
type = inflow
loc = 0, 0
inflow = data.src_csv.by_name.driver
ds_1 = conf

[node.b]
type = inflow
loc = 10, 0
inflow = 5
ds_1 = conf

[node.conf]
type = confluence
loc = 5, 10
expected_inflow = data.src_csv.by_name.forecast
ds_1 = sink

[node.sink]
type = blackhole
loc = 5, 20
"#;
    let model = crate::io::ini_model_io::IniModelIO::read_model_string(ini)
        .expect("model should load");
    let critical = model.data_cache.get_critical_input_names();

    assert!(critical.contains(&"data.src_csv.by_name.driver"),
        "`inflow` is driving data and must stay critical, got {:?}", critical);
    assert!(!critical.contains(&"data.src_csv.by_name.forecast"),
        "confluence `expected_inflow` must not set the simulation period, got {:?}", critical);
}
