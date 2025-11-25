/// Tests for DynamicInput functionality
///
/// This module tests the DynamicInput system which allows model inputs to be
/// constants, data references, or complex function expressions.

use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::timeseries::Timeseries;
use crate::tid::utils::wrap_to_u64;

#[test]
fn test_dynamic_input_constant() {
    let mut data_cache = DataCache::new();

    // Test simple constant
    let input = DynamicInput::from_string("5.0", &mut data_cache, true)
        .expect("Failed to parse constant");

    // Should be optimised to Constant variant
    match input {
        DynamicInput::Constant { value, .. } => {
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

    // Should be optimised to Constant variant (no variables)
    match input {
        DynamicInput::Constant { value, .. } => {
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

    // Should be optimised to DirectReference variant
    match input {
        DynamicInput::DirectReference { .. } => {
            // Correct optimisation!
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
        DynamicInput::None { .. } => {
            // Correct!
        }
        _ => panic!("Expected None variant for empty string"),
    }

    assert_eq!(input.get_value(&data_cache), 0.0);
}

#[test]
fn test_dynamic_input_case_insensitive_data_references() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Add series with lowercase name
    let idx = data_cache.get_or_add_new_series("data.evap", true);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(5.0);
    data_cache.series[idx] = ts;

    // Reference with CAPITALIZED name should work
    let input = DynamicInput::from_string("data.EVAP * 2", &mut data_cache, true)
        .expect("Failed to parse capitalized reference");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 10.0); // 5.0 * 2
}

#[test]
fn test_dynamic_input_mixed_case_data_references() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Add series
    let idx_a = data_cache.get_or_add_new_series("data.rainfall", true);
    let mut ts_a = Timeseries::new_daily();
    ts_a.start_timestamp = start_timestamp;
    ts_a.push_value(100.0);
    data_cache.series[idx_a] = ts_a;

    let idx_b = data_cache.get_or_add_new_series("data.evap", true);
    let mut ts_b = Timeseries::new_daily();
    ts_b.start_timestamp = start_timestamp;
    ts_b.push_value(20.0);
    data_cache.series[idx_b] = ts_b;

    // Use various capitalizations in expression
    let input = DynamicInput::from_string("data.RAINFALL - data.Evap", &mut data_cache, true)
        .expect("Failed to parse mixed case references");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 80.0); // 100.0 - 20.0
}

#[test]
fn test_dynamic_input_case_insensitive_functions() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Add test data
    let idx_a = data_cache.get_or_add_new_series("data.a", true);
    let mut ts_a = Timeseries::new_daily();
    ts_a.start_timestamp = start_timestamp;
    ts_a.push_value(5.0);
    data_cache.series[idx_a] = ts_a;

    let idx_b = data_cache.get_or_add_new_series("data.b", true);
    let mut ts_b = Timeseries::new_daily();
    ts_b.start_timestamp = start_timestamp;
    ts_b.push_value(3.0);
    data_cache.series[idx_b] = ts_b;

    data_cache.set_current_step(0);

    // Test MAX (uppercase)
    let input_max = DynamicInput::from_string("MAX(data.a, data.b)", &mut data_cache, true)
        .expect("Failed to parse MAX");
    assert_eq!(input_max.get_value(&data_cache), 5.0);

    // Test Max (mixed case)
    let input_max_mixed = DynamicInput::from_string("Max(data.a, data.b)", &mut data_cache, true)
        .expect("Failed to parse Max");
    assert_eq!(input_max_mixed.get_value(&data_cache), 5.0);

    // Test IF (uppercase)
    let input_if = DynamicInput::from_string("IF(data.a > data.b, 10, 20)", &mut data_cache, true)
        .expect("Failed to parse IF");
    assert_eq!(input_if.get_value(&data_cache), 10.0);

    // Test ABS (uppercase)
    let input_abs = DynamicInput::from_string("ABS(-5)", &mut data_cache, true)
        .expect("Failed to parse ABS");
    assert_eq!(input_abs.get_value(&data_cache), 5.0);

    // Test SQRT (uppercase)
    let input_sqrt = DynamicInput::from_string("SQRT(16)", &mut data_cache, true)
        .expect("Failed to parse SQRT");
    assert_eq!(input_sqrt.get_value(&data_cache), 4.0);
}

