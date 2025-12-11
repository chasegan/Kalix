use std::path::PathBuf;
use crate::io::custom_ini_parser::IniDocument;
use crate::io::ini_model_io::IniModelIO;

#[test]
fn test_line_continuation_integration() {
    let content = r#"
[kalix]
version = 0.0.1

[inputs]
./src/tests/example_models/1/flows.csv

[node.test_node]
type = sacramento
params = 0.01, 40.0, 23.0,
         0.009, 0.043, 130.0,
         0.01, 0.063, 1.0, 0.01, 0.0, 0.0,
         40.0, 0.245, 50.0, 40.0, 0.1
"#;

    let io = IniModelIO::new();
    let result = io.read_model_string(content);

    // Should parse successfully (though may fail model validation due to incomplete model)
    match result {
        Ok(_) => println!("✅ Line continuation parsing successful!"),
        Err(e) => {
            // Expected to fail at model building stage, not parsing stage
            assert!(!e.contains("Invalid line format"), "Should not fail at parsing stage: {}", e);
            println!("✅ Parsing succeeded, model building failed as expected: {}", e);
        }
    }
}

#[test]
fn test_to_string_preserves_unchanged() {
    let content = r#"# Top comment
[kalix]
version = 0.0.1

[node.test]
type = gr4j
# This is a multiline param
params = 100.0, 2.0,
         50.0, 0.5  # inline comment
"#;

    let doc = IniDocument::parse(content).unwrap();
    let output = doc.to_string();

    println!("Output:\n{}", output);

    // Should preserve comments and multiline formatting for unchanged properties
    assert!(output.contains("# Top comment"));
    assert!(output.contains("# This is a multiline param"));
    assert!(output.contains("# inline comment"));
    assert!(output.contains("         50.0, 0.5")); // Preserve indentation
}

#[test]
fn test_to_string_formats_modified() {
    let content = r#"
[node.test]
type = gr4j
params = 100.0, 2.0, 50.0, 0.5
"#;

    let mut doc = IniDocument::parse(content).unwrap();

    // Modify params
    doc.set_property("node.test", "params", "200.0, 3.0, 60.0, 0.6");

    let output = doc.to_string();
    println!("Output:\n{}", output);

    // Modified property should be formatted canonically
    assert!(output.contains("params = 200.0, 3.0, 60.0, 0.6"));

    // Unchanged property should still have original formatting
    assert!(output.contains("type = gr4j"));
}

#[test]
fn test_round_trip() {
    let content = r#"[kalix]
version = 0.0.1

[inputs]
./data/input1.csv
./data/input2.csv

[node.node1]
type = inflow
loc = 10.5, 20.3
ds_1 = node2

[outputs]
node.node1.dsflow
"#;

    // Parse -> to_string -> parse again
    let doc1 = IniDocument::parse(content).unwrap();
    let output1 = doc1.to_string();
    let doc2 = IniDocument::parse(&output1).unwrap();

    // Should have same structure
    assert_eq!(doc1.sections.len(), doc2.sections.len());

    // Check values match
    assert_eq!(
        doc1.get_property("kalix", "version"),
        doc2.get_property("kalix", "version")
    );
    assert_eq!(
        doc1.get_property("node.node1", "type"),
        doc2.get_property("node.node1", "type")
    );
    assert_eq!(
        doc1.get_property("node.node1", "loc"),
        doc2.get_property("node.node1", "loc")
    );
}

#[test]
fn test_modify_and_save() {
    let content = r#"[kalix]
version = 0.0.1

[node.gr4j_node]
type = gr4j
params = 100.0, 2.0, 50.0, 0.5
loc = 10.5, 20.3

[node.inflow_node]
type = inflow
ds_1 = gr4j_node
"#;

    let mut doc = IniDocument::parse(content).unwrap();

    // Modify GR4J parameters (like after optimisation)
    doc.set_property("node.gr4j_node", "params", "150.0, 2.5, 55.0, 0.6");

    // Convert back to string
    let output = doc.to_string();

    println!("Modified output:\n{}", output);

    // Parse the output to verify it's valid
    let doc2 = IniDocument::parse(&output).unwrap();

    // Verify modified value
    assert_eq!(
        doc2.get_property("node.gr4j_node", "params"),
        Some("150.0, 2.5, 55.0, 0.6")
    );

    // Verify unchanged values are preserved
    assert_eq!(
        doc2.get_property("node.gr4j_node", "type"),
        Some("gr4j")
    );
    assert_eq!(
        doc2.get_property("node.gr4j_node", "loc"),
        Some("10.5, 20.3")
    );
    assert_eq!(
        doc2.get_property("node.inflow_node", "type"),
        Some("inflow")
    );
}

