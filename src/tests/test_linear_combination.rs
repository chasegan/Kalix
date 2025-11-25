#[cfg(test)]
mod tests {
    use crate::model_inputs::DynamicInput;
    use crate::model_inputs::linear_combination::{detect_linear_combination, compute_symmetric_weights, logit};
    use crate::data_management::data_cache::DataCache;
    use crate::nodes::gr4j_node::Gr4jNode;
    use crate::nodes::sacramento_node::SacramentoNode;
    use crate::nodes::rainfall_weights::RainfallWeightHandler;
    use crate::nodes::Node;
    use crate::numerical::opt::optimisable_component::OptimisableComponent;
    use crate::functions::parse_function;
    use crate::functions::ast::ExpressionNode;

    #[test]
    fn test_detect_linear_combination_patterns() {
        // Test various linear combination patterns
        let test_cases = vec![
            ("0.2 * data.rain1 + 0.8 * data.rain2", true, vec![0.2, 0.8]),
            ("data.rain1 + data.rain2", true, vec![1.0, 1.0]),
            ("data.rain1 * 0.5 + data.rain2", true, vec![0.5, 1.0]),
            ("0.3 * data.rain1 + 0.4 * data.rain2 + 0.3 * data.rain3", true, vec![0.3, 0.4, 0.3]),
            ("1.0 * data.rain", true, vec![1.0]), // Explicit coefficient - linear combination
            ("2.5 * data.rain", true, vec![2.5]), // Explicit coefficient - linear combination
            ("data.rain * 0.7", true, vec![0.7]), // Explicit coefficient (reversed) - linear combination
            ("data.rain", false, vec![]), // Single variable without coefficient - direct reference
            ("sin(data.rain)", false, vec![]), // Function call, not linear
            ("data.rain1 * data.rain2", false, vec![]), // Product of data, not linear
        ];

        for (expr, should_detect, expected_coeffs) in test_cases {
            let parsed = parse_function(expr).unwrap();
            let ast = parsed.get_ast();
            let expr_node = (ast as &dyn std::any::Any)
                .downcast_ref::<ExpressionNode>()
                .unwrap();

            let result = detect_linear_combination(expr_node);

            if should_detect {
                assert!(result.is_some(), "Failed to detect linear combination in: {}", expr);
                let info = result.unwrap();
                assert_eq!(info.coefficients.len(), expected_coeffs.len());
                for (actual, expected) in info.coefficients.iter().zip(expected_coeffs.iter()) {
                    assert!((actual - expected).abs() < 1e-10,
                           "Coefficient mismatch in {}: got {}, expected {}",
                           expr, actual, expected);
                }
            } else {
                assert!(result.is_none(), "Incorrectly detected linear combination in: {}", expr);
            }
        }
    }

    #[test]
    fn test_symmetric_weight_computation() {
        // Test that when all u_params = 0.5, weights are equal (softmax of zeros is uniform)
        // For 3 stations, we need 2 u_params
        let u_params = vec![0.5, 0.5];  // n-1 parameters for n stations
        let coefficients = vec![0.2, 0.5, 0.3];  // Only used to determine n, not for weighting
        let bias = 2.0;

        let weights = compute_symmetric_weights(&u_params, &coefficients, bias);

        // When all u_params = 0.5 (logit(0.5) = 0), all w_i = 0
        // Softmax of all zeros gives equal distribution: each weight = bias / n
        let expected_weight = bias / 3.0;  // 2.0 / 3 ≈ 0.6667
        assert!((weights[0] - expected_weight).abs() < 1e-6);
        assert!((weights[1] - expected_weight).abs() < 1e-6);
        assert!((weights[2] - expected_weight).abs() < 1e-6);

        // All weights should be equal when u_params = 0.5
        assert!((weights[0] - weights[1]).abs() < 1e-6);
        assert!((weights[1] - weights[2]).abs() < 1e-6);

        // Sum should equal bias
        let weight_sum: f64 = weights.iter().sum();
        assert!((weight_sum - bias).abs() < 1e-6);
    }

