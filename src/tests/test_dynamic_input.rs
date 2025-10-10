/// Tests for DynamicInput functionality
///
/// This module tests the DynamicInput system which allows model inputs to be
/// constants, data references, or complex function expressions.

use crate::model_inputs::DynamicInput;
use crate::data_cache::DataCache;
use crate::timeseries::Timeseries;
use crate::tid::utils::wrap_to_u64;

#[test]
fn test_dynamic_input_constant() {
    let mut data_cache = DataCache::new();

    // Test simple constant
    let input = DynamicInput::from_string("5.0", &mut data_cache, true)
        .expect("Failed to parse constant");

    // Should be optimized to Constant variant
    match input {
        DynamicInput::Constant { value } => {
            assert_eq!(value, 5.0);
        }
        _ => panic!("Expected Constant variant"),
    }

    assert_eq!(input.get_value(&data_cache), 5.0);
}

#[test]
fn test_dynamic_input_constant_expression() {
    let mut data_cache = DataCache::new();

    // Test constant expression
    let input = DynamicInput::from_string("2 + 3 * 4", &mut data_cache, true)
        .expect("Failed to parse expression");

    // Should be optimized to Constant variant (no variables)
    match input {
        DynamicInput::Constant { value } => {
            assert_eq!(value, 14.0); // 2 + (3 * 4) = 14
        }
        _ => panic!("Expected Constant variant for expression with no variables"),
    }
}

#[test]
fn test_dynamic_input_direct_reference() {
    let mut data_cache = DataCache::new();
    // Use a valid timestamp: 2020-01-01 00:00:00 UTC (wrapped)
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Add some data
    let idx = data_cache.get_or_add_new_series("data.test", true);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(10.0);
    ts.push_value(20.0);
    ts.push_value(30.0);
    data_cache.series[idx] = ts;

    // Test direct reference
    let input = DynamicInput::from_string("data.test", &mut data_cache, true)
        .expect("Failed to parse reference");

    // Should be optimized to DirectReference variant
    match input {
        DynamicInput::DirectReference { idx: _ } => {
            // Correct optimization!
        }
        _ => panic!("Expected DirectReference variant for single variable"),
    }

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 10.0);

    data_cache.set_current_step(1);
    assert_eq!(input.get_value(&data_cache), 20.0);

    data_cache.set_current_step(2);
    assert_eq!(input.get_value(&data_cache), 30.0);
}

#[test]
fn test_dynamic_input_function_expression() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Add test data
    let idx1 = data_cache.get_or_add_new_series("data.temp", true);
    let mut ts1 = Timeseries::new_daily();
    ts1.start_timestamp = start_timestamp;
    ts1.push_value(25.0);
    ts1.push_value(30.0);
    ts1.push_value(15.0);
    data_cache.series[idx1] = ts1;

    let idx2 = data_cache.get_or_add_new_series("data.adjustment", true);
    let mut ts2 = Timeseries::new_daily();
    ts2.start_timestamp = start_timestamp;
    ts2.push_value(0.8);
    ts2.push_value(1.2);
    ts2.push_value(0.9);
    data_cache.series[idx2] = ts2;

    // Test function expression with multiple variables
    let input = DynamicInput::from_string("data.temp * data.adjustment", &mut data_cache, true)
        .expect("Failed to parse function");

    // Should be Function variant (multiple variables)
    match input {
        DynamicInput::Function { .. } => {
            // Correct!
        }
        _ => panic!("Expected Function variant for multi-variable expression"),
    }

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 20.0); // 25.0 * 0.8

    data_cache.set_current_step(1);
    assert_eq!(input.get_value(&data_cache), 36.0); // 30.0 * 1.2

    data_cache.set_current_step(2);
    assert_eq!(input.get_value(&data_cache), 13.5); // 15.0 * 0.9
}

#[test]
fn test_dynamic_input_conditional() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Add temperature data
    let idx = data_cache.get_or_add_new_series("data.temperature", true);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(5.0);
    ts.push_value(15.0);
    ts.push_value(25.0);
    data_cache.series[idx] = ts;

    // Test conditional expression
    let input = DynamicInput::from_string(
        "if(data.temperature > 20, 10.0, 5.0)",
        &mut data_cache,
        true
    ).expect("Failed to parse conditional");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 5.0); // temp=5, not > 20

    data_cache.set_current_step(1);
    assert_eq!(input.get_value(&data_cache), 5.0); // temp=15, not > 20

    data_cache.set_current_step(2);
    assert_eq!(input.get_value(&data_cache), 10.0); // temp=25, > 20
}

#[test]
fn test_dynamic_input_complex_expression() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Add data series with dots in names
    let idx1 = data_cache.get_or_add_new_series("data.rex_rain_csv.by_name.value", true);
    let mut ts1 = Timeseries::new_daily();
    ts1.start_timestamp = start_timestamp;
    ts1.push_value(10.0);
    data_cache.series[idx1] = ts1;

    let idx2 = data_cache.get_or_add_new_series("data.evap.factor", true);
    let mut ts2 = Timeseries::new_daily();
    ts2.start_timestamp = start_timestamp;
    ts2.push_value(1.5);
    data_cache.series[idx2] = ts2;

    // Test complex expression with dotted variable names
    let input = DynamicInput::from_string(
        "max(data.rex_rain_csv.by_name.value * data.evap.factor, 0)",
        &mut data_cache,
        true
    ).expect("Failed to parse complex expression");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 15.0); // max(10.0 * 1.5, 0) = 15.0
}

#[test]
fn test_dynamic_input_none() {
    let mut data_cache = DataCache::new();

    // Test empty string
    let input = DynamicInput::from_string("", &mut data_cache, true)
        .expect("Failed to parse empty");

    // Should be None variant
    match input {
        DynamicInput::None => {
            // Correct!
        }
        _ => panic!("Expected None variant for empty string"),
    }

    assert_eq!(input.get_value(&data_cache), 0.0);
}
