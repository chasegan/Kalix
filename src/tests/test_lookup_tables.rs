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
    LookupTable::from_ini_data("t", data, 2, false).expect("1D table should parse")
}

fn parse_2d(data: &str, ncols: usize) -> LookupTable {
    LookupTable::from_ini_data("t", data, ncols, false).expect("2D table should parse")
}

fn parse_2d_bilinear(data: &str, ncols: usize) -> LookupTable {
    LookupTable::from_ini_data("t", data, ncols, true).expect("2D bilinear table should parse")
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

fn lookup_2d_bilinear(table: &LookupTable, col_key: f64, row_key: f64) -> f64 {
    match table {
        LookupTable::TwoD(t) => t.lookup_bilinear(col_key, row_key),
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
    assert!(LookupTable::from_ini_data("t", "0, 0, 1", 2, false).is_err());
    // x values not strictly ascending
    assert!(LookupTable::from_ini_data("t", "0, 0, 0, 1", 2, false).is_err());
    assert!(LookupTable::from_ini_data("t", "1, 0, 0, 1", 2, false).is_err());
    // Bad number
    assert!(LookupTable::from_ini_data("t", "0, 0, blah, 1", 2, false).is_err());
    // NaN / inf cells rejected
    assert!(LookupTable::from_ini_data("t", "0, nan, 1, 1", 2, false).is_err());
    assert!(LookupTable::from_ini_data("t", "0, 0, 1, inf", 2, false).is_err());
    // Empty data
    assert!(LookupTable::from_ini_data("t", "  ", 2, false).is_err());
    // Header must be exactly two non-numeric labels
    assert!(LookupTable::from_ini_data("t", "stage, 0, 1, 1", 2, false).is_err());
    // Header with no data rows
    assert!(LookupTable::from_ini_data("t", "stage, flow", 2, false).is_err());
    // ncols below 2
    assert!(LookupTable::from_ini_data("t", "0, 0", 1, false).is_err());
    // bilinear has no meaning on a 1D table
    let err = LookupTable::from_ini_data("t", "0, 0, 1, 1", 2, true).unwrap_err();
    assert!(err.contains("bilinear applies only to 2D tables"), "{}", err);
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
fn test_2d_bilinear_interpolation_and_clamping() {
    let t = parse_2d_bilinear(GRID, 3);

    // Halfway between columns 1 and 2 at row 5:
    // column 1 = 50, column 2 = 1500.
    assert_eq!(lookup_2d_bilinear(&t, 1.5, 5.0), 775.0);

    // Unequal weights on both axes, so a swapped axis, a reversed weight or
    // a fixed half would each give a different number.
    // Row 2.5: column 1 = 25, column 2 = 1250; a quarter of the way across.
    assert_eq!(lookup_2d_bilinear(&t, 1.25, 2.5), 331.25);
    // Row 12: column 1 = 110, column 2 = 2120; three quarters of the way.
    assert_eq!(lookup_2d_bilinear(&t, 1.75, 12.0), 1617.5);

    // Column keys clamp at both ends.
    assert_eq!(lookup_2d_bilinear(&t, -100.0, 5.0), 50.0);
    assert_eq!(lookup_2d_bilinear(&t, 100.0, 5.0), 1500.0);

    // Row keys clamp at both ends.
    assert_eq!(lookup_2d_bilinear(&t, 1.5, -100.0), 500.0);
    assert_eq!(lookup_2d_bilinear(&t, 1.5, 100.0), 1375.0);

    // Both at once: the corner cells.
    assert_eq!(lookup_2d_bilinear(&t, -100.0, -100.0), 0.0);
    assert_eq!(lookup_2d_bilinear(&t, 100.0, 100.0), 2600.0);
}

/// Four value columns with unevenly spaced keys, so the bracket is not
/// always the first pair of columns.
const WIDE: &str = "x,  1,  2,   4,   8,
                    0,  0,  10,  100, 1000,
                    10, 10, 110, 300, 5000";

#[test]
fn test_2d_bilinear_brackets_interior_columns() {
    let t = parse_2d_bilinear(WIDE, 5);

    // Between keys 2 and 4, a quarter of the way; row 5: 60 and 200.
    assert_eq!(lookup_2d_bilinear(&t, 2.5, 5.0), 95.0);
    // Between keys 4 and 8, three quarters of the way; row 0: 100 and 1000.
    assert_eq!(lookup_2d_bilinear(&t, 7.0, 0.0), 775.0);
    // Between keys 1 and 2, clamped below the first row.
    assert_eq!(lookup_2d_bilinear(&t, 1.5, -1.0), 5.0);
}

#[test]
fn test_2d_bilinear_exact_column_key_equals_exact_match_lookup() {
    // At a column key the bilinear lookup takes that column alone, so it
    // returns bit for bit what the exact-match lookup returns. Interpolating
    // from the column before with a weight of one would not: in the first
    // table it gives 0.8999999999999999 for the 0.9 cell, and in the second
    // about -1.4e-17 for the zero cell.
    for (data, ncols, col_keys, row_keys) in [
        ("x, 1, 2, 3,  0, 0.2, 0.9, 5", 4, vec![1.0, 2.0, 3.0], vec![0.0]),
        ("x, 0, 0.1, 1.1,  0, 0.1, 0, 7", 4, vec![0.0, 0.1, 1.1], vec![0.0]),
        (GRID, 3, vec![1.0, 2.0], vec![-5.0, 0.0, 3.3, 10.0, 17.0, 20.0, 99.0]),
        (WIDE, 5, vec![1.0, 2.0, 4.0, 8.0], vec![-1.0, 0.0, 3.3, 10.0, 11.0]),
    ] {
        let exact = parse_2d(data, ncols);
        let bilinear = parse_2d_bilinear(data, ncols);
        for col_key in &col_keys {
            for row_key in &row_keys {
                let expected = lookup_2d(&exact, *col_key, *row_key);
                let got = lookup_2d_bilinear(&bilinear, *col_key, *row_key);
                assert_eq!(got.to_bits(), expected.to_bits(),
                    "table '{}' at ({}, {}): bilinear {} vs exact-match {}", data, col_key, row_key, got, expected);
            }
        }
    }
}

#[test]
fn test_2d_bilinear_nan_gives_nan() {
    // A NaN key gives NaN on either axis, as for 1D tables. This differs
    // from the exact-match lookup, where a NaN column key is a miss and
    // panics (test_2d_nan_column_key_panics).
    let t = parse_2d_bilinear(GRID, 3);
    assert!(lookup_2d_bilinear(&t, f64::NAN, 5.0).is_nan());
    assert!(lookup_2d_bilinear(&t, 1.5, f64::NAN).is_nan());
    assert!(lookup_2d_bilinear(&t, 1.0, f64::NAN).is_nan());
    assert!(lookup_2d_bilinear(&t, 100.0, f64::NAN).is_nan());
    assert!(lookup_2d_bilinear(&t, f64::NAN, f64::NAN).is_nan());

    // Single data row
    let t = parse_2d_bilinear("x, 1, 2, 3,  0, 10, 20, 30", 4);
    assert_eq!(lookup_2d_bilinear(&t, 1.5, 123.0), 15.0);
    assert!(lookup_2d_bilinear(&t, 1.5, f64::NAN).is_nan());
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
    assert!(LookupTable::from_ini_data("t", "0, 1, 2, 0, 0, 0", 3, false).is_err());
    // Element count not a multiple of ncols
    assert!(LookupTable::from_ini_data("t", "x, 1, 2, 0, 0", 3, false).is_err());
    // No data rows after the key row
    assert!(LookupTable::from_ini_data("t", "x, 1, 2", 3, false).is_err());
    // Column keys not strictly ascending
    assert!(LookupTable::from_ini_data("t", "x, 2, 1, 0, 0, 0", 3, false).is_err());
    assert!(LookupTable::from_ini_data("t", "x, 1, 1, 0, 0, 0", 3, false).is_err());
    // Row keys not strictly ascending
    assert!(LookupTable::from_ini_data("t", "x, 1, 2, 5, 0, 0, 5, 1, 1", 3, false).is_err());
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
    let model = IniModelIO::read_model_string(ini).expect("model should load");

    let rating = model.data_cache.tables.get("rating").expect("rating registered");
    assert_eq!(rating.arity(), 1);
    let monthly = model.data_cache.tables.get("monthly_demand").expect("monthly registered");
    assert_eq!(monthly.arity(), 2);
    assert_eq!(monthly.ncols(), 13);
}

#[test]
fn test_model_table_error_cases() {
    let read = |ini: &str| IniModelIO::read_model_string(ini);

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
    // bilinear takes true or false, lowercase
    let err = read("[kalix]\n[table.t]\nn_cols = 3\nbilinear = yes\nvalues = x, 1, 2, 0, 5, 6\n").err().expect("bad bilinear should fail").to_string();
    assert!(err.contains("bilinear for table 't' must be true or false"), "got: {}", err);
    assert!(read("[kalix]\n[table.t]\nn_cols = 3\nbilinear = True\nvalues = x, 1, 2, 0, 5, 6\n").is_err());
    // bilinear = true on a 1D table, in either property order
    let err = read("[kalix]\n[table.t]\nbilinear = true\nvalues = 0, 0, 1, 1\n").err().expect("bilinear on 1D should fail").to_string();
    assert!(err.contains("bilinear applies only to 2D tables"), "got: {}", err);
    assert!(read("[kalix]\n[table.t]\nvalues = 0, 0, 1, 1\nbilinear = true\nn_cols = 2\n").is_err());
    // bilinear = false on a 1D table says nothing untrue and is accepted
    assert!(read("[kalix]\n[table.t]\nbilinear = false\nvalues = 0, 0, 1, 1\n").is_ok());
    // Malformed table body surfaces the table parser's error
    let err = read("[kalix]\n[table.t]\nvalues = 0, 0, 1\n").err().expect("malformed table should fail").to_string();
    assert!(err.contains("table.t"), "error should name the table: {}", err);
}

// -------------------------------------------------------------------------------------
// Expression integration (DynamicInput lowering and evaluation)
// -------------------------------------------------------------------------------------

/// A data cache with a registered 1D table (0->0, 10->100, 20->150), a 2D
/// table (columns 1 and 2 over rows 0/10) and the same 2D table again with
/// `bilinear = true`, plus a data series "data.stage" holding
/// [5.0, 15.0, 50.0].
fn cache_with_tables() -> DataCache {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800); // 2020-01-01
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    data_cache.tables.insert(
        LookupTable::from_ini_data("rating", "0, 0, 10, 100, 20, 150", 2, false).unwrap()
    ).unwrap();
    data_cache.tables.insert(
        LookupTable::from_ini_data("grid", "x, 1, 2,  0, 0, 1000,  10, 100, 2000", 3, false).unwrap()
    ).unwrap();
    data_cache.tables.insert(
        LookupTable::from_ini_data("grid_bilinear", "x, 1, 2,  0, 0, 1000,  10, 100, 2000", 3, true).unwrap()
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
    assert_eq!(input.get_value(&mut data_cache), 50.0);   // interpolated
    data_cache.set_current_step(1);
    assert_eq!(input.get_value(&mut data_cache), 125.0);  // interpolated
    data_cache.set_current_step(2);
    assert_eq!(input.get_value(&mut data_cache), 150.0);  // clamped above range
}

#[test]
fn test_expression_2d_lookup_and_arithmetic() {
    let mut data_cache = cache_with_tables();

    // Constant column key, data-driven row key, wrapped in arithmetic
    let input = DynamicInput::from_string("2 * table.grid(2, data.stage) + 1", &mut data_cache, true, None)
        .expect("2D table expression should lower");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&mut data_cache), 2.0 * 1500.0 + 1.0); // row 5 -> 1500 in column 2
}

#[test]
fn test_expression_2d_bilinear_lookup() {
    let mut data_cache = cache_with_tables();

    // Data-driven row key: row 5 gives 50 and 1500; a quarter of the way across.
    let input = DynamicInput::from_string("table.grid_bilinear(1.25, data.stage)", &mut data_cache, true, None)
        .expect("bilinear 2D table expression should lower");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&mut data_cache), 412.5);
}

