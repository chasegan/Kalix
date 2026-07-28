use crate::io::ini_model_io::IniModelIO;

fn load(ini: &str) -> crate::model::Model {
    IniModelIO::read_model_string(ini).expect("model should load")
}

fn load_err(ini: &str) -> String {
    IniModelIO::read_model_string(ini).err().expect("expected a load error").to_string()
}

fn run(ini: &str) -> crate::model::Model {
    let mut model = load(ini);
    model.configure().expect("model should configure");
    model.run().expect("simulation should run");
    model
}

fn series(model: &mut crate::model::Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_series_idx(name, false)
        .unwrap_or_else(|| panic!("no series '{}'", name));
    model.data_cache.series[idx].values.clone()
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
fn test_ras_roll_cap() {
    // Rolling 2-WY cap of 20: the user (demand 10/d, ample flow) takes 20
    // before 2020-07-01 and exhausts the cap. The 2020 roll banks those
    // debits (nothing expires yet), so WY2020 stays dry all year. The 2021
    // roll expires them, crediting 20 back: the user takes 10/d again.
    let ini = r#"
[kalix]
start = 2020-06-28
end = 2021-07-02

[acc.g1]
accounts = name, size, initial,
           a1, 20, 20,

[ras.roll]
targets = acc.g1
trigger = start_water_year(7)
action  = roll_cap(2)

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
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 20

[outputs]
node.u1.diversion
"#;
    let mut model = run(ini);
    let div = series(&mut model, "node.u1.diversion");
    assert_eq!(&div[..3], &[10.0, 10.0, 0.0], "cap of 20 exhausts before the WY boundary");
    assert!(div[3..368].iter().all(|&x| x == 0.0), "banked debits keep WY2020 dry");
    assert_eq!(&div[368..370], &[10.0, 10.0], "2021 roll expires the debits; take resumes");
    assert_eq!(balance(&model, "a1"), 0.0, "20 credited back and re-used");
}

#[test]
fn test_ras_credit_fraction() {
    // credit_fraction credits each account by a fraction of its own size
    // (clamped at size); negative fractions debit.
    //   a1 (size 100, initial 90): +25 -> 100 (clamped), -5 -> 95, twice -> 95
    //   a2 (size 40, initial 0):   +10 - 2 = 8 per day, twice -> 16
    let ini = format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-02

[acc.g1]
accounts = name, size, initial,
           a1, 100, 90,
           a2, 40, 0,

[ras.up]
targets = acc.g1
trigger = every_step
action  = credit_fraction(0.25)

[ras.down]
targets = acc.g1
trigger = every_step
action  = credit_fraction(-0.05)
{TAIL}"#);
    let model = run(&ini);
    assert_eq!(balance(&model, "a1"), 95.0, "credit clamped at size, negative debits");
    assert_eq!(balance(&model, "a2"), 16.0, "fraction applies per-account size");
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
    let rendered = IniModelIO::model_to_string(&model);
    let model2 = IniModelIO::read_model_string(&rendered)
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
acc.a1.opening_balance
acc.a1.closing_balance
acc.a1.debits
acc.gs.closing_balance
ras.carryover.fired
"#;
    let mut model = run(ini);

    let open_idx = model.data_cache.get_series_idx("acc.a1.opening_balance", false).expect("opening series");
    let open = model.data_cache.series[open_idx].clone();
    assert_eq!(open.values, vec![30.0, 25.0, 20.0, 65.0, 60.0, 55.0],
        "opening balance is post-RAS, pre-take (Jul 1: reduce_to leaves 15, credit takes it to 65)");

    let close_idx = model.data_cache.get_series_idx("acc.a1.closing_balance", false).expect("closing series");
    let close = model.data_cache.series[close_idx].clone();
    assert_eq!(close.values, vec![25.0, 20.0, 15.0, 60.0, 55.0, 50.0], "closing balance is end of day");

    let deb_idx = model.data_cache.get_series_idx("acc.a1.debits", false).expect("debits series");
    let deb = model.data_cache.series[deb_idx].clone();
    assert_eq!(deb.values, vec![5.0; 6], "debits are node takes only, reset each step");

    // Single-member group aggregate tracks its one account
    let grp_idx = model.data_cache.get_series_idx("acc.gs.closing_balance", false).expect("group series");
    let grp = model.data_cache.series[grp_idx].clone();
    assert_eq!(grp.values, close.values, "group aggregate sums its members");

    let fired_idx = model.data_cache.get_series_idx("ras.carryover.fired", false).expect("fired series");
    let fired = model.data_cache.series[fired_idx].clone();
    assert_eq!(fired.values, vec![0.0, 0.0, 0.0, 1.0, 0.0, 0.0], "fired only at the boundary");

    let div_idx = model.data_cache.get_series_idx("node.u1.diversion", false).expect("diversion series");
    let div = model.data_cache.series[div_idx].clone();
    assert_eq!(div.values, vec![5.0; 6], "demand fully met throughout");
}

#[test]
fn test_acc_readable_in_node_expressions() {
    // A user's demand is driven by its own account's opening balance: take a
    // tenth of what it holds each day. Opening balance is the post-RAS,
    // pre-take snapshot, so day 1 demands 10 (of 100), leaving 90; day 2
    // demands 9, and so on.
    let ini = r#"
[kalix]
start = 2020-01-01
end = 2020-01-04

[acc.g1]
accounts = name, size, initial,
           a1, 100, 100,

[node.src]
type = inflow
loc = 0, 0
inflow = 50
ds_1 = u1

[node.u1]
type = unregulated_user
loc = 0, 10
demand = 0.1 * acc.a1.opening_balance
accounts = a1

[outputs]
node.u1.diversion
"#;
    let mut model = run(ini);
    let idx = model.data_cache.get_series_idx("node.u1.diversion", false).expect("diversion series");
    let div = model.data_cache.series[idx].clone();
    let expected = [10.0, 9.0, 8.1, 7.29];
    assert_eq!(div.values.len(), expected.len());
    for (got, want) in div.values.iter().zip(expected) {
        assert!((got - want).abs() < 1e-9, "demand follows the opening balance: {:?}", div.values);
    }
    assert!((balance(&model, "a1") - 65.61).abs() < 1e-9);
}

#[test]
fn test_acc_opening_balance_is_order_independent() {
    // Two users share one account and both size their demand off the opening
    // balance. The second reads the *same* value as the first even though the
    // first has already drawn the balance down — that is the point of the
    // snapshot. Both demand 10 (of 100); account ends at 80.
    let ini = r#"
[kalix]
start = 2020-01-01
end = 2020-01-01

[acc.g1]
accounts = name, size, initial,
           shared, 100, 100,

[node.src]
type = inflow
loc = 0, 0
inflow = 50
ds_1 = u1

[node.u1]
type = unregulated_user
loc = 0, 10
demand = 0.1 * acc.shared.opening_balance
accounts = shared
ds_1 = u2

[node.u2]
type = unregulated_user
loc = 0, 20
demand = 0.1 * acc.shared.opening_balance
accounts = shared

[outputs]
node.u1.diversion
node.u2.diversion
"#;
    let mut model = run(ini);
    for node in ["u1", "u2"] {
        let idx = model.data_cache.get_series_idx(&format!("node.{}.diversion", node), false).unwrap();
        assert_eq!(model.data_cache.series[idx].values[0], 10.0,
            "{} sees the same opening balance", node);
    }
    assert_eq!(balance(&model, "shared"), 80.0, "both takes debited the live balance");
}

#[test]
fn test_acc_group_aggregate_in_expression() {
    // A RAS trigger reading the group's opening balance: top the group up
    // whenever it opens below 50 (two accounts, 20 each = 40 -> fires).
    let ini = format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-02

[acc.g1]
accounts = name, size, initial,
           a1, 100, 20,
           a2, 100, 20,

[ras.top_up]
targets = acc.g1
trigger = acc.g1.opening_balance[-1, 0] < 50
action  = set_full
{TAIL}"#);
    let model = run(&ini);
    // Day 1: no prior opening balance (offset default 0) -> 0 < 50 fires, both full
    assert_eq!(balance(&model, "a1"), 100.0);
    assert_eq!(balance(&model, "a2"), 100.0);
}

#[test]
fn test_acc_reference_validation_errors() {
    let base = |expr: &str| format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-02

[acc.g1]
accounts = name, size,
           a1, 100,

[node.src]
type = inflow
loc = 0, 0
inflow = {expr}
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 10

[outputs]
node.src.dsflow
"#);

    let err = load_err(&base("acc.nope.opening_balance"));
    assert!(err.contains("Unknown account or account group 'nope'"), "unexpected: {}", err);

    let err = load_err(&base("acc.a1.oppening_balance"));
    assert!(err.contains("Unknown field 'oppening_balance'"), "unexpected: {}", err);

    // `size` is an account field but not a group aggregate
    let err = load_err(&base("acc.g1.size"));
    assert!(err.contains("Unknown field 'size'"), "unexpected: {}", err);
}

#[test]
fn test_acc_closing_balance_needs_offset_in_step() {
    // closing_balance is written at end of step, so reading it mid-step hits
    // the standard unwritten-value error — same rule as a node output that has
    // not run yet.
    let ini = r#"
[kalix]
start = 2020-01-01
end = 2020-01-02

[acc.g1]
accounts = name, size,
           a1, 100,

[node.src]
type = inflow
loc = 0, 0
inflow = acc.a1.closing_balance
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 10

[outputs]
node.src.dsflow
"#;
    let mut model = load(ini);
    model.configure().expect("configures fine — the error is a runtime one");
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| model.run()));
    match result {
        Err(_) => {} // panicked with the unwritten-value message
        Ok(Err(msg)) => assert!(msg.contains("no value yet"), "unexpected error: {}", msg),
        Ok(Ok(())) => panic!("expected mid-step closing_balance read to fail"),
    }
}

