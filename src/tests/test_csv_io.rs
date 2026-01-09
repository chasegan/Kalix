use crate::io::csv_io::{read_ts, write_ts};
use std::io::Write;


#[test]
fn test_csv_reader_1() {
    let filename = "./src/tests/example_data/output_31d6f6b4.csv";
    let r = read_ts(filename).unwrap();
    r[0].print();
}


#[test]
fn test_csv_reader_2() {
    let mpot = "./src/tests/example_models/1/rex_mpot.csv";
    let r = read_ts(mpot).unwrap();
    let ts = &r[0];
    println!("len={}", ts.len());
    ts.print();
}


#[test]
fn test_csv_reader_with_missing_data() {
    let mpot = "./src/tests/example_data/formatted_11000A.csv";
    let r = read_ts(mpot).unwrap();
    let ts = &r[0];

    // Verify that we successfully read the timeseries
    assert!(ts.len() > 0, "Timeseries should have data");

    // Count NaN values (missing data)
    let nan_count = ts.values.iter().filter(|v| v.is_nan()).count();

    // We know from the file inspection that there are missing values
    assert!(nan_count > 0, "Expected some NaN values for missing data, found {}", nan_count);

    // Count finite values
    let finite_count = ts.values.iter().filter(|v| v.is_finite()).count();

    // Verify both NaN and finite values exist
    assert!(finite_count > 0, "Should have some finite values");
    assert!(nan_count + finite_count == ts.len(), "All values should be either finite or NaN");

    println!("Successfully read {} values: {} finite, {} missing (NaN)",
             ts.len(), finite_count, nan_count);
}


#[test]
fn test_csv_reader() {
    let filename = "./src/tests/example_data/output_31d6f6b4.csv";
    let r = read_ts(filename);
    match r {
        Ok(vec_of_ts) => {
            let n_series = vec_of_ts.len();
            assert_eq!(n_series, 249); //check that we're reading all the series
            assert_eq!(vec_of_ts[0].name, "Network>Node_001");
            assert_eq!(vec_of_ts[1].name, "Network>Node_002");
            assert_eq!(vec_of_ts[248].name, "Network>Node_249");
            assert_eq!(vec_of_ts[0].len(), 16985);
            assert_eq!(vec_of_ts[248].len(), 16985);
            let mut sum_all = 0.0;
            for ts in vec_of_ts {
                let sum_values: f64 = ts.values.iter().sum();
                sum_all += sum_values;
            }
            assert!((sum_all - 3512804675.4945393).abs() < 0.001);
        },
        Err(e) => panic!("Something went wrong: {:?}", e)
    }
}


#[test]
fn test_csv_writer() {
    let filename = "./src/tests/example_data/output_31d6f6b4.csv";
    let r = read_ts(filename);
    match r {
        Ok(vec_of_ts) => {
            let vec_of_refs = vec_of_ts.iter().collect();
            write_ts("./src/tests/example_data/output_31d6f6b4_EXPORT.csv", vec_of_refs).expect("Something went wrong.");
        },
        Err(e) => panic!("Something went wrong: {:?}", e)
    }
    let r = read_ts("./src/tests/example_data/output_31d6f6b4_EXPORT.csv");
    match r {
        Ok(vec_of_ts) => {
            let n_series = vec_of_ts.len();
            assert_eq!(n_series, 249);
            assert_eq!(vec_of_ts[248].len(), 16985);
            assert_eq!(vec_of_ts[0].name, "Network>Node_001");
            assert_eq!(vec_of_ts[1].len(), 16985);
        },
        Err(e) => panic!("Something went wrong: {:?}", e)
    }
}


#[test]
fn test_csv_reader_trailing_comma_in_header() {
    // Create a temp file with trailing comma in header
    let temp_path = "./src/tests/example_data/temp_trailing_comma.csv";
    {
        let mut file = std::fs::File::create(temp_path).unwrap();
        // Header has trailing comma, data rows don't
        writeln!(file, "Date,col1,col2,col3,").unwrap();
        writeln!(file, "2020-01-01,1.0,2.0,3.0").unwrap();
        writeln!(file, "2020-01-02,4.0,5.0,6.0").unwrap();
    }

    let result = read_ts(temp_path);
    std::fs::remove_file(temp_path).ok(); // Clean up

    let timeseries = result.expect("Should handle trailing comma in header");
    assert_eq!(timeseries.len(), 3, "Should have 3 data columns");
    assert_eq!(timeseries[0].name, "col1");
    assert_eq!(timeseries[1].name, "col2");
    assert_eq!(timeseries[2].name, "col3");
    assert_eq!(timeseries[0].len(), 2);
    assert_eq!(timeseries[0].values[0], 1.0);
    assert_eq!(timeseries[2].values[1], 6.0);
}


#[test]
fn test_csv_reader_trailing_comma_in_header_and_data() {
    // Create a temp file with trailing commas in both header and data
    let temp_path = "./src/tests/example_data/temp_trailing_comma2.csv";
    {
        let mut file = std::fs::File::create(temp_path).unwrap();
        writeln!(file, "Date,col1,col2,").unwrap();
        writeln!(file, "2020-01-01,10.0,20.0,").unwrap();
        writeln!(file, "2020-01-02,30.0,40.0,").unwrap();
    }

    let result = read_ts(temp_path);
    std::fs::remove_file(temp_path).ok(); // Clean up

    let timeseries = result.expect("Should handle trailing commas");
    assert_eq!(timeseries.len(), 2, "Should have 2 data columns");
    assert_eq!(timeseries[0].values[0], 10.0);
    assert_eq!(timeseries[1].values[1], 40.0);
}


#[test]
fn test_csv_reader_whitespace_in_column_names() {
    // Create a temp file with whitespace around column names
    let temp_path = "./src/tests/example_data/temp_whitespace_cols.csv";
    {
        let mut file = std::fs::File::create(temp_path).unwrap();
        // Header has whitespace around column names
        writeln!(file, "Date, col1 , col2,  col3  ").unwrap();
        writeln!(file, "2020-01-01,1.0,2.0,3.0").unwrap();
    }

    let result = read_ts(temp_path);
    std::fs::remove_file(temp_path).ok(); // Clean up

    let timeseries = result.expect("Should handle whitespace in column names");
    assert_eq!(timeseries.len(), 3);
    assert_eq!(timeseries[0].name, "col1", "Should trim leading/trailing whitespace");
    assert_eq!(timeseries[1].name, "col2", "Should trim leading/trailing whitespace");
    assert_eq!(timeseries[2].name, "col3", "Should trim leading/trailing whitespace");
}