/// Tests for `{ ... }` program blocks in DynamicInput values.
///
/// A program block is `;`-terminated statements followed by a bare final
/// expression (the block's value). Statements are local assignments
/// (`name = expr;`) and `assert(expr);`. Locals live in the DataCache
/// expression-state arena, resolved to absolute slots at lowering.
///
/// See docs/functions/structured_expressions_design.md §3-4,
/// FunctionParser::parse_program, and DynamicInput::program_from_string.
///
/// The sections mirror the test plan:
///   A. Happy-path evaluation (mirrors test_dynamic_input.rs helpers).
///   B. Parse/load errors (from_string returns Err with a key phrase).
///   C. Variant-selection guards (plain expressions keep their exact variants).
///   D. End-to-end via a full model (mirrors test_ini_with_functions.rs).

use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::timeseries::Timeseries;
use crate::tid::utils::wrap_to_u64;
use crate::io::ini_model_io::IniModelIO;

/// 2020-01-01 00:00:00 UTC (wrapped), the start used by every cache here.
fn start_ts() -> u64 {
    wrap_to_u64(1577836800)
}

/// A cache initialised for daily stepping, positioned at step 0. No series.
fn base_cache() -> DataCache {
    let mut dc = DataCache::new();
    let ts0 = start_ts();
    dc.initialize(ts0);
    dc.set_start_and_stepsize(ts0, 86400);
    dc.set_current_step(0);
    dc
}

/// Add a daily series with the given values, returning its cache index.
fn add_series(dc: &mut DataCache, name: &str, values: &[f64], critical: bool) -> usize {
    let idx = dc.get_or_add_new_series(name, critical);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_ts();
    for &v in values {
        ts.push_value(v);
    }
    dc.series[idx] = ts;
    idx
}

/// A cache holding a single series "data.x" with the given values, at step 0.
fn cache_with_x(values: &[f64]) -> DataCache {
    let mut dc = base_cache();
    add_series(&mut dc, "data.x", values, true);
    dc
}

// ============================================================================
// A. Happy-path program evaluation
// ============================================================================

/// 1. A single local, used in the result expression.
#[test]
fn test_program_single_local() {
    let mut dc = cache_with_x(&[5.0]);
    let input = DynamicInput::from_string("{ x = data.x * 2; x + 1 }", &mut dc, true, None)
        .expect("program should parse");
    // Ensure the block form actually produced a Program variant.
    assert!(matches!(input, DynamicInput::Program { .. }), "expected Program variant");
    assert_eq!(input.get_value(&mut dc), 11.0); // (5*2) + 1
}

/// 2. Locals chain and re-assign. A constant-only program is still a Program
/// (block form is chosen on the leading '{'), never folded to a Constant.
#[test]
fn test_program_chaining_and_reassignment() {
    let mut dc = base_cache();
    let input = DynamicInput::from_string("{ a = 1; a = a + 1; a = a * 10; a }", &mut dc, true, None)
        .expect("program should parse");
    assert!(matches!(input, DynamicInput::Program { .. }),
        "constant program must stay a Program, not fold to a Constant");
    assert_eq!(input.get_value(&mut dc), 20.0); // 1 -> 2 -> 20
}

/// 3. Local names are case-insensitive, like every name in the language:
/// assign `Total`, read `total`. Pins the case-folding in both
/// `Program::get_external_variables` (the bare-name pre-check) and the
/// lowering's locals map — the two must agree, and once disagreed.
#[test]
fn test_program_case_insensitive_locals() {
    let mut dc = cache_with_x(&[4.0]);
    let input = DynamicInput::from_string("{ Total = data.x + 1; total * 2 }", &mut dc, true, None)
        .expect("program should parse");
    assert_eq!(input.get_value(&mut dc), 10.0); // (4+1) * 2
}

/// 3b. Same-case locals DO work — the companion to the ignored cross-case test
/// above, documenting that locals themselves are fine; only the case-folding
/// in the external-variable pre-check is defective.
#[test]
fn test_program_same_case_locals_work() {
    let mut dc = cache_with_x(&[4.0]);
    let input = DynamicInput::from_string("{ Total = data.x + 1; Total * 2 }", &mut dc, true, None)
        .expect("same-case locals should parse");
    assert_eq!(input.get_value(&mut dc), 10.0); // (4+1) * 2
}

/// 4. A passing assert leaves the result undisturbed.
#[test]
fn test_program_passing_assert() {
    let mut dc = cache_with_x(&[7.0]);
    let input = DynamicInput::from_string("{ v = data.x; assert(v > 0); v * 3 }", &mut dc, true, None)
        .expect("program should parse");
    assert_eq!(input.get_value(&mut dc), 21.0); // 7 * 3, assert passes silently
}

