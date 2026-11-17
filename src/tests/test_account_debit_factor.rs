// Tests for the `debit_factor` column of the [acc.*] accounts table (#520).
//
// The contract (website/docs/docs/accounts.md, "Debit factor"):
// 1. A user node's take of V debits `debit_factor * V` from the account.
// 2. A take is limited to what can be debited without the balance going below zero: the
//    take available from an account is `balance / debit_factor`, and the order available
//    `(balance - committed) / debit_factor`. In a list or a combination each account has
//    its own.
// 3. An accepted order of V commits `debit_factor * V`.
// 4. order_accounts follow the same rule at order time.
// 5. [ras.*] actions are not factored.
// 6. A factor of 1, written or not, gives the results of a model without the column. Every
//    user applies the factor, with no test for whether it is 1.
// 7. The factor must be finite, greater than 0 and not subnormal.
// 8. `acc.<name>.debit_factor` reads as a static; a group has none.
// 9. The column is saved where any member's factor is not 1, at full precision, and a node's
//    plain accounts list is saved as a list.
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

/// Factors that are not exact in binary. A single account that is drained is exactly empty,
/// and its debits are exactly its balance. The last case also checks the take: 48 at 1.2
/// covers 40.
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

/// The delivery is limited by the balance of the day, at the factor. A rule takes 20 from
/// the account each step, so the 40 ordered on day 1 arrives on day 3 to a balance of 40,
/// which covers a take of 32.
#[test]
fn a_delivery_is_limited_by_the_take_available_on_the_day() {
    let ini = regulated_model(40.0, "a, 1000, 100, 1.25,", "accounts = a")
        + "\n[ras.cut]\ntargets = acc.pools\ntrigger = every_step\naction = debit(20)\n";
    let mut m = run(&ini);
    // Day 1: 80 covers 64, and the order of 40 commits 50. Day 2: 60 less 50 covers 8.
    assert_eq!(series(&mut m, "node.user.order")[..3], [40.0, 8.0, 0.0]);
    assert_eq!(series(&mut m, "node.user.diversion")[..3], [0.0, 0.0, 32.0]);
    assert_eq!(series(&mut m, "acc.a.debits")[2], 40.0);
    assert_eq!(series(&mut m, "acc.a.closing_balance")[2], 0.0);
}

/// An order of all an account covers, at factors that are not exact in binary. (b / f) * f
/// is above b for 100 at 1.2, which would overdraw, and below it for 12 at 0.7 and 7.3 at 3,
/// which would leave dust to order the next day. The order commits the whole balance all
/// the same, so nothing is left over, and the delivery debits the whole balance.
#[test]
fn a_full_order_at_an_inexact_factor_leaves_nothing_to_order() {
    for (b, f) in [(100.0, "1.2"), (12.0, "0.7"), (7.3, "3")] {
        let mut m = run(&regulated_model(100.0, &format!("a, 1000, {b}, {f},"), "accounts = a"));
        assert_eq!(series(&mut m, "node.user.order")[1..], [0.0, 0.0, 0.0, 0.0, 0.0, 0.0], "{b} at {f}");
        assert_eq!(series(&mut m, "acc.a.committed"), vec![0.0, b, b, 0.0, 0.0, 0.0, 0.0], "{b} at {f}");
        assert_eq!(series(&mut m, "acc.a.debits"), vec![0.0, 0.0, b, 0.0, 0.0, 0.0, 0.0], "{b} at {f}");
        assert_eq!(balance(&m, "a"), 0.0, "{b} at {f}");
    }
}

/// With river flow and no order, the opportunistic take is the only take, and 100 at a factor of
/// 2 covers 50 of it.
#[test]
fn an_opportunistic_take_is_limited_by_the_factor() {
    let ini = r#"
[kalix]
start = 2020-01-01
end = 2020-01-03

[acc.pools]
accounts = name, size, initial, debit_factor,
           a,    1000, 100,     2,

[node.src]
type = inflow
loc = 0, 0
inflow = 500
ds_1 = user

[node.user]
type = regulated_user
loc = 0, 10
order = 0
opportunistic_demand = 80
accounts = a
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 20

[outputs]
node.user.diversion_opportunistic
acc.a.debits
"#;
    let mut m = run(ini);
    assert_eq!(series(&mut m, "node.user.diversion_opportunistic"), vec![50.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "acc.a.debits"), vec![100.0, 0.0, 0.0]);
    assert_eq!(balance(&m, "a"), 0.0);
}

