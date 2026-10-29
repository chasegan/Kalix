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
fn test_reference_matches_node_name_case_insensitively() {
    let extra = "[node.reader]\nloc = 0, 10\ntype = inflow\ninflow = node.mygr4j.dsflow[-1, 0.0]\nds_1 = sink\n";
    load_and_run(&model_ini("MyGr4j", "1", extra, "node.reader.dsflow")).unwrap();
}