/// 5. Offset syntax works inside a block. At step 0 the [-1] lookback has no
/// history, so the default (99.0) is returned.
#[test]
fn test_program_offset_syntax() {
    let mut dc = cache_with_x(&[10.0, 20.0]);
    dc.set_current_step(0);
    let input = DynamicInput::from_string("{ prev = data.x[-1, 99.0]; prev }", &mut dc, true, None)
        .expect("program should parse");
    assert_eq!(input.get_value(&mut dc), 99.0); // default at step 0
}

/// 6. `this.` expands inside a block: with self_context "node.mynode", the body
/// reference `this.x` resolves to the series "node.mynode.x".
#[test]
fn test_program_this_expansion() {
    let mut dc = base_cache();
    let idx = add_series(&mut dc, "node.mynode.x", &[42.0], false);
    // Sanity: the series we wrote is the one the program should read.
    assert_eq!(dc.get_existing_series_idx("node.mynode.x"), Some(idx));

    let input = DynamicInput::from_string(
        "{ v = this.x; v }", &mut dc, false, Some("node.mynode"))
        .expect("program with this. should parse");
    dc.set_current_step(0);
    assert_eq!(input.get_value(&mut dc), 42.0);
}

/// 7. Two programs sharing the local name `x` do not interfere — each gets its
/// own arena slots. Both evaluate against one DataCache.
#[test]
fn test_program_separate_locals_do_not_interfere() {
    let mut dc = cache_with_x(&[5.0]);
    let a = DynamicInput::from_string("{ x = data.x * 2; x + 1 }", &mut dc, true, None)
        .expect("program a should parse");
    let b = DynamicInput::from_string("{ x = data.x * 3; x + 10 }", &mut dc, true, None)
        .expect("program b should parse");

    // Evaluate interleaved to prove neither clobbers the other's slot.
    assert_eq!(a.get_value(&mut dc), 11.0); // (5*2) + 1
    assert_eq!(b.get_value(&mut dc), 25.0); // (5*3) + 10
    assert_eq!(a.get_value(&mut dc), 11.0); // still correct after b ran
    assert_eq!(b.get_value(&mut dc), 25.0);
}

// ============================================================================
// B. Parse / load errors — from_string returns Err containing a key phrase.
// ============================================================================

/// Helper: parse a program string, expecting an Err whose message contains
/// `needle`. Returns the full error for context on failure.
fn expect_program_err(body: &str, needle: &str) {
    let mut dc = cache_with_x(&[1.0]);
    let result = DynamicInput::from_string(body, &mut dc, true, None);
    let err = match result {
        Ok(_) => panic!("'{}' should fail to load, but it parsed", body),
        Err(e) => e,
    };
    assert!(
        err.contains(needle),
        "'{}' error should contain '{}', got: {}",
        body, needle, err
    );
}

/// 8. A trailing ';' on the final line leaves the block with no result value.
#[test]
fn test_err_trailing_semicolon_on_final_line() {
    expect_program_err("{ x = data.x; x + 1; }", "program has no result value");
}

/// 9. An assignment cannot be the last item — the block needs a bare result.
#[test]
fn test_err_assignment_as_last_item() {
    expect_program_err("{ x = 1; }", "no result value");
}

/// 10. An assert cannot be the last item.
#[test]
fn test_err_assert_as_last_item() {
    expect_program_err("{ assert(1); }", "no result value");
}

/// 11. An empty block has no result value.
#[test]
fn test_err_empty_block() {
    expect_program_err("{}", "no result value");
}

/// 12. An unclosed block is a missing-brace error. The "missing '}'" branch
/// fires when EOF is reached at a statement-start position (here, right after
/// a terminating ';'). NOTE: an unclosed block that ends mid-expression, e.g.
/// `{ x = 1; x`, instead reports "unexpected token in program: EOF" — a less
/// specific but still-loud parse error (see the sibling test below).
#[test]
fn test_err_unclosed_block() {
    expect_program_err("{ x = data.x; ", "missing '}'");
}

/// 12b. An unclosed block ending mid-expression still fails loudly at load,
/// documenting the (less specific) message the parser produces there.
#[test]
fn test_err_unclosed_block_mid_expression() {
    expect_program_err("{ x = 1; x", "EOF");
}

/// 13. Tokens after the closing brace are rejected.
#[test]
fn test_err_trailing_tokens_after_brace() {
    expect_program_err("{ 1 } 2", "after closing '}'");
}

/// 14. Assigning to a dotted name is rejected — dotted names are model refs.
#[test]
fn test_err_assign_to_dotted_name() {
    expect_program_err("{ data.x = 1; 1 }", "dotted names are model references");
}

/// 15. A local may not shadow a builtin function name.
#[test]
fn test_err_shadow_builtin() {
    expect_program_err("{ min = 1; min }", "builtin function name");
}

