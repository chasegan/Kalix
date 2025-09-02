use crate::timeseries_input::TimeseriesInput;

#[test]
fn test_load() {
    let vts = match TimeseriesInput::load("./src/tests/example_models/1/rex_mpot.csv") {
        Ok(v) => v,
        Err(e) => panic!("{}", e),
    };
    let sum = vts[0].timeseries.sum();
    assert!((sum - 251302.61119047567).abs() < 0.00001);
}