#[test]
#[should_panic(expected = "no column with key")]
fn test_expression_2d_lookup_without_bilinear_still_requires_exact_column() {
    // The same grid without `bilinear`: lowering must keep the exact-match
    // lookup, so a column key between two keys stops the run.
    let mut data_cache = cache_with_tables();
    let input = DynamicInput::from_string("table.grid(1.25, data.stage)", &mut data_cache, true, None)
        .expect("2D table expression should lower");

    data_cache.set_current_step(0);
    input.get_value(&mut data_cache);
}

#[test]
fn test_expression_2d_bilinear_lookup_advances_stateful_arguments() {
    // A stateful function inside a bilinear lookup's argument has its state
    // advanced each step: the two-step mean of data.stage is 10 at step 1,
    // where the grid gives 100 and 2000.
    let mut data_cache = cache_with_tables();
    let input = DynamicInput::from_string(
        "table.grid_bilinear(1.5, moving_mean(data.stage, 2, 0))", &mut data_cache, true, None)
        .expect("stateful argument should lower");

    let mut value = f64::NAN;
    for step in 0..2 {
        data_cache.set_current_step(step);
        value = input.get_value(&mut data_cache);
    }
    assert_eq!(value, 1050.0);
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
    assert_eq!(input.get_value(&mut data_cache), 50.0);
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
    let mut model = IniModelIO::read_model_string(ini).expect("model should load");
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
    let model = IniModelIO::read_model_string(ini).expect("model should load");
    let saved = IniModelIO::model_to_string(&model);

    // The saved model must still contain the table definition...
    assert!(saved.contains("[table.rating]"), "saved model should keep the table section:\n{}", saved);

    // ...and re-reading it must yield a working, identical table.
    let model2 = IniModelIO::read_model_string(&saved).expect("saved model should re-load");
    let rating = model2.data_cache.tables.get("rating").expect("rating survives round-trip");
    match rating {
        LookupTable::OneD(t) => assert_eq!(t.lookup(0.25), 60.0),
        LookupTable::TwoD(_) => panic!("expected 1D table after round-trip"),
    }
}

