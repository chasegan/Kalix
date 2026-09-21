//! The ordering module asks every node type, by name (ADR-0008 §2).
//!
//! What the ordering system needs to know about a node type is answered in
//! matches that name every `NodeEnum` variant, so that a new node type does
//! not compile until it has answered. The compiler holds that for as long as
//! no match has a wildcard arm; this test holds the other half, that a
//! wildcard arm does not come back. It reads the module's source, in the
//! manner of `test_linter_schema.rs`.

const ORDERING_SRC: &str = include_str!("../ordering/simple_nodewise_ordering.rs");

#[test]
fn ordering_module_has_no_wildcard_match_arms() {
    let offenders: Vec<String> = ORDERING_SRC.lines().enumerate()
        .filter(|(_, line)| {
            let code = line.split("//").next().unwrap_or("");
            code.contains("_ =>")
        })
        .map(|(i, line)| format!("line {}: {}", i + 1, line.trim()))
        .collect();
    assert!(offenders.is_empty(),
        "simple_nodewise_ordering.rs has a wildcard match arm. Per ADR-0008 §2, a match that decides \
         something about a node type names every type, so that a new node type cannot be forgotten. \
         If this wildcard is over something that is not a node type, narrow this test and say why here.\n  {}",
        offenders.join("\n  "));
}
