//! The ordering module asks every node type, by name (ADR-0008 §2).
//!
//! What the ordering system needs to know about a node type is answered in
//! matches that name every `NodeEnum` variant, so that a new node type does
//! not compile until it has answered. The compiler holds that for as long as
//! no match has a wildcard arm; this test holds the other half, that a
//! wildcard arm does not come back. It reads the module's source, in the
//! manner of `test_linter_schema.rs`.

const ORDERING_SRC: &str = include_str!("../ordering/simple_nodewise_ordering.rs");
const NODE_ENUM_SRC: &str = include_str!("../nodes/node_enum.rs");

#[test]
fn ordering_module_has_no_wildcard_match_arms() {
    assert_no_wildcard_arms(ORDERING_SRC, "simple_nodewise_ordering.rs");
}

/// `NodeEnum`'s own questions (`optimisable`, the type string) answer for every
/// variant by name, so that a sub-variant cannot be left out of the optimiser's
/// or the STDIO parameter list silently, as it could while each caller kept its
/// own list ahead of a wildcard.
#[test]
fn node_enum_has_no_wildcard_match_arms() {
    assert_no_wildcard_arms(NODE_ENUM_SRC, "node_enum.rs");
}

fn assert_no_wildcard_arms(src: &str, file: &str) {
    let offenders: Vec<String> = src.lines().enumerate()
        .filter(|(_, line)| {
            let code = line.split("//").next().unwrap_or("");
            code.contains("_ =>")
        })
        .map(|(i, line)| format!("line {}: {}", i + 1, line.trim()))
        .collect();
    assert!(offenders.is_empty(),
        "{file} has a wildcard match arm. Per ADR-0008 §2, a match that decides \
         something about a node type names every type, so that a new node type cannot be forgotten. \
         If this wildcard is over something that is not a node type, narrow this test and say why here.\n  {}",
        offenders.join("\n  "));
}
