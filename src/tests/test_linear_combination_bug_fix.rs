#[cfg(test)]
mod tests {
    use crate::model_inputs::DynamicInput;
    use crate::data_management::data_cache::DataCache;

    #[test]
    fn test_linear_combination_preserves_coefficient_sum() {
        let mut data_cache = DataCache::new();

        // Test case 1: Coefficients that sum to 0.5
        let expr1 = "0.25 * data.rain1 + 0.25 * data.rain2";
        let input1 = DynamicInput::from_string(expr1, &mut data_cache, true, None).unwrap();

        if let DynamicInput::LinearCombination { coefficients, bias, .. } = &input1 {
            let weight_sum: f64 = coefficients.iter().sum();
            let expected_sum = 0.5; // Original coefficients were [0.25, 0.25]

            println!("Expression: {}", expr1);
            println!("Current weights: {:?}, sum = {}", coefficients, weight_sum);
            println!("Bias: {}", bias);

            // The bias should be initialized to the sum of original coefficients
            assert!((bias - expected_sum).abs() < 1e-10,
                    "Bias {} should equal original coefficient sum {}", bias, expected_sum);

            // Initially (with default u_params=[0.5]), weights should sum to bias
            assert!((weight_sum - expected_sum).abs() < 1e-10,
                    "Initial weight sum {} should equal bias {}", weight_sum, expected_sum);
        } else {
            panic!("Expected LinearCombination");
        }

        // Test case 2: Coefficients that sum to 1.0
        let expr2 = "0.3 * data.rain1 + 0.7 * data.rain2";
        let input2 = DynamicInput::from_string(expr2, &mut data_cache, true, None).unwrap();

        if let DynamicInput::LinearCombination { bias, coefficients, .. } = &input2 {
            let weight_sum: f64 = coefficients.iter().sum();

            assert!((bias - 1.0).abs() < 1e-10, "Bias should be 1.0 when coefficients sum to 1.0");
            assert!((weight_sum - 1.0).abs() < 1e-10, "Weights should sum to 1.0");
        }

        // Test case 3: Coefficients that sum to 2.0
        let expr3 = "1.5 * data.rain1 + 0.5 * data.rain2";
        let input3 = DynamicInput::from_string(expr3, &mut data_cache, true, None).unwrap();

        if let DynamicInput::LinearCombination { bias, coefficients, .. } = &input3 {
            let weight_sum: f64 = coefficients.iter().sum();

            assert!((bias - 2.0).abs() < 1e-10, "Bias should be 2.0 when coefficients sum to 2.0");
            assert!((weight_sum - 2.0).abs() < 1e-10, "Weights should sum to 2.0");
        }
    }

    #[test]
    fn test_saved_optimized_expression_preserves_weights() {
        let mut data_cache = DataCache::new();

        // Simulate an optimized expression that was saved to file
        let saved_expr = "0.1371563839 * data.rain1 + 0.5095107995 * data.rain2 + 0.5975703828 * data.rain3";
        let input = DynamicInput::from_string(saved_expr, &mut data_cache, true, None).unwrap();

        if let DynamicInput::LinearCombination { coefficients, bias, .. } = &input {
            // The weights should be exactly as specified in the saved expression
            assert!((coefficients[0] - 0.1371563839).abs() < 1e-10,
                    "First weight should be preserved");
            assert!((coefficients[1] - 0.5095107995).abs() < 1e-10,
                    "Second weight should be preserved");
            assert!((coefficients[2] - 0.5975703828).abs() < 1e-10,
                    "Third weight should be preserved");

            // The bias should be the sum of the coefficients
            let expected_bias = 0.1371563839 + 0.5095107995 + 0.5975703828;
            assert!((bias - expected_bias).abs() < 1e-10,
                    "Bias {} should equal sum of coefficients {}", bias, expected_bias);

            // The weights should sum to the bias
            let weight_sum: f64 = coefficients.iter().sum();
            assert!((weight_sum - expected_bias).abs() < 1e-10,
                    "Weight sum {} should equal bias {}", weight_sum, expected_bias);
        } else {
            panic!("Expected LinearCombination");
        }
    }
}