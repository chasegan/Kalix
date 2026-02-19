#[cfg(test)]
mod tests {
    use crate::model_inputs::DynamicInput;
    use crate::data_management::data_cache::DataCache;
    use crate::nodes::gr4j_node::Gr4jNode;
    use crate::nodes::Node;
    use crate::numerical::opt::optimisable_component::OptimisableComponent;

    #[test]
    fn test_linear_combination_save_after_optimization() {
        let mut gr4j = Gr4jNode::new();
        gr4j.name = "test_gr4j".to_string();
        let mut data_cache = DataCache::new();

        // Set up rainfall as linear combination
        let original_expr = "0.3 * data.rain1 + 0.7 * data.rain2";
        gr4j.rain_mm_input = DynamicInput::from_string(original_expr, &mut data_cache, true, None).unwrap();

        // Initialize the node
        gr4j.initialise(&mut data_cache).unwrap();

        let initial_string = gr4j.rain_mm_input.to_string();
        println!("Original expression: {}", initial_string);

        // Update bias parameter
        gr4j.set_param("rf_bias", 1.5).unwrap();
        let after_bias_update = gr4j.rain_mm_input.to_string();
        println!("After setting rf_bias=1.5: {}", after_bias_update);

        // The expression should have changed
        assert_ne!(initial_string, after_bias_update, "Expression should change after updating rf_bias");

        // Update distribution parameter
        gr4j.set_param("rf_d0", 0.8).unwrap();
        let after_dist_update = gr4j.rain_mm_input.to_string();
        println!("After setting rf_d0=0.8: {}", after_dist_update);

        // The expression should have changed again
        assert_ne!(after_bias_update, after_dist_update, "Expression should change after updating rf_d0");

        // Get parameters back to verify they were set
        assert_eq!(gr4j.get_param("rf_bias").unwrap(), 1.5);
        assert_eq!(gr4j.get_param("rf_d0").unwrap(), 0.8);

        // Parse the updated expression again and ensure it still works
        let updated_input = DynamicInput::from_string(&after_dist_update, &mut data_cache, true, None).unwrap();
        assert!(matches!(updated_input, DynamicInput::LinearCombination { .. }));
    }
}