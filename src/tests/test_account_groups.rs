use crate::io::ini_model_io::IniModelIO;

const BASE_MODEL: &str = r#"
[kalix]
start = 2020-01-01
end = 2020-01-10

[node.n1]
type = inflow
loc = 0, 0
inflow = 1.0

[outputs]
node.n1.dsflow
"#;

fn model_with_acc(acc_sections: &str) -> String {
    format!("{}\n{}", acc_sections, BASE_MODEL)
}

#[test]
fn test_acc_group_parses_and_creates_accounts() {
    let ini = model_with_acc(
        r#"[acc.gs_annual]
accounts = name, size, initial,
           smith, 42, 10,
           jones, 105, 0,
"#);
    let model = IniModelIO::new().read_model_string(&ini).expect("model should load");

    let smith = model.account_manager.get_account_idx("smith").expect("smith exists");
    let jones = model.account_manager.get_account_idx("jones").expect("jones exists");
    assert_eq!(model.account_manager.get_account(smith).unwrap().size, 42.0);
    assert_eq!(model.account_manager.get_account(smith).unwrap().initial_balance, 10.0);
    assert_eq!(model.account_manager.get_account(jones).unwrap().size, 105.0);

    let group_idx = model.account_manager.get_group_idx("gs_annual").expect("group exists");
    let group = model.account_manager.get_group(group_idx).unwrap();
    assert_eq!(group.member_ids, vec![smith, jones]); // table row order
    assert_eq!(group.name, "gs_annual");
}

#[test]
fn test_acc_columns_order_free_and_initial_defaults() {
    // size before name is rejected (name must lead), but size/initial may swap
    let ini = model_with_acc(
        r#"[acc.g1]
accounts = name, initial, size,
           a1, 5, 50,
"#);
    let model = IniModelIO::new().read_model_string(&ini).expect("model should load");
    let a1 = model.account_manager.get_account_idx("a1").unwrap();
    assert_eq!(model.account_manager.get_account(a1).unwrap().size, 50.0);
    assert_eq!(model.account_manager.get_account(a1).unwrap().initial_balance, 5.0);

    // initial column absent -> defaults to 0
    let ini = model_with_acc(
        r#"[acc.g2]
accounts = name, size,
           a2, 7,
"#);
    let model = IniModelIO::new().read_model_string(&ini).expect("model should load");
    let a2 = model.account_manager.get_account_idx("a2").unwrap();
    assert_eq!(model.account_manager.get_account(a2).unwrap().initial_balance, 0.0);
}

#[test]
fn test_acc_header_contract_errors() {
    // Typo'd column name: header run ends early, so the stray header cell
    // shifts into the data and fails loudly downstream — either the row shape
    // stops dividing, or a shifted cell fails numeric parsing.
    let ini = model_with_acc(
        r#"[acc.g1]
accounts = name, size, initail,
           a1, 42, 0,
"#);
    let err = IniModelIO::new().read_model_string(&ini).err().expect("expected a load error");
    assert!(err.contains("not a whole number") || err.contains("Invalid size"),
        "unexpected error: {}", err);

    // Missing size column
    let ini = model_with_acc(
        r#"[acc.g1]
accounts = name, initial,
           a1, 0,
"#);
    let err = IniModelIO::new().read_model_string(&ini).err().expect("expected a load error");
    assert!(err.contains("requires a 'size' column"), "unexpected error: {}", err);

    // name not first
    let ini = model_with_acc(
        r#"[acc.g1]
accounts = size, name,
           42, a1,
"#);
    let err = IniModelIO::new().read_model_string(&ini).err().expect("expected a load error");
    assert!(err.contains("must be 'name'") || err.contains("must start with a header"),
        "unexpected error: {}", err);

    // Header only, no rows
    let ini = model_with_acc(
        r#"[acc.g1]
accounts = name, size,
"#);
    let err = IniModelIO::new().read_model_string(&ini).err().expect("expected a load error");
    assert!(err.contains("no account rows"), "unexpected error: {}", err);

    // Account named after a column keyword, first row: the name extends the
    // header run, so the table shape breaks — loud, if obliquely.
    let ini = model_with_acc(
        r#"[acc.g1]
accounts = name, size,
           initial, 42,
"#);
    let err = IniModelIO::new().read_model_string(&ini).err().expect("expected a load error");
    assert!(err.contains("not a whole number"), "unexpected error: {}", err);

    // Account named after a column keyword, later row: caught by the explicit check
    let ini = model_with_acc(
        r#"[acc.g1]
accounts = name, size,
           a1, 42,
           initial, 10,
"#);
    let err = IniModelIO::new().read_model_string(&ini).err().expect("expected a load error");
    assert!(err.contains("clashes with an accounts-table column name"), "unexpected error: {}", err);

    // Initial exceeding size
    let ini = model_with_acc(
        r#"[acc.g1]
accounts = name, size, initial,
           a1, 42, 43,
"#);
    let err = IniModelIO::new().read_model_string(&ini).err().expect("expected a load error");
    assert!(err.contains("outside [0, size"), "unexpected error: {}", err);
}

#[test]
fn test_acc_sections_are_nouns_only() {
    let ini = model_with_acc(
        r#"[acc.g1]
water_year = 7
accounts = name, size,
           a1, 42,
"#);
    let err = IniModelIO::new().read_model_string(&ini).err().expect("expected a load error");
    assert!(err.contains("policy belongs in [ras.*]"), "unexpected error: {}", err);
}

#[test]
fn test_acc_namespace_collisions() {
    // Duplicate account name across groups
    let ini = model_with_acc(
        r#"[acc.g1]
accounts = name, size,
           a1, 42,

[acc.g2]
accounts = name, size,
           a1, 10,
"#);
    let err = IniModelIO::new().read_model_string(&ini).err().expect("expected a load error");
    assert!(err.contains("more than once"), "unexpected error: {}", err);

    // Account name colliding with a group name (flat acc. namespace)
    let ini = model_with_acc(
        r#"[acc.g1]
accounts = name, size,
           g2, 42,

[acc.g2]
accounts = name, size,
           a2, 10,
"#);
    let err = IniModelIO::new().read_model_string(&ini).err().expect("expected a load error");
    assert!(err.contains("clashes"), "unexpected error: {}", err);
}

#[test]
fn test_acc_round_trip_canonical() {
    let ini = model_with_acc(
        r#"[acc.gs_annual]
accounts = name, size, initial,
           smith, 42, 10,
           jones, 105, 0,
"#);
    let model = IniModelIO::new().read_model_string(&ini).expect("model should load");
    let rendered = IniModelIO::new().model_to_string(&model);

    // Re-load the canonical render and compare account state
    let model2 = IniModelIO::new().read_model_string(&rendered)
        .unwrap_or_else(|e| panic!("canonical render should re-load, got: {}\n---\n{}", e, rendered));
    for name in ["smith", "jones"] {
        let i1 = model.account_manager.get_account_idx(name).unwrap();
        let i2 = model2.account_manager.get_account_idx(name).unwrap();
        let a1 = model.account_manager.get_account(i1).unwrap();
        let a2 = model2.account_manager.get_account(i2).unwrap();
        assert_eq!(a1.size, a2.size, "size round-trips for {}", name);
        assert_eq!(a1.initial_balance, a2.initial_balance, "initial round-trips for {}", name);
    }
    let g2 = model2.account_manager.get_group_idx("gs_annual").expect("group survives round-trip");
    assert_eq!(model2.account_manager.get_group(g2).unwrap().member_ids.len(), 2);
}

#[test]
fn test_inline_account_declaration_is_a_hard_error() {
    let ini = r#"
[kalix]
start = 2020-01-01
end = 2020-01-10

[node.u1]
type = unregulated_user
loc = 0, 0
demand = 5
account = n0031, avl, 42, 7

[outputs]
node.u1.dsflow
"#;
    let err = IniModelIO::new().read_model_string(ini).err().expect("expected a load error");
    assert!(err.contains("no longer supported") && err.contains("[acc.*]"),
        "error should carry the migration message, got: {}", err);
}

#[test]
fn test_accounts_reference_errors() {
    // Unknown account
    let ini = model_with_acc(
        r#"[acc.g1]
accounts = name, size,
           a1, 42,

[node.u1]
type = unregulated_user
loc = 0, 10
demand = 5
accounts = nonexistent
ds_1 = n1
"#);
    let err = IniModelIO::new().read_model_string(&ini).err().expect("expected a load error");
    assert!(err.contains("Unknown account 'nonexistent'"), "unexpected error: {}", err);

    // Group name where an account name is required
    let ini = model_with_acc(
        r#"[acc.g1]
accounts = name, size,
           a1, 42,

[node.u1]
type = unregulated_user
loc = 0, 10
demand = 5
accounts = g1
ds_1 = n1
"#);
    let err = IniModelIO::new().read_model_string(&ini).err().expect("expected a load error");
    assert!(err.contains("is an account group"), "unexpected error: {}", err);
}

#[test]
fn test_accounts_ordered_cascade_draw() {
    // Two accounts in order of use: a_first (5 ML) then b_second (10 ML).
    // Ample inflow, demand 8/day: day 1 takes 5 from a_first + 3 from b_second,
    // day 2 takes remaining 7 from b_second, day 3 takes nothing.
    let ini = r#"
[kalix]
start = 2020-01-01
end = 2020-01-05

[acc.g1]
accounts = name, size, initial,
           a_first, 100, 5,
           b_second, 100, 10,

[node.src]
type = inflow
loc = 0, 0
inflow = 50
ds_1 = u1

[node.u1]
type = unregulated_user
loc = 0, 10
demand = 8
accounts = a_first, b_second

[outputs]
node.u1.diversion
"#;
    let mut model = IniModelIO::new().read_model_string(ini).expect("model should load");
    model.configure().expect("model should configure");
    model.run().expect("simulation should run");

    let idx = model.data_cache.get_series_idx("node.u1.diversion", false).expect("diversion series");
    let series = model.data_cache.series[idx].clone();
    assert_eq!(series.values[0], 8.0, "day 1: full demand met (5 + 3 across accounts)");
    assert_eq!(series.values[1], 7.0, "day 2: only b_second's remaining 7");
    assert_eq!(series.values[2], 0.0, "day 3: both accounts empty");

    let a = model.account_manager.get_account_idx("a_first").unwrap();
    let b = model.account_manager.get_account_idx("b_second").unwrap();
    assert_eq!(model.account_manager.get_account_balance(a), 0.0, "a_first drained first");
    assert_eq!(model.account_manager.get_account_balance(b), 0.0, "b_second drained second");
}

#[test]
fn test_acc_no_implicit_behaviour() {
    // Accounts carry no behaviour and no calendar: without a [ras.*] section
    // targeting the group, a balance never moves except by node takes (§3.1) —
    // in particular there is no implicit water-year refill.
    let ini = model_with_acc(
        r#"[acc.g1]
accounts = name, size, initial,
           a1, 42, 10,
"#);
    let mut model = IniModelIO::new().read_model_string(&ini).expect("model should load");
    model.configure().expect("model should configure");
    let a1 = model.account_manager.get_account_idx("a1").unwrap();
    let account = model.account_manager.get_account(a1).unwrap();
    assert_eq!(account.balance, 10.0, "balance initialised to initial");
    assert!(model.ras_systems.is_empty(), "no implicit RAS generated");
}