/// 16. `this` is reserved and cannot be an assignment target.
#[test]
fn test_err_assign_to_this() {
    expect_program_err("{ this = 1; 1 }", "reserved");
}

/// 17. Using a local before it is assigned is a load error.
#[test]
fn test_err_use_before_assign() {
    expect_program_err("{ y = x + 1; y }", "used before it is assigned");
}

/// 18. A bare expression statement (not the final result) has no effect.
#[test]
fn test_err_bare_expression_statement() {
    expect_program_err("{ 1 + 2; 3 }", "statement has no effect");
}

/// 19. `assert` without parentheses is reserved; it must be written assert(...).
#[test]
fn test_err_assert_without_parens() {
    expect_program_err("{ assert 1; 2 }", "reserved");
}

/// 20. `==` still means equality where `=` is assignment — the two are not
/// confused inside a block. `x == 1` yields 1.0 (true).
#[test]
fn test_program_equality_vs_assignment() {
    let mut dc = base_cache();
    let input = DynamicInput::from_string("{ x = 1; x == 1 }", &mut dc, true, None)
        .expect("program should parse");
    assert_eq!(input.get_value(&mut dc), 1.0);
}

// ============================================================================
// C. Variant-selection guards (design §9) — plain expressions must keep their
// exact variants; only a leading '{' selects the Program form.
// ============================================================================

#[test]
fn test_variant_selection_guards() {
    // "data.x" -> DirectReference
    {
        let mut dc = base_cache();
        add_series(&mut dc, "data.x", &[1.0], true);
        let input = DynamicInput::from_string("data.x", &mut dc, true, None).unwrap();
        assert!(matches!(input, DynamicInput::DirectReference { .. }),
            "'data.x' should be DirectReference, got {:?}", input);
    }
    // "5.0" -> Constant
    {
        let mut dc = base_cache();
        let input = DynamicInput::from_string("5.0", &mut dc, true, None).unwrap();
        assert!(matches!(input, DynamicInput::Constant { .. }),
            "'5.0' should be Constant, got {:?}", input);
    }
    // "0.5 * data.x + 0.5 * data.y" -> LinearCombination
    {
        let mut dc = base_cache();
        let input = DynamicInput::from_string("0.5 * data.x + 0.5 * data.y", &mut dc, true, None).unwrap();
        assert!(matches!(input, DynamicInput::LinearCombination { .. }),
            "weighted sum should be LinearCombination, got {:?}", input);
    }
    // "data.x * 2" -> LinearCombination. A single data reference scaled by a
    // constant is a one-term linear combination (matches the established
    // test_single_term_with_coefficient_creates_linear_combination), NOT a
    // Function. The task plan's "-> Function" label for this exact string is
    // inaccurate; the real guarantee is that this variant is unchanged.
    {
        let mut dc = base_cache();
        add_series(&mut dc, "data.x", &[1.0], true);
        let input = DynamicInput::from_string("data.x * 2", &mut dc, true, None).unwrap();
        assert!(matches!(input, DynamicInput::LinearCombination { .. }),
            "'data.x * 2' is a single scaled ref -> LinearCombination, got {:?}", input);
    }
    // A genuinely non-linear expression -> Function (the variant the task
    // intended to guard). A nested function call over a data ref cannot be a
    // linear combination or a single variable.
    {
        let mut dc = base_cache();
        add_series(&mut dc, "data.x", &[9.0], true);
        let input = DynamicInput::from_string("sqrt(data.x) * 2", &mut dc, true, None).unwrap();
        assert!(matches!(input, DynamicInput::Function { .. }),
            "'sqrt(data.x) * 2' should be Function, got {:?}", input);
    }
    // "{ data.x }" -> Program
    {
        let mut dc = base_cache();
        add_series(&mut dc, "data.x", &[1.0], true);
        let input = DynamicInput::from_string("{ data.x }", &mut dc, true, None).unwrap();
        assert!(matches!(input, DynamicInput::Program { .. }),
            "'{{ data.x }}' should be Program, got {:?}", input);
    }
}

// ============================================================================
// D. End-to-end via a full model (mirrors test_ini_with_functions.rs).
// ============================================================================

