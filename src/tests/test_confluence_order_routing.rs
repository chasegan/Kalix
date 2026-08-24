// Tests for confluence order routing via `regulated = <upstream node(s)>`:
// one name = the only regulated pathway (all orders up it, immediately);
// two names = harmony_fraction is the fraction sent to the FIRST listed, so
// the split's direction is stated rather than inferred from link order.
// Bare harmony_fraction (no `regulated`) keeps its legacy meaning: the
// fraction to the first-encountered regulated link.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

/// Two supplied storages joining at one confluence, a regulated user below.
/// `{split}` is the confluence's routing configuration; who releases the
/// user's order tells us where the orders went.
fn rig(split: &str) -> String {
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-04

[node.srca]
type = inflow
loc = 0, 0
inflow = 100
ds_1 = dama

[node.dama]
type = storage
loc = 0, 10
initial_volume = 500
dimensions = Level [m], Volume [ML], Area [km2], Spill [ML],
             0.0      , 0.0        , 0.0       , 0.0,
             1.0      , 1000.0     , 0.1       , 0.0,
             2.0      , 2000.0     , 0.1       , 1.0E9,
ds_1_outlet = 0, 10000
ds_1 = conf

[node.srcb]
type = inflow
loc = 10, 0
inflow = 100
ds_1 = damb

[node.damb]
type = storage
loc = 10, 10
initial_volume = 500
dimensions = Level [m], Volume [ML], Area [km2], Spill [ML],
             0.0      , 0.0        , 0.0       , 0.0,
             1.0      , 1000.0     , 0.1       , 0.0,
             2.0      , 2000.0     , 0.1       , 1.0E9,
ds_1_outlet = 0, 10000
ds_1 = conf

[node.conf]
type = confluence
loc = 5, 20
{split}
ds_1 = u1

[node.u1]
type = regulated_user
loc = 5, 30
order = 6

[outputs]
node.dama.ds_1
node.damb.ds_1
node.u1.usflow
node.conf.expected_inflow
"#)
}

fn run(ini: &str) -> Model {
    let mut model = IniModelIO::read_model_string(ini).expect("model should load");
    model.configure().expect("model should configure");
    model.run().expect("simulation should run");
    model
}

fn load_err(ini: &str) -> String {
    let mut model = match IniModelIO::read_model_string(ini) {
        Err(e) => return e.to_string(),
        Ok(m) => m,
    };
    match model.configure() {
        Err(e) => return e.to_string(),
        Ok(()) => {}
    }
    model.run().err().expect("expected an error").to_string()
}

fn series(model: &mut Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_series_idx(name, false)
        .unwrap_or_else(|| panic!("missing series {}", name));
    model.data_cache.series[idx].values.clone()
}

/// One named pathway: every order goes up it, whichever link order the file
/// happened to declare — the other branch releases nothing.
#[test]
fn test_single_regulated_pathway_takes_all_orders() {
    let mut model = run(&rig("regulated = damb"));
    assert_eq!(series(&mut model, "node.damb.ds_1")[1], 6.0, "named branch supplies the order");
    assert_eq!(series(&mut model, "node.dama.ds_1")[1], 0.0, "unnamed branch releases nothing");

    // And naming the other branch flips it — no dependence on link order.
    let mut model = run(&rig("regulated = dama"));
    assert_eq!(series(&mut model, "node.dama.ds_1")[1], 6.0);
    assert_eq!(series(&mut model, "node.damb.ds_1")[1], 0.0);
}

/// Two named pathways: harmony_fraction is the fraction to the FIRST listed.
/// Swapping the list swaps the split without touching the fraction.
#[test]
fn test_two_regulated_pathways_split_by_harmony() {
    let mut model = run(&rig("regulated = dama, damb\nharmony_fraction = 0.25"));
    assert_eq!(series(&mut model, "node.dama.ds_1")[1], 1.5, "first listed gets the fraction");
    assert_eq!(series(&mut model, "node.damb.ds_1")[1], 4.5);

    let mut model = run(&rig("regulated = damb, dama\nharmony_fraction = 0.25"));
    assert_eq!(series(&mut model, "node.damb.ds_1")[1], 1.5, "direction follows the list, not link order");
    assert_eq!(series(&mut model, "node.dama.ds_1")[1], 4.5);
}

/// Bare harmony_fraction keeps its legacy meaning: fraction to the
/// first-encountered regulated link (dama, declared first).
#[test]
fn test_legacy_bare_harmony_fraction_unchanged() {
    let mut model = run(&rig("harmony_fraction = 0.25"));
    assert_eq!(series(&mut model, "node.dama.ds_1")[1], 1.5);
    assert_eq!(series(&mut model, "node.damb.ds_1")[1], 4.5);
}

