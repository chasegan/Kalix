// Each failing item sits mid-file, between items run before and after it, so a stale item cannot pass
// Var block and [ras.*] cases are in test_var_blocks.rs

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

fn load(ini: &str) -> Model {
    let mut model = IniModelIO::read_model_string(ini)
        .unwrap_or_else(|e| panic!("model should load: {e}"));
    model.configure().unwrap_or_else(|e| panic!("configure should succeed: {e}"));
    model
}

fn run_err(model: &mut Model) -> String {
    match model.run() {
        Ok(()) => panic!("expected the run to fail"),
        Err(e) => e.to_string(),
    }
}

/// A run whose interrupt check panics at the top of the first step, before any item runs
fn interrupt_err(model: &mut Model) -> String {
    match model.run_with_interrupt(|| -> bool { panic!("interrupt check failed") }, None) {
        Ok(_) => panic!("expected the run to fail"),
        Err(e) => e,
    }
}

/// Chain top -> mid -> low -> outlet; `{bad}` is mid's inflow, `{flow_var}` an optional flow var block
fn flow_rig(bad: &str, flow_var: &str) -> String {
    format!("\
[kalix]
start = 2020-01-30
end = 2020-02-02

[node.top]
loc = 0, 0
type = inflow
inflow = 10
ds_1 = mid

{flow_var}

[node.mid]
loc = 0, 10
type = inflow
inflow = {bad}
ds_1 = low

[node.low]
loc = 0, 20
type = gauge
ds_1 = outlet

[node.outlet]
loc = 0, 30
type = blackhole

[outputs]
node.outlet.usflow
")
}

/// The plain flow loop names the node: `mid` reads `low`, which runs after it.
#[test]
fn flow_phase_error_names_the_node_in_the_plain_loop() {
    let err = run_err(&mut load(&flow_rig("node.low.dsflow", "")));
    assert!(err.contains("no value yet"), "unexpected: {err}");
    assert!(err.contains("Phase: flow") && err.contains("Node: 'mid'"),
            "context should name the node: {err}");
}

/// The interleaved flow loop (a flow var block present) names the node too.
#[test]
fn flow_phase_error_names_the_node_in_the_interleaved_loop() {
    let err = run_err(&mut load(&flow_rig("node.low.dsflow", "[var.v]\nx = 1")));
    assert!(err.contains("no value yet"), "unexpected: {err}");
    assert!(err.contains("Phase: flow") && err.contains("Node: 'mid'"),
            "context should name the node: {err}");
}

/// Regulated chain without order var blocks; `{user}` is u1's order, `{split}` the confluence's routing
fn order_rig(user: &str, split: &str) -> String {
    format!(r#"
[kalix]
start = 2020-01-30
end = 2020-02-02

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
order = {user}
ds_1 = u2

[node.u2]
type = regulated_user
loc = 5, 40
order = 3
ds_1 = gauge

[node.gauge]
type = gauge
loc = 5, 50

[outputs]
node.u1.order
"#)
}

/// The order phase names the node: `u1` reads `u2`'s flow, written later in the step
#[test]
fn order_phase_error_names_the_node() {
    let split = "regulated = dama, damb\nharmony_fraction = 0.5";
    let err = run_err(&mut load(&order_rig("node.u2.usflow", split)));
    assert!(err.contains("no value yet"), "unexpected: {err}");
    assert!(err.contains("Phase: ordering") && err.contains("Node: 'u1'"),
            "context should name the node: {err}");
}

/// A confluence's order phase returns early from the visit, and still names the confluence
#[test]
fn order_phase_error_names_the_confluence() {
    let split = "regulated = dama, damb\nharmony_fraction = node.u2.usflow";
    let err = run_err(&mut load(&order_rig("5", split)));
    assert!(err.contains("no value yet"), "unexpected: {err}");
    assert!(err.contains("Phase: ordering") && err.contains("Node: 'conf'"),
            "context should name the confluence: {err}");
}

/// A failure before any item names none: on a new model, a rerun, or a copy of a failed model
#[test]
fn failure_before_any_item_names_none() {
    let err = interrupt_err(&mut load(&flow_rig("5", "")));
    assert!(err.contains("interrupt check failed") && err.contains("unknown_node"),
            "a new model should name no item: {err}");

    let mut model = load(&flow_rig("node.low.dsflow", ""));
    let err = run_err(&mut model);
    assert!(err.contains("Node: 'mid'"), "first run should name mid: {err}");
    let err = interrupt_err(&mut model.clone_for_run());
    assert!(err.contains("unknown_node"), "a copy of a failed model should name no item: {err}");
    let err = interrupt_err(&mut model);
    assert!(err.contains("unknown_node"), "a rerun of a failed model should name no item: {err}");
}
