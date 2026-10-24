use crate::model::Model;
use crate::nodes::inflow_node::InflowNode;
use crate::nodes::blackhole_node::BlackholeNode;
use crate::nodes::NodeEnum;
use crate::model_inputs::DynamicInput;
use crate::io::ini_model_io::IniModelIO;
use crate::io::model_input_swap::{self, InMemoryColumn};

/// Test that a typo in a data reference (using ".csv" instead of "_csv" in the path)
/// produces a helpful error message at configure time, rather than a runtime panic.
///
/// This test uses two real model files:
/// - picnic_sacr_working.ini: uses correct path `data.formatted_11000A_csv.by_index.1`
/// - picnic_sacr_broken.ini: uses incorrect path `data.formatted_11000A.csv.by_index.1`
#[test]
fn test_broken_model_file_invalid_data_reference() {
    // The broken model should fail at configure time with a helpful error
    let mut broken_model = IniModelIO::read_model_file("./src/tests/example_models/8/picnic_sacr_broken.ini")
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
    let mut working_model = IniModelIO::read_model_file("./src/tests/example_models/8/picnic_sacr_working.ini")
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
    model.load_input_data("./src/tests/example_data/test.csv", None)
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
    model.load_input_data("./src/tests/example_data/test.csv", None)
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
    model.load_input_data("./src/tests/example_data/test.csv", None)
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


/// A declared-but-unsupplied input (an empty-valued [data] entry with a bare
/// alias name) must load fine but be rejected at configure time, since nothing
/// in the engine can supply it.
#[test]
fn test_declared_input_not_supplied_fails_at_configure() {
    let ini = "\
[data]
observed_flows =

[node.src]
type = inflow
inflow = 1
ds_1 = sink

[node.sink]
type = blackhole
";
    let mut model = IniModelIO::read_model_string(ini)
        .expect("Model with a bare declaration should parse");

    // The declaration is recorded as a source with no columns.
    assert_eq!(model.input_columns().count(), 0);

    let err = model.configure().expect_err(
        "configure() must reject a declared-but-unsupplied input");
    assert!(
        err.contains("observed_flows") && err.contains("declared but not supplied"),
        "Error should name the unsupplied input. Got: {}",
        err
    );
}


/// Writes `csv` to a fresh temp file and returns its path.
fn write_temp_csv(name: &str, csv: &str) -> String {
    let dir = std::env::temp_dir().join("kalix_tests").join(uuid::Uuid::new_v4().to_string());
    std::fs::create_dir_all(&dir).unwrap();
    let path = dir.join(name);
    std::fs::write(&path, csv).unwrap();
    path.display().to_string()
}

/// A model with `[data]` entries `data`, an inflow fed by `inflow`, and an optional `[kalix]` block.
fn model_with_inputs(kalix: &str, data: &str, inflow: &str) -> Model {
    let ini = format!("{kalix}\n[data]\n{data}\n\n[node.src]\ntype = inflow\ninflow = {inflow}\nds_1 = sink\n\n[node.sink]\ntype = blackhole\n");
    IniModelIO::read_model_string(&ini).expect("Model should parse")
}

const ONE_ROW_CSV: &str = "date,flow\n2000-01-01,1.0\n";
const TWO_ROW_CSV: &str = "date,flow\n2000-01-01,1.0\n2000-01-02,2.0\n";

/// A one-row critical input has no timestep; configure used to divide by zero (#479).
#[test]
fn test_one_row_critical_input_fails_at_configure() {
    let path = write_temp_csv("flows.csv", ONE_ROW_CSV);
    let mut model = model_with_inputs("", &format!("flows = {path}"), "data.flows.by_name.flow");
    let err = model.configure().expect_err("configure() must reject a one-row input");
    assert_eq!(err, format!(
        "Input 'flows' ({path}) has only one row; at least two are needed to infer the timestep"));
}

/// With a start given, the period override divided by zero before the fill did.
#[test]
fn test_one_row_input_with_specified_start_fails_at_configure() {
    let path = write_temp_csv("flows.csv", ONE_ROW_CSV);
    let mut model = model_with_inputs(
        "[kalix]\nstart = 2000-01-01\n", &format!("flows = {path}"), "data.flows.by_name.flow");
    let err = model.configure().expect_err("configure() must reject a one-row input");
    assert!(err.contains("has only one row"), "Got: {}", err);
}

/// A one-row input nothing reads gets the same error, not a step mismatch.
#[test]
fn test_one_row_non_critical_input_fails_at_configure() {
    let good = write_temp_csv("good.csv", TWO_ROW_CSV);
    let one_row = write_temp_csv("spare.csv", ONE_ROW_CSV);
    let mut model = model_with_inputs(
        "", &format!("good = {good}\n{one_row}"), "data.good.by_name.flow");
    let err = model.configure().expect_err("configure() must reject a one-row input");
    assert_eq!(err, format!(
        "Input '{one_row}' has only one row; at least two are needed to infer the timestep"));
}

/// A header-only input used to underflow when sizing the simulation period.
#[test]
fn test_header_only_input_fails_at_configure() {
    let path = write_temp_csv("flows.csv", "date,flow\n");
    let mut model = model_with_inputs("", &format!("flows = {path}"), "data.flows.by_name.flow");
    let err = model.configure().expect_err("configure() must reject an input with no rows");
    assert_eq!(err, format!("Input 'flows' ({path}) has no data"));
}

/// A critical input with rows but no values used to underflow when sizing the period.
#[test]
fn test_all_blank_critical_input_fails_at_configure() {
    let path = write_temp_csv("flows.csv", "date,flow\n2000-01-01,\n2000-01-02,\n");
    let mut model = model_with_inputs("", &format!("flows = {path}"), "data.flows.by_name.flow");
    let err = model.configure().expect_err("configure() must reject a critical input with no values");
    assert_eq!(err, "No timestep has data for every critical input ('data.flows.by_name.flow').");
}

/// Critical inputs with no dates in common hit the same underflow.
#[test]
fn test_non_overlapping_critical_inputs_fail_at_configure() {
    let a = write_temp_csv("a.csv", TWO_ROW_CSV);
    let b = write_temp_csv("b.csv", "date,flow\n2005-01-01,1.0\n2005-01-02,2.0\n");
    let mut model = model_with_inputs(
        "", &format!("a = {a}\nb = {b}"), "data.a.by_name.flow + data.b.by_name.flow");
    let err = model.configure().expect_err("configure() must reject critical inputs with no overlap");
    assert!(err.starts_with("No timestep has data for every critical input"), "Got: {}", err);
}

/// Critical inputs on different timesteps used to panic in Timeseries::mask_with.
#[test]
fn test_critical_inputs_with_different_timesteps_fail_at_configure() {
    let a = write_temp_csv("a.csv", TWO_ROW_CSV);
    let h = write_temp_csv("h.csv", "date,flow\n2000-01-01T00:00:00,1.0\n2000-01-01T01:00:00,2.0\n");
    let mut model = model_with_inputs(
        "", &format!("a = {a}\nh = {h}"), "data.a.by_name.flow + data.h.by_name.flow");
    let err = model.configure().expect_err("configure() must reject mixed timesteps");
    assert_eq!(err, "Input 'data.h.by_name.flow' has a timestep of 3600s but 'data.a.by_name.flow' has 86400s. \
                     All inputs must share the same timestep.");
}

/// A non-critical input on a different timestep is caught when the data is filled, by name.
#[test]
fn test_non_critical_input_with_different_timestep_fails_at_configure() {
    let a = write_temp_csv("a.csv", TWO_ROW_CSV);
    let h = write_temp_csv("h.csv", "date,flow\n2000-01-01T00:00:00,1.0\n2000-01-01T01:00:00,2.0\n");
    let mut model = model_with_inputs("", &format!("a = {a}\nh = {h}"), "data.a.by_name.flow");
    let err = model.configure().expect_err("configure() must reject mixed timesteps");
    assert_eq!(err, "Input 'data.h.by_name.flow' has a timestep of 3600s but the simulation runs at 86400s. \
                     All inputs must share the same timestep.");
}

/// Writes a Pixie pair holding series `a` (three daily values) and `b` (`b_values`), then
/// rewrites `b`'s index row with `edit_b`, standing in for a .pxt from another tool.
fn write_temp_pixie(b_values: Vec<f64>, edit_b: impl Fn(&mut Vec<String>)) -> String {
    let dir = std::env::temp_dir().join("kalix_tests").join(uuid::Uuid::new_v4().to_string());
    std::fs::create_dir_all(&dir).unwrap();
    let base = dir.join("p").display().to_string();
    let series = |name: &str, values: Vec<f64>| {
        let mut ts = crate::timeseries::Timeseries::new(86400);
        ts.name = name.into();
        ts.start_timestamp = crate::tid::utils::wrap_to_u64(946_684_800);
        ts.values = values;
        ts
    };
    crate::io::pixie_io::write_series(&base, &[&series("a", vec![1.0, 2.0, 3.0]), &series("b", b_values)]).unwrap();
    let pxt = format!("{base}.pxt");
    let text = std::fs::read_to_string(&pxt).unwrap();
    let mut lines: Vec<String> = text.lines().map(String::from).collect();
    let mut fields: Vec<String> = lines[2].split(',').map(String::from).collect();
    edit_b(&mut fields);
    lines[2] = fields.join(",");
    std::fs::write(&pxt, lines.join("\n") + "\n").unwrap();
    pxt
}

/// Pixie series are independent, so a later empty series must be caught too.
#[test]
fn test_pixie_source_with_empty_later_series_fails_at_configure() {
    // Kalix's own writer gives an empty series unreadable dates; another tool might not.
    let pxt = write_temp_pixie(vec![], |f| { f[2] = "2000-01-01".into(); f[3] = "2000-01-01".into(); });
    let mut model = model_with_inputs("", &format!("p = {pxt}"), "data.p.by_name.b");
    let err = model.configure().expect_err("configure() must reject an empty series");
    assert_eq!(err, format!("Series 'b' of input 'p' ({pxt}) has no data"));
}

/// A later Pixie series with timestep 0 used to divide by zero once a start was given.
#[test]
fn test_pixie_source_with_zero_timestep_series_fails_at_configure() {
    let pxt = write_temp_pixie(vec![1.0, 2.0, 3.0], |f| f[4] = "0".into());
    let mut model = model_with_inputs(
        "[kalix]\nstart = 2000-01-01\n", &format!("p = {pxt}"), "data.p.by_name.b");
    let err = model.configure().expect_err("configure() must reject a zero timestep");
    assert_eq!(err, format!("Series 'b' of input 'p' ({pxt}) has a timestep of 0"));
}


/// Two rows are enough: the timestep comes from the gap between them.
#[test]
fn test_two_row_input_configures() {
    let path = write_temp_csv("flows.csv", TWO_ROW_CSV);
    let mut model = model_with_inputs("", &format!("flows = {path}"), "data.flows.by_name.flow");
    model.configure().expect("two rows must configure");
    assert_eq!(model.configuration.sim_stepsize, 86400);
    assert_eq!(model.configuration.sim_nsteps, 2);
}

/// Two rows with one blank value still have a timestep; the period is the one row with data.
#[test]
fn test_two_row_input_with_one_blank_runs_one_step() {
    let path = write_temp_csv("flows.csv", "date,flow\n2000-01-01,1.0\n2000-01-02,\n");
    let mut model = model_with_inputs("", &format!("flows = {path}"), "data.flows.by_name.flow");
    model.configure().expect("a two-row input with one value must configure");
    assert_eq!(model.configuration.sim_nsteps, 1);
}

/// A one-row Pixie series carries its timestep in the index, so unlike a one-row CSV it runs.
#[test]
fn test_one_row_pixie_series_configures() {
    let pxt = write_temp_pixie(vec![1.0], |_| {});
    let mut model = model_with_inputs("", &format!("p = {pxt}"), "data.p.by_name.b");
    model.configure().expect("a one-row Pixie series with a timestep must configure");
    assert_eq!(model.configuration.sim_stepsize, 86400);
    assert_eq!(model.configuration.sim_nsteps, 1);
}


/// A declaration doesn't need the `=` — the custom INI parser treats a bare
/// key line (no `=`, same syntax as a direct file path) and `alias =` as
/// identical (key present, value empty), and the model-loading code only
/// ever branches on the value being empty plus the key being a valid bare
/// name. So `observed_flows` on its own line must declare the alias exactly
/// like `observed_flows =` does.
#[test]
fn test_declared_input_without_equals_sign() {
    let ini = "\
[data]
observed_flows

[node.src]
type = inflow
inflow = 1
ds_1 = sink

[node.sink]
type = blackhole
";
    let mut model = IniModelIO::read_model_string(ini)
        .expect("Model with a bare (no '=') declaration should parse");

    assert_eq!(model.input_columns().count(), 0);

    let err = model.configure().expect_err(
        "configure() must reject a declared-but-unsupplied input");
    assert!(
        err.contains("observed_flows") && err.contains("declared but not supplied"),
        "Error should name the unsupplied input. Got: {}",
        err
    );
}


/// Round-trip fidelity: the enum -> [data] mapping must re-emit exactly what
/// the parser read — a bare declaration (with and without `=`), a direct file
/// path, and an aliased file path all survive a to_string() round trip.
#[test]
fn test_inputs_section_round_trips() {
    let ini = "\
[data]
observed_flows =
rainfall
./src/tests/example_data/test.csv
climate = ./src/tests/example_data/test.csv
";
    let model = IniModelIO::read_model_string(ini)
        .expect("Model should parse");
    let rendered = IniModelIO::model_to_string(&model);

    for line in [
        "observed_flows =",
        "rainfall",
        "./src/tests/example_data/test.csv",
        "climate = ./src/tests/example_data/test.csv",
    ] {
        assert!(
            rendered.contains(line),
            "Rendered model should preserve input line '{}'. Got:\n{}",
            line, rendered
        );
    }
}


/// `set_input()` must find a declared alias regardless of the casing it was
/// declared with. An aliased-file declaration (`Alias = path.csv`) keeps its
/// alias exactly as written in the INI -- unlike a bare declaration, it isn't
/// restricted to already-sanitized (lowercase) text -- so the lookup has to
/// sanitize the stored alias before comparing, not just the incoming one.
#[test]
fn test_set_input_finds_mixed_case_aliased_file_declaration() {
    let ini = "\
[data]
Climate_Data = ./src/tests/example_data/test.csv
";
    let mut model = IniModelIO::read_model_string(ini)
        .expect("Model with an aliased file declaration should parse");

    let columns = vec![InMemoryColumn {
        name: "value".to_string(),
        values: vec![1.0, 2.0, 3.0],
    }];

    model_input_swap::set_input(&mut model, "Climate_Data", 0, 86400, columns)
        .expect("set_input() should find the declared alias regardless of casing");

    let source = model.input_sources.iter()
        .find(|s| s.columns().iter().any(|c| c.full_colname_path == "data.climate_data.by_name.value"))
        .expect("Supplied data should be addressable under the sanitized alias");
    assert_eq!(source.columns()[0].timeseries.values, vec![1.0, 2.0, 3.0]);
}
