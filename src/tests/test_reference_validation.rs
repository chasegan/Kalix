/// Tests for #442: node names, [outputs] layout, and expression references
/// must be checked at load, never left to read silently during the run.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

/// A source node `src` (inflow = `src_inflow`), then `extra` sections, then a
/// sink, then an [outputs] section listing `outputs`.
fn model_ini(src_name: &str, src_inflow: &str, extra: &str, outputs: &str) -> String {
    format!(
        "[kalix]\n\
         start = 2020-01-01\n\
         end = 2020-01-10\n\
         \n\
         [node.{src_name}]\n\
         loc = 0, 0\n\
         type = inflow\n\
         inflow = {src_inflow}\n\
         ds_1 = sink\n\
         \n\
         {extra}\n\
         [node.sink]\n\
         loc = 0, 20\n\
         type = blackhole\n\
         \n\
         [outputs]\n\
         {outputs}\n"
    )
}

/// Loads and configures (the structural checks run there); runs if both pass.
fn load_and_run(ini: &str) -> Result<Model, String> {
    let mut model = IniModelIO::read_model_string(ini).map_err(|e| e.to_string())?;
    model.configure()?;
    model.run()?;
    Ok(model)
}

fn expect_err(ini: &str) -> String {
    match load_and_run(ini) {
        Ok(_) => panic!("model should be refused:\n{}", ini),
        Err(e) => e,
    }
}

// -----------------------------------------------------------------------------
// Node names
// -----------------------------------------------------------------------------

#[test]
fn test_node_name_with_symbols_is_refused() {
    let err = expect_err(&model_ini("gr 4*j?", "1", "", "node.sink.usflow"));
    assert!(err.contains("Invalid node name 'gr 4*j?'"), "{}", err);
}

#[test]
fn test_node_name_with_dot_is_refused() {
    let err = expect_err(&model_ini("my.gr4j", "1", "", "node.sink.usflow"));
    assert!(err.contains("Invalid node name 'my.gr4j'"), "{}", err);
}

#[test]
fn test_mixed_case_and_digit_leading_node_names_are_accepted() {
    load_and_run(&model_ini("MyGr4j", "1", "", "node.MyGr4j.dsflow")).unwrap();
    load_and_run(&model_ini("0001_in", "1", "", "node.0001_in.dsflow")).unwrap();
}

#[test]
fn test_node_names_differing_only_in_case_are_refused() {
    let extra = "[node.SRC]\nloc = 0, 10\ntype = inflow\ninflow = 1\nds_1 = sink\n";
    let err = expect_err(&model_ini("src", "1", extra, "node.sink.usflow"));
    assert!(err.contains("Duplicate node name 'SRC'"), "{}", err);
    assert!(err.contains("not case sensitive"), "{}", err);
}

#[test]
fn test_repeated_node_section_is_refused() {
    let extra = "[node.src]\nloc = 0, 10\ntype = inflow\ninflow = 7\nds_1 = sink\n";
    let err = expect_err(&model_ini("src", "1", extra, "node.sink.usflow"));
    assert!(err.contains("Duplicate section header at line 11: [node.src] (also defined on line 5)"), "{}", err);
}

#[test]
fn test_reference_matches_node_name_case_insensitively() {
    let extra = "[node.reader]\nloc = 0, 10\ntype = inflow\ninflow = node.mygr4j.dsflow[-1, 0.0]\nds_1 = sink\n";
    load_and_run(&model_ini("MyGr4j", "1", extra, "node.reader.dsflow")).unwrap();
}

// -----------------------------------------------------------------------------
// [outputs] layout: checked for shape, never for existence
// -----------------------------------------------------------------------------

#[test]
fn test_malformed_outputs_are_refused() {
    for bad in ["node.my gr4j.dsflow", "node..dsflow", "node.src.", "dsflow", "node.src*2.dsflow"] {
        let err = expect_err(&model_ini("src", "1", "", bad));
        assert!(err.contains(&format!("Invalid output '{}'", bad)), "{}: {}", bad, err);
    }
}