/// Two users share one account, where 100 at a factor of 2 covers 50 between them. Each sees the
/// other's order committed, so the two orders come to 50 whichever orders first, and the
/// deliveries debit the whole 100.
#[test]
fn two_users_on_one_account_share_what_it_covers() {
    let ini = r#"
[kalix]
start = 2020-01-01
end = 2020-01-03

[acc.pools]
accounts = name, size, initial, debit_factor,
           a,    1000, 100,     2,

[node.dam]
type = storage
loc = 0, 0
initial_volume = 12000
dimensions = 0,  0,     0,  0,
             10, 12000, 10, 0,
             11, 12100, 10, 1000,
ds_1 = u1

[node.u1]
type = regulated_user
loc = 0, 10
order = 30
accounts = a
ds_1 = u2

[node.u2]
type = regulated_user
loc = 0, 20
order = 30
accounts = a
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 30

[outputs]
node.u1.order
node.u2.order
node.u1.diversion
node.u2.diversion
acc.a.debits
"#;
    let mut m = run(ini);
    let (o1, o2) = (series(&mut m, "node.u1.order"), series(&mut m, "node.u2.order"));
    let mut first_day = [o1[0], o2[0]];
    first_day.sort_by(f64::total_cmp);
    assert_eq!(first_day, [20.0, 30.0], "30 for the first to order, the 20 left for the other");
    assert_eq!(series(&mut m, "node.u1.diversion")[0] + series(&mut m, "node.u2.diversion")[0], 50.0);
    assert_eq!(series(&mut m, "acc.a.debits"), vec![100.0, 0.0, 0.0]);
    assert_eq!(o1[1..], [0.0, 0.0]);
    assert_eq!(o2[1..], [0.0, 0.0]);
    assert_eq!(balance(&m, "a"), 0.0);
}

// ------------------------------------------------------------------------------------------
// Contract 4: order accounts
// ------------------------------------------------------------------------------------------

/// An order beyond what the accounts cover, authorised by an order account, is committed to
/// the first account at that account's factor. a covers 10 / 2 = 5 of the 16 and commits its
/// 10; the other 11 commits 22 more.
#[test]
fn an_order_beyond_the_accounts_is_committed_at_the_first_accounts_factor() {
    let table = "a, 1000, 10, 2,\n           bridge, 1000, 100, 1,";
    let mut m = run(&regulated_model(16.0, table, "accounts = a\norder_accounts = bridge"));
    assert_eq!(series(&mut m, "node.user.order")[0], 16.0);
    assert_eq!(series(&mut m, "acc.a.committed")[1], 32.0);
}

/// Under `&` every side is committed the whole order, at its own factor.
#[test]
fn each_side_of_a_combination_is_committed_the_whole_order_at_its_factor() {
    let table = "a, 1000, 10, 2,\n           cap, 1000, 500, 1,\n           bridge, 1000, 100, 1,";
    let ini = regulated_model(16.0, table, "accounts = a & cap\norder_accounts = bridge") + "acc.cap.committed\n";
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "node.user.order")[0], 16.0);
    assert_eq!(series(&mut m, "acc.a.committed")[1], 32.0);
    assert_eq!(series(&mut m, "acc.cap.committed")[1], 16.0);
}

