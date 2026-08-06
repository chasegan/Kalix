// Tests for `order_accounts` on regulated_user (debit-on-order authorisation
// accounts, designed with Chas for the Proserpine WY-boundary bridge).
//
// The contract:
// 1. EXCESS-ONLY ATTRIBUTION: the order cap is
//    min(order, sum(accounts) + sum(order_accounts)), and only the excess of
//    the approved order over the regular balance is debited from
//    order_accounts, walked in list order, at order time.
// 2. FLOW-PHASE INVISIBILITY: order_accounts never contribute to the delivery
//    cap and are never debited (or refunded) by takes.
// 3. NO REFUNDS: an authorised-and-debited order that goes undelivered stays
//    debited.
// 4. With `accounts` empty, the node runs a pure order-debit scheme.
//
// The harness is a two-day model: inflow -> storage (supplier, so the order
// phase runs) -> regulated user (lag 0) -> blackhole. Day 2 exercises the
// drained-regular-account state.
use crate::io::ini_model_io::IniModelIO;

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

fn user_model(order: f64, accounts_line: &str, order_accounts_line: &str) -> String {
    format!(r#"
[kalix]
start = 2020-01-01
end = 2020-01-02

[acc.regular]
accounts = name, size, initial,
           a1, 1000, 10,

[acc.bridge]
accounts = name, size, initial,
           b1, 1000, 5,
           b2, 1000, 100,

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
{accounts_line}
{order_accounts_line}
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 30

[outputs]
node.user.order
node.user.diversion
"#)
}

/// Contracts 1 + 2: order beyond the regular balance is approved against the
/// order_accounts with the excess debited at order time; the take is still
/// capped by (and debited to) the regular account alone. Day 2 (regular
/// drained): the whole order is authorised by the bridge, nothing delivered.
#[test]
fn test_excess_debited_at_order_take_capped_by_regular() {
    let ini = user_model(30.0, "accounts = a1", "order_accounts = b2");
    let mut model = run(&ini);
    assert_eq!(series(&mut model, "node.user.order"), vec![30.0, 30.0], "full order approved both days");
    assert_eq!(balance(&model, "b2"), 50.0, "excess 20 then 30 debited from b2 at order time");
    assert_eq!(series(&mut model, "node.user.diversion"), vec![10.0, 0.0], "takes capped by regular balance");
    assert_eq!(balance(&model, "a1"), 0.0, "takes debited to the regular account");
}

/// Contract 1: orders within the regular balance never touch order_accounts.
#[test]
fn test_regular_order_leaves_order_accounts_untouched() {
    let ini = user_model(5.0, "accounts = a1", "order_accounts = b2");
    let mut model = run(&ini);
    assert_eq!(series(&mut model, "node.user.order"), vec![5.0, 5.0]);
    assert_eq!(balance(&model, "b2"), 100.0, "no excess, no order-time debit");
    assert_eq!(balance(&model, "a1"), 0.0, "5 + 5 debited to the regular account");
    assert_eq!(series(&mut model, "node.user.diversion"), vec![5.0, 5.0]);
}

/// Contract 1: the excess debit walks order_accounts in list order.
#[test]
fn test_excess_debit_walks_list_order() {
    let ini = user_model(30.0, "accounts = a1", "order_accounts = b1, b2");
    let model = run(&ini);
    assert_eq!(balance(&model, "b1"), 0.0, "b1 (5) drained first on day 1");
    assert_eq!(balance(&model, "b2"), 55.0, "b2 pays 15 on day 1, 30 on day 2");
}

/// Contract 1: the combined balance still caps the order.
#[test]
fn test_combined_balance_caps_order() {
    let ini = user_model(30.0, "accounts = a1", "order_accounts = b1");
    let mut model = run(&ini);
    assert_eq!(series(&mut model, "node.user.order"), vec![15.0, 0.0],
               "day 1 capped at 10 + 5; day 2 nothing left to authorise");
    assert_eq!(balance(&model, "b1"), 0.0, "excess 5 debited");
    assert_eq!(series(&mut model, "node.user.diversion"), vec![10.0, 0.0]);
}

/// Contract 4: with `accounts` empty this is a pure order-debit scheme —
/// the whole approved order is debited at order time and the take is not
/// balance-capped (nor double-debited) at flow time.
#[test]
fn test_pure_order_debit_mode() {
    let ini = user_model(30.0, "", "order_accounts = b2");
    let mut model = run(&ini);
    assert_eq!(series(&mut model, "node.user.order"), vec![30.0, 30.0]);
    assert_eq!(balance(&model, "b2"), 40.0, "whole orders debited at order time");
    assert_eq!(series(&mut model, "node.user.diversion"), vec![30.0, 30.0], "takes not balance-capped");
    assert_eq!(balance(&model, "a1"), 10.0, "unreferenced regular account untouched");
}

/// Contract 3: authorised orders that cannot be delivered (empty dam, no
/// inflow) stay debited — no refunds.
#[test]
fn test_no_refund_for_undelivered_order() {
    let ini = user_model(30.0, "accounts = a1", "order_accounts = b2")
        .replace("inflow = 50", "inflow = 0")
        .replace("initial_volume = 500", "initial_volume = 0");
    let mut model = run(&ini);
    assert_eq!(balance(&model, "b2"), 60.0, "order-time debits (20 + 20) stand");
    assert_eq!(series(&mut model, "node.user.diversion"), vec![0.0, 0.0], "nothing delivered");
    assert_eq!(balance(&model, "a1"), 10.0, "regular account undebited (nothing taken)");
}
