use crate::numerical::table::Table;

/// Manually build a table with 2 columns and then do a bunch of
/// interpolations within the table, including between and on the
/// end values but not outside the table range.
#[test]
fn test_interpolation_table() {
    let mut tab = Table::new(2);
    let col =0;
    tab.set_value(0, col, 5.0);
    tab.set_value(1, col, 7.0);
    tab.set_value(2, col, 7.5);
    tab.set_value(3, col, 10.0);
    tab.set_value(4, col, 20.0);

    let col = 1;
    tab.set_value(0, col, 0.0);
    tab.set_value(1, col, 9.0);
    tab.set_value(2, col, 9.1);
    tab.set_value(3, col, 10.0);
    tab.set_value(4, col, 100.0);

    //tab.print();
    assert!(f64::is_nan(tab.interpolate(0, 1, 4.999)));
    assert!(f64::is_nan(tab.interpolate(0, 1, 20.0001)));
    assert_eq!(tab.interpolate(0, 1, 5.0), 0.0);
    assert_eq!(tab.interpolate(0, 1, 6.0), 4.5);
    assert_eq!(tab.interpolate(0, 1, 10.0), 10.0);
    assert_eq!(tab.interpolate(0, 1, 19.0), 91.0);
    assert_eq!(tab.interpolate(0, 1, 20.0), 100.0);
}



/// Reading tables from CSV, and checking if the relationships
/// are monotonic.
#[test]
fn test_table_is_monotonic() {
    {
        let tab = Table::from_csv_file("./src/tests/example_tables/test_dim_table.csv");
        let level_col = 0;
        let volume_col = 1;
        let area_col = 2;
        assert_eq!(false, tab.assert_monotonically_increasing(level_col, volume_col).is_err());
        assert_eq!(false, tab.assert_monotonically_increasing(volume_col, level_col).is_err());
        assert_eq!(false, tab.assert_monotonically_increasing(volume_col, area_col).is_err())
    }
    {
        let tab = Table::from_csv_file("./src/tests/example_tables/test_dim_table_2.csv");
        let level_col = 0;
        let volume_col = 1;
        let area_col = 2;
        assert_eq!(false, tab.assert_monotonically_increasing(level_col, volume_col).is_err());
        assert_eq!(false, tab.assert_monotonically_increasing(volume_col, level_col).is_err());
        assert_eq!(false, tab.assert_monotonically_increasing(volume_col, area_col).is_err())
    }
    {
        let tab = Table::from_csv_file("./src/tests/example_tables/test_dim_table_bad.csv");
        let level_col = 0;
        let volume_col = 1;
        let area_col = 2;
        assert_eq!(true, tab.assert_monotonically_increasing(level_col, volume_col).is_err());
        assert_eq!(false, tab.assert_monotonically_increasing(volume_col, level_col).is_err());
        assert_eq!(false, tab.assert_monotonically_increasing(volume_col, area_col).is_err())
    }
}


/// The slope check must tolerate floating-point rounding on a segment whose
/// slope is exactly 1:1. Here inflow 1 -> 4 with loss 0.7 -> 3.7 keeps the
/// outflow (inflow - loss) flat at 0.3, but in binary the two differences round
/// to 0.3000...004 and 0.2999...998; a naive strict comparison would wrongly
/// report the outflow decreasing. Taken from a real loss-node model that failed.
#[test]
fn test_slope_not_exceeding_one_fp_rounding() {
    let rows = [
        (0.0, 0.0),
        (1.0, 0.7),
        (4.0, 3.7),
        (20.0, 15.0),
        (40.0, 30.0),
        (80.0, 40.0),
        (100.0, 40.0),
    ];
    let mut tab = Table::new(2);
    for (i, (inflow, loss)) in rows.iter().enumerate() {
        tab.set_value(i, 0, *inflow);
        tab.set_value(i, 1, *loss);
    }
    // Exactly-1:1 segment: legal, must pass despite floating-point noise.
    assert_eq!(false, tab.assert_slope_not_exceeding_one(0, 1).is_err());

    // A genuine slope > 1:1 (outflow actually decreases) must still be rejected:
    // inflow 4 -> 5 but loss 3.7 -> 5.0, so outflow drops 0.3 -> 0.0.
    let mut bad = Table::new(2);
    bad.set_value(0, 0, 0.0);  bad.set_value(0, 1, 0.0);
    bad.set_value(1, 0, 4.0);  bad.set_value(1, 1, 3.7);
    bad.set_value(2, 0, 5.0);  bad.set_value(2, 1, 5.0);
    assert_eq!(true, bad.assert_slope_not_exceeding_one(0, 1).is_err());
}