#[test]
fn test_ras_placement_in_file_is_free() {
    // A RAS may sit anywhere — above the nodes, below them, or beside the
    // nodes it pertains to. Execution is always at the top of the step, in the
    // order the sections appear. Here `first` sits before the nodes and
    // `second` after them: set(10) then debit(3) leaves 7, exactly as when the
    // two sit together (test_ras_file_order_is_execution_order).
    let ini = r#"
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

[node.src]
type = inflow
loc = 0, 0
inflow = 50
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 10

[ras.second]
targets = acc.g1
trigger = every_step
action  = debit(3)

[outputs]
node.src.dsflow
"#;
    let model = run(ini);
    assert_eq!(balance(&model, "a1"), 7.0, "file order holds across node sections");

    // And the reverse declaration order gives the other answer, proving it is
    // genuinely file order rather than a fixed rule.
    let reversed = ini
        .replace("[ras.first]\ntargets = acc.g1\ntrigger = every_step\naction  = set(10)\n\n", "")
        .replace("[ras.second]\ntargets = acc.g1\ntrigger = every_step\naction  = debit(3)\n",
                 "[ras.second]\ntargets = acc.g1\ntrigger = every_step\naction  = debit(3)\n\n\
                  [ras.first]\ntargets = acc.g1\ntrigger = every_step\naction  = set(10)\n");
    let model = run(&reversed);
    assert_eq!(balance(&model, "a1"), 10.0, "debit then set leaves the set value");
}