#[test]
fn test_dynamic_input_direct_constant_reference() {
    let mut data_cache = DataCache::new();

    // Set a constant value
    data_cache.constants.set_value("c.gravity", 9.81);

    // Test direct constant reference
    let input = DynamicInput::from_string("c.gravity", &mut data_cache, true)
        .expect("Failed to parse constant reference");

    // Should be optimised to DirectConstantReference variant
    match input {
        DynamicInput::DirectConstantReference { .. } => {
            // Correct optimisation!
        }
        _ => panic!("Expected DirectConstantReference variant for single constant"),
    }

    assert_eq!(input.get_value(&data_cache), 9.81);
}

#[test]
fn test_dynamic_input_constant_in_expression() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Set a constant
    data_cache.constants.set_value("c.factor", 1.2);

    // Add rainfall data
    let idx = data_cache.get_or_add_new_series("data.rain", true);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(10.0);
    ts.push_value(20.0);
    ts.push_value(30.0);
    data_cache.series[idx] = ts;

    // Test expression mixing constant and data
    let input = DynamicInput::from_string("c.factor * data.rain", &mut data_cache, true)
        .expect("Failed to parse constant expression");

    // Should be Function variant (has both constant and data variable)
    match input {
        DynamicInput::Function { .. } => {
            // Correct!
        }
        _ => panic!("Expected Function variant for expression with constant and data"),
    }

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 12.0); // 1.2 * 10.0

    data_cache.set_current_step(1);
    assert_eq!(input.get_value(&data_cache), 24.0); // 1.2 * 20.0

    data_cache.set_current_step(2);
    assert_eq!(input.get_value(&data_cache), 36.0); // 1.2 * 30.0
}

#[test]
fn test_dynamic_input_multiple_constants() {
    let mut data_cache = DataCache::new();

    // Set multiple constants
    data_cache.constants.set_value("c.a", 5.0);
    data_cache.constants.set_value("c.b", 3.0);

    // Test expression with multiple constants
    let input = DynamicInput::from_string("c.a + c.b", &mut data_cache, true)
        .expect("Failed to parse multi-constant expression");

    // Should be Function variant (multiple variables)
    match input {
        DynamicInput::Function { .. } => {
            // Correct!
        }
        _ => panic!("Expected Function variant for multi-constant expression"),
    }

    assert_eq!(input.get_value(&data_cache), 8.0); // 5.0 + 3.0
}

#[test]
fn test_dynamic_input_constant_conditional() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Set constant threshold
    data_cache.constants.set_value("c.threshold", 20.0);

    // Add temperature data
    let idx = data_cache.get_or_add_new_series("data.temperature", true);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(15.0);
    ts.push_value(25.0);
    ts.push_value(18.0);
    data_cache.series[idx] = ts;

    // Test conditional with constant threshold
    let input = DynamicInput::from_string(
        "if(data.temperature > c.threshold, 1.0, 0.0)",
        &mut data_cache,
        true
    ).expect("Failed to parse conditional with constant");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 0.0); // 15.0 not > 20.0

    data_cache.set_current_step(1);
    assert_eq!(input.get_value(&data_cache), 1.0); // 25.0 > 20.0

    data_cache.set_current_step(2);
    assert_eq!(input.get_value(&data_cache), 0.0); // 18.0 not > 20.0
}

#[test]
fn test_dynamic_input_constant_case_insensitive() {
    let mut data_cache = DataCache::new();

    // Set constant with lowercase
    data_cache.constants.set_value("c.my_constant", 42.0);

    // Reference with mixed case
    let input = DynamicInput::from_string("C.MY_CONSTANT * 2", &mut data_cache, true)
        .expect("Failed to parse mixed case constant");

    assert_eq!(input.get_value(&data_cache), 84.0); // 42.0 * 2
}

#[test]
fn test_dynamic_input_unassigned_constant_registers() {
    let mut data_cache = DataCache::new();

    // Parse expression with constant that hasn't been assigned yet
    let input = DynamicInput::from_string("c.unassigned * 10", &mut data_cache, true)
        .expect("Failed to parse unassigned constant");

    // The constant should be registered (but not assigned)
    assert!(data_cache.constants.len() > 0);

    // Validation should fail
    let validation = data_cache.constants.assert_all_constants_have_assigned_values();
    assert!(validation.is_err());
    assert!(validation.unwrap_err().contains("c.unassigned"));

    // Now assign it
    data_cache.constants.set_value("c.unassigned", 5.0);

    // Validation should now pass
    assert!(data_cache.constants.assert_all_constants_have_assigned_values().is_ok());

    // And the expression should evaluate correctly
    assert_eq!(input.get_value(&data_cache), 50.0); // 5.0 * 10
}

