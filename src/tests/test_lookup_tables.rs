/// Tests for named lookup tables ([table.*] sections).
///
/// Covers the LookupTable parser and lookup semantics (clamped 1D
/// interpolation, exact-match 2D column selection), the TableRegistry, and
/// model-level loading / canonical round-trip through the INI reader.

use crate::data_management::data_cache::DataCache;
use crate::io::ini_model_io::IniModelIO;
use crate::model_inputs::DynamicInput;
use crate::numerical::lookup_table::LookupTable;
use crate::timeseries::Timeseries;
use crate::tid::utils::wrap_to_u64;

fn parse_1d(data: &str) -> LookupTable {
    LookupTable::from_ini_data("t", data, 2).expect("1D table should parse")
}

fn parse_2d(data: &str, ncols: usize) -> LookupTable {
    LookupTable::from_ini_data("t", data, ncols).expect("2D table should parse")
}

fn lookup_1d(table: &LookupTable, x: f64) -> f64 {
    match table {
        LookupTable::OneD(t) => t.lookup(x),
        LookupTable::TwoD(_) => panic!("expected a 1D table"),
    }
}

fn lookup_2d(table: &LookupTable, col_key: f64, row_key: f64) -> f64 {
    match table {
        LookupTable::TwoD(t) => t.lookup(col_key, row_key),
        LookupTable::OneD(_) => panic!("expected a 2D table"),
    }
}

// -------------------------------------------------------------------------------------
// 1D parsing and lookup semantics
// -------------------------------------------------------------------------------------

#[test]
fn test_1d_interpolation_and_clamping() {
    let t = parse_1d("0, 0,  10, 100,  20, 150");
    assert_eq!(t.arity(), 1);
    assert_eq!(t.ncols(), 2);

    // Exact breakpoints
    assert_eq!(lookup_1d(&t, 0.0), 0.0);
    assert_eq!(lookup_1d(&t, 10.0), 100.0);
    assert_eq!(lookup_1d(&t, 20.0), 150.0);

    // Interior interpolation
    assert_eq!(lookup_1d(&t, 5.0), 50.0);
    assert_eq!(lookup_1d(&t, 15.0), 125.0);

    // Clamped at both ends — no extrapolation
    assert_eq!(lookup_1d(&t, -100.0), 0.0);
    assert_eq!(lookup_1d(&t, 1e9), 150.0);

    // NaN propagates
    assert!(lookup_1d(&t, f64::NAN).is_nan());
}

#[test]
fn test_1d_with_text_header() {
    let t = parse_1d("stage, flow,  0, 0,  1, 250");
    assert_eq!(lookup_1d(&t, 0.5), 125.0);

    // Header survives canonical formatting, and the formatted data re-parses
    let formatted = t.format_data(4);
    assert!(formatted.starts_with("stage, flow,"));
    let t2 = parse_1d(&formatted);
    assert_eq!(lookup_1d(&t2, 0.5), 125.0);
}

#[test]
fn test_1d_single_row_is_constant() {
    let t = parse_1d("5, 42");
    assert_eq!(lookup_1d(&t, -1.0), 42.0);
    assert_eq!(lookup_1d(&t, 5.0), 42.0);
    assert_eq!(lookup_1d(&t, 100.0), 42.0);
    assert!(lookup_1d(&t, f64::NAN).is_nan());
}

#[test]
fn test_1d_trailing_comma_and_whitespace_tolerated() {
    let t = parse_1d(" 0, 0, 1, 10, \n");
    assert_eq!(lookup_1d(&t, 0.5), 5.0);
}

#[test]
fn test_1d_parse_errors() {
    // Odd number of values
    assert!(LookupTable::from_ini_data("t", "0, 0, 1", 2).is_err());
    // x values not strictly ascending
    assert!(LookupTable::from_ini_data("t", "0, 0, 0, 1", 2).is_err());
    assert!(LookupTable::from_ini_data("t", "1, 0, 0, 1", 2).is_err());
    // Bad number
    assert!(LookupTable::from_ini_data("t", "0, 0, blah, 1", 2).is_err());
    // NaN / inf cells rejected
    assert!(LookupTable::from_ini_data("t", "0, nan, 1, 1", 2).is_err());
    assert!(LookupTable::from_ini_data("t", "0, 0, 1, inf", 2).is_err());
    // Empty data
    assert!(LookupTable::from_ini_data("t", "  ", 2).is_err());
    // Header must be exactly two non-numeric labels
    assert!(LookupTable::from_ini_data("t", "stage, 0, 1, 1", 2).is_err());
    // Header with no data rows
    assert!(LookupTable::from_ini_data("t", "stage, flow", 2).is_err());
    // ncols below 2
    assert!(LookupTable::from_ini_data("t", "0, 0", 1).is_err());
}