// ==================== allocate / reset_allocation (§3.4) ====================

#[test]
fn test_allocate_sets_allocation_not_balance() {
    // allocate(pct) raises each account's *allocation* — balance plus use to
    // date — to pct% of its entitlement. With no use yet, that is a plain
    // credit: 60% of 100 = 60.
    let ini = format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-02

[acc.g1]
accounts = name, size,
           a1, 100,
           a2, 50,

[ras.announce]
targets = acc.g1
trigger = every_step
action  = allocate(60)
{TAIL}"#);
    let model = run(&ini);
    assert_eq!(balance(&model, "a1"), 60.0, "60% of 100");
    assert_eq!(balance(&model, "a2"), 30.0, "60% of 50 — pro-rata by entitlement");
}

#[test]
fn test_allocate_is_monotone_and_use_does_not_reduce_allocation() {
    // The account holds 100 ML entitlement. Announce 50% (balance 50), the
    // user takes 20 (balance 30, allocation still 50), then the announcement
    // is repeated at 50% — nothing should happen, because allocation has not
    // fallen. Announce 80% and the account gains the 30 ML increment only.
    let ini = r#"
[kalix]
start = 2020-01-01
end = 2020-01-04

[const]
const.day3 = 3

[acc.g1]
accounts = name, size,
           a1, 100,

[ras.announce]
targets = acc.g1
trigger = every_step
action  = allocate(if(sim.day >= const.day3, 80, 50))

[node.src]
type = inflow
loc = 0, 0
inflow = 50
ds_1 = u1

[node.u1]
type = unregulated_user
loc = 0, 10
demand = if(sim.day == 2, 20, 0)
accounts = a1

[outputs]
acc.a1.closing_balance
acc.a1.allocation
"#;
    let mut model = run(ini);

    let bal = series(&mut model, "acc.a1.closing_balance");
    let alloc = series(&mut model, "acc.a1.allocation");

    // Day 1: announce 50 -> balance 50. Day 2: take 20 -> balance 30.
    // Day 3: announce 80 -> allocation 50 rises to 80, balance 30 + 30 = 60.
    // Day 4: announce 80 again -> no change.
    assert_eq!(bal, vec![50.0, 30.0, 60.0, 60.0], "balances");
    assert_eq!(alloc, vec![50.0, 50.0, 80.0, 80.0],
        "allocation never falls: use moves water from balance into the use term");
}

