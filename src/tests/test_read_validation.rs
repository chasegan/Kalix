/// Tests for the fail-fast contract on DataCache reads.
///
/// A cornerstone of the platform: an expression referencing a value that has
/// not been computed yet at that point in the timestep (e.g. today's
/// downstream flow of a node that runs later) must fail clearly, never read
/// silently. These tests pin that contract, including the first-timestep
/// validation walk that covers references hiding in untaken `if` branches.

use crate::io::ini_model_io::IniModelIO;

fn model_ini(a_inflow_expression: &str) -> String {
    format!(
        "[kalix]\n\
         start = 2020-01-01\n\
         end = 2020-01-10\n\
         \n\
         [node.a]\n\
         loc = 0, 0\n\
         type = inflow\n\
         inflow = {}\n\
         ds_1 = b\n\
         \n\
         [node.b]\n\
         loc = 0, 10\n\
         type = inflow\n\
         inflow = 1\n\
         ds_1 = sink\n\
         \n\
         [node.sink]\n\
         loc = 0, 20\n\
         type = blackhole\n\
         \n\
         [outputs]\n\
         node.a.ds_1\n",
        a_inflow_expression
    )
}

fn run_model(a_inflow_expression: &str) -> Result<crate::model::Model, String> {
    let mut model = IniModelIO::new()
        .read_model_string(&model_ini(a_inflow_expression))
        .expect("model should parse");
    model.configure()?;
    model.run()?;
    Ok(model)
}

/// expect_err needs Debug on the Ok type; discard the model for error cases.
fn run_model_expect_err(a_inflow_expression: &str, context: &str) -> String {
    match run_model(a_inflow_expression) {
        Ok(_) => panic!("{}", context),
        Err(e) => e,
    }
}

/// Node `a` runs before node `b`, so referencing today's `node.b.ds_1` from
/// `a` is illegal: the value does not exist yet. The run must fail on the
/// first timestep with an error naming the offending series.
#[test]
fn test_same_step_forward_reference_fails_clearly() {
    let err = run_model_expect_err("node.b.ds_1", "illegal reference must fail");
    assert!(err.contains("node.b.ds_1"), "error should name the series: {}", err);
    assert!(err.contains("later in the timestep"), "error should explain: {}", err);
}

/// The same illegal reference hidden in a branch that never executes
/// (`if(0, ...)`) must STILL fail on the first timestep: evaluation
/// short-circuits, but the first-step validation walk visits every reference
/// in every branch. Without the walk, this model would run for years and
/// fail (or misbehave) only when data first selected the branch.
#[test]
fn test_illegal_reference_in_untaken_branch_fails_on_first_step() {
    let err = run_model_expect_err("if(0, node.b.ds_1, 1.0)",
        "illegal reference in untaken branch must fail on step 0");
    assert!(err.contains("node.b.ds_1"), "error should name the series: {}", err);
}

/// The legal form of the same reference — yesterday's value with an explicit
/// default — must work, and produce the expected one-day-lagged feedback.
#[test]
fn test_offset_reference_is_legal() {
    let model = run_model("node.b.ds_1[-1, 0.5]").expect("offset reference is legal");

    let idx = model.data_cache.get_existing_series_idx("node.a.ds_1")
        .expect("output series exists");
    let values = &model.data_cache.series[idx].values;

    // Day 0: default 0.5. Day 1: yesterday's b = 1 + 0.5 = 1.5. Day 2: 2.5...
    assert!((values[0] - 0.5).abs() < 1e-12, "day 0 should use the default, got {}", values[0]);
    assert!((values[1] - 1.5).abs() < 1e-12, "day 1 should see yesterday's b, got {}", values[1]);
    assert!((values[2] - 2.5).abs() < 1e-12, "day 2, got {}", values[2]);
}

#[test]
fn test_renamed_inputs_section_gives_migration_hint() {
    // [inputs] was renamed to [data]. Every model predating the rename has the
    // old header, so the error names the new section rather than a bare
    // "unexpected section".
    let ini = "[kalix]\n\
               start = 2020-01-01\n\
               end = 2020-01-02\n\
               \n\
               [inputs]\n\
               ./flows.csv\n\
               \n\
               [node.a]\n\
               loc = 0, 0\n\
               type = inflow\n\
               inflow = 1\n";
    let err = IniModelIO::new().read_model_string(ini).err().expect("old [inputs] must fail to load");
    assert!(err.contains("[inputs]") && err.contains("[data]") && err.contains("renamed"),
        "error should point [inputs] users at [data], got: {}", err);
}
