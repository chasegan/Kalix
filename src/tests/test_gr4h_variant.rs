//! Phase 2 tests: parsing and serialising the GR4J node's `variant` parameter
//! (classic daily `gr4j` vs sub-daily `gr4h`).

use crate::hydrology::rainfall_runoff::gr4j::Gr4Variant;
use crate::io::ini_model_io::IniModelIO;
use crate::nodes::NodeEnum;

/// Helper: read the variant of a named gr4j node from a parsed model.
fn variant_of(model: &crate::model::Model, node_name: &str) -> Gr4Variant {
    match model.get_node(node_name).expect("node not found") {
        NodeEnum::Gr4jNode(n) => n.gr4j_model.variant,
        other => panic!("node '{}' is not a gr4j node: {}", node_name, other.get_type_as_string()),
    }
}

fn model_ini(variant_line: &str) -> String {
    format!(
        "[kalix]\n\
         \n\
         [node.test_gr4]\n\
         type = gr4j\n\
         {variant_line}\
         loc = 0, 0\n\
         area = 100\n\
         params = 350, 0, 90, 1.7\n"
    )
}

#[test]
fn variant_absent_defaults_to_gr4j() {
    let m = IniModelIO::new().read_model_string(&model_ini("")).unwrap();
    assert_eq!(variant_of(&m, "test_gr4"), Gr4Variant::Gr4j);
}

#[test]
fn variant_gr4j_parses_as_daily() {
    let m = IniModelIO::new().read_model_string(&model_ini("variant = gr4j\n")).unwrap();
    assert_eq!(variant_of(&m, "test_gr4"), Gr4Variant::Gr4j);
}

#[test]
fn variant_gr4h_parses_as_subdaily() {
    let m = IniModelIO::new().read_model_string(&model_ini("variant = gr4h\n")).unwrap();
    assert_eq!(variant_of(&m, "test_gr4"), Gr4Variant::Gr4h);
}

#[test]
fn variant_is_case_insensitive() {
    let m = IniModelIO::new().read_model_string(&model_ini("variant = GR4H\n")).unwrap();
    assert_eq!(variant_of(&m, "test_gr4"), Gr4Variant::Gr4h);
}

#[test]
fn unknown_variant_is_an_error() {
    let result = IniModelIO::new().read_model_string(&model_ini("variant = gr4x\n"));
    match result {
        Ok(_) => panic!("expected an error for an unknown variant"),
        Err(err) => assert!(err.contains("variant"), "error should mention the variant: {err}"),
    }
}

#[test]
fn writer_emits_variant_only_for_gr4h() {
    let io = IniModelIO::new();

    // Default (gr4j): no variant line should be written.
    let m_default = io.read_model_string(&model_ini("")).unwrap();
    let out_default = io.model_to_string(&m_default);
    assert!(!out_default.contains("variant"),
            "classic GR4J model should not emit a variant line:\n{out_default}");

    // gr4h: variant line must be present.
    let m_h = io.read_model_string(&model_ini("variant = gr4h\n")).unwrap();
    let out_h = io.model_to_string(&m_h);
    assert!(out_h.contains("variant = gr4h"),
            "GR4H model should emit `variant = gr4h`:\n{out_h}");
}

#[test]
fn variant_survives_a_full_round_trip() {
    let io = IniModelIO::new();

    let m1 = io.read_model_string(&model_ini("variant = gr4h\n")).unwrap();
    let serialised = io.model_to_string(&m1);
    let m2 = io.read_model_string(&serialised).unwrap();

    assert_eq!(variant_of(&m2, "test_gr4"), Gr4Variant::Gr4h,
               "variant should be preserved through write -> read");
}
