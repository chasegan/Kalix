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
        let tab = Table::from_csv("./src/tests/example_tables/test_dim_table.csv");
        let level_col = 0;
        let volume_col = 1;
        let area_col = 2;
        assert_eq!(true, tab.is_monotonically_increasing(level_col, volume_col));
        assert_eq!(true, tab.is_monotonically_increasing(volume_col, level_col));
        assert_eq!(true, tab.is_monotonically_increasing(volume_col, area_col))
    }
    {
        let tab = Table::from_csv("./src/tests/example_tables/test_dim_table_2.csv");
        let level_col = 0;
        let volume_col = 1;
        let area_col = 2;
        assert_eq!(true, tab.is_monotonically_increasing(level_col, volume_col));
        assert_eq!(true, tab.is_monotonically_increasing(volume_col, level_col));
        assert_eq!(true, tab.is_monotonically_increasing(volume_col, area_col))
    }
    {
        let tab = Table::from_csv("./src/tests/example_tables/test_dim_table_bad.csv");
        let level_col = 0;
        let volume_col = 1;
        let area_col = 2;
        assert_eq!(false, tab.is_monotonically_increasing(level_col, volume_col));
        assert_eq!(true, tab.is_monotonically_increasing(volume_col, level_col));
        assert_eq!(true, tab.is_monotonically_increasing(volume_col, area_col))
    }
}