    #[test]
    fn test_symmetric_weight_extremes() {
        // Test extreme u_params values
        // For 2 stations, we need 1 u_param (station 0 is reference, u_param controls station 1)
        let coefficients = vec![1.0, 1.0];
        let bias = 1.0;

        // When u_param is very low (0.01), station 1 gets much less weight than reference
        let u_params_low = vec![0.01];  // Single param for 2 stations
        let weights_low = compute_symmetric_weights(&u_params_low, &coefficients, bias);
        assert!(weights_low[1] < weights_low[0] * 0.1);  // Station 1 < 10% of station 0

        // When u_param is very high (0.99), station 1 gets much more weight than reference
        let u_params_high = vec![0.99];  // Single param for 2 stations
        let weights_high = compute_symmetric_weights(&u_params_high, &coefficients, bias);
        assert!(weights_high[1] > weights_high[0] * 10.0);  // Station 1 > 10x station 0
    }

    #[test]
    fn test_dynamic_input_linear_combination_creation() {
        let mut data_cache = DataCache::new();

        // Test creation of LinearCombination from expression with multiple terms
        let expr = "0.3 * data.rain_station1 + 0.7 * data.rain_station2";
        let input = DynamicInput::from_string(expr, &mut data_cache, true).unwrap();

        // Verify it created a LinearCombination variant
        match input {
            DynamicInput::LinearCombination { coefficients, u_params, bias, .. } => {
                assert_eq!(coefficients.len(), 2);
                assert!((coefficients[0] - 0.3).abs() < 1e-10);
                assert!((coefficients[1] - 0.7).abs() < 1e-10);
                assert_eq!(u_params, vec![0.5]); // n-1 params for n stations (2 stations -> 1 param)
                assert_eq!(bias, 1.0); // Default bias
            },
            _ => panic!("Expected LinearCombination variant, got {:?}", input),
        }
    }

    #[test]
    fn test_single_term_with_coefficient_creates_linear_combination() {
        let mut data_cache = DataCache::new();

        // Test that "1.0 * data.rain" creates a LinearCombination
        let expr = "1.0 * data.rain";
        let input = DynamicInput::from_string(expr, &mut data_cache, true).unwrap();

        match input {
            DynamicInput::LinearCombination { coefficients, u_params, bias, .. } => {
                assert_eq!(coefficients.len(), 1);
                assert!((coefficients[0] - 1.0).abs() < 1e-10);
                assert_eq!(u_params, Vec::<f64>::new()); // No distribution params for single term
                assert_eq!(bias, 1.0); // Default bias
            },
            _ => panic!("Expected LinearCombination for '1.0 * data.rain', got {:?}", input),
        }

        // Also test with a non-unity coefficient
        let expr2 = "2.5 * data.rain";
        let input2 = DynamicInput::from_string(expr2, &mut data_cache, true).unwrap();

        match input2 {
            DynamicInput::LinearCombination { coefficients, .. } => {
                assert_eq!(coefficients.len(), 1);
                assert!((coefficients[0] - 2.5).abs() < 1e-10);
            },
            _ => panic!("Expected LinearCombination for '2.5 * data.rain'"),
        }
    }

    #[test]
    fn test_bare_variable_creates_direct_reference() {
        let mut data_cache = DataCache::new();

        // Test that bare "data.rain" creates a DirectReference, not LinearCombination
        let expr = "data.rain";
        let input = DynamicInput::from_string(expr, &mut data_cache, true).unwrap();

        match input {
            DynamicInput::DirectReference { .. } => {
                // Good, it's a direct reference as expected
            },
            _ => panic!("Expected DirectReference for bare 'data.rain', got {:?}", input),
        }
    }

