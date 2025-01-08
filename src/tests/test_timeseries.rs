use crate::timeseries::Timeseries;


/*
Create a timeseries and add values to into it. Then check the sum() of the timeseries.
 */
#[test]
fn test_timeseries_constructor() {
    println!("========== STARTING TEST ==========");
    let mut t = Timeseries::new_daily();
    for i in 0..5 {
        t.push_value((1000 + i) as f64);
    }
    t.print();
    println!("The sum is: {}", t.sum());
    assert_eq!(30, 30);
}


/*
Create a timeseries. Check that the function results are correct.
 */
#[test]
fn test_timeseries_count_finite() {
    let mut what = Timeseries::new_daily();

    what.push(1, 1f64);
    what.push(2, f64::NAN);
    what.push(3, 2f64);
    what.push(4, f64::INFINITY);
    what.push(5, f64::NEG_INFINITY);
    what.push(6, 0f64);

    what.print();
    assert_eq!(what.len(), 6);               // Length of the timeseries including missing values.
    assert_eq!(what.count_not_missing(), 5); // Length of the timeseries excluding missing values.
    assert_eq!(what.count_finite(), 3);      // Missing values are ignored, and infinities are not finite.
    assert_eq!(what.count_nonzero(), 4);     // Missing values are ignored, and infinities count as nonzero.
    assert!(f64::is_nan(what.sum()));        // Missing values cause sum -> NaN
}


/*
Read a CSV file into a timeseries. This one just has 1 column. Check that the values are
correct.
 */
#[test]
fn test_read_csv_values() {
    let what = match crate::io::csv_io::read_ts("./src/tests/example_data/test.csv") {
        Ok(v) => v,
        Err(_) => panic!("Error reading csv."),
    };
    //let what2 = what[0]; //.unwrap();
    assert_eq!(what[0].len(), 6);
    assert_eq!(what[0].count_finite(), 6);
    assert_eq!(what[0].count_nonzero(), 4);
    assert_eq!(what[0].sum(), 38.1);
}
