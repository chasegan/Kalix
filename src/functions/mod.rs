/// # Kalix Custom Functions Module
///
/// This module provides a complete mathematical expression parser and evaluator for Kalix.
/// It enables users to define mathematical expressions as strings that can be parsed once
/// at initialization and then evaluated efficiently at each simulation timestep.
///
/// ## Features
///
/// - **Parse-once, evaluate-many architecture** for optimal performance
/// - **Complete mathematical operations**: arithmetic, trigonometric, logical
/// - **Variable support**: Dynamic variable resolution from runtime dictionaries
/// - **Standard operator precedence** and parentheses support
/// - **Comprehensive error handling** at both parse-time and runtime
/// - **20+ built-in mathematical functions**

pub mod ast;
pub mod errors;
pub mod evaluator;
pub mod functions;
pub mod operators;
pub mod parser;

pub use errors::{EvaluationError, ParseError};
pub use evaluator::{EvaluationConfig, VariableContext};
pub use parser::{FunctionParser, ParsedFunction};

use std::collections::HashMap;

/// Convenience function to parse and create a function from a string expression.
///
/// This function creates a new parser and parses the given expression into a
/// [`ParsedFunction`] that can be evaluated multiple times efficiently.
///
/// # Arguments
///
/// * `expression` - The mathematical expression as a string
///
/// # Returns
///
/// A [`Result`] containing either a [`ParsedFunction`] or a [`ParseError`].
pub fn parse_function(expression: &str) -> Result<ParsedFunction, ParseError> {
    let parser = FunctionParser::new();
    parser.parse(expression)
}

/// Convenience function to evaluate an expression with variables in a single call.
///
/// This function combines parsing and evaluation for simple use cases where the
/// expression will only be evaluated once. For repeated evaluations, use
/// [`parse_function`] followed by [`ParsedFunction::evaluate`] for better performance.
///
/// # Arguments
///
/// * `expression` - The mathematical expression as a string
/// * `variables` - A HashMap containing variable names and their values
///
/// # Returns
///
/// A [`Result`] containing either the evaluated result as [`f64`] or an error.
///
/// # Examples
///
/// use std::collections::HashMap;
/// use kalix::functions::evaluate_expression;
///
/// let mut vars = HashMap::new();
/// vars.insert("temperature".to_string(), 25.0);
/// vars.insert("threshold".to_string(), 20.0);
///
/// let result = evaluate_expression(
///     "if(temperature > threshold, 1.0, 0.0)",
///     &vars
/// ).unwrap();
/// assert_eq!(result, 1.0);
pub fn evaluate_expression(
    expression: &str,
    variables: &HashMap<String, f64>,
) -> Result<f64, Box<dyn std::error::Error>> {
    let function = parse_function(expression)?;
    let config = EvaluationConfig::default();
    let context = VariableContext::new(variables, &config);
    Ok(function.evaluate(&context)?)
}