#[test]
fn test_allocate_from_lookup_table() {
    // The percentage normally comes from a lookup over assessed resources.
    let ini = format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-02

[table.alloc_curve]
values = 0, 0,
         500, 40,
         1000, 100,

[acc.g1]
accounts = name, size,
           a1, 200,

[ras.announce]
targets = acc.g1
trigger = every_step
action  = allocate(table.alloc_curve(750))
{TAIL}"#);
    let model = run(&ini);
    assert_eq!(balance(&model, "a1"), 140.0, "70% interpolated from the curve, of 200");
}

#[test]
fn test_reset_allocation_starts_a_new_year() {
    // Announce 100%, use some, then reset at the water-year boundary: both
    // terms of the allocation go to zero, so the next announcement credits
    // from scratch rather than being suppressed as "not an increase".
    let ini = r#"
[kalix]
start = 2020-06-29
end = 2020-07-02

[acc.g1]
accounts = name, size,
           a1, 100,

[ras.reset]
targets = acc.g1
trigger = start_water_year(7)
action  = reset_allocation

[ras.announce]
targets = acc.g1
trigger = every_step
action  = allocate(if(sim.month == 7, 20, 100))

[node.src]
type = inflow
loc = 0, 0
inflow = 50
ds_1 = u1

[node.u1]
type = unregulated_user
loc = 0, 10
demand = if(sim.day == 30, 40, 0)
accounts = a1

[outputs]
acc.a1.closing_balance
acc.a1.allocation
"#;
    let mut model = run(ini);
    let bal = series(&mut model, "acc.a1.closing_balance");
    let alloc = series(&mut model, "acc.a1.allocation");

    // Jun 29: announce 100 -> 100. Jun 30: take 40 -> balance 60, allocation 100.
    // Jul 1: reset (file order: reset runs before announce) then announce 20 -> 20.
    // Jul 2: announce 20 again -> no change.
    assert_eq!(bal, vec![100.0, 60.0, 20.0, 20.0], "balances across the rollover");
    assert_eq!(alloc, vec![100.0, 100.0, 20.0, 20.0], "allocation restarts at the reset");
}

#[test]
fn test_allocate_above_100_percent_is_allowed() {
    // Some schemes announce more than 100% of entitlement; the balance is not
    // clamped to the account size for allocation.
    let ini = format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-02

[acc.g1]
accounts = name, size,
           a1, 100,

[ras.announce]
targets = acc.g1
trigger = every_step
action  = allocate(150)
{TAIL}"#);
    let model = run(&ini);
    assert_eq!(balance(&model, "a1"), 150.0, "150% of entitlement");
}

#[test]
fn test_allocate_recorders() {
    let ini = format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-02

[acc.g1]
accounts = name, size,
           a1, 100,

[ras.announce]
targets = acc.g1
trigger = every_step
action  = allocate(60)

[node.src]
type = inflow
loc = 0, 0
inflow = 50
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 10

[outputs]
ras.announce.pct
acc.g1.allocation
"#);
    let mut model = run(&ini);
    let pct = series(&mut model, "ras.announce.pct");
    assert_eq!(pct, vec![60.0, 60.0], "announced percentage recorded");
    let grp = series(&mut model, "acc.g1.allocation");
    assert_eq!(grp, vec![60.0, 60.0], "group allocation aggregate");
}
