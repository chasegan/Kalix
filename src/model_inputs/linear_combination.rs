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
                let left_ok = extract_addition_terms(left, terms);
                let right_ok = extract_single_term(right, terms);
                left_ok && right_ok
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
                if let ExpressionNode::Constant { value } = left.as_ref() {
                    if let ExpressionNode::Variable { name } = right.as_ref() {
                        if name.to_lowercase().starts_with("data.") {
                            terms.push((*value, name.clone()));
                            return true;
                        }
                    }
                }
                if let ExpressionNode::Variable { name } = left.as_ref() {
                    if let ExpressionNode::Constant { value } = right.as_ref() {
                        if name.to_lowercase().starts_with("data.") {
                            terms.push((*value, name.clone()));
                            return true;
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

/// Compute weights from the Beta-corrected stick-breaking parameterisation.
///
/// Stations are walked in order; station i (0-based, i < n-1) takes a fraction
/// `v_i = 1 - (1 - u_i)^(1/(n-1-i))` of the weight remaining at its turn, and
/// the last station takes the remainder. The exponent is the Beta(1, n-1-i)
/// quantile correction that makes a uniform search of the u-box an exactly
/// uniform (Dirichlet(1), exchangeable) search of the weight simplex — no
/// station is statistically privileged, unlike a fixed-reference softmax.
///
/// # Arguments
/// * `u_params` - Distribution parameters in [0,1]; length n-1 for n stations
/// * `n` - Number of stations
/// * `bias` - Total sum of weights
///
/// # Returns
/// Final weights (all non-negative for bias >= 0) whose sum equals bias
pub fn compute_stick_breaking_weights(u_params: &[f64], n: usize, bias: f64) -> Vec<f64> {
    if n == 0 {
        return Vec::new();
    }

    // Special case: single term (no distribution parameters needed)
    if n == 1 {
        return vec![bias];
    }

    debug_assert_eq!(u_params.len(), n - 1);

    let mut weights = Vec::with_capacity(n);
    let mut remaining = 1.0_f64;
    for (i, &u) in u_params.iter().enumerate() {
        let m = (n - 1 - i) as f64; // stations still waiting after this one
        let v = 1.0 - (1.0 - u.clamp(0.0, 1.0)).powf(1.0 / m);
        let share = v * remaining;
        weights.push(bias * share);
        remaining -= share;
    }
    weights.push(bias * remaining.max(0.0));
    weights
}

/// Invert the stick-breaking parameterisation: recover `(u_params, bias)` from
/// concrete weights, such that [`compute_stick_breaking_weights`] reproduces
/// them exactly. Used at load so the (bias, u) state always matches the
/// coefficients written in the model file — warm-starting an optimisation from
/// the modeller's weights, and keeping them intact when only a subset of the
/// rf_* parameters is ever set.
///
/// Returns `None` when the weights are not representable: any negative
/// coefficient, or a non-positive total with more than one station. Callers
/// should fall back to [`equal_weight_u_params`].
pub fn invert_stick_breaking_weights(coefficients: &[f64]) -> Option<(Vec<f64>, f64)> {
    let n = coefficients.len();
    if n == 0 || coefficients.iter().any(|&a| a < 0.0) {
        return None;
    }
    let bias: f64 = coefficients.iter().sum();
    if n == 1 {
        return Some((Vec::new(), bias));
    }
    if bias <= 0.0 {
        return None;
    }

    let mut u_params = Vec::with_capacity(n - 1);
    let mut remaining = 1.0_f64;
    for i in 0..(n - 1) {
        let m = (n - 1 - i) as f64;
        // Fraction of the remaining stick this station takes; if earlier
        // stations consumed everything, later shares are necessarily zero.
        let v = if remaining > 0.0 {
            (coefficients[i] / bias / remaining).min(1.0)
        } else {
            0.0
        };
        u_params.push(1.0 - (1.0 - v).powf(m));
        remaining *= 1.0 - v;
    }
    Some((u_params, bias))
}

/// u-parameters that produce exactly equal weights: `u_i = 1 - (m/(m+1))^m`
/// with m = n-1-i. The load-time fallback when parsed coefficients cannot be
/// inverted (a negative or all-zero weight vector). Note the box centre
/// (all u = 0.5) is NOT the equal-weights point for n > 2.
pub fn equal_weight_u_params(n: usize) -> Vec<f64> {
    if n <= 1 {
        return Vec::new();
    }
    (0..n - 1)
        .map(|i| {
            let m = (n - 1 - i) as f64;
            1.0 - (m / (m + 1.0)).powf(m)
        })
        .collect()
}