/// `(a | b) & cap` with a factor on every account. Account a is first in the table and the
/// others are not. a covers 10 / 2 = 5 and b 10 / 1.25 = 8, so `a | b` covers 13 of the 16
/// and the bridge authorises the other 3. Through `|` each account commits its whole balance,
/// the 3 beyond goes on a at its factor, 6 more, and cap is committed the whole 16 at 0.5.
#[test]
fn a_nested_or_is_committed_account_by_account_at_each_factor() {
    let table = "a, 1000, 10, 2,\n           b, 1000, 10, 1.25,\n           cap, 1000, 500, 0.5,\n           bridge, 1000, 100, 1,";
    let ini = regulated_model(16.0, table, "accounts = (a | b) & cap\norder_accounts = bridge")
        + "acc.b.committed\nacc.cap.committed\nacc.bridge.closing_balance\n";
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "node.user.order")[0], 16.0);
    assert_eq!(series(&mut m, "acc.a.committed")[1], 16.0, "its 10, and 6 for the 3 beyond");
    assert_eq!(series(&mut m, "acc.b.committed")[1], 10.0);
    assert_eq!(series(&mut m, "acc.cap.committed")[1], 8.0);
    assert_eq!(series(&mut m, "acc.bridge.closing_balance")[0], 97.0);
}

/// In a plain list, a covers 10 / 2 = 5 and b 10 / 1.25 = 8, so the bridge authorises 3 of
/// the 16. The part beyond goes on a, the first account, at its factor, which is 6 more.
#[test]
fn an_order_beyond_a_list_is_committed_to_its_first_account() {
    let table = "a, 1000, 10, 2,\n           b, 1000, 10, 1.25,\n           bridge, 1000, 100, 1,";
    let ini = regulated_model(16.0, table, "accounts = a, b\norder_accounts = bridge") + "acc.b.committed\n";
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "node.user.order")[0], 16.0);
    assert_eq!(series(&mut m, "acc.a.committed")[1], 16.0, "its 10, and 6 for the 3 beyond");
    assert_eq!(series(&mut m, "acc.b.committed")[1], 10.0);
}

/// A rule cuts a below what is committed against it. Account a holds 20 at factor 2 and
/// loses 8 a step. Day 1 leaves 12, and an order of 5 commits 10. Day 2 leaves 4 against the
/// 10, so a covers nothing and b, in another group, takes the whole order of 5.
#[test]
fn an_account_cut_below_its_commitment_commits_nothing_more() {
    let ini = regulated_model(5.0, "a, 1000, 20, 2,", "accounts = a, b")
        + "acc.b.committed\n\n[acc.others]\naccounts = name, size, initial,\n           b, 1000, 100,\n"
        + "\n[ras.cut]\ntargets = acc.pools\ntrigger = every_step\naction = debit(8)\n";
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "node.user.order")[..2], [5.0, 5.0]);
    assert_eq!(series(&mut m, "acc.a.committed")[..3], [0.0, 10.0, 10.0]);
    assert_eq!(series(&mut m, "acc.b.committed")[..3], [0.0, 0.0, 5.0]);
}

/// In `b | (a & cap)`, b covers 10 / 2 = 5 and `a & cap` 6, so the bridge authorises 5 of the
/// 16. The part beyond goes on b, the first account of the outermost `|`, at its factor.
#[test]
fn an_order_beyond_a_root_or_is_committed_at_its_first_accounts_factor() {
    let table = "a, 1000, 100, 1,\n           b, 1000, 10, 2,\n           cap, 1000, 6, 1,\n           bridge, 1000, 100, 1,";
    let ini = regulated_model(16.0, table, "accounts = b | (a & cap)\norder_accounts = bridge")
        + "acc.b.committed\nacc.cap.committed\n";
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "node.user.order")[0], 16.0);
    assert_eq!(series(&mut m, "acc.b.committed")[1], 20.0, "its 10, and 10 for the 5 beyond");
    assert_eq!(series(&mut m, "acc.a.committed")[1], 6.0);
    assert_eq!(series(&mut m, "acc.cap.committed")[1], 6.0);
}

