//! The KalixIDE linter schema must agree with the engine.
//!
//! Every node property lives in two places by hand: the INI parser's match
//! arm in `ini_doc_model_io_0_0_1.rs`, and the linter's
//! `kalix-model-schema.json`. Every output lives in the node's `initialise`
//! (a `recorder(...)` registration) and in the schema's `allowed_outputs`.
//! Nothing else ties them together, so a property added to one side and not
//! the other is silent: the linter flags a valid line, or passes one the
//! engine rejects.
//!
//! These tests read both sides from source at compile time and compare them.
//! They scan text, so they depend on the parser's uniform style — every
//! property check is `name_lower == "..."`, every recorder is
//! `recorder(data_cache, &self.name, "...")`, name families are derived with
//! `strip_suffix("_...")` or `format!("ds_{n}...")`. If that style changes
//! these tests fail loudly and say so, which is the intended behaviour of a
//! guard.

use crate::io::ini_model_io_versions::ini_doc_model_io_0_0_1::NODE_STATIC_F64_PROPERTIES;
use serde_json::Value;
use std::collections::BTreeSet;

const PARSER_SRC: &str = include_str!("../io/ini_model_io_versions/ini_doc_model_io_0_0_1.rs");
const SCHEMA_SRC: &str = include_str!("../../kalixide/src/main/resources/linter/kalix-model-schema.json");

/// Each node type's source, so its recorder registrations can be read.
const NODE_SOURCES: &[(&str, &str)] = &[
    ("awbm", include_str!("../nodes/awbm_node.rs")),
    ("blackhole", include_str!("../nodes/blackhole_node.rs")),
    ("confluence", include_str!("../nodes/confluence_node.rs")),
    ("gauge", include_str!("../nodes/gauge_node.rs")),
    ("gr4j", include_str!("../nodes/gr4j_node.rs")),
    ("inflow", include_str!("../nodes/inflow_node.rs")),
    ("loss", include_str!("../nodes/loss_node.rs")),
    ("order_control", include_str!("../nodes/order_control_node.rs")),
    ("regulated_user", include_str!("../nodes/regulated_user_node.rs")),
    ("routing", include_str!("../nodes/routing_node.rs")),
    ("sacramento", include_str!("../nodes/sacramento_node.rs")),
    ("splitter", include_str!("../nodes/splitter_node.rs")),
    ("storage", include_str!("../nodes/storage_node.rs")),
    ("surm", include_str!("../nodes/surm_node.rs")),
    ("unregulated_user", include_str!("../nodes/unregulated_user_node.rs")),
];

/// Every string literal that directly follows `prefix` in `hay`.
fn literals_after(hay: &str, prefix: &str) -> BTreeSet<String> {
    let mut out = BTreeSet::new();
    let mut rest = hay;
    while let Some(i) = rest.find(prefix) {
        let after = &rest[i + prefix.len()..];
        if let Some(end) = after.find('"') {
            out.insert(after[..end].to_string());
        }
        rest = after;
    }
    out
}

/// `name` is `ds_<digits><suffix>` — the shape of every name family the engine derives.
fn is_ds_family(name: &str, suffix: &str) -> bool {
    let Some(rest) = name.strip_prefix("ds_") else { return false };
    let digits = rest.chars().take_while(|c| c.is_ascii_digit()).count();
    digits > 0 && &rest[digits..] == suffix
}