    #[test]
    fn test_dynamic_input_linear_combination_evaluation() {
        use crate::tid::utils::wrap_to_u64;

        let mut data_cache = DataCache::new();

        // Initialize timestamps (use 2020-01-01 00:00:00 UTC)
        let start_timestamp: u64 = wrap_to_u64(1577836800);
        data_cache.initialize(start_timestamp);
        data_cache.set_start_and_stepsize(start_timestamp, 86400);

        // Create a linear combination
        // Note: coefficients are preserved as-is from parsing. The update_weights()
        // function must be called explicitly to recalculate from u_params and bias.
        let expr = "0.2 * data.rain1 + 0.8 * data.rain2";
        let input = DynamicInput::from_string(expr, &mut data_cache, true).unwrap();

        // Add test data to cache
        let idx1 = data_cache.get_or_add_new_series("data.rain1", true);
        let idx2 = data_cache.get_or_add_new_series("data.rain2", true);

        // Set up values
        data_cache.add_value_at_index(idx1, 10.0);
        data_cache.add_value_at_index(idx2, 20.0);
        data_cache.set_current_step(0);

        // Evaluate - uses original coefficients (0.2 and 0.8)
        let value = input.get_value(&data_cache);
        let expected = 0.2 * 10.0 + 0.8 * 20.0;  // = 2 + 16 = 18
        assert!((value - expected).abs() < 1e-6, "Got {}, expected {}", value, expected);
    }

    #[test]
    fn test_gr4j_node_rainfall_parameters() {
        let mut gr4j = Gr4jNode::new_named("test_gr4j");
        let mut data_cache = DataCache::new();

        // Set up rainfall as linear combination
        let expr = "0.25 * data.rain1 + 0.75 * data.rain2";
        gr4j.rain_mm_input = DynamicInput::from_string(expr, &mut data_cache, true).unwrap();

        // Initialize the node
        gr4j.initialise(&mut data_cache).unwrap();

        // Check that rainfall parameters are listed
        // For 2 stations, we should have rf_bias and rf_d0 (n-1 = 1 distribution param)
        let params = gr4j.list_params();
        assert!(params.contains(&"rf_bias".to_string()));
        assert!(params.contains(&"rf_d0".to_string()));
        assert!(!params.contains(&"rf_d1".to_string())); // Should NOT have rf_d1

        // Test setting and getting rf_bias
        gr4j.set_param("rf_bias", 1.5).unwrap();
        assert_eq!(gr4j.get_param("rf_bias").unwrap(), 1.5);

        // Test setting and getting rf_d parameters (only rf_d0 exists)
        gr4j.set_param("rf_d0", 0.3).unwrap();
        assert_eq!(gr4j.get_param("rf_d0").unwrap(), 0.3);

        // rf_d1 should not exist for 2 stations
        assert!(gr4j.set_param("rf_d1", 0.7).is_err());
        assert!(gr4j.get_param("rf_d1").is_err());

        // Test bounds checking for rf_d0
        assert!(gr4j.set_param("rf_d0", -0.1).is_err()); // Below 0
        assert!(gr4j.set_param("rf_d0", 1.1).is_err());  // Above 1
    }

    #[test]
    fn test_gr4j_node_non_linear_combination() {
        let mut gr4j = Gr4jNode::new_named("test_gr4j");
        let mut data_cache = DataCache::new();

        // Set up rainfall as simple data reference (not linear combination)
        gr4j.rain_mm_input = DynamicInput::from_string("data.rain", &mut data_cache, true).unwrap();

        // Initialize the node
        gr4j.initialise(&mut data_cache).unwrap();

        // Check that rainfall parameters are NOT listed
        let params = gr4j.list_params();
        assert!(!params.contains(&"rf_bias".to_string()));
        assert!(!params.contains(&"rf_d0".to_string()));

        // Trying to access rainfall parameters should fail
        assert!(gr4j.get_param("rf_bias").is_err());
        assert!(gr4j.set_param("rf_bias", 1.5).is_err());
    }

