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

/// Minimal network so the model runs; the tests live in the [var.*] block.
const TAIL: &str = r#"
[node.src]
type = inflow
loc = 0, 0
inflow = 1
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 10
"#;

/// sim.days_in_month / sim.days_in_year / sim.is_leap across a leap-year
/// February boundary.
#[test]
fn test_sim_calendar_vars_leap_year() {
    let ini = format!(r#"
[kalix]
start = 2024-02-27
end = 2024-03-02

[var.cal]
dim = sim.days_in_month
diy = sim.days_in_year
leap = sim.is_leap
{TAIL}
[outputs]
var.cal.dim
var.cal.diy
var.cal.leap
"#);
    let mut model = run(&ini);
    assert_eq!(series(&mut model, "var.cal.dim"), vec![29.0, 29.0, 29.0, 31.0, 31.0],
        "Feb 2024 has 29 days, March 31");
    assert_eq!(series(&mut model, "var.cal.diy"), vec![366.0; 5]);
    assert_eq!(series(&mut model, "var.cal.leap"), vec![1.0; 5]);
}

/// The same fields in a common year.
#[test]
fn test_sim_calendar_vars_common_year() {
    let ini = format!(r#"
[kalix]
start = 2023-02-27
end = 2023-03-02

[var.cal]
dim = sim.days_in_month
diy = sim.days_in_year
leap = sim.is_leap
{TAIL}
[outputs]
var.cal.dim
var.cal.diy
var.cal.leap
"#);
    let mut model = run(&ini);
    assert_eq!(series(&mut model, "var.cal.dim"), vec![28.0, 28.0, 31.0, 31.0],
        "Feb 2023 has 28 days (27th, 28th, then March)");
    assert_eq!(series(&mut model, "var.cal.diy"), vec![365.0; 4]);
    assert_eq!(series(&mut model, "var.cal.leap"), vec![0.0; 4]);
}

/// is_leap_year(yyyy) is a pure builtin: the Gregorian rule, including the
/// century exceptions, both with constant and sim-supplied arguments.
#[test]
fn test_is_leap_year_builtin() {
    let ini = format!(r#"
[kalix]
start = 2024-01-01
end = 2024-01-01

[var.cal]
y2000 = is_leap_year(2000)
y1900 = is_leap_year(1900)
y2024 = is_leap_year(2024)
y2023 = is_leap_year(2023)
current = is_leap_year(sim.year)
{TAIL}
[outputs]
var.cal.y2000
var.cal.y1900
var.cal.y2024
var.cal.y2023
var.cal.current
"#);
    let mut model = run(&ini);
    assert_eq!(series(&mut model, "var.cal.y2000"), vec![1.0], "400-year rule");
    assert_eq!(series(&mut model, "var.cal.y1900"), vec![0.0], "century exception");
    assert_eq!(series(&mut model, "var.cal.y2024"), vec![1.0]);
    assert_eq!(series(&mut model, "var.cal.y2023"), vec![0.0]);
    assert_eq!(series(&mut model, "var.cal.current"), vec![1.0]);
}

/// month_at / days_in_month_at across the Dec->Jan year boundary, including
/// a negative (look-back) offset.
#[test]
fn test_calendar_at_year_boundary() {
    let ini = format!(r#"
[kalix]
start = 2023-12-30
end = 2024-01-02

[var.cal]
m0 = month_at(0)
m3 = month_at(3)
mback = month_at(-1)
d0 = days_in_month_at(0)
d3 = days_in_month_at(3)
{TAIL}
[outputs]
var.cal.m0
var.cal.m3
var.cal.mback
var.cal.d0
var.cal.d3
"#);
    let mut model = run(&ini);
    // Days: Dec 30, Dec 31, Jan 1, Jan 2
    assert_eq!(series(&mut model, "var.cal.m0"), vec![12.0, 12.0, 1.0, 1.0]);
    assert_eq!(series(&mut model, "var.cal.m3"), vec![1.0, 1.0, 1.0, 1.0],
        "+3 days from Dec 30 already lands in January");
    assert_eq!(series(&mut model, "var.cal.mback"), vec![12.0, 12.0, 12.0, 1.0],
        "-1 day looks back across the year boundary");
    assert_eq!(series(&mut model, "var.cal.d0"), vec![31.0, 31.0, 31.0, 31.0]);
    assert_eq!(series(&mut model, "var.cal.d3"), vec![31.0, 31.0, 31.0, 31.0]);
}

/// The 28-day lookahead the hand-rolled arrival-month idiom could never do:
/// from 1 Feb it stays in February in a leap year (landing on the 29th) and
/// reaches March in a common year.
#[test]
fn test_calendar_at_28_day_lookahead() {
    let leap = format!(r#"
[kalix]
start = 2024-02-01
end = 2024-02-01

[var.cal]
m = month_at(28)
d = days_in_month_at(28)
m60 = month_at(60)
{TAIL}
[outputs]
var.cal.m
var.cal.d
var.cal.m60
"#);
    let mut model = run(&leap);
    assert_eq!(series(&mut model, "var.cal.m"), vec![2.0], "1 Feb 2024 + 28 = 29 Feb");
    assert_eq!(series(&mut model, "var.cal.d"), vec![29.0]);
    assert_eq!(series(&mut model, "var.cal.m60"), vec![4.0], "+60 days crosses two month boundaries");

    let common = format!(r#"
[kalix]
start = 2023-02-01
end = 2023-02-01

[var.cal]
m = month_at(28)
d = days_in_month_at(28)
{TAIL}
[outputs]
var.cal.m
var.cal.d
"#);
    let mut model = run(&common);
    assert_eq!(series(&mut model, "var.cal.m"), vec![3.0], "1 Feb 2023 + 28 = 1 Mar");
    assert_eq!(series(&mut model, "var.cal.d"), vec![31.0]);
}

/// The calendar functions take exactly one argument, and their names are
/// reserved so a [fn] definition cannot shadow them.
#[test]
fn test_calendar_function_validation() {
    let bad_arity = format!(r#"
[kalix]
start = 2024-01-01
end = 2024-01-01

[var.cal]
m = month_at(1, 2)
{TAIL}"#);
    let err = IniModelIO::read_model_string(&bad_arity).err()
        .expect("expected a load error").to_string();
    assert!(err.contains("expects 1 argument"), "unexpected: {}", err);

    let shadowed = format!(r#"
[kalix]
start = 2024-01-01
end = 2024-01-01

[fn]
month_at(n) = n
{TAIL}"#);
    let err = IniModelIO::read_model_string(&shadowed).err()
        .expect("expected a load error").to_string();
    assert!(err.contains("calendar function"), "unexpected: {}", err);
}