#[test]
fn test_dynamic_input_mixed_case_same_constant() {
    let mut data_cache = DataCache::new();

    // Set a constant
    data_cache.constants.set_value("c.factor", 2.0);

    // Expression uses the same constant with different cases
    // This tests that we don't create duplicate map entries
    let input = DynamicInput::from_string("c.FACTOR + C.Factor + C.factor", &mut data_cache, true)
        .expect("Failed to parse mixed-case constant expression");

    // Should evaluate to 2.0 + 2.0 + 2.0 = 6.0
    // This verifies that all three references resolve to the same constant
    assert_eq!(input.get_value(&data_cache), 6.0);
}

#[test]
fn test_dynamic_input_mixed_case_constant_and_data() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Set constant
    data_cache.constants.set_value("c.multiplier", 3.0);

    // Add data
    let idx = data_cache.get_or_add_new_series("data.value", true);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(10.0);
    data_cache.series[idx] = ts;

    // Use mixed cases for both constant and data variable
    let input = DynamicInput::from_string("C.MULTIPLIER * DATA.VALUE", &mut data_cache, true)
        .expect("Failed to parse mixed-case expression");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 30.0); // 3.0 * 10.0
}

// ============================================================================
// Tests for node.* namespace (referencing other node outputs)
// ============================================================================

#[test]
fn test_dynamic_input_node_direct_reference() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Simulate a node output being recorded (as would happen during simulation)
    let idx = data_cache.get_or_add_new_series("node.upstream.ds_1", false);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(100.0);
    ts.push_value(150.0);
    ts.push_value(200.0);
    data_cache.series[idx] = ts;

    // Test direct reference to node output
    let input = DynamicInput::from_string("node.upstream.ds_1", &mut data_cache, true)
        .expect("Failed to parse node reference");

    // Should be optimised to DirectReference variant
    match input {
        DynamicInput::DirectReference { .. } => {
            // Correct optimisation!
        }
        _ => panic!("Expected DirectReference variant for single node variable"),
    }

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 100.0);

    data_cache.set_current_step(1);
    assert_eq!(input.get_value(&data_cache), 150.0);

    data_cache.set_current_step(2);
    assert_eq!(input.get_value(&data_cache), 200.0);
}

#[test]
fn test_dynamic_input_node_reference_not_critical() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Parse a node reference - should NOT be marked as critical
    let _input = DynamicInput::from_string("node.upstream.ds_1", &mut data_cache, true)
        .expect("Failed to parse node reference");

    // Find the series and verify it's NOT critical
    let idx = data_cache.get_existing_series_idx("node.upstream.ds_1")
        .expect("Series should exist");
    assert!(!data_cache.is_critical[idx], "node.* references should NOT be marked as critical");

    // Now parse a data reference with the same flag - should be critical
    let _input2 = DynamicInput::from_string("data.rainfall", &mut data_cache, true)
        .expect("Failed to parse data reference");

    let idx2 = data_cache.get_existing_series_idx("data.rainfall")
        .expect("Series should exist");
    assert!(data_cache.is_critical[idx2], "data.* references SHOULD be marked as critical");
}

#[test]
fn test_dynamic_input_node_in_expression() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Simulate node outputs
    let idx1 = data_cache.get_or_add_new_series("node.catchment1.dsflow", false);
    let mut ts1 = Timeseries::new_daily();
    ts1.start_timestamp = start_timestamp;
    ts1.push_value(50.0);
    data_cache.series[idx1] = ts1;

    let idx2 = data_cache.get_or_add_new_series("node.catchment2.dsflow", false);
    let mut ts2 = Timeseries::new_daily();
    ts2.start_timestamp = start_timestamp;
    ts2.push_value(30.0);
    data_cache.series[idx2] = ts2;

    // Test expression combining two node outputs
    let input = DynamicInput::from_string(
        "node.catchment1.dsflow + node.catchment2.dsflow",
        &mut data_cache,
        true
    ).expect("Failed to parse node expression");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 80.0); // 50.0 + 30.0
}

