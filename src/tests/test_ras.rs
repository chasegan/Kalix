use crate::io::ini_model_io::IniModelIO;

fn load(ini: &str) -> crate::model::Model {
    IniModelIO::new().read_model_string(ini).expect("model should load")
}

fn load_err(ini: &str) -> String {
    IniModelIO::new().read_model_string(ini).err().expect("expected a load error")
}

fn run(ini: &str) -> crate::model::Model {
    let mut model = load(ini);
    model.configure().expect("model should configure");
    model.run().expect("simulation should run");
    model
}

fn balance(model: &crate::model::Model, name: &str) -> f64 {
    let idx = model.account_manager.get_account_idx(name).unwrap();
    model.account_manager.get_account_balance(idx)
}

const TAIL: &str = r#"
[node.src]
type = inflow
loc = 0, 0
inflow = 50
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 10

[outputs]
node.src.dsflow
"#;

#[test]
fn test_ras_water_year_reset() {
    // Annual refill is now explicit policy: set_full at start_water_year(7).
    let ini = format!(r#"
[kalix]
start = 2020-06-28
end = 2020-07-03

[acc.g1]
accounts = name, size, initial,
           a1, 42, 2,

[ras.rollover]
targets = acc.g1
trigger = start_water_year(7)
action  = set_full
{TAIL}"#);
    let model = run(&ini);
    assert_eq!(balance(&model, "a1"), 42.0, "account refilled at water year start");
}

#[test]
fn test_ras_water_year_month_from_const() {
    let ini = format!(r#"
[kalix]
start = 2020-06-28
end = 2020-07-03

[const]
const.wy = 7

[acc.g1]
accounts = name, size, initial,
           a1, 42, 2,

[ras.rollover]
targets = acc.g1
trigger = start_water_year(const.wy)
action  = set_full
{TAIL}"#);
    let model = run(&ini);
    assert_eq!(balance(&model, "a1"), 42.0, "month resolved from [const]");
}

#[test]
fn test_ras_expression_trigger_is_level_semantic() {
    // scale(0.5) while src flows. RAS triggers run before the flow phase, so
    // node-output reads need the explicit previous-step offset (the engine's
    // no-lookahead rule): the trigger first sees flow on day 2 and fires every
    // day after — 4 firings over a 5-day run -> 100 * 0.5^4.
    let ini = format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-05

[acc.g1]
accounts = name, size, initial,
           a1, 1000, 100,

[ras.forfeit]
targets = acc.g1
trigger = node.src.dsflow[-1, 0] > 0
action  = scale(0.5)
{TAIL}"#);
    let model = run(&ini);
    assert_eq!(balance(&model, "a1"), 100.0 * 0.5f64.powi(4), "level trigger compounds per step");
}

#[test]
fn test_ras_file_order_is_execution_order() {
    // set(10) then debit(3) each step: final balance 7. Reversed order would
    // leave 10 — file order is meaning.
    let ini = format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-03

[acc.g1]
accounts = name, size,
           a1, 100,

[ras.first]
targets = acc.g1
trigger = every_step
action  = set(10)

[ras.second]
targets = acc.g1
trigger = every_step
action  = debit(3)
{TAIL}"#);
    let model = run(&ini);
    assert_eq!(balance(&model, "a1"), 7.0, "RAS sections execute in file order");
}

#[test]
fn test_ras_runs_before_flow_phase() {
    // Credit lands at the top of the step, so the day's take can use it:
    // credit(5) every step, demand 10 -> diversion is 5 every day.
    let ini = r#"
[kalix]
start = 2020-01-01
end = 2020-01-03

[acc.g1]
accounts = name, size,
           a1, 100,

[ras.daily_credit]
targets = acc.g1
trigger = every_step
action  = credit(5)

[node.src]
type = inflow
loc = 0, 0
inflow = 50
ds_1 = u1

[node.u1]
type = unregulated_user
loc = 0, 10
demand = 10
accounts = a1

[outputs]
node.u1.diversion
"#;
    let mut model = run(ini);
    let idx = model.data_cache.get_series_idx("node.u1.diversion", false).expect("diversion series");
    let series = model.data_cache.series[idx].clone();
    assert_eq!(series.values[0], 5.0, "day 1 take sees day 1 credit");
    assert_eq!(series.values[1], 5.0);
    assert_eq!(balance(&model, "a1"), 0.0, "credited then fully drawn each day");
}

#[test]
fn test_ras_reduce_to_carryover() {
    // Carryover limit at start_year: balance 80 reduced to 30.
    let ini = format!(r#"
[kalix]
start = 2019-12-30
end = 2020-01-02

[acc.g1]
accounts = name, size, initial,
           a1, 100, 80,

[ras.carryover]
targets = acc.g1
trigger = start_year
action  = reduce_to(30)
{TAIL}"#);
    let model = run(&ini);
    assert_eq!(balance(&model, "a1"), 30.0, "reduce_to caps at the rollover");
}

#[test]
fn test_ras_multi_target_stencilled() {
    let ini = format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-02

[acc.g1]
accounts = name, size,
           a1, 100,

[acc.g2]
accounts = name, size,
           b1, 200,

[ras.fill_both]
targets = acc.g1, acc.g2
trigger = every_step
action  = set_full
{TAIL}"#);
    let model = run(&ini);
    assert_eq!(balance(&model, "a1"), 100.0);
    assert_eq!(balance(&model, "b1"), 200.0);
}

#[test]
fn test_ras_parse_errors() {
    let base = |ras: &str| format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-02

[acc.g1]
accounts = name, size,
           a1, 100,
{ras}
{TAIL}"#);

    // Target must be an acc.* reference
    let err = load_err(&base("[ras.r1]\ntargets = g1\ntrigger = every_step\naction = set_full\n"));
    assert!(err.contains("must be an account group reference"), "unexpected: {}", err);

    // Unknown group
    let err = load_err(&base("[ras.r1]\ntargets = acc.nope\ntrigger = every_step\naction = set_full\n"));
    assert!(err.contains("Unknown account group"), "unexpected: {}", err);

    // Missing action
    let err = load_err(&base("[ras.r1]\ntargets = acc.g1\ntrigger = every_step\n"));
    assert!(err.contains("missing 'action'"), "unexpected: {}", err);

    // Unknown action
    let err = load_err(&base("[ras.r1]\ntargets = acc.g1\ntrigger = every_step\naction = obliterate(2)\n"));
    assert!(err.contains("Unknown RAS action"), "unexpected: {}", err);

    // Unexpected property
    let err = load_err(&base("[ras.r1]\ntargets = acc.g1\ntrigger = every_step\naction = set_full\nwater_year = 7\n"));
    assert!(err.contains("exactly three properties"), "unexpected: {}", err);

    // start_water_year without month
    let err = load_err(&base("[ras.r1]\ntargets = acc.g1\ntrigger = start_water_year\naction = set_full\n"));
    assert!(err.contains("needs its month"), "unexpected: {}", err);

    // start_water_year month out of range
    let err = load_err(&base("[ras.r1]\ntargets = acc.g1\ntrigger = start_water_year(13)\naction = set_full\n"));
    assert!(err.contains("whole number 1-12"), "unexpected: {}", err);
}

#[test]
fn test_ras_round_trip() {
    let ini = format!(r#"
[kalix]
start = 2020-06-28
end = 2020-07-03

[acc.g1]
accounts = name, size, initial,
           a1, 42, 2,

[ras.rollover]
targets = acc.g1
trigger = start_water_year(7)
action  = set_full
{TAIL}"#);
    let model = load(&ini);
    let rendered = IniModelIO::new().model_to_string(&model);
    let model2 = IniModelIO::new().read_model_string(&rendered)
        .unwrap_or_else(|e| panic!("canonical render should re-load, got: {}\n---\n{}", e, rendered));
    assert_eq!(model2.ras_systems.len(), 1, "RAS survives round-trip");
    assert!(rendered.contains("trigger = start_water_year(7)"), "trigger re-emitted as written:\n{}", rendered);
}

#[test]
fn test_ras_fixture_annual_rollover_with_recorders() {
    // Hand-computed annual-accounting fixture: one account, a carryover cap
    // and an allocation credit at the water-year boundary (file order:
    // carryover applies BEFORE the credit), and a daily user take.
    //
    //   a1: size 100, initial 30; user takes 5/day (ample flow)
    //   Jun 28: 30-5=25   Jun 29: 20   Jun 30: 15
    //   Jul  1: reduce_to(20) -> 15 (under cap), credit 50 -> 65, take 5 -> 60
    //   Jul  2: 55   Jul 3: 50
    let ini = r#"
[kalix]
start = 2020-06-28
end = 2020-07-03

[acc.gs]
accounts = name, size, initial,
           a1, 100, 30,

[ras.carryover]
targets = acc.gs
trigger = start_water_year(7)
action  = reduce_to(20)

[ras.allocation]
targets = acc.gs
trigger = start_water_year(7)
action  = credit(50)

[node.src]
type = inflow
loc = 0, 0
inflow = 50
ds_1 = u1

[node.u1]
type = unregulated_user
loc = 0, 10
demand = 5
accounts = a1

[outputs]
node.u1.diversion
acc.a1.balance
acc.a1.space
ras.carryover.fired
"#;
    let mut model = run(ini);

    let bal_idx = model.data_cache.get_series_idx("acc.a1.balance", false).expect("balance series");
    let bal = model.data_cache.series[bal_idx].clone();
    assert_eq!(bal.values, vec![25.0, 20.0, 15.0, 60.0, 55.0, 50.0], "date-by-date balances");

    let space_idx = model.data_cache.get_series_idx("acc.a1.space", false).expect("space series");
    let space = model.data_cache.series[space_idx].clone();
    assert_eq!(space.values, vec![75.0, 80.0, 85.0, 40.0, 45.0, 50.0], "space = size - balance");

    let fired_idx = model.data_cache.get_series_idx("ras.carryover.fired", false).expect("fired series");
    let fired = model.data_cache.series[fired_idx].clone();
    assert_eq!(fired.values, vec![0.0, 0.0, 0.0, 1.0, 0.0, 0.0], "fired only at the boundary");

    let div_idx = model.data_cache.get_series_idx("node.u1.diversion", false).expect("diversion series");
    let div = model.data_cache.series[div_idx].clone();
    assert_eq!(div.values, vec![5.0; 6], "demand fully met throughout");
}
