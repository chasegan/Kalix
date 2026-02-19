use crate::nodes::rainfall_weights::{RainfallWeightHandler, validate_distribution_param};
use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;

#[test]
fn test_is_rainfall_param() {
    assert!(RainfallWeightHandler::is_rainfall_param("rf_bias"));
    assert!(RainfallWeightHandler::is_rainfall_param("rf_d0"));
    assert!(RainfallWeightHandler::is_rainfall_param("rf_d1"));
    assert!(!RainfallWeightHandler::is_rainfall_param("x1"));
    assert!(!RainfallWeightHandler::is_rainfall_param("lztwm"));
}

#[test]
fn test_validate_distribution_param() {
    assert!(validate_distribution_param(0.0).is_ok());
    assert!(validate_distribution_param(0.5).is_ok());
    assert!(validate_distribution_param(1.0).is_ok());
    assert!(validate_distribution_param(-0.1).is_err());
    assert!(validate_distribution_param(1.1).is_err());
}

#[test]
fn test_list_params() {
    let mut data_cache = DataCache::new();

    // Test with linear combination of 2 stations
    let expr = "0.3 * data.rain1 + 0.7 * data.rain2";
    let input = DynamicInput::from_string(expr, &mut data_cache, true, None).unwrap();
    let params = RainfallWeightHandler::list_params(&input);
    assert_eq!(params, vec!["rf_bias", "rf_d0"]);

    // Test with single station (no distribution params)
    let expr2 = "1.0 * data.rain";
    let input2 = DynamicInput::from_string(expr2, &mut data_cache, true, None).unwrap();
    let params2 = RainfallWeightHandler::list_params(&input2);
    assert_eq!(params2, vec!["rf_bias"]);

    // Test with direct reference (no params)
    let expr3 = "data.rain";
    let input3 = DynamicInput::from_string(expr3, &mut data_cache, true, None).unwrap();
    let params3 = RainfallWeightHandler::list_params(&input3);
    assert!(params3.is_empty());
}