#[test]
fn test_outputs_are_not_case_sensitive() {
    let model = load_and_run(&model_ini("MyGr4j", "1", "", "NODE.mygr4j.DSFLOW")).unwrap();
    let series = model.get_output_series(Some(vec!["node.MyGr4j.dsflow".to_string()]), false).unwrap();
    assert_eq!(series[0].values, vec![1.0; 10]);
}

#[test]
fn test_output_for_missing_node_is_not_an_error() {
    load_and_run(&model_ini("src", "1", "", "node.nosuch.dsflow")).unwrap();
}

// -----------------------------------------------------------------------------
// Expression references must exist, lagged or not
// -----------------------------------------------------------------------------

#[test]
fn test_lagged_reference_to_missing_node_is_refused() {
    let err = expect_err(&model_ini("src", "node.nosuch.dsflow[-1, 0.0]", "", "node.src.dsflow"));
    assert!(err.contains("Unknown node 'nosuch' in reference 'node.nosuch.dsflow'"), "{}", err);
}

#[test]
fn test_unlagged_reference_to_missing_node_says_unknown_not_later() {
    let err = expect_err(&model_ini("src", "node.nosuch.dsflow", "", "node.src.dsflow"));
    assert!(err.contains("Unknown node 'nosuch'"), "{}", err);
    assert!(!err.contains("later in the timestep"), "{}", err);
}

#[test]
fn test_reference_to_missing_output_of_real_node_is_refused() {
    let extra = "[node.reader]\nloc = 0, 10\ntype = inflow\ninflow = node.src.dsflw[-1, 0.0]\nds_1 = sink\n";
    let err = expect_err(&model_ini("src", "1", extra, "node.reader.dsflow"));
    assert!(err.contains("Node 'src' has no output 'dsflw'"), "{}", err);
}

#[test]
fn test_reference_to_missing_var_is_refused() {
    let err = expect_err(&model_ini("src", "var.nosuch.x[-1, 0.0]", "", "node.src.dsflow"));
    assert!(err.contains("Unknown var 'var.nosuch.x'"), "{}", err);
}

#[test]
fn test_missing_reference_is_caught_even_when_also_an_output() {
    let err = expect_err(&model_ini("src", "node.nosuch.dsflow[-1, 0.0]", "", "node.nosuch.dsflow"));
    assert!(err.contains("Unknown node 'nosuch'"), "{}", err);
}

#[test]
fn test_missing_reference_in_linear_combination_is_refused() {
    let err = expect_err(&model_ini("src", "0.5 * node.nosuch.dsflow", "", "node.src.dsflow"));
    assert!(err.contains("Unknown node 'nosuch'"), "{}", err);
}

#[test]
fn test_lagged_forward_reference_to_real_node_and_earlier_var_are_accepted() {
    let extra = concat!(
        "[var.calc]\ny = 2\n\n",
        "[node.reader]\nloc = 0, 10\ntype = inflow\ninflow = node.later.dsflow[-1, 0.0] + var.calc.y\nds_1 = sink\n\n",
        "[node.later]\nloc = 0, 15\ntype = inflow\ninflow = 3\nds_1 = sink\n");
    let model = load_and_run(&model_ini("src", "1", extra, "node.reader.dsflow")).unwrap();
    let series = model.get_output_series(Some(vec!["node.reader.dsflow".to_string()]), false).unwrap();
    assert_eq!(series[0].values[0], 2.0);
    assert_eq!(series[0].values[1], 5.0);
}

// -----------------------------------------------------------------------------
// Constants: a reference must name a declared constant
// -----------------------------------------------------------------------------

#[test]
fn test_reference_to_undeclared_constant_is_refused() {
    let err = expect_err(&model_ini("src", "1 + const.nosuch", "", "node.src.dsflow"));
    assert!(err.contains("Unknown constant 'const.nosuch'"), "{}", err);
}

#[test]
fn test_reference_to_constant_declared_after_use_is_accepted() {
    let extra = "[const]\nconst.k = 2\n";
    let model = load_and_run(&model_ini("src", "1 + const.K", extra, "node.src.dsflow")).unwrap();
    let series = model.get_output_series(Some(vec!["node.src.dsflow".to_string()]), false).unwrap();
    assert_eq!(series[0].values[0], 3.0);
}
