//! Tests for reading and writing the `[fn]` model-file section (user-defined
//! functions — structured_expressions_design.md §8).
//!
//! The `[fn]` section is parsed in a pre-pass (functions are passive and may be
//! defined anywhere in the file, even after the nodes that call them), validated
//! as a DAG, and inlined at expression lowering. These tests exercise the IO
//! wiring only: that definitions load, that a duplicate/recursive/invalid/reserved
//! definition is rejected at load, and that a loaded model round-trips its `[fn]`
//! section through `model_to_string`.

use crate::io::ini_model_io::IniModelIO;

/// Load a model expected to fail, returning the error text. (`Model` is not
/// `Debug`, so `Result::expect_err` cannot be used on the load result.)
fn load_err(ini: &str) -> String {
    match IniModelIO::read_model_string(ini) {
        Ok(_) => panic!("expected a load error, but the model loaded"),
        Err(e) => e.to_string(),
    }
}

/// A model with two functions: a one-line expression body and a multi-line
/// block body written across INI continuation lines (leading spaces, a `;`
/// statement, a bare final line). Both must register.
#[test]
fn fn_section_parses_oneline_and_block_bodies() {
    let ini = "\
[kalix]
start = 2020-01-01
end = 2020-01-03

[fn]
double(x) = x * 2
net(a, b) = {
    s = a + b;
    s * 2
    }
";
    let model = IniModelIO::read_model_string(ini)
        .expect("model with an [fn] section should parse");

    let double = model.data_cache.fns.get("double")
        .expect("fn.double should be registered");
    assert_eq!(double.name, "double");
    assert_eq!(double.params, vec!["x".to_string()]);

    let net = model.data_cache.fns.get("net")
        .expect("fn.net should be registered");
    assert_eq!(net.name, "net");
    assert_eq!(net.params, vec!["a".to_string(), "b".to_string()]);
}

/// The `[fn]` section may sit AFTER the nodes that call its functions — functions
/// are passive and looked up by name. A node calling `fn.double(5)` with the
/// definition at the end of the file must load, and evaluate correctly.
#[test]
fn fn_section_may_follow_the_nodes_that_call_it() {
    let ini = "\
[kalix]
start = 2020-01-01
end = 2020-01-03

[node.a]
loc = 0, 0
type = inflow
inflow = fn.double(5)
ds_1 = sink

[node.sink]
loc = 0, 10
type = blackhole

[outputs]
node.a.ds_1

[fn]
double(x) = x * 2
";
    let mut model = IniModelIO::read_model_string(ini)
        .expect("[fn] after its callers should still load");
    model.configure().expect("configuration should succeed");
    model.run().expect("run should succeed");

    let idx = model.data_cache.get_existing_series_idx("node.a.ds_1")
        .expect("output series should exist");
    let values = &model.data_cache.series[idx].values;
    // double(5) = 10 for every step.
    assert!(values.iter().all(|v| (v - 10.0).abs() < 1e-12),
            "fn.double(5) should evaluate to 10 each step, got {:?}", values);
}

/// Two definitions of the same function name (even at different arities) are a
/// load error — fixed signatures, no overloads (design §8.1). Distinct signature
/// keys are used so the INI parser's IndexMap does not silently dedupe them.
#[test]
fn duplicate_fn_name_is_a_load_error() {
    let ini = "\
[kalix]
start = 2020-01-01
end = 2020-01-03

[fn]
foo(x) = x * 2
foo(a, b) = a + b
";
    let err = load_err(ini);
    assert!(err.contains("duplicate"),
            "error should mention 'duplicate': {err}");
}

/// Mutual recursion (a calls fn.b, b calls fn.a) is rejected at load by the DAG
/// check — even though neither function is called by any node.
#[test]
fn recursive_fn_pair_is_a_load_error() {
    let ini = "\
[kalix]
start = 2020-01-01
end = 2020-01-03

[fn]
a(x) = fn.b(x) + 1
b(x) = fn.a(x) + 1
";
    let err = load_err(ini);
    assert!(err.contains("recursive"),
            "error should mention 'recursive': {err}");
}

/// A key that is not a signature (no parentheses) is a load error.
#[test]
fn invalid_signature_key_is_a_load_error() {
    let ini = "\
[kalix]
start = 2020-01-01
end = 2020-01-03

[fn]
foo = 1
";
    let err = load_err(ini);
    assert!(err.contains("[fn]") || err.contains("signature"),
            "error should describe the bad signature: {err}");
}

/// A function name that collides with a builtin is a load error.
#[test]
fn reserved_fn_name_is_a_load_error() {
    let ini = "\
[kalix]
start = 2020-01-01
end = 2020-01-03

[fn]
min(x) = x
";
    let err = load_err(ini);
    assert!(err.contains("builtin") || err.contains("reserved"),
            "error should mention builtin/reserved: {err}");
}

/// A loaded model re-emits its `[fn]` definitions through `model_to_string`, and
/// re-loading the emitted text yields a registry with the same function names.
#[test]
fn fn_section_survives_a_full_round_trip() {
    let ini = "\
[kalix]
start = 2020-01-01
end = 2020-01-03

[fn]
double(x) = x * 2
net(a, b) = {
    s = a + b;
    s * 2
    }
";
    let m1 = IniModelIO::read_model_string(ini)
        .expect("model with an [fn] section should parse");

    let serialised = IniModelIO::model_to_string(&m1);
    assert!(serialised.contains("double(x)"),
            "serialised model should re-emit the fn signatures:\n{serialised}");

    let m2 = IniModelIO::read_model_string(&serialised)
        .expect("re-emitted [fn] section should re-parse");
    assert!(m2.data_cache.fns.get("double").is_some(),
            "fn.double should survive the round-trip");
    assert!(m2.data_cache.fns.get("net").is_some(),
            "fn.net should survive the round-trip");
}