/// The order cap, the commit and the debit divide the balance by the factor, not multiply it
/// by its reciprocal. 45.15 / 1.05 is 43, and 45.15 * (1 / 1.05) is 42.99999999999999. An
/// order or take of 43 is then all a covers, and b, after it in the list, is not touched.
/// The take cap is pinned by `a_drained_account_lands_on_zero`.
#[test]
fn the_cover_is_the_balance_divided_by_the_factor() {
    let mut m = run(&regulated_model(100.0, "a, 1000, 45.15, 1.05,", "accounts = a"));
    assert_eq!(series(&mut m, "node.user.order")[0], 43.0, "the order cap");

    let table = "a, 1000, 45.15, 1.05,\n           b, 1000, 10, 1,";
    let mut m = run(&(regulated_model(43.0, table, "accounts = a, b") + "acc.b.committed\n"));
    assert_eq!(series(&mut m, "acc.a.committed")[1], 45.15, "the commit");
    assert_eq!(series(&mut m, "acc.b.committed")[1], 0.0, "the commit");

    let mut m = run(&(unregulated_model(43.0, table, "accounts = a, b") + "acc.b.closing_balance\n"));
    assert_eq!(series(&mut m, "acc.a.closing_balance")[0], 0.0, "the take");
    assert_eq!(series(&mut m, "acc.b.closing_balance")[0], 10.0, "the take");
}

#[test]
fn order_accounts_are_debited_by_factor_at_order_time() {
    // No accounts, so the bridge authorises the whole order: 20 at a factor of 1.25 covers 16
    let table = "a, 1000, 0, 1,\n           bridge, 1000, 20, 1.25,";
    let ini = regulated_model(30.0, table, "order_accounts = bridge") + "acc.bridge.closing_balance\n";
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "node.user.order"), vec![16.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0]);
    assert_eq!(series(&mut m, "acc.bridge.closing_balance")[0], 0.0,
               "debited on the day of the order, two days before the delivery");
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

/// The other actions are in the balance's units too. Account a, at a factor of 2, holds 100.
/// distribute(-40, by_balance) weights a and b (a factor of 1) by their balances of 100 each,
/// and draws 20 from each.
#[test]
fn every_ras_action_works_in_the_balances_units() {
    let table = "a, 1000, 100, 2,\n           b, 1000, 100, 1,";
    for (action, a, b) in [
        ("set(10)", 10.0, 10.0),
        ("credit(10)", 110.0, 110.0),
        ("credit_fraction(0.01)", 110.0, 110.0),
        ("scale(0.5)", 50.0, 50.0),
        ("distribute(-40, by_balance)", 80.0, 80.0),
    ] {
        let ini = unregulated_model(0.0, table, "accounts = a")
            + "acc.b.closing_balance\n"
            + &format!("\n[ras.act]\ntargets = acc.pools\ntrigger = every_step\naction = {action}\n");
        let mut m = run(&ini);
        assert_eq!(series(&mut m, "acc.a.closing_balance")[0], a, "{action} on a");
        assert_eq!(series(&mut m, "acc.b.closing_balance")[0], b, "{action} on b");
    }
}

/// roll_cap credits back what the takes debited. A take of 20 at a factor of 2 debits 40, and
/// roll_cap(1) at the top of the next step credits the 40 back. The size is well above the
/// balance, so a credit of more than 40 is not hidden by the size limit.
#[test]
fn roll_cap_credits_back_what_the_take_debited() {
    let ini = unregulated_model(20.0, "a, 1000, 100, 2,", "accounts = a")
        + "\n[ras.cap]\ntargets = acc.pools\ntrigger = every_step\naction = roll_cap(1)\n";
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "acc.a.closing_balance"), vec![60.0, 60.0]);
}

/// allocate(50) announces 500 each step, and announcing it again adds nothing. A take moves
/// what it debits from the balance to use, and leaves the allocation as it was.
#[test]
fn allocate_counts_what_the_takes_debited_as_used() {
    let ini = unregulated_model(40.0, "a, 1000, 0, 2,", "accounts = a") + "acc.a.allocation\n"
        + "\n[ras.ann]\ntargets = acc.pools\ntrigger = every_step\naction = allocate(50)\n";
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "node.user.diversion"), vec![40.0, 40.0]);
    assert_eq!(series(&mut m, "acc.a.closing_balance"), vec![420.0, 340.0]);
    assert_eq!(series(&mut m, "acc.a.use"), vec![80.0, 160.0]);
    assert_eq!(series(&mut m, "acc.a.allocation"), vec![500.0, 500.0]);
}

