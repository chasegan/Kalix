// Tests for account combinations on user nodes (#507): `accounts = (a | b) & cap`.
//
// The contract (website/docs/docs/accounts.md, "Combining accounts"):
// 1. `a | b` draws on a first, then b: available is the sum, the debit fills left to right.
//    Without `&` it is the plain list, and is written back as one.
// 2. `a & b` debits the whole take from each side: available is the minimum.
// 3. `|` and `&` mix only with parentheses; a comma, `&&`, `||`, an unknown account and an
//    account used twice are each a load error that names the node.
// 4. A regulated user's order cap, commit and debit all follow the same tree; the order
//    beyond what the tree can cover (authorised by order_accounts) is committed to the first
//    account of each side that fell short.
// 5. The canonical render has single spaces and parentheses only where the operator changes.
use crate::hydrology::accounts::account_tree::{parse_combination, AccountsProperty};
use crate::io::ini_model_io::IniModelIO;
use crate::misc::configuration::SaveMethod;

fn run(ini: &str) -> crate::model::Model {
    let mut model = IniModelIO::read_model_string(ini).expect("model should load");
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

fn saved_accounts_line(ini: &str) -> String {
    saved_accounts_line_by(ini, SaveMethod::Canonical)
}

fn saved_accounts_line_by(ini: &str, save_method: SaveMethod) -> String {
    let mut model = IniModelIO::read_model_string(ini).expect("model should load");
    model.configuration.save_method = save_method;
    let text = IniModelIO::model_to_string(&model);
    text.lines()
        .find(|l| l.trim_start().starts_with("accounts =") && !l.contains("name"))
        .unwrap_or_else(|| panic!("no accounts line in saved model:\n{}", text))
        .trim()
        .to_string()
}

const ACCOUNTS: &str = r#"
[acc.pools]
accounts = name, size, initial,
           a,    1000, 10,
           b,    1000, 5,
           cap,  1000, 12,
           big,  1000, 100,
"#;

/// inflow (50) -> unregulated user -> blackhole, two days
fn unregulated_model(demand: f64, accounts_line: &str) -> String {
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-02
{ACCOUNTS}
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
"#)
}

/// inflow (50) -> storage -> regulated user (lag 0) -> blackhole, two days
fn regulated_model(order: f64, extra_lines: &str) -> String {
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-02
{ACCOUNTS}
[node.src]
type = inflow
loc = 0, 0
inflow = 50
ds_1 = dam

[node.dam]
type = storage
loc = 0, 10
initial_volume = 500
dimensions = Level [m], Volume [ML], Area [km2], Spill [ML],
             0        , 0          , 0         , 0,
             10       , 10000      , 0         , 0,
             10.1     , 10100      , 0         , 1e9,
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
"#)
}

// ------------------------------------------------------------------------------------------
// Contract 1: `|`
// ------------------------------------------------------------------------------------------

#[test]
fn pipe_only_is_the_plain_list() {
    let mut as_list = run(&unregulated_model(20.0, "accounts = a, b"));
    let mut as_pipe = run(&unregulated_model(20.0, "accounts = a | b"));
    assert_eq!(series(&mut as_pipe, "node.user.diversion"), series(&mut as_list, "node.user.diversion"));
    assert_eq!(series(&mut as_pipe, "node.user.diversion"), vec![15.0, 0.0], "a then b, then nothing");
    assert_eq!(balance(&as_pipe, "a"), 0.0);
    assert_eq!(balance(&as_pipe, "b"), 0.0);
    assert_eq!(saved_accounts_line(&unregulated_model(20.0, "accounts = a | b")), "accounts = a, b",
               "a combination without & is the plain list");
}

#[test]
fn pipe_fills_left_to_right_inside_and() {
    // `big` never binds, so the walk through (a | b) must match the plain list exactly
    let mut as_list = run(&unregulated_model(12.0, "accounts = a, b"));
    let mut as_tree = run(&unregulated_model(12.0, "accounts = (a | b) & big"));
    assert_eq!(series(&mut as_tree, "node.user.diversion"), series(&mut as_list, "node.user.diversion"));
    assert_eq!(series(&mut as_tree, "node.user.diversion"), vec![12.0, 3.0]);
    assert_eq!(balance(&as_tree, "a"), 0.0, "a drained first");
    assert_eq!(balance(&as_tree, "b"), 0.0, "then b");
    assert_eq!(balance(&as_tree, "big"), 85.0, "every megalitre debited from big as well");
}

// ------------------------------------------------------------------------------------------
// Contract 2: `&`
// ------------------------------------------------------------------------------------------

#[test]
fn and_takes_the_minimum_and_debits_each_side_in_full() {
    let mut model = run(&unregulated_model(20.0, "accounts = big & cap"));
    assert_eq!(series(&mut model, "node.user.diversion"), vec![12.0, 0.0], "limited by cap, the shorter side");
    assert_eq!(balance(&model, "big"), 88.0, "the whole take debited from big");
    assert_eq!(balance(&model, "cap"), 0.0, "and from cap");
}

#[test]
fn nested_pipe_inside_and_on_an_unregulated_user() {
    let mut model = run(&unregulated_model(20.0, "accounts = (a | b) & cap"));
    assert_eq!(series(&mut model, "node.user.diversion"), vec![12.0, 0.0], "min(10 + 5, 12)");
    assert_eq!(balance(&model, "a"), 0.0, "a drained first");
    assert_eq!(balance(&model, "b"), 3.0, "b pays the rest of the 12");
    assert_eq!(balance(&model, "cap"), 0.0, "cap pays all 12");
}

#[test]
fn and_inside_pipe() {
    // a first; then (b & cap), limited by b: 10 + min(5, 12) = 15
    let mut model = run(&unregulated_model(20.0, "accounts = a | (b & cap)"));
    assert_eq!(series(&mut model, "node.user.diversion"), vec![15.0, 0.0]);
    assert_eq!(balance(&model, "a"), 0.0);
    assert_eq!(balance(&model, "b"), 0.0);
    assert_eq!(balance(&model, "cap"), 7.0, "cap pays the 5 that came through b");
}

#[test]
fn and_before_a_later_pipe_sibling_absorbs_only_its_capacity() {
    // (b & cap) covers min(5, 12) = 5, so the | carries the rest on to a
    let mut model = run(&unregulated_model(20.0, "accounts = (b & cap) | a"));
    assert_eq!(series(&mut model, "node.user.diversion"), vec![15.0, 0.0]);
    assert_eq!(balance(&model, "b"), 0.0);
    assert_eq!(balance(&model, "cap"), 7.0, "cap pays the same 5 as b, not what was handed to the pair");
    assert_eq!(balance(&model, "a"), 0.0, "a pays the 10 the pair could not");
}

#[test]
fn two_ands_under_a_pipe() {
    // (a & b) covers 5, then (cap & big) covers 12: 17 in all
    let mut model = run(&unregulated_model(20.0, "accounts = (a & b) | (cap & big)"));
    assert_eq!(series(&mut model, "node.user.diversion"), vec![17.0, 0.0]);
    assert_eq!(balance(&model, "a"), 5.0);
    assert_eq!(balance(&model, "b"), 0.0);
    assert_eq!(balance(&model, "cap"), 0.0);
    assert_eq!(balance(&model, "big"), 88.0);
}

#[test]
fn pipe_carries_past_a_nested_and_to_the_account_after_it() {
    // Demand 50 of inflow 50: a 10, then (b & cap) 5, then big 35; next day big alone
    let mut model = run(&unregulated_model(50.0, "accounts = a | (b & cap) | big"));
    assert_eq!(series(&mut model, "node.user.diversion"), vec![50.0, 50.0]);
    assert_eq!(balance(&model, "a"), 0.0);
    assert_eq!(balance(&model, "b"), 0.0);
    assert_eq!(balance(&model, "cap"), 7.0);
    assert_eq!(balance(&model, "big"), 15.0, "big pays 35 on day 1 and 50 on day 2");
}

// ------------------------------------------------------------------------------------------
// Contract 4: the regulated user
// ------------------------------------------------------------------------------------------

#[test]
fn regulated_order_capped_by_the_tree_and_debited_through_it() {
    // uncommitted: min(10, 12) = 10 on day 1
    let mut model = run(&regulated_model(30.0, "accounts = a & cap"));
    assert_eq!(series(&mut model, "node.user.order"), vec![10.0, 0.0], "order capped at min(a, cap), then nothing");
    assert_eq!(series(&mut model, "node.user.diversion"), vec![10.0, 0.0]);
    assert_eq!(balance(&model, "a"), 0.0);
    assert_eq!(balance(&model, "cap"), 2.0, "cap debited the whole 10");
}

#[test]
fn regulated_opportunistic_take_shares_the_tree() {
    // The dam releases only the order, so a second inflow gives the user water to take
    // opportunistically. Order 5 is delivered; the opportunistic take gets what
    // min(a, cap) = 10 leaves: 5. Both are debited through the tree.
    let ini = regulated_model(5.0, "accounts = a & cap\nopportunistic_demand = 20")
        .replace("[node.user]", "[node.src2]\ntype = inflow\nloc = 5, 15\ninflow = 50\nds_1 = user\n\n[node.user]");
    let mut model = run(&ini);
    assert_eq!(series(&mut model, "node.user.diversion"), vec![10.0, 0.0]);
    assert_eq!(balance(&model, "a"), 0.0);
    assert_eq!(balance(&model, "cap"), 2.0);
}

#[test]
fn regulated_order_accounts_extend_the_cap_over_a_tree() {
    // Regular: min(10 + 5, 100) = 15. The bridge authorises the rest of 30 and pays the excess.
    let mut model = run(&regulated_model(30.0, "accounts = (a | b) & big\norder_accounts = cap"));
    assert_eq!(series(&mut model, "node.user.order"), vec![27.0, 0.0], "15 + the 12 cap authorises; then nothing on either");
    assert_eq!(series(&mut model, "node.user.diversion"), vec![15.0, 0.0], "takes capped by the tree alone");
    assert_eq!(balance(&model, "cap"), 0.0, "excess of 12 debited from the bridge on day 1");
    assert_eq!(balance(&model, "a"), 0.0);
    assert_eq!(balance(&model, "b"), 0.0);
    assert_eq!(balance(&model, "big"), 85.0, "big pays the whole 15");
}

#[test]
fn regulated_and_before_a_later_pipe_sibling() {
    // Uncommitted (b & cap) | a = min(5, 12) + 10 = 15; committed and then debited the same way
    let mut model = run(&regulated_model(30.0, "accounts = (b & cap) | a"));
    assert_eq!(series(&mut model, "node.user.order"), vec![15.0, 0.0]);
    assert_eq!(series(&mut model, "node.user.diversion"), vec![15.0, 0.0]);
    assert_eq!(balance(&model, "b"), 0.0);
    assert_eq!(balance(&model, "cap"), 7.0);
    assert_eq!(balance(&model, "a"), 0.0);
}

#[test]
fn commit_overflow_under_a_pipe_root_goes_on_its_first_account() {
    let mut model = IniModelIO::read_model_string(&regulated_model(1.0, "accounts = a | (b & cap)")).unwrap();
    model.configure().unwrap();
    let manager = &mut model.account_manager;
    let (a, b, cap) = (manager.get_account_idx("a").unwrap(), manager.get_account_idx("b").unwrap(), manager.get_account_idx("cap").unwrap());
    for idx in [a, b, cap] {
        manager.set_commit_horizon(idx, 0);
    }
    let tree = match parse_combination("a | (b & cap)", manager).unwrap() {
        AccountsProperty::Tree(tree) => tree,
        other => panic!("expected a tree, got {:?}", other),
    };
    assert_eq!(tree.uncommitted(manager), 15.0);
    tree.commit(manager, 40.0, 0);
    let committed = |idx: usize| manager.get_account(idx).unwrap().committed;
    assert_eq!(committed(b), 5.0, "the pair covers 5");
    assert_eq!(committed(cap), 5.0, "the same 5 on each side of the &");
    assert_eq!(committed(a), 35.0, "a's 10 plus the 25 the whole could not cover");
    assert_eq!(tree.uncommitted(manager), 0.0);
}

#[test]
fn commit_of_nothing_touches_nothing() {
    let mut model = IniModelIO::read_model_string(&regulated_model(1.0, "accounts = (a | b) & big")).unwrap();
    model.configure().unwrap();
    let manager = &mut model.account_manager;
    let tree = match parse_combination("(a | b) & big", manager).unwrap() {
        AccountsProperty::Tree(tree) => tree,
        other => panic!("expected a tree, got {:?}", other),
    };
    tree.commit(manager, 0.0, 0);
    for name in ["a", "b", "big"] {
        let idx = manager.get_account_idx(name).unwrap();
        assert_eq!(manager.get_account(idx).unwrap().committed, 0.0);
    }
}

#[test]
fn commit_overflow_goes_on_the_first_account_of_each_side_that_fell_short() {
    let mut model = IniModelIO::read_model_string(&regulated_model(1.0, "accounts = (a | b) & big")).unwrap();
    model.configure().unwrap();
    let manager = &mut model.account_manager;
    let (a, b, big) = (manager.get_account_idx("a").unwrap(), manager.get_account_idx("b").unwrap(), manager.get_account_idx("big").unwrap());
    for idx in [a, b, big] {
        manager.set_commit_horizon(idx, 0);
    }
    let tree = match parse_combination("(a | b) & big", manager).unwrap() {
        AccountsProperty::Tree(tree) => tree,
        other => panic!("expected a tree, got {:?}", other),
    };
    assert_eq!(tree.uncommitted(manager), 15.0);
    tree.commit(manager, 40.0, 0);
    assert_eq!(manager.get_account_uncommitted_balance(a), 0.0);
    assert_eq!(manager.get_account_uncommitted_balance(b), 0.0);
    assert_eq!(manager.get_account_uncommitted_balance(big), 60.0, "big committed the whole 40");
    let committed = |idx: usize| manager.get_account(idx).unwrap().committed;
    assert_eq!(committed(a), 35.0, "a's 10 plus the 25 the left side could not cover");
    assert_eq!(committed(b), 5.0);
    assert_eq!(committed(big), 40.0);
    assert_eq!(tree.uncommitted(manager), 0.0);
}

// ------------------------------------------------------------------------------------------
// Contract 3: load errors
// ------------------------------------------------------------------------------------------

fn load_error(accounts_line: &str) -> String {
    match IniModelIO::read_model_string(&unregulated_model(1.0, accounts_line)) {
        Ok(_) => panic!("'{}' should not load", accounts_line),
        Err(e) => e.to_string(),
    }
}

#[test]
fn errors_name_the_node_and_the_fault() {
    let cases = [
        ("accounts = a | b & cap", "cannot be mixed without parentheses"),
        ("accounts = a, b & cap", "a comma cannot be mixed"),
        ("accounts = a && cap", "'&&' is not an account operator"),
        ("accounts = a || b", "'||' is not an account operator"),
        ("accounts = a & nope", "Unknown account 'nope'"),
        ("accounts = a & pools", "'pools' is an account group"),
        ("accounts = (a | b) & (a | cap)", "Account 'a' appears more than once"),
        ("accounts = (a | b", "missing ')'"),
        ("accounts = a | b)", "unmatched ')'"),
        ("accounts = a &", "ends where an account name was expected"),
        ("accounts = () & a", "empty parentheses"),
        ("accounts = a & (| b)", "expected an account name or '('"),
        ("accounts = a & b.c", "unexpected character '.'"),
    ];
    for (line, expected) in cases {
        let message = load_error(line);
        assert!(message.contains("Node 'user'"), "'{}' should name the node, got: {}", line, message);
        assert!(message.contains(expected), "'{}' should say '{}', got: {}", line, expected, message);
    }
}

#[test]
fn plain_list_errors_name_the_node_too() {
    let message = load_error("accounts = nope");
    assert!(message.contains("Node 'user'"), "got: {}", message);
    assert!(message.contains("Unknown account 'nope'"), "got: {}", message);
}

#[test]
fn order_accounts_stay_a_plain_list() {
    let message = match IniModelIO::read_model_string(&regulated_model(1.0, "accounts = a\norder_accounts = b & cap")) {
        Ok(_) => panic!("should not load"),
        Err(e) => e.to_string(),
    };
    assert!(message.contains("'order_accounts' is a plain list"), "got: {}", message);
    assert!(message.contains("Node 'user'"), "got: {}", message);
}

#[test]
fn names_match_case_insensitively() {
    let mut model = run(&unregulated_model(20.0, "accounts = Big & CAP"));
    assert_eq!(series(&mut model, "node.user.diversion"), vec![12.0, 0.0]);
}

// ------------------------------------------------------------------------------------------
// Contract 5: the canonical render
// ------------------------------------------------------------------------------------------

#[test]
fn saved_in_canonical_form() {
    let cases = [
        ("accounts = (a|b)&cap", "accounts = (a | b) & cap"),
        ("accounts = ((a | b)) & (cap & big)", "accounts = (a | b) & cap & big"),
        ("accounts = a | (b | cap)", "accounts = a, b, cap"),
        ("accounts = (a)", "accounts = a"),
        ("accounts = a & (b | cap)", "accounts = a & (b | cap)"),
        ("accounts = a | (b & cap)", "accounts = a | (b & cap)"),
        ("accounts = a | ((b & cap) | big)", "accounts = a | (b & cap) | big"),
    ];
    for (written, saved) in cases {
        assert_eq!(saved_accounts_line(&unregulated_model(1.0, written)), saved, "for '{}'", written);
    }
}

#[test]
fn standard_save_keeps_the_line_as_authored() {
    assert_eq!(saved_accounts_line_by(&unregulated_model(1.0, "accounts = ((a|b)) & cap"), SaveMethod::Standard),
               "accounts = ((a|b)) & cap");
}

#[test]
fn saved_model_reloads_to_the_same_result() {
    let ini = unregulated_model(20.0, "accounts = (a | b) & cap");
    let model = IniModelIO::read_model_string(&ini).unwrap();
    let saved = IniModelIO::model_to_string(&model);
    let mut first = run(&ini);
    let mut second = run(&saved);
    assert_eq!(series(&mut second, "node.user.diversion"), series(&mut first, "node.user.diversion"));
}