// -------------------------------------------------------------------------------------
// 2D parsing and lookup semantics
// -------------------------------------------------------------------------------------

/// 3-column grid: column keys 1 and 2, three rows keyed 0/10/20.
const GRID: &str = "x,  1,   2,
                    0,  0,   1000,
                    10, 100, 2000,
                    20, 150, 2600";

#[test]
fn test_2d_exact_column_match_and_row_interpolation() {
    let t = parse_2d(GRID, 3);
    assert_eq!(t.arity(), 2);
    assert_eq!(t.ncols(), 3);

    // Column 1, exact rows and interpolation
    assert_eq!(lookup_2d(&t, 1.0, 0.0), 0.0);
    assert_eq!(lookup_2d(&t, 1.0, 5.0), 50.0);
    assert_eq!(lookup_2d(&t, 1.0, 20.0), 150.0);

    // Column 2 (verifies the column-major layout picks the right column)
    assert_eq!(lookup_2d(&t, 2.0, 0.0), 1000.0);
    assert_eq!(lookup_2d(&t, 2.0, 15.0), 2300.0);

    // Row key clamps at both ends
    assert_eq!(lookup_2d(&t, 2.0, -5.0), 1000.0);
    assert_eq!(lookup_2d(&t, 2.0, 999.0), 2600.0);

    // NaN row key propagates
    assert!(lookup_2d(&t, 1.0, f64::NAN).is_nan());
}

#[test]
#[should_panic(expected = "no column with key")]
fn test_2d_column_miss_panics() {
    let t = parse_2d(GRID, 3);
    lookup_2d(&t, 1.5, 0.0);
}

#[test]
#[should_panic(expected = "no column with key")]
fn test_2d_nan_column_key_panics() {
    let t = parse_2d(GRID, 3);
    lookup_2d(&t, f64::NAN, 0.0);
}

#[test]
fn test_2d_single_data_row_monthly_constants() {
    // One row of monthly values: exact column match, row key irrelevant
    let t = parse_2d("x, 1, 2, 3,  0, 10, 20, 30", 4);
    assert_eq!(lookup_2d(&t, 2.0, -999.0), 20.0);
    assert_eq!(lookup_2d(&t, 3.0, 999.0), 30.0);
}

#[test]
fn test_2d_format_data_round_trip() {
    let t = parse_2d(GRID, 3);
    let formatted = t.format_data(4);
    let t2 = parse_2d(&formatted, 3);
    assert_eq!(lookup_2d(&t2, 2.0, 15.0), 2300.0);
    assert_eq!(lookup_2d(&t2, 1.0, 5.0), 50.0);
}

#[test]
fn test_2d_parse_errors() {
    // Numeric corner cell (missing marker)
    assert!(LookupTable::from_ini_data("t", "0, 1, 2, 0, 0, 0", 3).is_err());
    // Element count not a multiple of ncols
    assert!(LookupTable::from_ini_data("t", "x, 1, 2, 0, 0", 3).is_err());
    // No data rows after the key row
    assert!(LookupTable::from_ini_data("t", "x, 1, 2", 3).is_err());
    // Column keys not strictly ascending
    assert!(LookupTable::from_ini_data("t", "x, 2, 1, 0, 0, 0", 3).is_err());
    assert!(LookupTable::from_ini_data("t", "x, 1, 1, 0, 0, 0", 3).is_err());
    // Row keys not strictly ascending
    assert!(LookupTable::from_ini_data("t", "x, 1, 2, 5, 0, 0, 5, 1, 1", 3).is_err());
}

// -------------------------------------------------------------------------------------
// Model-level loading and round-trip
// -------------------------------------------------------------------------------------