#[test]
fn test_model_bilinear_table_round_trip() {
    let ini = "\
[kalix]

[table.grid]
n_cols = 3
bilinear = true
values = x, 1, 2,
       0, 0, 1000,
       10, 100, 2000,

[table.plain]
n_cols = 3
values = x, 1, 2,
       0, 0, 1000,
";

    let mut model = IniModelIO::read_model_string(ini).expect("model should load");
    assert!(model.data_cache.tables.get("grid").expect("grid registered").is_bilinear());

    // The standard save re-emits an unchanged section from the source text,
    // which would pass whatever the saver wrote. Dropping the source document
    // makes the save a full canonical render, so this checks the saver.
    model.ini_document = None;
    let saved = IniModelIO::model_to_string(&model);
    assert_eq!(saved.matches("bilinear = true").count(), 1,
        "bilinear is written for the bilinear table and only for it:\n{}", saved);

    let model2 = IniModelIO::read_model_string(&saved).expect("saved model should re-load");
    match model2.data_cache.tables.get("grid").expect("grid survives round-trip") {
        LookupTable::TwoD(t) => assert_eq!(t.lookup_bilinear(1.25, 5.0), 412.5),
        LookupTable::OneD(_) => panic!("expected 2D table after round-trip"),
    }
    assert!(!model2.data_cache.tables.get("plain").expect("plain survives round-trip").is_bilinear());
}