#[test]
fn test_full_model_round_trip() {
    // This test simulates the optimisation workflow:
    // 1. Load model from INI
    // 2. Modify parameters (like after optimisation)
    // 3. Save to new INI file
    // 4. Reload and verify changes

    let original_ini = r#"[kalix]
version = 0.0.1

[inputs]
./src/tests/example_models/1/rex_rain.csv
./src/tests/example_models/1/rex_mpot.csv

[node.gr4j_node]
type = gr4j
loc = 50.5, 89
area = 22.8
rain = data.rex_rain_csv.by_name.value
evap = data.rex_mpot_csv.by_name.value
params = 100.0, 2.0, 50.0, 0.5

[node.gauge_node]
type = gauge
loc = 100.5, 189
ds_1 = gr4j_node

[outputs]
node.gr4j_node.dsflow
"#;

    // Step 1: Load model
    let ini_io = IniModelIO::new();
    let mut model = ini_io.read_model_string(original_ini).unwrap();

    // Verify INI document is attached
    assert!(model.ini_document.is_some());

    // Step 2: Modify parameters (simulating optimisation)
    model.update_node_parameter_in_ini("gr4j_node", "params", "150.0, 2.5, 55.0, 0.6").unwrap();

    // Step 3: Get the updated INI as a string
    let updated_ini = model.get_ini_string().unwrap();

    println!("Updated INI:\n{}", updated_ini);

    // Step 4: Reload from the updated string
    let model2 = ini_io.read_model_string(&updated_ini).unwrap();

    // Verify the parameter was updated
    if let Some(ref ini_doc) = model2.ini_document {
        assert_eq!(
            ini_doc.get_property("node.gr4j_node", "params"),
            Some("150.0, 2.5, 55.0, 0.6")
        );

        // Verify other properties are unchanged
        assert_eq!(
            ini_doc.get_property("node.gr4j_node", "type"),
            Some("gr4j")
        );
        assert_eq!(
            ini_doc.get_property("node.gr4j_node", "area"),
            Some("22.8")
        );
        assert_eq!(
            ini_doc.get_property("node.gauge_node", "type"),
            Some("gauge")
        );
    } else {
        panic!("Model2 should have INI document attached");
    }
}

#[test]
fn test_save_and_reload_from_file() {
    use std::path::Path;

    let original_ini = r#"[kalix]
version = 0.0.1

[inputs]
./example_models/1/rex_rain.csv

[node.simple_node]
type = inflow
loc = 10.5, 20.3
inflow = data.rex_rain_csv.by_name.value
ds_1 = blackhole_node

[node.blackhole_node]
type = blackhole
loc = 50.5, 60.3

[outputs]
node.simple_node.dsflow
"#;

    let test_file_path = "./src/tests/test_output_model.ini";

    // Clean up any existing test file
    if Path::new(test_file_path).exists() {
        std::fs::remove_file(test_file_path).ok();
    }

    // Load model
    let ini_io = IniModelIO::new();
    let mut model = ini_io.read_model_string_with_working_directory(original_ini, Some(PathBuf::from( "./src/tests"))).unwrap();

    // Modify a parameter
    model.update_node_parameter_in_ini("simple_node", "loc", "15.0, 25.0").unwrap();

    // Save to file
    model.save_ini_to_file(test_file_path).unwrap();

    // Reload from file
    let model2 = ini_io.read_model_file(test_file_path).unwrap();

    // Verify the change
    if let Some(ref ini_doc) = model2.ini_document {
        assert_eq!(
            ini_doc.get_property("node.simple_node", "loc"),
            Some("15.0, 25.0")
        );
        assert_eq!(
            ini_doc.get_property("node.simple_node", "type"),
            Some("inflow")
        );
    } else {
        panic!("Model2 should have INI document attached");
    }

    // Clean up
    std::fs::remove_file(test_file_path).ok();
}