#[test]
fn test_model_loads_tables_regardless_of_section_order() {
    // The [table.*] section sits after the node that could reference it —
    // the pre-pass must make order irrelevant.
    let ini = "\
[kalix]

[node.g]
loc = 0, 0
type = gauge

[table.rating]
values = 0, 0,
       1, 250,

[table.monthly_demand]
n_cols = 13
values = x, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12,
       0, 9, 8, 7, 5, 3, 2, 2, 3, 5, 7,  8,  9,
";
    let model = IniModelIO::new().read_model_string(ini).expect("model should load");

    let rating = model.data_cache.tables.get("rating").expect("rating registered");
    assert_eq!(rating.arity(), 1);
    let monthly = model.data_cache.tables.get("monthly_demand").expect("monthly registered");
    assert_eq!(monthly.arity(), 2);
    assert_eq!(monthly.ncols(), 13);
}

#[test]
fn test_model_table_error_cases() {
    let read = |ini: &str| IniModelIO::new().read_model_string(ini);

    // Invalid table name (uppercase)
    assert!(read("[kalix]\n[table.Bad]\nvalues = 0, 0, 1, 1\n").is_err());
    // Invalid table name (contains a dot)
    assert!(read("[kalix]\n[table.a.b]\nvalues = 0, 0, 1, 1\n").is_err());
    // Unexpected property
    assert!(read("[kalix]\n[table.t]\nvalues = 0, 0, 1, 1\nfoo = 1\n").is_err());
    // Missing data property
    assert!(read("[kalix]\n[table.t]\nn_cols = 2\n").is_err());
    // Bad n_cols
    assert!(read("[kalix]\n[table.t]\nn_cols = two\nvalues = 0, 0, 1, 1\n").is_err());
    // Malformed table body surfaces the table parser's error
    let err = read("[kalix]\n[table.t]\nvalues = 0, 0, 1\n").err().expect("malformed table should fail");
    assert!(err.contains("table.t"), "error should name the table: {}", err);
}

// -------------------------------------------------------------------------------------
// Expression integration (DynamicInput lowering and evaluation)
// -------------------------------------------------------------------------------------

/// A data cache with a registered 1D table (0->0, 10->100, 20->150) and a 2D
/// table (columns 1 and 2 over rows 0/10), plus a data series "data.stage"
/// holding [5.0, 15.0, 50.0].
fn cache_with_tables() -> DataCache {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800); // 2020-01-01
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    data_cache.tables.insert(
        LookupTable::from_ini_data("rating", "0, 0, 10, 100, 20, 150", 2).unwrap()
    ).unwrap();
    data_cache.tables.insert(
        LookupTable::from_ini_data("grid", "x, 1, 2,  0, 0, 1000,  10, 100, 2000", 3).unwrap()
    ).unwrap();

    let idx = data_cache.get_or_add_new_series("data.stage", true);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(5.0);
    ts.push_value(15.0);
    ts.push_value(50.0);
    data_cache.series[idx] = ts;

    data_cache
}

#[test]
fn test_expression_1d_lookup_over_data_series() {
    let mut data_cache = cache_with_tables();
    let input = DynamicInput::from_string("table.rating(data.stage)", &mut data_cache, true, None)
        .expect("1D table expression should lower");

    match input {
        DynamicInput::Function { .. } => {}
        _ => panic!("Expected Function variant for a table lookup"),
    }

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 50.0);   // interpolated
    data_cache.set_current_step(1);
    assert_eq!(input.get_value(&data_cache), 125.0);  // interpolated
    data_cache.set_current_step(2);
    assert_eq!(input.get_value(&data_cache), 150.0);  // clamped above range
}

#[test]
fn test_expression_2d_lookup_and_arithmetic() {
    let mut data_cache = cache_with_tables();

    // Constant column key, data-driven row key, wrapped in arithmetic
    let input = DynamicInput::from_string("2 * table.grid(2, data.stage) + 1", &mut data_cache, true, None)
        .expect("2D table expression should lower");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 2.0 * 1500.0 + 1.0); // row 5 -> 1500 in column 2
}

#[test]
fn test_expression_constant_argument_table_lookup() {
    // No variables at all: must not be folded away by the constant path,
    // and must still evaluate correctly through the lowered table node.
    let mut data_cache = cache_with_tables();
    let input = DynamicInput::from_string("table.rating(5)", &mut data_cache, true, None)
        .expect("constant-argument table lookup should lower");

    match input {
        DynamicInput::Function { .. } => {}
        _ => panic!("Expected Function variant, not constant folding"),
    }
    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 50.0);
}

