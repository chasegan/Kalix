//! Parsing and serialising the storage node's `exists` parameter.
//!
//! `exists` marks a storage as ephemeral: 0 or NaN means it is not there this
//! timestep, any other value means it is. Behaviour is covered by regression
//! models 29 and 30; these tests cover the INI reader and writer.

use crate::io::ini_model_io::IniModelIO;
use crate::model_inputs::DynamicInput;
use crate::nodes::NodeEnum;

/// Helper: borrow the `exists` input of a named storage node.
fn exists_of<'a>(model: &'a crate::model::Model, node_name: &str) -> &'a DynamicInput {
    match model.get_node(node_name).expect("node not found") {
        NodeEnum::StorageNode(n) => &n.exists,
        other => panic!("node '{}' is not a storage node: {}", node_name, other.get_type_as_string()),
    }
}

fn model_ini(exists_line: &str) -> String {
    format!(
        "[kalix]\n\
         \n\
         [node.test_storage]\n\
         type = storage\n\
         loc = 0, 0\n\
         initial_volume = 100\n\
         {exists_line}\
         dimensions = Level [m], Volume [ML], Area [km2], Spill [ML],\n\
         \x200        , 0          , 0         , 0,\n\
         \x2010       , 2000       , 10        , 0,\n\
         \x2011       , 2100       , 10        , 1000,\n"
    )
}

#[test]
fn exists_absent_leaves_the_input_unset() {
    let m = IniModelIO::read_model_string(&model_ini("")).unwrap();
    assert!(matches!(exists_of(&m, "test_storage"), DynamicInput::None { .. }),
            "an absent `exists` must stay unset, so the storage always exists");
}

#[test]
fn exists_constant_parses() {
    let m = IniModelIO::read_model_string(&model_ini("exists = 0\n")).unwrap();
    assert!(!matches!(exists_of(&m, "test_storage"), DynamicInput::None { .. }),
            "`exists = 0` must parse into a configured input");
}

/// An absent `exists` must not be materialised into the canonical form — that
/// would write a parameter the modeller never typed.
#[test]
fn absent_exists_is_not_emitted() {
    let m = IniModelIO::read_model_string(&model_ini("")).unwrap();
    let serialised = IniModelIO::model_to_string(&m);
    assert!(!serialised.contains("exists"),
            "absent `exists` should not be serialised, got:\n{}", serialised);
}

#[test]
fn exists_survives_a_full_round_trip() {
    let m1 = IniModelIO::read_model_string(&model_ini("exists = 1\n")).unwrap();
    let serialised = IniModelIO::model_to_string(&m1);
    let m2 = IniModelIO::read_model_string(&serialised).unwrap();

    let before = exists_of(&m1, "test_storage").to_string();
    let after = exists_of(&m2, "test_storage").to_string();
    assert_eq!(before, after, "`exists` should be preserved through write -> read");
    assert!(!after.is_empty(), "`exists` should survive as a configured input");
}