    #[test]
    fn test_sacramento_node_rainfall_parameters() {
        let mut sacramento = SacramentoNode::new_named("test_sacramento");
        let mut data_cache = DataCache::new();

        // Set up rainfall as linear combination
        let expr = "0.4 * data.rain1 + 0.6 * data.rain2";
        sacramento.rain_mm_input = DynamicInput::from_string(expr, &mut data_cache, true).unwrap();

        // Initialize the node
        sacramento.initialise(&mut data_cache).unwrap();

        // Check that rainfall parameters are listed along with Sacramento parameters
        let params = sacramento.list_params();
        assert!(params.contains(&"rf_bias".to_string()));
        assert!(params.contains(&"rf_d0".to_string()));
        assert!(!params.contains(&"rf_d1".to_string())); // Should NOT have rf_d1 for 2 stations

        // Sacramento parameters should still be present
        assert!(params.contains(&"lztwm".to_string()));
        assert!(params.contains(&"uztwm".to_string()));

        // Test setting and getting rf_bias
        sacramento.set_param("rf_bias", 1.2).unwrap();
        assert_eq!(sacramento.get_param("rf_bias").unwrap(), 1.2);

        // Test setting and getting rf_d parameters (only rf_d0 exists)
        sacramento.set_param("rf_d0", 0.4).unwrap();
        assert_eq!(sacramento.get_param("rf_d0").unwrap(), 0.4);

        // rf_d1 should not exist for 2 stations
        assert!(sacramento.set_param("rf_d1", 0.7).is_err());
        assert!(sacramento.get_param("rf_d1").is_err());

        // Test bounds checking for rf_d0
        assert!(sacramento.set_param("rf_d0", -0.1).is_err()); // Below 0
        assert!(sacramento.set_param("rf_d0", 1.1).is_err());  // Above 1

        // Test that Sacramento parameters still work
        sacramento.set_param("lztwm", 150.0).unwrap();
        assert_eq!(sacramento.get_param("lztwm").unwrap(), 150.0);
    }

    #[test]
    fn test_linear_combination_to_string() {
        let mut data_cache = DataCache::new();

        // Create a linear combination
        let expr = "0.3 * data.rain1 + 0.7 * data.rain2";
        let mut input = DynamicInput::from_string(expr, &mut data_cache, true).unwrap();

        // Check that original_string returns the original expression
        assert_eq!(input.original_string(), expr);

        // Check that to_string initially returns approximately the original
        // (weights should be close to original after initialization)
        let output = input.to_string();
        // Since default parameters give equal softmax weights, the actual weights
        // will be bias * 0.5 * 0.3 = 0.15 and bias * 0.5 * 0.7 = 0.35
        assert!(output.contains("data.rain1"));
        assert!(output.contains("data.rain2"));
        println!("Initial expression: {}", output);

        // Modify the weights by changing parameters
        if let DynamicInput::LinearCombination { u_params, bias, .. } = &mut input {
            *bias = 2.0;  // Double the total weight
            u_params[0] = 0.9;  // Heavily favor second station
        }
        RainfallWeightHandler::update_weights(&mut input);

        if let DynamicInput::LinearCombination { coefficients, .. } = &input {
            println!("Updated weights: {:?}", coefficients);
        }

        // Check that to_string now returns different weights
        let optimized = input.to_string();
        assert!(optimized.contains("data.rain1"));
        assert!(optimized.contains("data.rain2"));
        println!("Optimized expression: {}", optimized);
        // The expression should be different from the original
        assert_ne!(optimized, expr);

        // Test with weight close to 1.0
        if let DynamicInput::LinearCombination { coefficients, .. } = &mut input {
            coefficients[0] = 1.0;
            coefficients[1] = 0.5;
        }
        let with_unity = input.to_string();
        // First term should have coefficient "1 * " to maintain LinearCombination on round-trip
        assert!(with_unity.contains("1 * data.rain1"));
        assert!(with_unity.contains("0.5 * data.rain2"));
        println!("Expression with unity weight: {}", with_unity);

        // Test with very small and very large weights
        if let DynamicInput::LinearCombination { coefficients, .. } = &mut input {
            coefficients[0] = 0.000001;  // Will use scientific notation
            coefficients[1] = 1000000.0; // Will use scientific notation
        }
        let with_extreme = input.to_string();
        assert!(with_extreme.contains("1e-6 * data.rain1"));
        assert!(with_extreme.contains("1e+6 * data.rain2"));
        println!("Expression with extreme weights: {}", with_extreme);
    }

