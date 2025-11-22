/// Rainfall weight parameter handling for nodes with linear combination inputs
///
/// This module provides a reusable handler for managing rainfall weight parameters
/// in nodes that support linear combinations of rainfall inputs. It implements
/// the symmetric parameterization scheme with bias and distribution parameters.

use crate::model_inputs::DynamicInput;
use crate::model_inputs::linear_combination::compute_symmetric_weights;

// Constants for parameter names
const RAINFALL_BIAS_PARAM: &str = "rf_bias";
const RAINFALL_DIST_PREFIX: &str = "rf_d";

/// Handler for rainfall weight parameters in nodes that support linear combinations
pub struct RainfallWeightHandler;

impl RainfallWeightHandler {
    /// Update rainfall weights when parameters change
    /// This recomputes the weights from the u_params and bias
    pub fn update_weights(rain_input: &mut DynamicInput) {
        if let DynamicInput::LinearCombination {
            u_params,
            coefficients,
            bias,
            ..
        } = rain_input {
            // Recompute weights using the symmetric parameterization
            // We pass coefficients as the size hint but the actual values come from bias and u_params
            let new_weights = compute_symmetric_weights(u_params, coefficients, *bias);
            *coefficients = new_weights;
        }
    }

    /// Set a rainfall weight parameter
    /// Returns Ok(true) if parameter was handled, Ok(false) if not a rainfall parameter,
    /// or Err with error message if there was a problem
    pub fn try_set_param(rain_input: &mut DynamicInput, param_name: &str, value: f64, node_name: &str) -> Result<bool, String> {
        // Check for bias parameter
        if param_name == RAINFALL_BIAS_PARAM {
            if let DynamicInput::LinearCombination { bias, .. } = rain_input {
                *bias = value;
                Self::update_weights(rain_input);
                return Ok(true);
            }
            return Err(format!("Node '{}': Rainfall input is not a linear combination", node_name));
        }

        // Check for distribution parameters
        if param_name.starts_with(RAINFALL_DIST_PREFIX) {
            if let DynamicInput::LinearCombination { u_params, data_indices, .. } = rain_input {
                // Parse the index from the parameter name
                let idx_str = &param_name[RAINFALL_DIST_PREFIX.len()..];
                let idx = idx_str.parse::<usize>()
                    .map_err(|_| format!("Node '{}': Invalid rainfall distribution index: {}", node_name, idx_str))?;

                // Check bounds - idx should be 0 to n-2 for n stations
                let n_stations = data_indices.len();
                if n_stations <= 1 {
                    return Err(format!("Node '{}': No distribution parameters for single station", node_name));
                }

                if idx >= n_stations - 1 {
                    return Err(format!(
                        "Node '{}': Rainfall distribution index {} out of range (max: {})",
                        node_name, idx, n_stations - 2
                    ));
                }

                // Validate the parameter value
                validate_distribution_param(value)?;

                u_params[idx] = value;
                Self::update_weights(rain_input);
                return Ok(true);
            }
            return Err(format!("Node '{}': Rainfall input is not a linear combination", node_name));
        }

        // Not a rainfall parameter
        Ok(false)
    }

    /// Get a rainfall weight parameter value
    /// Returns Ok(Some(value)) if parameter exists, Ok(None) if not a rainfall parameter,
    /// or Err with error message if there was a problem
    pub fn try_get_param(rain_input: &DynamicInput, param_name: &str, node_name: &str) -> Result<Option<f64>, String> {
        // Check for bias parameter
        if param_name == RAINFALL_BIAS_PARAM {
            if let DynamicInput::LinearCombination { bias, .. } = rain_input {
                return Ok(Some(*bias));
            }
            return Err(format!("Node '{}': Rainfall input is not a linear combination", node_name));
        }

        // Check for distribution parameters
        if param_name.starts_with(RAINFALL_DIST_PREFIX) {
            if let DynamicInput::LinearCombination { u_params, data_indices, .. } = rain_input {
                // Parse the index from the parameter name
                let idx_str = &param_name[RAINFALL_DIST_PREFIX.len()..];
                let idx = idx_str.parse::<usize>()
                    .map_err(|_| format!("Node '{}': Invalid rainfall distribution index: {}", node_name, idx_str))?;

                // Check bounds - idx should be 0 to n-2 for n stations
                let n_stations = data_indices.len();
                if n_stations <= 1 {
                    return Err(format!("Node '{}': No distribution parameters for single station", node_name));
                }

                if idx >= n_stations - 1 {
                    return Err(format!(
                        "Node '{}': Rainfall distribution index {} out of range (max: {})",
                        node_name, idx, n_stations - 2
                    ));
                }

                return Ok(Some(u_params[idx]));
            }
            return Err(format!("Node '{}': Rainfall input is not a linear combination", node_name));
        }

        // Not a rainfall parameter
        Ok(None)
    }

    /// Get list of rainfall weight parameter names for a given input
    pub fn list_params(rain_input: &DynamicInput) -> Vec<String> {
        let mut params = Vec::new();

        if let DynamicInput::LinearCombination { data_indices, .. } = rain_input {
            params.push(RAINFALL_BIAS_PARAM.to_string());

            // For n stations, we have n-1 distribution parameters
            let n_stations = data_indices.len();
            if n_stations > 1 {
                for i in 0..(n_stations - 1) {
                    params.push(format!("{}{}", RAINFALL_DIST_PREFIX, i));
                }
            }
        }

        params
    }

    /// Check if a parameter name is a rainfall weight parameter
    pub fn is_rainfall_param(param_name: &str) -> bool {
        param_name == RAINFALL_BIAS_PARAM || param_name.starts_with(RAINFALL_DIST_PREFIX)
    }
}

/// Validate that a distribution parameter value is in the valid range [0,1]
pub fn validate_distribution_param(value: f64) -> Result<(), String> {
    if value < 0.0 || value > 1.0 {
        Err(format!("Rainfall distribution parameter must be in [0, 1], got {}", value))
    } else {
        Ok(())
    }
}

