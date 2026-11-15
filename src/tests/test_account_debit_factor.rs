// Tests for the `debit_factor` column of the [acc.*] accounts table (#520).
//
// The contract (website/docs/docs/accounts.md, "Debit factor"):
// 1. A user node's take of V debits `debit_factor * V` from the account.
// 2. A take is limited to what can be debited without the balance going below zero: the
//    account covers `balance / debit_factor`, and an order `(balance - committed) /
//    debit_factor`. In a list or a combination each account covers its own.
// 3. An accepted order of V commits `debit_factor * V`.
// 4. order_accounts follow the same rule at order time.
// 5. [ras.*] actions are not factored.
// 6. A factor of 1, written or not, gives the results of a model without the column. Every
//    user applies the factor, with no test for whether it is 1.
// 7. The factor must be finite and greater than 0.
// 8. `acc.<name>.debit_factor` reads as a static; a group has none.
// 9. The column is saved where any member's factor is not 1, and the node's accounts line is
//    saved as written.
use crate::io::ini_model_io::IniModelIO;
use crate::misc::configuration::SaveMethod;

fn load(ini: &str) -> crate::model::Model {
    let mut model = IniModelIO::read_model_string(ini).expect("model should load");
    model.configure().expect("model should configure");
    model
}

fn run(ini: &str) -> crate::model::Model {
    let mut model = load(ini);
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

fn load_error(ini: &str) -> String {
    match IniModelIO::read_model_string(ini) {
        Ok(_) => panic!("model should not load"),
        Err(e) => e.to_string(),
    }
}

/// inflow (50) -> unregulated user -> blackhole, two days. `table` is the rows of one
/// accounts table whose header is `name, size, initial, debit_factor`.
fn unregulated_model(demand: f64, table: &str, accounts_line: &str) -> String {
    unregulated_model_with_header("name, size, initial, debit_factor,", demand, table, accounts_line)
}

fn unregulated_model_with_header(header: &str, demand: f64, table: &str, accounts_line: &str) -> String {
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-02

[acc.pools]
accounts = {header}
           {table}

[node.src]
type = inflow
loc = 0, 0
inflow = 50
ds_1 = user

[node.user]
type = unregulated_user
loc = 0, 10
demand = {demand}
{accounts_line}
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 20

[outputs]
node.user.diversion
acc.a.debits
acc.a.use
acc.a.closing_balance
"#)
}

/// dam -> reach (lag 2) -> regulated user -> blackhole, seven days
fn regulated_model(order: f64, table: &str, extra_lines: &str) -> String {
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-07

[acc.pools]
accounts = name, size, initial, debit_factor,
           {table}

[node.dam]
type = storage
loc = 0, 0
initial_volume = 12000
dimensions = 0,  0,     0,  0,
             10, 12000, 10, 0,
             11, 12100, 10, 1000,
ds_1 = reach

[node.reach]
type = routing
loc = 0, 10
lag = 2
ds_1 = user

[node.user]
type = regulated_user
loc = 0, 20
order = {order}
{extra_lines}
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 30

[outputs]
node.user.order
node.user.diversion
acc.a.committed
acc.a.closing_balance
acc.a.debits
"#)
}

// ------------------------------------------------------------------------------------------
// Contracts 1 and 2: the debit and the limit, on an unregulated user
// ------------------------------------------------------------------------------------------

#[test]
fn a_take_debits_the_factor_times_the_take() {
    let mut m = run(&unregulated_model(20.0, "a, 1000, 100, 1.25,", "accounts = a"));
    assert_eq!(series(&mut m, "node.user.diversion"), vec![20.0, 20.0]);
    assert_eq!(series(&mut m, "acc.a.debits"), vec![25.0, 25.0]);
    assert_eq!(series(&mut m, "acc.a.use"), vec![25.0, 50.0]);
    assert_eq!(series(&mut m, "acc.a.closing_balance"), vec![75.0, 50.0]);
}

#[test]
fn a_take_is_limited_to_what_the_balance_can_be_debited_for() {
    // 20 at a factor of 1.25 covers a take of 16, and the account lands on zero
    let mut m = run(&unregulated_model(30.0, "a, 1000, 20, 1.25,", "accounts = a"));
    assert_eq!(series(&mut m, "node.user.diversion"), vec![16.0, 0.0]);
    assert_eq!(series(&mut m, "acc.a.debits"), vec![20.0, 0.0]);
    assert_eq!(series(&mut m, "acc.a.closing_balance"), vec![0.0, 0.0]);
}

#[test]
fn a_factor_below_one_covers_more_than_the_balance() {
    let mut m = run(&unregulated_model(30.0, "a, 1000, 10, 0.5,", "accounts = a"));
    assert_eq!(series(&mut m, "node.user.diversion"), vec![20.0, 0.0]);
    assert_eq!(series(&mut m, "acc.a.closing_balance"), vec![0.0, 0.0]);
}

/// A factor that is not exact in binary: the take reads as the round number, and the drained
/// account is exactly empty, never a rounding error below zero.
#[test]
fn a_drained_account_lands_on_zero() {
    for factor in ["1.2", "1.05", "1.1", "0.7", "3"] {
        let mut m = run(&unregulated_model(40.0, &format!("a, 1000, 12, {factor},"), "accounts = a"));
        assert_eq!(balance(&m, "a"), 0.0, "factor {factor}");
        assert_eq!(series(&mut m, "acc.a.debits"), vec![12.0, 0.0], "factor {factor}");
    }
    let mut m = run(&unregulated_model(200.0, "a, 1000, 48, 1.2,", "accounts = a"));
    assert_eq!(series(&mut m, "node.user.diversion"), vec![40.0, 0.0], "48 at 1.2 covers 40");
}

#[test]
fn each_account_in_a_list_covers_its_own() {
    // a covers 10 / 2 = 5, b covers 5: 10 in all, a first
    let table = "a, 1000, 10, 2,\n           b, 1000, 5, 1,";
    let mut m = run(&unregulated_model(8.0, table, "accounts = a, b"));
    assert_eq!(series(&mut m, "node.user.diversion"), vec![8.0, 2.0]);
    assert_eq!(series(&mut m, "acc.a.debits"), vec![10.0, 0.0], "a's 5 of take is debited 10");
    assert_eq!(balance(&m, "a"), 0.0);
    assert_eq!(balance(&m, "b"), 0.0, "3 on day 1 and 2 on day 2");
}

#[test]
fn a_factor_on_a_later_account_alone_is_applied() {
    let table = "a, 1000, 4, 1,\n           b, 1000, 20, 2,";
    let mut m = run(&unregulated_model(10.0, table, "accounts = a, b"));
    assert_eq!(series(&mut m, "node.user.diversion"), vec![10.0, 4.0], "4 from a and 6 from b, then b's last 4");
    assert_eq!(balance(&m, "a"), 0.0);
    assert_eq!(balance(&m, "b"), 0.0);
}

#[test]
fn a_combination_takes_the_least_cover_and_debits_each_side_by_its_factor() {
    // a covers 20 / 1.25 = 16, cap covers 8: the take is 8, debited 10 from a and 8 from cap
    let table = "a, 1000, 20, 1.25,\n           cap, 1000, 8, 1,";
    let mut m = run(&unregulated_model(30.0, table, "accounts = a & cap"));
    assert_eq!(series(&mut m, "node.user.diversion"), vec![8.0, 0.0]);
    assert_eq!(balance(&m, "a"), 10.0);
    assert_eq!(balance(&m, "cap"), 0.0);
}

#[test]
fn a_nested_combination_is_factored_leaf_by_leaf() {
    // (a | b) covers 4 / 2 + 6 = 8; cap covers 20 / 2 = 10: the take is 8
    let table = "a, 1000, 4, 2,\n           b, 1000, 6, 1,\n           cap, 1000, 20, 2,";
    let mut m = run(&unregulated_model(30.0, table, "accounts = (a | b) & cap"));
    assert_eq!(series(&mut m, "node.user.diversion"), vec![8.0, 0.0]);
    assert_eq!(balance(&m, "a"), 0.0);
    assert_eq!(balance(&m, "b"), 0.0);
    assert_eq!(balance(&m, "cap"), 4.0, "debited 16 for a take of 8");
}

// ------------------------------------------------------------------------------------------
// Contract 6: a factor of 1
// ------------------------------------------------------------------------------------------

#[test]
fn a_factor_of_one_is_no_factor() {
    for accounts_line in ["accounts = a, b", "accounts = (a | b) & cap"] {
        let with = "a, 1000, 7.3, 1,\n           b, 1000, 5.1, 1.0,\n           cap, 1000, 11.7, 1,";
        let without = "a, 1000, 7.3,\n           b, 1000, 5.1,\n           cap, 1000, 11.7,";
        let mut m_with = run(&unregulated_model(9.9, with, accounts_line));
        let mut m_without = run(&unregulated_model_with_header("name, size, initial,", 9.9, without, accounts_line));
        for name in ["node.user.diversion", "acc.a.debits", "acc.a.closing_balance"] {
            assert_eq!(series(&mut m_with, name), series(&mut m_without, name), "{name} on '{accounts_line}'");
        }
        for account in ["a", "b", "cap"] {
            assert_eq!(balance(&m_with, account).to_bits(), balance(&m_without, account).to_bits());
        }
    }
}

// ------------------------------------------------------------------------------------------
// Contracts 2 and 3 on a regulated user: the order cap and the commitment
// ------------------------------------------------------------------------------------------

#[test]
fn an_order_is_capped_and_committed_in_the_accounts_units() {
    // 100 at a factor of 1.25 covers an order of 80, which commits the whole 100
    let mut m = run(&regulated_model(100.0, "a, 1000, 100, 1.25,", "accounts = a"));
    assert_eq!(series(&mut m, "node.user.order"), vec![80.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "acc.a.committed"), vec![0.0, 100.0, 100.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "node.user.diversion"), vec![0.0, 0.0, 80.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "acc.a.debits"), vec![0.0, 0.0, 100.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "acc.a.closing_balance"), vec![100.0, 100.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
}

#[test]
fn an_order_within_the_cover_leaves_the_rest_to_order() {
    // 30 a day commits 37.5 a day: 100 covers two orders and 20 of a third. Each leaves the
    // forecast on the step after it falls due, two steps after it was placed.
    let mut m = run(&regulated_model(30.0, "a, 1000, 100, 1.25,", "accounts = a"));
    assert_eq!(series(&mut m, "node.user.order"), vec![30.0, 30.0, 20.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "acc.a.committed"), vec![0.0, 37.5, 75.0, 62.5, 25.0, 0.0, 0.0]);
    assert_eq!(balance(&m, "a"), 0.0);
}

#[test]
fn a_regulated_combination_is_capped_committed_and_debited_by_factor() {
    // a covers 100 / 1.25 = 80, cap covers 60 / 0.5 = 120: the order is 80
    let table = "a, 1000, 100, 1.25,\n           cap, 1000, 60, 0.5,";
    let mut m = run(&regulated_model(100.0, table, "accounts = a & cap"));
    assert_eq!(series(&mut m, "node.user.order"), vec![80.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "acc.a.committed"), vec![0.0, 100.0, 100.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(balance(&m, "a"), 0.0);
    assert_eq!(balance(&m, "cap"), 20.0, "debited 40 for a take of 80");
}

// ------------------------------------------------------------------------------------------
// Contract 4: order accounts
// ------------------------------------------------------------------------------------------

#[test]
fn order_accounts_are_debited_by_factor_at_order_time() {
    // No accounts, so the bridge authorises the whole order: 20 at a factor of 1.25 covers 16
    let table = "a, 1000, 0, 1,\n           bridge, 1000, 20, 1.25,";
    let mut m = run(&regulated_model(30.0, table, "order_accounts = bridge"));
    assert_eq!(series(&mut m, "node.user.order"), vec![16.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(balance(&m, "bridge"), 0.0);
    assert_eq!(series(&mut m, "node.user.diversion"), vec![0.0, 0.0, 16.0, 0.0, 0.0, 0.0, 0.0],
               "with no accounts the delivery is not capped by one");
}

#[test]
fn a_factored_order_account_extends_an_unfactored_account() {
    // a covers 10; the bridge covers 20 / 2 = 10 more, debited 2 for 1
    let table = "a, 1000, 10, 1,\n           bridge, 1000, 20, 2,";
    let mut m = run(&regulated_model(16.0, table, "accounts = a\norder_accounts = bridge"));
    // Day 1: a covers 10 and the bridge pays 12 for the 6 beyond it. Day 2: a is spoken for,
    // and the bridge's last 8 authorises 4.
    assert_eq!(series(&mut m, "node.user.order"), vec![16.0, 4.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "acc.a.committed")[1], 16.0, "the part beyond a's cover goes on a, the first account");
    assert_eq!(balance(&m, "bridge"), 0.0);
}

// ------------------------------------------------------------------------------------------
// Contract 5: [ras.*] actions are not factored
// ------------------------------------------------------------------------------------------

#[test]
fn ras_actions_work_in_the_balances_units() {
    let ini = unregulated_model(0.0, "a, 1000, 100, 2,", "accounts = a")
        + "\n[ras.loss]\ntargets = acc.pools\ntrigger = every_step\naction = debit(5)\n";
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "acc.a.closing_balance"), vec![95.0, 90.0]);
    assert_eq!(series(&mut m, "acc.a.debits"), vec![0.0, 0.0]);
}

// ------------------------------------------------------------------------------------------
// Contract 7: validation
// ------------------------------------------------------------------------------------------

#[test]
fn the_factor_must_be_finite_and_greater_than_zero() {
    for bad in ["0", "-1.2", "nan", "inf"] {
        let e = load_error(&unregulated_model(1.0, &format!("a, 1000, 100, {bad},"), "accounts = a"));
        assert!(e.contains("Account 'a' has debit factor") && e.contains("finite number greater than 0"), "{bad}: {e}");
    }
    let e = load_error(&unregulated_model(1.0, "a, 1000, 100, lots,", "accounts = a"));
    assert!(e.contains("Invalid debit factor 'lots' for account 'a'"), "{e}");
}

// ------------------------------------------------------------------------------------------
// Contract 8: the static
// ------------------------------------------------------------------------------------------

#[test]
fn the_factor_reads_as_a_static() {
    let table = "a, 1000, 100, 1.25,\n           b, 1000, 100, 1,";
    let ini = unregulated_model(0.0, table, "accounts = a")
        + "acc.a.debit_factor\nacc.b.debit_factor\nvar.calc.at_the_pump\n\n[var.calc]\nat_the_pump = acc.a.opening_balance / acc.a.debit_factor\n";
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "acc.a.debit_factor"), vec![1.25, 1.25]);
    assert_eq!(series(&mut m, "acc.b.debit_factor"), vec![1.0, 1.0]);
    assert_eq!(series(&mut m, "var.calc.at_the_pump"), vec![80.0, 80.0]);
}

#[test]
fn an_account_without_the_column_has_a_factor_of_one() {
    let ini = unregulated_model_with_header("name, size, initial,", 0.0, "a, 1000, 100,", "accounts = a")
        + "acc.a.debit_factor\n";
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "acc.a.debit_factor"), vec![1.0, 1.0]);
}

#[test]
fn a_group_has_no_debit_factor() {
    let ini = unregulated_model(0.0, "a, 1000, 100, 1.25,", "accounts = a")
        + "var.calc.x\n\n[var.calc]\nx = acc.pools.debit_factor\n";
    let e = load_error(&ini);
    assert!(e.contains("Unknown field 'debit_factor'") && e.contains("an account group"), "{e}");
}

// ------------------------------------------------------------------------------------------
// Contract 9: saving, and running again
// ------------------------------------------------------------------------------------------

fn saved(ini: &str, after_a_run: bool) -> String {
    let mut model = IniModelIO::read_model_string(ini).expect("model should load");
    model.configuration.save_method = SaveMethod::Canonical;
    if after_a_run {
        model.configure().expect("model should configure");
        model.run().expect("simulation should run");
    }
    IniModelIO::model_to_string(&model)
}

#[test]
fn the_column_is_saved_where_a_factor_is_not_one() {
    let table = "a, 1000, 100, 1.25,\n           b, 1000, 50, 1,";
    let text = saved(&unregulated_model(1.0, table, "accounts = a, b"), false);
    assert!(text.contains("name, size, initial, debit_factor,"), "{text}");
    assert!(text.contains("a, 1000, 100, 1.25,"), "{text}");
    assert!(text.contains("b, 1000, 50, 1,"), "{text}");

    let all_ones = saved(&unregulated_model(1.0, "a, 1000, 100, 1,", "accounts = a"), false);
    assert!(!all_ones.contains("debit_factor"), "a column of ones is not written:\n{all_ones}");
}

#[test]
fn the_accounts_line_is_saved_as_a_list_after_a_run() {
    let table = "a, 1000, 100, 1.25,\n           b, 1000, 50, 1,";
    let text = saved(&unregulated_model(1.0, table, "accounts = a, b"), true);
    assert!(text.lines().any(|l| l.trim() == "accounts = a, b"), "{text}");
}

#[test]
fn a_saved_model_reloads_to_the_same_result() {
    let table = "a, 1000, 20, 1.25,\n           b, 1000, 50, 2,";
    let ini = unregulated_model(30.0, table, "accounts = a, b");
    let mut first = run(&ini);
    let mut second = run(&saved(&ini, false));
    assert_eq!(series(&mut first, "node.user.diversion"), series(&mut second, "node.user.diversion"));
    assert_eq!(balance(&first, "b").to_bits(), balance(&second, "b").to_bits());
}

#[test]
fn a_second_run_of_the_same_model_gives_the_same_result() {
    let mut m = load(&regulated_model(30.0, "a, 1000, 100, 1.25,", "accounts = a"));
    m.run().expect("first run");
    let first = series(&mut m, "node.user.order");
    m.run().expect("second run");
    assert_eq!(series(&mut m, "node.user.order"), first);
    assert_eq!(first, vec![30.0, 30.0, 20.0, 0.0, 0.0, 0.0, 0.0]);
}