#[test]
fn test_dynamic_input_node_and_data_mixed() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Add input data (critical)
    let idx1 = data_cache.get_or_add_new_series("data.evap_factor", true);
    let mut ts1 = Timeseries::new_daily();
    ts1.start_timestamp = start_timestamp;
    ts1.push_value(0.8);
    data_cache.series[idx1] = ts1;

    // Simulate node output (not critical)
    let idx2 = data_cache.get_or_add_new_series("node.upstream.dsflow", false);
    let mut ts2 = Timeseries::new_daily();
    ts2.start_timestamp = start_timestamp;
    ts2.push_value(100.0);
    data_cache.series[idx2] = ts2;

    // Test expression mixing data and node references
    let input = DynamicInput::from_string(
        "node.upstream.dsflow * data.evap_factor",
        &mut data_cache,
        true
    ).expect("Failed to parse mixed expression");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 80.0); // 100.0 * 0.8
}

#[test]
fn test_dynamic_input_node_with_constant() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Set a constant
    data_cache.constants.set_value("c.loss_factor", 0.1);

    // Simulate node output
    let idx = data_cache.get_or_add_new_series("node.catchment.dsflow", false);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(1000.0);
    data_cache.series[idx] = ts;

    // Test expression with constant and node reference
    let input = DynamicInput::from_string(
        "node.catchment.dsflow * (1 - c.loss_factor)",
        &mut data_cache,
        true
    ).expect("Failed to parse node + constant expression");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 900.0); // 1000.0 * 0.9
}

#[test]
fn test_dynamic_input_node_conditional() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Simulate node output (storage volume)
    let idx = data_cache.get_or_add_new_series("node.reservoir.volume", false);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(5000.0);  // Below threshold
    ts.push_value(15000.0); // Above threshold
    data_cache.series[idx] = ts;

    // Test conditional based on node output
    let input = DynamicInput::from_string(
        "if(node.reservoir.volume > 10000, 100, 50)",
        &mut data_cache,
        true
    ).expect("Failed to parse node conditional");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 50.0); // 5000 not > 10000

    data_cache.set_current_step(1);
    assert_eq!(input.get_value(&data_cache), 100.0); // 15000 > 10000
}

#[test]
fn test_dynamic_input_node_case_insensitive() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Add series with lowercase name
    let idx = data_cache.get_or_add_new_series("node.mynode.dsflow", false);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(42.0);
    data_cache.series[idx] = ts;

    // Reference with mixed case should work
    let input = DynamicInput::from_string("NODE.MyNode.DSFlow", &mut data_cache, true)
        .expect("Failed to parse mixed case node reference");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 42.0);
}

#[test]
fn test_dynamic_input_node_creates_series_if_not_exists() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Parse a node reference before any data exists
    // This simulates parsing during INI load, before nodes initialize
    let _input = DynamicInput::from_string("node.future_node.ds_1", &mut data_cache, true)
        .expect("Failed to parse node reference");

    // The series should have been created
    let idx = data_cache.get_existing_series_idx("node.future_node.ds_1");
    assert!(idx.is_some(), "Series should be created for node reference");

    // And it should NOT be marked as critical
    assert!(!data_cache.is_critical[idx.unwrap()], "node.* should not be critical");
}

// ============================================================================
// Tests for temporal offset syntax [offset, default]
// ============================================================================

#[test]
fn test_dynamic_input_offset_zero_same_as_direct() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Add test data
    let idx = data_cache.get_or_add_new_series("node.upstream.ds_1", false);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(100.0);
    ts.push_value(200.0);
    ts.push_value(300.0);
    data_cache.series[idx] = ts;

    // Parse with offset [0, 0.0] - should optimize to DirectReference (same as no offset)
    let input = DynamicInput::from_string("node.upstream.ds_1[0, 0.0]", &mut data_cache, true)
        .expect("Failed to parse offset reference");

    // Should be DirectReference (optimized away the [0, default])
    match input {
        DynamicInput::DirectReference { .. } => {
            // Correct - offset 0 optimizes to DirectReference
        }
        _ => panic!("Expected DirectReference for [0, default] offset"),
    }

    data_cache.set_current_step(2);
    assert_eq!(input.get_value(&data_cache), 300.0);
}

