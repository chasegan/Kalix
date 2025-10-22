#[test]
fn test_load() {
    use crate::timeseries_input::TimeseriesInput;
    let vts = match TimeseriesInput::load("./src/tests/example_models/1/rex_mpot.csv") {
        Ok(v) => v,
        Err(e) => panic!("{}", e),
    };

    //Print
    println!("vts[0].timeseries.len() = {}", vts[0].timeseries.len());
    println!("vts[0].len() = {}", vts[0].len());

    // Assert
    let sum = vts[0].timeseries.sum();
    assert!((sum - 251302.61119047567).abs() < 0.00001);
}


#[test]
fn test_load_2() {
    use crate::timeseries_input::TimeseriesInput;
    let vts = match TimeseriesInput::load("./src/tests/example_models/1/rex_rain.csv") {
        Ok(v) => v,
        Err(e) => panic!("{}", e),
    };

    //Print
    println!("vts[0].timeseries.len() = {}", vts[0].timeseries.len());
    println!("vts[0].len() = {}", vts[0].len());

    // Assert
    let sum = vts[0].timeseries.sum();
    println!("sum = {}", sum);
    assert!((sum - 310683.1999999939).abs() < 0.00001);
}

#[test]
fn test_name_sanitization() {
    use crate::timeseries_input::TimeseriesInput;

    // Load a CSV file
    let vts = match TimeseriesInput::load("./src/tests/example_models/1/rex_rain.csv") {
        Ok(v) => v,
        Err(e) => panic!("{}", e),
    };

    // Check that filename is sanitized (dots converted to underscores)
    // "rex_rain.csv" should become "rex_rain_csv"
    assert_eq!(vts[0].source_name, "rex_rain_csv");

    // Check that paths are constructed correctly with sanitization
    // The paths should only contain lowercase a-z, 0-9, underscores, and dots (as delimiters)
    assert!(vts[0].full_colname_path.chars().all(|c|
        c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '.'
    ));
    assert!(vts[0].full_colindex_path.chars().all(|c|
        c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '.'
    ));

    // Print for debugging
    println!("source_name: {}", vts[0].source_name);
    println!("full_colname_path: {}", vts[0].full_colname_path);
    println!("full_colindex_path: {}", vts[0].full_colindex_path);
}