/// reset_allocation then allocate(4) each step gives 40 a step, which covers a take of 20 at a
/// factor of 2.
#[test]
fn reset_allocation_then_allocate_refills_in_the_balances_units() {
    let ini = unregulated_model(30.0, "a, 1000, 0, 2,", "accounts = a") + "acc.a.allocation\n"
        + "\n[ras.reset]\ntargets = acc.pools\ntrigger = every_step\naction = reset_allocation\n"
        + "\n[ras.ann]\ntargets = acc.pools\ntrigger = every_step\naction = allocate(4)\n";
    let mut m = run(&ini);
    assert_eq!(series(&mut m, "node.user.diversion"), vec![20.0, 20.0]);
    assert_eq!(series(&mut m, "acc.a.use"), vec![40.0, 40.0], "use restarts each step");
    assert_eq!(series(&mut m, "acc.a.allocation"), vec![40.0, 40.0]);
}

// ------------------------------------------------------------------------------------------
// Contract 7: validation
// ------------------------------------------------------------------------------------------

#[test]
fn the_factor_must_be_finite_normal_and_greater_than_zero() {
    for bad in ["0", "-1.2", "nan", "inf", "1e-320"] {
        let e = load_error(&unregulated_model(1.0, &format!("a, 1000, 100, {bad},"), "accounts = a"));
        assert!(e.contains("Account 'a' has debit factor") && e.contains("finite number greater than 0"), "{bad}: {e}");
    }
    let e = load_error(&unregulated_model(1.0, "a, 1000, 100, lots,", "accounts = a"));
    assert!(e.contains("Invalid debit factor 'lots' for account 'a'"), "{e}");
}

/// The smallest normal f64 is a valid factor. The bound is where subnormals begin, and no higher.
#[test]
fn the_smallest_normal_factor_loads() {
    let mut m = run(&unregulated_model(1.0, "a, 1000, 100, 2.2250738585072014e-308,", "accounts = a"));
    assert_eq!(series(&mut m, "node.user.diversion")[0], 1.0);
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

/// The column is written when any member's factor is not 1, here one below 1 on a later row.
#[test]
fn the_column_is_saved_for_a_factor_below_one_on_a_later_row() {
    let table = "a, 1000, 100, 1,\n           b, 1000, 50, 0.5,";
    let text = saved(&unregulated_model(1.0, table, "accounts = a, b"), false);
    assert!(text.contains("name, size, initial, debit_factor,"), "{text}");
    assert!(text.contains("b, 1000, 50, 0.5,"), "{text}");
}

/// A table with both a `pair` and a `debit_factor` column saves and reloads to the same run.
#[test]
fn a_table_with_a_pair_and_a_factor_round_trips() {
    let ini = unregulated_model_with_header("name, size, initial, pair, debit_factor,", 30.0, "a, 1000, 100, p, 2,", "accounts = a")
        + "\n[acc.pools2]\naccounts = name, size, initial,\n           p, 1000, 0,\n";
    let text = saved(&ini, false);
    assert!(text.contains("name, size, initial, pair, debit_factor,"), "{text}");
    assert!(text.contains("a, 1000, 100, p, 2,"), "{text}");
    let mut first = run(&ini);
    let mut second = run(&text);
    assert_eq!(series(&mut first, "acc.a.debits"), vec![60.0, 40.0]);
    assert_eq!(series(&mut second, "acc.a.debits"), vec![60.0, 40.0]);
}

/// A factor with more digits than the other columns are saved with: 1 / 0.95.
#[test]
fn a_long_factor_is_saved_exactly() {
    let ini = unregulated_model(30.0, "a, 1000, 100, 1.0526315789473684,", "accounts = a");
    let text = saved(&ini, false);
    assert!(text.contains("a, 1000, 100, 1.0526315789473684,"), "{text}");
    let mut first = run(&ini);
    let mut second = run(&text);
    assert_eq!(series(&mut first, "acc.a.debits"), series(&mut second, "acc.a.debits"));
    assert_eq!(balance(&first, "a").to_bits(), balance(&second, "a").to_bits());
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