#[test]
fn test_lookups_return_the_table_value_at_a_key() {
    // Interpolating to a breakpoint with a weight of one is not exact in
    // doubles: 0.1 + 0.1 * (0 - 0.1) / 0.1 is -1.4e-17. A key that matches
    // must return the cell, so a zero in the table is a zero out of it.
    let t = parse_1d("0, 0.1,  0.1, 0,  1.1, 7");
    assert_eq!(lookup_1d(&t, 0.1).to_bits(), 0.0f64.to_bits());
    let t = parse_1d("0, 0.3,  0.7, 0.9,  2.5, 5");
    assert_eq!(lookup_1d(&t, 0.7).to_bits(), 0.9f64.to_bits());

    // The same on the row axis of both 2D lookups, and on the bilinear
    // column axis, with a cell that interpolation gets wrong.
    let grid = "x, 1, 2, 3,  0, 0.1, 0.2, 0.3,  0.1, 0, 0.9, 0,  1.1, 7, 5, 7";
    let exact = parse_2d(grid, 4);
    let bilinear = parse_2d_bilinear(grid, 4);
    assert_eq!(lookup_2d(&exact, 1.0, 0.1).to_bits(), 0.0f64.to_bits());
    assert_eq!(lookup_2d(&exact, 2.0, 0.1).to_bits(), 0.9f64.to_bits());
    assert_eq!(lookup_2d_bilinear(&bilinear, 1.0, 0.1).to_bits(), 0.0f64.to_bits());
    assert_eq!(lookup_2d_bilinear(&bilinear, 2.0, 0.1).to_bits(), 0.9f64.to_bits());
    assert_eq!(lookup_2d_bilinear(&bilinear, 3.0, 0.1).to_bits(), 0.0f64.to_bits());
    // Between column keys at a row key: the row's cells, interpolated.
    assert_eq!(lookup_2d_bilinear(&bilinear, 1.5, 0.1), 0.45);
}