#[test]
fn test_dynamic_input_offset_previous_timestep() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Add test data - simulating node output over 5 days
    let idx = data_cache.get_or_add_new_series("node.storage.volume", false);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(1000.0); // Day 0
    ts.push_value(1100.0); // Day 1
    ts.push_value(1200.0); // Day 2
    ts.push_value(1300.0); // Day 3
    ts.push_value(1400.0); // Day 4
    data_cache.series[idx] = ts;

    // Parse with offset [1, 0.0] - yesterday's value, default 0.0
    let input = DynamicInput::from_string("node.storage.volume[1, 0.0]", &mut data_cache, true)
        .expect("Failed to parse offset reference");

    // Should be DirectReferenceWithOffset
    match &input {
        DynamicInput::DirectReferenceWithOffset { offset, default_value, .. } => {
            assert_eq!(*offset, 1);
            assert_eq!(*default_value, 0.0);
        }
        _ => panic!("Expected DirectReferenceWithOffset for [1, 0.0] offset"),
    }

    // Test at different timesteps
    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 0.0); // Default because no previous value

    data_cache.set_current_step(1);
    assert_eq!(input.get_value(&data_cache), 1000.0); // Yesterday was Day 0

    data_cache.set_current_step(2);
    assert_eq!(input.get_value(&data_cache), 1100.0); // Yesterday was Day 1

    data_cache.set_current_step(4);
    assert_eq!(input.get_value(&data_cache), 1300.0); // Yesterday was Day 3
}

#[test]
fn test_dynamic_input_offset_with_nonzero_default() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Add test data
    let idx = data_cache.get_or_add_new_series("node.reservoir.volume", false);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(5000.0);
    ts.push_value(6000.0);
    data_cache.series[idx] = ts;

    // Parse with offset [1, 5000.0] - default to initial volume
    let input = DynamicInput::from_string("node.reservoir.volume[1, 5000.0]", &mut data_cache, true)
        .expect("Failed to parse offset reference");

    // At step 0, no yesterday - should return default 5000.0
    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 5000.0);

    // At step 1, yesterday was 5000.0
    data_cache.set_current_step(1);
    assert_eq!(input.get_value(&data_cache), 5000.0);
}

#[test]
fn test_dynamic_input_offset_multiple_days() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Add test data
    let idx = data_cache.get_or_add_new_series("data.rainfall", true);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    for i in 0..10 {
        ts.push_value((i * 10) as f64);
    }
    data_cache.series[idx] = ts;

    // Parse with offset [3, -999.0] - value from 3 days ago, obvious default
    let input = DynamicInput::from_string("data.rainfall[3, -999.0]", &mut data_cache, true)
        .expect("Failed to parse offset reference");

    data_cache.set_current_step(5);
    assert_eq!(input.get_value(&data_cache), 20.0); // 3 days ago was step 2 (value=20)

    data_cache.set_current_step(9);
    assert_eq!(input.get_value(&data_cache), 60.0); // 3 days ago was step 6 (value=60)

    // Edge case: offset exceeds current step - returns default
    data_cache.set_current_step(2);
    assert_eq!(input.get_value(&data_cache), -999.0); // Can't go back 3 days from day 2
}

#[test]
fn test_dynamic_input_offset_in_expression() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Add test data for current flow
    let idx1 = data_cache.get_or_add_new_series("node.dam.dsflow", false);
    let mut ts1 = Timeseries::new_daily();
    ts1.start_timestamp = start_timestamp;
    ts1.push_value(100.0);
    ts1.push_value(150.0);
    ts1.push_value(200.0);
    data_cache.series[idx1] = ts1;

    // Expression: current flow minus yesterday's flow (change in flow)
    // Default to current flow so day 0 shows 0 change
    let input = DynamicInput::from_string(
        "node.dam.dsflow - node.dam.dsflow[1, 100.0]",
        &mut data_cache,
        true
    ).expect("Failed to parse expression with offset");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 0.0); // 100 - 100 (default)

    data_cache.set_current_step(1);
    assert_eq!(input.get_value(&data_cache), 50.0); // 150 - 100

    data_cache.set_current_step(2);
    assert_eq!(input.get_value(&data_cache), 50.0); // 200 - 150
}