/// An inflow model whose inflow is a multi-statement block using a local, a
/// constant (c.*), sim.step, and a passing assert. node.a.ds_1 equals the
/// evaluated inflow each step, so the outputs are hand-computable.
#[test]
fn test_model_program_inflow_end_to_end() {
    let ini = "\
[kalix]
start = 2020-01-01
end = 2020-01-05

[const]
const.base = 10

[node.a]
loc = 0, 0
type = inflow
inflow = { lvl = const.base; assert(lvl > 0); lvl + sim.step }
ds_1 = b

[node.b]
loc = 0, 10
type = inflow
inflow = 1
ds_1 = sink

[node.sink]
loc = 0, 20
type = blackhole

[outputs]
node.a.ds_1
";
    let mut model = IniModelIO::read_model_string(ini)
        .expect("model with a program inflow should parse");
    model.configure().expect("configuration should succeed");
    model.run().expect("run should succeed");

    let idx = model.data_cache.get_existing_series_idx("node.a.ds_1")
        .expect("output series should exist");
    let values = &model.data_cache.series[idx].values;

    // 5 daily steps (2020-01-01 .. 2020-01-05), value = base(10) + sim.step.
    let expected = [10.0, 11.0, 12.0, 13.0, 14.0];
    assert_eq!(values.len(), expected.len(), "expected 5 steps, got {}", values.len());
    for (i, &e) in expected.iter().enumerate() {
        assert!((values[i] - e).abs() < 1e-12,
            "step {} expected {}, got {}", i, e, values[i]);
    }
}

/// An assert that fails mid-run must make the run return an Err. The run loop
/// wraps the whole simulation in catch_unwind, so the panic surfaces as an
/// error whose text mentions "Assertion failed".
#[test]
fn test_model_program_failing_assert() {
    let ini = "\
[kalix]
start = 2020-01-01
end = 2020-01-05

[node.a]
loc = 0, 0
type = inflow
inflow = { s = sim.step; assert(s < 2); s }
ds_1 = b

[node.b]
loc = 0, 10
type = inflow
inflow = 1
ds_1 = sink

[node.sink]
loc = 0, 20
type = blackhole

[outputs]
node.a.ds_1
";
    let mut model = IniModelIO::read_model_string(ini)
        .expect("model should parse");
    model.configure().expect("configuration should succeed");

    // assert(sim.step < 2) holds for steps 0 and 1, fails (0) at step 2.
    let result = model.run();
    let err = match result {
        Ok(_) => panic!("run should fail when an assert fails mid-run"),
        Err(e) => e,
    };
    assert!(err.contains("Assertion failed"),
        "run error should mention the failed assert, got: {}", err);
}

/// A model run twice in a row gives identical results — the expression-state
/// arena is reset at the start of every run (Model::run calls
/// expr_state.reset()), so program locals never carry state between runs.
#[test]
fn test_model_program_run_twice_identical() {
    let ini = "\
[kalix]
start = 2020-01-01
end = 2020-01-05

[const]
const.base = 10

[node.a]
loc = 0, 0
type = inflow
inflow = { lvl = const.base; acc = lvl + sim.step; acc }
ds_1 = b

[node.b]
loc = 0, 10
type = inflow
inflow = 1
ds_1 = sink

[node.sink]
loc = 0, 20
type = blackhole

[outputs]
node.a.ds_1
";
    let mut model = IniModelIO::read_model_string(ini)
        .expect("model should parse");
    model.configure().expect("configuration should succeed");

    model.run().expect("first run should succeed");
    let idx = model.data_cache.get_existing_series_idx("node.a.ds_1")
        .expect("output series should exist");
    let first: Vec<f64> = model.data_cache.series[idx].values.clone();

    model.run().expect("second run should succeed");
    let idx2 = model.data_cache.get_existing_series_idx("node.a.ds_1")
        .expect("output series should still exist");
    let second: Vec<f64> = model.data_cache.series[idx2].values.clone();

    assert_eq!(first, second, "two runs must produce identical results");
    // And the values are the hand-computed base + step, as a sanity check.
    assert_eq!(first, vec![10.0, 11.0, 12.0, 13.0, 14.0]);
}

/// Locals may not shadow ANY reserved tier — builtins, stateful functions,
/// or keywords (owner decision, July 2026; one registry answers for all,
/// so new tiers extend the guard automatically).
#[test]
fn test_local_cannot_shadow_stateful_or_reserved() {
    for (value, needle) in [
        ("{ steps_since = 1; steps_since }", "stateful function"),
        ("{ moving_mean = 1; moving_mean }", "stateful function"),
        ("{ moving_annual_sum = 1; moving_annual_sum }", "stateful function"),
        ("{ moving_monthly_sum = 1; moving_monthly_sum }", "stateful function"),
        ("{ moving_daily_sum = 1; moving_daily_sum }", "stateful function"),
        ("{ min = 1; min }", "builtin function"),
        ("{ this = 1; 1 }", "reserved word"),
    ] {
        let mut dc = DataCache::new();
        let err = DynamicInput::from_string(value, &mut dc, true, None)
            .expect_err(&format!("'{}' should be rejected", value));
        assert!(err.contains(needle),
            "'{}' error should mention '{}', got: {}", value, needle, err);
    }
}