    #[test]
    fn test_linear_combination_round_trip() {
        let mut data_cache = DataCache::new();

        // Test that "1 * data.rain" round-trips as LinearCombination
        let expr = "1 * data.rain";
        let input1 = DynamicInput::from_string(expr, &mut data_cache, true).unwrap();

        // Should be a LinearCombination
        assert!(matches!(input1, DynamicInput::LinearCombination { .. }));

        // Get the string representation
        let serialized = input1.to_string();
        assert_eq!(serialized, "1 * data.rain");

        // Parse it again - should still be LinearCombination
        let input2 = DynamicInput::from_string(&serialized, &mut data_cache, true).unwrap();
        assert!(matches!(input2, DynamicInput::LinearCombination { .. }));

        // Test that bare "data.rain" is still DirectReference
        let bare_expr = "data.rain";
        let bare_input = DynamicInput::from_string(bare_expr, &mut data_cache, true).unwrap();
        assert!(matches!(bare_input, DynamicInput::DirectReference { .. }));
    }

    #[test]
    fn test_sacramento_node_non_linear_combination() {
        let mut sacramento = SacramentoNode::new_named("test_sacramento");
        let mut data_cache = DataCache::new();

        // Set up rainfall as simple data reference (not linear combination)
        sacramento.rain_mm_input = DynamicInput::from_string("data.rain", &mut data_cache, true).unwrap();

        // Initialize the node
        sacramento.initialise(&mut data_cache).unwrap();

        // Check that rainfall parameters are NOT listed, but Sacramento ones are
        let params = sacramento.list_params();
        assert!(!params.contains(&"rf_bias".to_string()));
        assert!(!params.contains(&"rf_d0".to_string()));
        assert!(params.contains(&"lztwm".to_string())); // Sacramento params still there

        // Trying to access rainfall parameters should fail
        assert!(sacramento.get_param("rf_bias").is_err());
        assert!(sacramento.set_param("rf_bias", 1.5).is_err());

        // But Sacramento parameters should work
        sacramento.set_param("lztwm", 150.0).unwrap();
        assert_eq!(sacramento.get_param("lztwm").unwrap(), 150.0);
    }

    #[test]
    fn test_logit() {
        // Test boundary behavior
        assert!(logit(0.0001).is_finite());
        assert!(logit(0.9999).is_finite());

        // Test midpoint
        assert!((logit(0.5) - 0.0).abs() < 1e-10);

        // Test symmetry
        assert!((logit(0.25) + logit(0.75)).abs() < 1e-10);
    }

    #[test]
    fn test_symmetric_weights() {
        // Test equal weights when all u_i = 0.5
        // For 4 stations, we need 3 u_params
        let u_params = vec![0.5, 0.5, 0.5];
        let original_coefficients = vec![1.0, 2.0, 3.0, 1.0]; // Just used for size
        let bias = 2.0;

        let weights = compute_symmetric_weights(&u_params, &original_coefficients, bias);

        // When all u_params = 0.5, all w_i = 0, softmax gives equal distribution (1/4 each)
        assert_eq!(weights.len(), 4);
        for weight in &weights {
            assert!((weight - 0.5).abs() < 1e-6); // 2.0 / 4 = 0.5 each
        }

        // Sum should equal bias
        let weight_sum: f64 = weights.iter().sum();
        assert!((weight_sum - bias).abs() < 1e-6);
    }

    #[test]
    fn test_bias_scaling() {
        // For 3 stations, we need 2 u_params
        let u_params = vec![0.5, 0.5];
        let original_coefficients = vec![0.3, 0.4, 0.3]; // Just used for size
        let bias = 2.5;

        let weights = compute_symmetric_weights(&u_params, &original_coefficients, bias);
        let weight_sum: f64 = weights.iter().sum();

        // When all u_params = 0.5, softmax gives equal distribution (1/3 each)
        // Sum should equal bias
        assert!((weight_sum - bias).abs() < 1e-6);

        // Each weight should be bias/3
        for weight in &weights {
            assert!((weight - bias/3.0).abs() < 1e-6);
        }
    }

    #[test]
    fn test_single_station() {
        // Single station should only use bias, no u_params
        let u_params = vec![];
        let original_coefficients = vec![2.0]; // Just used for size
        let bias = 3.0;

        let weights = compute_symmetric_weights(&u_params, &original_coefficients, bias);

        assert_eq!(weights.len(), 1);
        assert!((weights[0] - bias).abs() < 1e-6); // Should just be the bias value
    }

}