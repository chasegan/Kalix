//! Pins the casing contract of `Model::get_output_series` (used by the Python
//! `get_outputs()` binding): lookups against `[outputs]` are case-insensitive,
//! but the returned `Timeseries` always carries the casing the name was
//! *declared* under in `[outputs]`, regardless of the casing requested.
//! See `Model::get_output_series` doc comment and `docs/python_api_spec.md`.

use crate::io::ini_model_io::IniModelIO;
use crate::model::{Model, OutputLookupError};

/// One output, declared with deliberately mixed casing.
fn build_and_run() -> Model {
    let ini = "\
[kalix]
start = 2020-01-01
end = 2020-01-04

[node.a]
loc = 0, 0
type = inflow
inflow = 1
ds_1 = sink

[node.sink]
loc = 0, 10
type = blackhole

[outputs]
Node.A.DsFlow
";
    let mut model =
        IniModelIO::read_model_string(ini).expect("model should load");
    model.configure().expect("configuration should succeed");
    model.run().expect("run should succeed");
    model
}

#[test]
fn test_output_lookup_is_case_insensitive() {
    let model = build_and_run();
    let result = model.get_output_series(Some(vec!["node.a.dsflow".to_string()]), false);
    assert!(
        result.is_ok(),
        "lookup should be case-insensitive: {:?}",
        result.err()
    );
}

#[test]
fn test_output_series_retains_declared_casing() {
    let model = build_and_run();
    let series = model
        .get_output_series(Some(vec!["NODE.A.DSFLOW".to_string()]), false)
        .expect("case-insensitive lookup should succeed");
    assert_eq!(series.len(), 1);
    assert_eq!(
        series[0].name, "Node.A.DsFlow",
        "returned series should carry the casing declared in [outputs], not the requested casing"
    );
}

#[test]
fn test_collect_output_series_uses_declared_casing() {
    let model = build_and_run();
    let series = model
        .get_output_series(None, false)
        .expect("collecting all outputs should succeed");
    assert_eq!(series.len(), 1);
    assert_eq!(series[0].name, "Node.A.DsFlow");
}

#[test]
fn test_undeclared_output_case_insensitive_error() {
    let model = build_and_run();
    let result = model.get_output_series(Some(vec!["node.a.notreal".to_string()]), false);
    let err = match result {
        Err(e) => e,
        Ok(_) => panic!("undeclared output should error regardless of casing"),
    };
    // Variant, not just message text: `Undeclared` is what routes this to
    // KalixKeyError at the PyO3 boundary (see `output_err_to_py`).
    assert!(
        matches!(err, OutputLookupError::Undeclared(ref name) if name == "node.a.notreal"),
        "expected Undeclared naming the requested output, got {err:?}"
    );
}

#[test]
fn test_declared_but_unrecorded_output_is_unpopulated_not_wrong_length() {
    // An `[outputs]` entry naming a series no component produces leaves an
    // empty series registered under that name. Empty must report as
    // `Unpopulated` -- a name problem, pointing the user at their
    // declaration -- and not as `WrongLength { actual: 0 }`, which is
    // technically true, routes to the wrong exception at the PyO3 boundary,
    // and tells the user nothing about the typo that caused it.
    let ini = "\
[kalix]
start = 2020-01-01
end = 2020-01-04

[node.a]
loc = 0, 0
type = inflow
inflow = 1
ds_1 = sink

[node.sink]
loc = 0, 10
type = blackhole

[outputs]
node.a.dsflow
node.a.notathing
";
    let mut model = IniModelIO::read_model_string(ini).expect("model should load");
    model.configure().expect("configuration should succeed");
    model.run().expect("run should succeed");

    // `expect_err` is unavailable here: the Ok type (`Vec<Timeseries>`)
    // doesn't implement Debug.
    let err = match model.get_output_series(Some(vec!["node.a.notathing".to_string()]), false) {
        Err(e) => e,
        Ok(_) => panic!("a declared but unrecorded output should error"),
    };
    assert!(
        matches!(err, OutputLookupError::Unpopulated(ref name) if name == "node.a.notathing"),
        "expected Unpopulated, got {err:?}"
    );
}
