/// Linear combination detection and handling for DynamicInput
/// This module provides utilities for detecting and processing linear combinations
/// of data references in expressions like "0.2 * data.rain1 + 0.8 * data.rain2"

use crate::functions::ast::ExpressionNode;
use crate::functions::operators::BinaryOperator;

/// Information extracted from a linear combination pattern
#[derive(Debug)]
pub struct LinearCombinationInfo {
    /// Coefficients for each term (the constant multipliers)
    pub coefficients: Vec<f64>,
    /// Variable names for each term (data references)
    pub variables: Vec<String>,
}

/// Detect if an ExpressionNode represents a linear combination pattern
/// Form: c1 * data1 + c2 * data2 + ... or data1 * c1 + data2 * c2 + ...
/// Also handles implicit coefficients: data1 + 0.5 * data2 → [1.0, 0.5]
pub fn detect_linear_combination(node: &ExpressionNode) -> Option<LinearCombinationInfo> {
    let mut terms = Vec::new();

    // Helper function to extract terms from an addition chain
    fn extract_addition_terms(node: &ExpressionNode, terms: &mut Vec<(f64, String)>) -> bool {
        match node {
            ExpressionNode::BinaryOp { left, op, right } if *op == BinaryOperator::Add => {
                // Recursively extract from left side (could be another addition)
                let left_expr = (left.as_ref() as &dyn std::any::Any)
                    .downcast_ref::<ExpressionNode>();
                let right_expr = (right.as_ref() as &dyn std::any::Any)
                    .downcast_ref::<ExpressionNode>();

                if let (Some(left_node), Some(right_node)) = (left_expr, right_expr) {
                    let left_ok = extract_addition_terms(left_node, terms);
                    let right_ok = extract_single_term(right_node, terms);
                    left_ok && right_ok
                } else {
                    false
                }
            }
            _ => {
                // Base case: not an addition, try to extract as a single term
                extract_single_term(node, terms)
            }
        }
    }

    // Helper function to extract a single term (coefficient * variable)
    fn extract_single_term(node: &ExpressionNode, terms: &mut Vec<(f64, String)>) -> bool {
        match node {
            ExpressionNode::BinaryOp { left, op, right } if *op == BinaryOperator::Multiply => {
                // Try both orders: constant * variable or variable * constant
                let left_expr = (left.as_ref() as &dyn std::any::Any)
                    .downcast_ref::<ExpressionNode>();
                let right_expr = (right.as_ref() as &dyn std::any::Any)
                    .downcast_ref::<ExpressionNode>();

                if let (Some(left_node), Some(right_node)) = (left_expr, right_expr) {
                    // Try constant * variable
                    if let ExpressionNode::Constant { value } = left_node {
                        if let ExpressionNode::Variable { name } = right_node {
                            if name.to_lowercase().starts_with("data.") {
                                terms.push((*value, name.clone()));
                                return true;
                            }
                        }
                    }
                    // Try variable * constant
                    if let ExpressionNode::Variable { name } = left_node {
                        if let ExpressionNode::Constant { value } = right_node {
                            if name.to_lowercase().starts_with("data.") {
                                terms.push((*value, name.clone()));
                                return true;
                            }
                        }
                    }
                }
                false
            }
            ExpressionNode::Variable { name } => {
                // Just a variable with implicit coefficient 1.0
                // Examples: "data.rain" or "data_2" in "0.2 * data_1 + data_2"
                if name.to_lowercase().starts_with("data.") || name.to_lowercase().starts_with("data_") {
                    terms.push((1.0, name.clone()));
                    true
                } else {
                    false
                }
            }
            _ => false
        }
    }

    // Start extraction
    if extract_addition_terms(node, &mut terms) {
        if terms.len() >= 2 {
            // Multiple terms - definitely a linear combination
            let coefficients: Vec<f64> = terms.iter().map(|(c, _)| *c).collect();
            let variables: Vec<String> = terms.iter().map(|(_, v)| v.clone()).collect();

            Some(LinearCombinationInfo {
                coefficients,
                variables,
            })
        } else if terms.len() == 1 {
            // Single term - check if it has an explicit coefficient (not 1.0)
            // If coefficient is exactly 1.0, it means there was no multiplication,
            // just a bare variable, so it should be a direct reference
            let (coeff, var) = &terms[0];

            // Check if this came from an explicit multiplication
            // by seeing if the original node was a multiplication operation
            if let ExpressionNode::BinaryOp { op, .. } = node {
                if *op == BinaryOperator::Multiply {
                    // Explicit multiplication like "1.0 * data" - treat as linear combination
                    let coefficients = vec![*coeff];
                    let variables = vec![var.clone()];

                    Some(LinearCombinationInfo {
                        coefficients,
                        variables,
                    })
                } else {
                    None
                }
            } else {
                // Not a multiplication, just a bare variable
                None
            }
        } else {
            None
        }
    } else {
        None
    }
}

/// Logit function: maps [0,1] to (-inf, +inf)
/// Uses clamping to avoid infinities at boundaries
pub fn logit(u: f64) -> f64 {
    // Clamp to avoid log(0) or log(1)
    let u_clamped = u.max(1e-10).min(1.0 - 1e-10);
    (u_clamped / (1.0 - u_clamped)).ln()
}

/// Compute final weights using symmetric parameterization
/// Following the wiki: a_i = bias * softmax(logit(u_i))
///
/// # Arguments
/// * `u_params` - Normalized parameters in [0,1] where 0.5 = equal weight to reference station.
///                Length should be n-1 for n stations (first station is reference)
/// * `original_coefficients` - Original coefficients from expression (used to determine n, but not in computation)
/// * `bias` - Total sum of weights
///
/// # Returns
/// Final weights where sum equals bias
pub fn compute_symmetric_weights(u_params: &[f64], original_coefficients: &[f64], bias: f64) -> Vec<f64> {
    let n = original_coefficients.len();

    if n == 0 {
        return Vec::new();
    }

    // Special case: single term (no distribution parameters needed)
    if n == 1 {
        return vec![bias];
    }

    // For n stations, we have n-1 u_params
    // First station is the reference (w = 0)
    // Other stations have w = logit(u_i)
    let mut w_values = vec![0.0]; // Reference station has w = 0
    for &u in u_params.iter() {
        w_values.push(logit(u));
    }

    // Apply softmax to w_i values
    let max_w = w_values.iter().fold(f64::NEG_INFINITY, |a, &b| a.max(b));
    let exp_weights: Vec<f64> = w_values.iter().map(|w| (w - max_w).exp()).collect();
    let sum_exp = exp_weights.iter().sum::<f64>();

    // Compute final weights: a_i = bias * softmax_i
    exp_weights.iter()
        .map(|exp_w| bias * (exp_w / sum_exp))
        .collect()
}