#[test]
fn test_expression_table_errors() {
    let mut data_cache = cache_with_tables();

    let err = DynamicInput::from_string("table.nope(1)", &mut data_cache, true, None)
        .err().expect("unknown table should fail");
    assert!(err.contains("Unknown table 'table.nope'"), "got: {}", err);

    let err = DynamicInput::from_string("table.rating(1, 2)", &mut data_cache, true, None)
        .err().expect("1D table with 2 args should fail");
    assert!(err.contains("expects 1 argument"), "got: {}", err);

    let err = DynamicInput::from_string("table.grid(1)", &mut data_cache, true, None)
        .err().expect("2D table with 1 arg should fail");
    assert!(err.contains("expects 2 arguments"), "got: {}", err);

    // A bare (uncalled) table reference must not become a phantom data series
    for expr in ["table.rating", "table.rating[-1, 0.0]", "2 * table.rating + 1"] {
        let err = DynamicInput::from_string(expr, &mut data_cache, true, None)
            .err().unwrap_or_else(|| panic!("bare table reference '{}' should fail", expr));
        assert!(err.contains("must be called"), "got: {}", err);
    }
}

#[test]
fn test_sign_builtin() {
    let mut data_cache = DataCache::new();

    let constant_value = |expr: &str, data_cache: &mut DataCache| -> f64 {
        match DynamicInput::from_string(expr, data_cache, true, None).expect("should parse") {
            DynamicInput::Constant { value, .. } => value,
            _ => panic!("Expected Constant variant for '{}'", expr),
        }
    };

    assert_eq!(constant_value("sign(-3.5)", &mut data_cache), -1.0);
    assert_eq!(constant_value("sign(0)", &mut data_cache), 0.0);
    assert_eq!(constant_value("sign(42)", &mut data_cache), 1.0);

    // 'log' is deliberately not a function — modellers must write the
    // explicit ln or log10.
    assert!(DynamicInput::from_string("log(1)", &mut data_cache, true, None).is_err());
}

// -------------------------------------------------------------------------------------
// End-to-end: a model that drives a node input through a table lookup
// -------------------------------------------------------------------------------------

#[test]
fn test_model_runs_with_table_lookup_expression() {
    // sim.day runs 1..=10 over the simulation; the rating table maps day d to
    // 10*d, so the inflow node's dsflow should be exactly that each step.
    let ini = "\
[kalix]
start = 2020-01-01
end = 2020-01-10

[table.rating]
values = 0, 0,
       10, 100,

[node.in1]
loc = 0, 0
type = inflow
inflow = table.rating(sim.day)

[outputs]
node.in1.dsflow
";
    let io = IniModelIO::new();
    let mut model = io.read_model_string(ini).expect("model should load");
    model.configure().expect("model should configure");
    model.run().expect("model should run");

    let idx = model.data_cache.get_or_add_new_series("node.in1.dsflow", false);
    let values = &model.data_cache.series[idx].values;
    assert_eq!(values.len(), 10, "expected one value per simulated day");
    for (i, v) in values.iter().enumerate() {
        let expected = 10.0 * (i as f64 + 1.0); // day 1 -> 10, ..., day 10 -> 100
        assert_eq!(*v, expected, "day {}: expected {}, got {}", i + 1, expected, v);
    }
}

#[test]
fn test_model_table_round_trip() {
    let ini = "\
[kalix]

[table.rating]
values = 0, 0,
       0.5, 120,
       3, 2200,

[node.g]
loc = 0, 0
type = gauge
";
    let io = IniModelIO::new();
    let model = io.read_model_string(ini).expect("model should load");
    let saved = io.model_to_string(&model);

    // The saved model must still contain the table definition...
    assert!(saved.contains("[table.rating]"), "saved model should keep the table section:\n{}", saved);

    // ...and re-reading it must yield a working, identical table.
    let model2 = io.read_model_string(&saved).expect("saved model should re-load");
    let rating = model2.data_cache.tables.get("rating").expect("rating survives round-trip");
    match rating {
        LookupTable::OneD(t) => assert_eq!(t.lookup(0.25), 60.0),
        LookupTable::TwoD(_) => panic!("expected 1D table after round-trip"),
    }
}