/// The parser's node-type arms: (type, accepted literal names, accepted `ds_N<suffix>` families).
fn parser_arms() -> Vec<(String, BTreeSet<String>, BTreeSet<String>)> {
    const ARM_OPEN: &str = "\n                \"";
    const ARM_CLOSE: &str = "\n                }";
    let mut arms = Vec::new();
    let mut rest = PARSER_SRC;
    while let Some(i) = rest.find(ARM_OPEN) {
        rest = &rest[i + ARM_OPEN.len()..];
        let Some(q) = rest.find("\" => {") else { break };
        let name = rest[..q].to_string();
        let body = rest[q..].split(ARM_CLOSE).next().unwrap_or("");
        // Only the node-type arms construct a NodeEnum; other matches in the
        // file share the indentation but not the shape.
        if body.contains("NodeEnum::") && name.chars().all(|c| c.is_ascii_lowercase() || c == '_' || c.is_ascii_digit()) {
            let literals = literals_after(body, "name_lower == \"");
            let families = literals_after(body, "strip_suffix(\"");
            arms.push((name, literals, families));
        }
    }
    arms
}

fn schema_node_types() -> serde_json::Map<String, Value> {
    let schema: Value = serde_json::from_str(SCHEMA_SRC).expect("linter schema is valid JSON");
    schema["node_types"].as_object().expect("schema has node_types").clone()
}

fn string_set(v: &Value, key: &str) -> BTreeSet<String> {
    v[key].as_array().map(|a| a.iter().filter_map(|x| x.as_str().map(String::from)).collect()).unwrap_or_default()
}

#[test]
fn linter_schema_lists_exactly_the_properties_the_parser_accepts() {
    let arms = parser_arms();
    assert!(arms.len() >= 12, "expected the parser's node arms, found {} - has the parser's style changed?", arms.len());
    let schema = schema_node_types();
    let mut failures: Vec<String> = Vec::new();

    for (node_type, literals, families) in &arms {
        let Some(entry) = schema.get(node_type) else {
            failures.push(format!("parser accepts node type '{node_type}' but the linter schema has no entry for it"));
            continue;
        };
        let mut listed: BTreeSet<String> = BTreeSet::new();
        for key in ["required_params", "optional_params", "dsnode_params"] {
            listed.extend(string_set(entry, key));
        }
        for p in literals.difference(&listed) {
            failures.push(format!("{node_type}: parser accepts '{p}' but the schema does not list it (add to optional_params / required_params / dsnode_params)"));
        }
        for p in listed.difference(literals) {
            if families.iter().any(|f| is_ds_family(p, f)) { continue; }
            failures.push(format!("{node_type}: schema lists '{p}' but the parser rejects it as an unexpected parameter"));
        }
    }
    for node_type in schema.keys() {
        if !arms.iter().any(|(t, _, _)| t == node_type) {
            failures.push(format!("schema has node type '{node_type}' but the parser has no such node type"));
        }
    }
    assert!(failures.is_empty(), "linter schema and parser disagree on node properties:\n  {}", failures.join("\n  "));
}

#[test]
fn linter_schema_lists_exactly_the_outputs_each_node_records() {
    let schema = schema_node_types();
    let mut failures: Vec<String> = Vec::new();

    for (node_type, src) in NODE_SOURCES {
        let Some(entry) = schema.get(*node_type) else { continue }; // reported by the properties test
        let mut recorded = literals_after(src, "recorder(data_cache, &self.name, \"");
        recorded.extend(NODE_STATIC_F64_PROPERTIES.iter().filter(|(t, _)| t == node_type).map(|(_, p)| p.to_string()));
        // Families registered in a loop: recorder(data_cache, &self.name, &format!("ds_{n}<suffix>"))
        let families: BTreeSet<String> = literals_after(src, "&format!(\"ds_{n}")
            .into_iter().collect();
        let allowed = string_set(entry, "allowed_outputs");
        for o in recorded.difference(&allowed) {
            failures.push(format!("{node_type}: records '{o}' but the schema's allowed_outputs omits it - the linter will flag a valid [outputs] line"));
        }
        for o in allowed.difference(&recorded) {
            if families.iter().any(|f| is_ds_family(o, f)) { continue; }
            failures.push(format!("{node_type}: schema allows output '{o}' but the node never records it"));
        }
    }
    assert!(failures.is_empty(), "linter schema and node recorders disagree on outputs:\n  {}", failures.join("\n  "));
}