#[test]
fn test_lookups_stay_within_the_neighbouring_values() {
    // Between two breakpoints the rounded arithmetic can land an ulp past
    // either value. The lookups hold the result between them, so a table
    // of non-negative values never returns a negative number, and a
    // monotone table stays monotone. Decimal keys and values, as typed.
    let mut seed: u64 = 7;
    let mut next = || { seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407); (seed >> 11) as f64 / (1u64 << 53) as f64 };
    let mut outside = 0usize;
    for _ in 0..20_000 {
        let x0 = (next() * 1000.0).round() / 10.0;
        let x1 = x0 + ((next() * 500.0).round() + 1.0) / 100.0;
        let y0 = (next() * 2000.0 - 1000.0).round() / 100.0;
        let y1 = (next() * 2000.0 - 1000.0).round() / 100.0;
        let t = parse_1d(&format!("{}, {}, {}, {}", x0, y0, x1, y1));
        let (lo, hi) = (y0.min(y1), y0.max(y1));
        for x in [x1 - (x1 - x0) * 1e-12, x0 + (x1 - x0) * next(), f64::from_bits(x1.to_bits() - 1)] {
            let y = lookup_1d(&t, x);
            if y < lo || y > hi { outside += 1; }
        }
    }
    assert_eq!(outside, 0, "lookups outside their bracket's values");

    // The same bound on each 2D path: the exact-match lookup down a column,
    // and the bilinear lookup down a column at a column key, across a row
    // at a row key, and in the interior, against its four corner cells.
    let mut outside = [0usize; 4];
    for _ in 0..20_000 {
        let c0 = (next() * 1000.0).round() / 10.0;
        let c1 = c0 + ((next() * 500.0).round() + 1.0) / 100.0;
        let r0 = (next() * 1000.0).round() / 10.0;
        let r1 = r0 + ((next() * 500.0).round() + 1.0) / 100.0;
        let cells: Vec<f64> = (0..4).map(|_| (next() * 2000.0 - 1000.0).round() / 100.0).collect();
        let grid = format!("x, {}, {},  {}, {}, {},  {}, {}, {}", c0, c1, r0, cells[0], cells[1], r1, cells[2], cells[3]);
        let exact = parse_2d(&grid, 3);
        let bilinear = parse_2d_bilinear(&grid, 3);
        let within = |y: f64, vals: &[f64]| {
            let lo = vals.iter().cloned().fold(f64::INFINITY, f64::min);
            let hi = vals.iter().cloned().fold(f64::NEG_INFINITY, f64::max);
            y >= lo && y <= hi
        };
        let row_mid = r0 + (r1 - r0) * next();
        let col_mid = c0 + (c1 - c0) * next();
        for row_key in [row_mid, f64::from_bits(r1.to_bits() - 1)] {
            if !within(lookup_2d(&exact, c0, row_key), &[cells[0], cells[2]]) { outside[0] += 1; }
            if !within(lookup_2d_bilinear(&bilinear, c1, row_key), &[cells[1], cells[3]]) { outside[1] += 1; }
            for col_key in [col_mid, f64::from_bits(c1.to_bits() - 1)] {
                if !within(lookup_2d_bilinear(&bilinear, col_key, r0), &[cells[0], cells[1]]) { outside[2] += 1; }
                if !within(lookup_2d_bilinear(&bilinear, col_key, row_key), &cells) { outside[3] += 1; }
            }
        }
    }
    assert_eq!(outside, [0, 0, 0, 0], "2D lookups outside their cells: [exact-match column, bilinear column, bilinear row, bilinear interior]");

    // The interior overshoot is rare, about one probe in a million above,
    // so two found cases are pinned: without the clamp the first returns
    // -6.870000000000001 against a lowest cell of -6.87, the second
    // 9.940000000000001 against a highest of 9.94.
    let t = parse_2d_bilinear("x, 44.1, 46.65,  1.2, 2.0, -3.17,  3.7199999999999998, -6.87, -6.85", 3);
    let y = lookup_2d_bilinear(&t, f64::from_bits(44.1f64.to_bits() + 1), f64::from_bits(3.7199999999999998f64.to_bits() - 1));
    assert!(y >= -6.87, "got {}", y);
    let t = parse_2d_bilinear("x, 0.7, 3.3099999999999996,  9.4, -7.2, 9.36,  12.82, -7.97, 9.94", 3);
    let y = lookup_2d_bilinear(&t, f64::from_bits(3.3099999999999996f64.to_bits() - 1), f64::from_bits(12.82f64.to_bits() - 1));
    assert!(y <= 9.94, "got {}", y);
    assert!(lookup_1d(&parse_1d("0, 0.1, 0.1, 0, 1.1, 7"), f64::NAN).is_nan());
}