#[test]
fn test_dynamic_input_offset_in_conditional() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    // Add storage volume data (increasing)
    let idx = data_cache.get_or_add_new_series("node.reservoir.volume", false);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(5000.0);
    ts.push_value(6000.0);
    ts.push_value(5500.0); // Decreased
    ts.push_value(7000.0); // Increased
    data_cache.series[idx] = ts;

    // Expression: if volume increased compared to yesterday, return 1, else 0
    // Default yesterday to 0 so first day always shows "increased"
    let input = DynamicInput::from_string(
        "if(node.reservoir.volume > node.reservoir.volume[1, 0.0], 1, 0)",
        &mut data_cache,
        true
    ).expect("Failed to parse conditional with offset");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), 1.0); // 5000 > 0 (default)

    data_cache.set_current_step(1);
    assert_eq!(input.get_value(&data_cache), 1.0); // 6000 > 5000 (increased)

    data_cache.set_current_step(2);
    assert_eq!(input.get_value(&data_cache), 0.0); // 5500 not > 6000 (decreased)

    data_cache.set_current_step(3);
    assert_eq!(input.get_value(&data_cache), 1.0); // 7000 > 5500 (increased)
}

#[test]
fn test_dynamic_input_offset_constant_not_supported() {
    let mut data_cache = DataCache::new();

    // Set a constant
    data_cache.constants.set_value("c.threshold", 100.0);

    // Offset on constants should fail
    let result = DynamicInput::from_string("c.threshold[1, 0.0]", &mut data_cache, true);
    assert!(result.is_err(), "Offset syntax should not be supported for constants");
    assert!(result.unwrap_err().contains("not supported for constants"));
}

#[test]
fn test_dynamic_input_offset_requires_default() {
    let mut data_cache = DataCache::new();
    data_cache.get_or_add_new_series("data.test", true);

    // Missing default should fail at parse time
    let result = DynamicInput::from_string("data.test[1]", &mut data_cache, true);
    assert!(result.is_err(), "Offset syntax should require default value");
    assert!(result.unwrap_err().contains("requires default value"));
}

#[test]
fn test_dynamic_input_offset_negative_default() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    let idx = data_cache.get_or_add_new_series("data.temp", true);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(20.0);
    data_cache.series[idx] = ts;

    // Negative default value should work (e.g., for temperatures)
    let input = DynamicInput::from_string("data.temp[1, -10.0]", &mut data_cache, true)
        .expect("Failed to parse offset with negative default");

    data_cache.set_current_step(0);
    assert_eq!(input.get_value(&data_cache), -10.0); // Returns negative default
}

#[test]
fn test_dynamic_input_offset_nan_default() {
    let mut data_cache = DataCache::new();
    let start_timestamp: u64 = wrap_to_u64(1577836800);
    data_cache.initialize(start_timestamp);
    data_cache.set_start_and_stepsize(start_timestamp, 86400);

    let idx = data_cache.get_or_add_new_series("data.flow", true);
    let mut ts = Timeseries::new_daily();
    ts.start_timestamp = start_timestamp;
    ts.push_value(100.0);
    ts.push_value(200.0);
    data_cache.series[idx] = ts;

    // NaN default - makes missing data explicit in output
    let input = DynamicInput::from_string("data.flow[1, nan]", &mut data_cache, true)
        .expect("Failed to parse offset with nan default");

    data_cache.set_current_step(0);
    assert!(input.get_value(&data_cache).is_nan()); // Returns NaN default

    data_cache.set_current_step(1);
    assert_eq!(input.get_value(&data_cache), 100.0); // Normal lookback works

    // Also test case-insensitive: NaN, NAN
    let input2 = DynamicInput::from_string("data.flow[1, NaN]", &mut data_cache, true)
        .expect("Failed to parse offset with NaN default");
    data_cache.set_current_step(0);
    assert!(input2.get_value(&data_cache).is_nan());

    let input3 = DynamicInput::from_string("data.flow[1, NAN]", &mut data_cache, true)
        .expect("Failed to parse offset with NAN default");
    data_cache.set_current_step(0);
    assert!(input3.get_value(&data_cache).is_nan());
}