#[test]
fn test_regulated_validation_errors() {
    // A single pathway takes everything: a fraction beside it is a contradiction
    let err = load_err(&rig("regulated = damb\nharmony_fraction = 0.5"));
    assert!(err.contains("nothing to split"), "unexpected: {}", err);

    // Two pathways need the fraction stated
    let err = load_err(&rig("regulated = dama, damb"));
    assert!(err.contains("no harmony_fraction"), "unexpected: {}", err);

    // At most two names, no duplicates
    let err = load_err(&rig("regulated = dama, damb, dama\nharmony_fraction = 0.5"));
    assert!(err.contains("one or two upstream node names"), "unexpected: {}", err);
    let err = load_err(&rig("regulated = damb, damb\nharmony_fraction = 0.5"));
    assert!(err.contains("names 'damb' twice"), "unexpected: {}", err);

    // Names must exist and must be upstream neighbours
    let err = load_err(&rig("regulated = nope"));
    assert!(err.contains("unknown node 'nope'"), "unexpected: {}", err);
    let err = load_err(&rig("regulated = u1"));
    assert!(err.contains("not one of its upstream nodes"), "unexpected: {}", err);
}

/// The regulated property survives the canonical render.
#[test]
fn test_regulated_round_trip() {
    let model = IniModelIO::read_model_string(&rig("regulated = damb")).expect("model should load");
    let rendered = IniModelIO::model_to_string(&model);
    assert!(rendered.contains("regulated = damb"), "regulated re-emitted:\n{}", rendered);
    let mut model2 = IniModelIO::read_model_string(&rendered)
        .unwrap_or_else(|e| panic!("canonical render should re-load, got: {}\n---\n{}", e, rendered));
    model2.configure().expect("model should configure");
    model2.run().expect("simulation should run");
    assert_eq!(series(&mut model2, "node.damb.ds_1")[1], 6.0, "routing survives the round-trip");
}

/// `expected_inflow` nets against the downstream order BEFORE the harmony
/// split, same as InflowNode's `usorders = (dsorders - expected_inflow).max(0)`.
/// Single named pathway: order 6, expected inflow 4 -> 2 goes upstream.
#[test]
fn test_expected_inflow_nets_against_downstream_order() {
    let mut model = run(&rig("regulated = damb\nexpected_inflow = 4"));
    assert_eq!(series(&mut model, "node.damb.ds_1")[1], 2.0, "order net of expected inflow");
    assert_eq!(series(&mut model, "node.dama.ds_1")[1], 0.0, "unnamed branch still releases nothing");
}

/// Expected inflow greater than the order clamps the outgoing order at
/// zero rather than going negative.
#[test]
fn test_expected_inflow_exceeding_order_clamps_to_zero() {
    let mut model = run(&rig("regulated = damb\nexpected_inflow = 10"));
    assert_eq!(series(&mut model, "node.damb.ds_1")[1], 0.0, "order fully covered by expected inflow");
}

/// With two named pathways, the netting happens once against the total
/// order and the harmony fraction splits what's left — not the raw order.
#[test]
fn test_expected_inflow_nets_before_harmony_split() {
    let mut model = run(&rig("regulated = dama, damb\nharmony_fraction = 0.25\nexpected_inflow = 2"));
    // total_outgoing_order = max(6 - 2, 0) = 4, split 0.25 / 0.75.
    assert_eq!(series(&mut model, "node.dama.ds_1")[1], 1.0, "first listed gets the fraction of the netted order");
    assert_eq!(series(&mut model, "node.damb.ds_1")[1], 3.0);
}

/// expected_inflow is optional: omitting it behaves exactly like `= 0`
/// (the un-netted regulated-pathway cases above already assume this).
#[test]
fn test_expected_inflow_defaults_to_zero_when_omitted() {
    let mut model = run(&rig("regulated = damb"));
    assert_eq!(series(&mut model, "node.conf.expected_inflow")[1], 0.0);
    assert_eq!(series(&mut model, "node.damb.ds_1")[1], 6.0, "no netting applied");
}

/// The evaluated expected_inflow value is recorded each step.
#[test]
fn test_expected_inflow_is_recorded() {
    let mut model = run(&rig("regulated = damb\nexpected_inflow = 4"));
    assert_eq!(series(&mut model, "node.conf.expected_inflow")[1], 4.0);
}

/// expected_inflow survives the canonical render, same as `regulated`.
#[test]
fn test_expected_inflow_round_trip() {
    let model = IniModelIO::read_model_string(&rig("regulated = damb\nexpected_inflow = 4"))
        .expect("model should load");
    let rendered = IniModelIO::model_to_string(&model);
    assert!(rendered.contains("expected_inflow = 4"), "expected_inflow re-emitted:\n{}", rendered);
    let mut model2 = IniModelIO::read_model_string(&rendered)
        .unwrap_or_else(|e| panic!("canonical render should re-load, got: {}\n---\n{}", e, rendered));
    model2.configure().expect("model should configure");
    model2.run().expect("simulation should run");
    assert_eq!(series(&mut model2, "node.damb.ds_1")[1], 2.0, "netting survives the round-trip");
}