// -------------------------------------------------------------------------------------
// Bracket search across table sizes
// -------------------------------------------------------------------------------------

/// Clamped linear interpolation by a plain scan, with the arithmetic in the
/// lookup's order, so a correct lookup matches it bit for bit.
fn reference_lerp(xs: &[f64], ys: &[f64], x: f64) -> f64 {
    let n = xs.len();
    if x <= xs[0] {
        return ys[0];
    }
    if x >= xs[n - 1] {
        return ys[n - 1];
    }
    let i = (1..n).find(|i| xs[*i] >= x).unwrap();
    if xs[i] == x {
        return ys[i];
    }
    let y = ys[i - 1] + (x - xs[i - 1]) * (ys[i] - ys[i - 1]) / (xs[i] - xs[i - 1]);
    y.max(ys[i - 1].min(ys[i])).min(ys[i - 1].max(ys[i]))
}

/// `n` unevenly spaced ascending keys.
fn sized_keys(n: usize) -> Vec<f64> {
    (0..n).map(|i| i as f64 * 1.5 + (i % 3) as f64 * 0.25).collect()
}

/// Every key, a point either side of it, a point between it and the next, and
/// points beyond both ends.
fn probes(keys: &[f64]) -> Vec<f64> {
    let mut xs = vec![keys[0] - 10.0, keys[keys.len() - 1] + 10.0];
    for (i, k) in keys.iter().enumerate() {
        xs.extend([*k, k - 1e-9, k + 1e-9]);
        if i + 1 < keys.len() {
            xs.push(k + 0.37 * (keys[i + 1] - k));
        }
    }
    xs
}

