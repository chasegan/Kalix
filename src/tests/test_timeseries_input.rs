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