/// Lookups find their bracket by counting in a short key set and by binary
/// search in a long one. The sizes here sit either side of the widths the
/// count is vectorised in and either side of the cutoff between the two
/// (128 keys), and run well past it.
const BRACKET_SIZES: [usize; 12] = [2, 3, 7, 8, 9, 16, 33, 127, 128, 129, 200, 300];

#[test]
fn test_1d_lookup_matches_reference_at_every_table_size() {
    for n in BRACKET_SIZES {
        let xs = sized_keys(n);
        let ys: Vec<f64> = xs.iter().map(|x| (x * 0.7).sin() * 100.0).collect();
        let data = xs.iter().zip(&ys).map(|(x, y)| format!("{}, {}", x, y)).collect::<Vec<_>>().join(", ");
        let t = parse_1d(&data);
        for x in probes(&xs) {
            let expected = reference_lerp(&xs, &ys, x);
            let got = lookup_1d(&t, x);
            assert_eq!(got.to_bits(), expected.to_bits(), "{} keys at x = {}: got {}, expected {}", n, x, got, expected);
        }
        assert!(lookup_1d(&t, f64::NAN).is_nan());
    }
}

#[test]
fn test_2d_lookups_match_reference_at_every_table_size() {
    // The same key set on both axes, so each size exercises the column
    // search and the row search, for the exact-match and bilinear lookups.
    for n in BRACKET_SIZES {
        let keys = sized_keys(n);
        let cell = |c: usize, r: usize| ((c * 31 + r * 17) % 101) as f64 * 0.5 - 7.0;
        let mut data = format!("x, {}", keys.iter().map(|k| k.to_string()).collect::<Vec<_>>().join(", "));
        for (r, row_key) in keys.iter().enumerate() {
            data += &format!(", {}", row_key);
            for c in 0..n {
                data += &format!(", {}", cell(c, r));
            }
        }
        let exact = parse_2d(&data, n + 1);
        let bilinear = parse_2d_bilinear(&data, n + 1);
        let column = |c: usize| (0..n).map(|r| cell(c, r)).collect::<Vec<f64>>();

        // A few rows are enough to cover the row search at each size.
        let row_probes: Vec<f64> = probes(&keys).into_iter().step_by(1 + n / 8).collect();
        for row_key in &row_probes {
            // Exact-match lookup at every column key
            for (c, col_key) in keys.iter().enumerate() {
                let expected = reference_lerp(&keys, &column(c), *row_key);
                let got = lookup_2d(&exact, *col_key, *row_key);
                assert_eq!(got.to_bits(), expected.to_bits(), "{} keys, exact-match at ({}, {})", n, col_key, row_key);
            }
            // Bilinear lookup: down each column, then across
            let down: Vec<f64> = (0..n).map(|c| reference_lerp(&keys, &column(c), *row_key)).collect();
            for col_key in probes(&keys) {
                let expected = reference_lerp(&keys, &down, col_key);
                let got = lookup_2d_bilinear(&bilinear, col_key, *row_key);
                // The lookup holds its result within the four corner cells
                // where this reference holds each step within its own two
                // values, so the two can differ by an ulp between grid points.
                assert!((got - expected).abs() <= 2.0 * f64::EPSILON * expected.abs().max(1.0),
                    "{} keys, bilinear at ({}, {}): got {}, expected {}", n, col_key, row_key, got, expected);
            }
        }
    }
}
