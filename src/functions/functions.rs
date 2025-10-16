/// Built-in mathematical functions for the functions module.
///
/// This module provides implementations of all the mathematical functions that
/// can be called from expressions. Functions are organized by category and
/// include domain validation for mathematical correctness.

use crate::functions::errors::EvaluationError;

/// Evaluate a built-in mathematical function by name with the given arguments.
///
/// This function serves as the main dispatch point for all built-in mathematical
/// functions. It validates argument counts and returns the computed result.
/// Mathematical domain errors (sqrt of negative, log of zero, etc.) return NaN or ∞
/// following IEEE 754 standard, rather than returning errors.
///
/// # Supported Functions
///
/// ## Single Argument Functions
/// - `abs(x)` - Absolute value
/// - `sqrt(x)` - Square root (returns NaN for x < 0)
/// - `sin(x)`, `cos(x)`, `tan(x)` - Trigonometric functions
/// - `asin(x)`, `acos(x)` - Inverse trigonometric functions (returns NaN for |x| > 1)
/// - `atan(x)` - Inverse tangent
/// - `exp(x)` - Exponential function (e^x)
/// - `ln(x)` - Natural logarithm (returns -∞ for x = 0, NaN for x < 0)
/// - `log10(x)`, `log2(x)` - Base-10 and base-2 logarithms (returns -∞ for x = 0, NaN for x < 0)
/// - `ceil(x)`, `floor(x)`, `round(x)` - Rounding functions
///
/// ## Two Argument Functions
/// - `pow(x, y)` - Power function (x^y)
/// - `atan2(y, x)` - Two-argument arctangent
///
/// ## Variable Argument Functions
/// - `min(x, y, ...)` - Minimum of all arguments (2+ args)
/// - `max(x, y, ...)` - Maximum of all arguments (2+ args)
/// - `sum(x, y, ...)` - Sum of all arguments (1+ args)
/// - `avg(x, y, ...)` - Average of all arguments (1+ args)
///
/// ## Special Functions
/// - `if(condition, true_value, false_value)` - Conditional expression
///
/// # Arguments
///
/// * `name` - The name of the function to evaluate
/// * `args` - A slice of f64 values representing the function arguments
///
/// # Returns
///
/// A `Result` containing either the computed result as `f64` or an `EvaluationError`
/// for invalid function names or wrong argument counts. Mathematical domain violations
/// return NaN or ∞ rather than errors.
///
/// # Examples
///
/// ```
/// use kalix::functions::functions::evaluate_builtin_function;
///
/// assert_eq!(evaluate_builtin_function("abs", &[-5.0]).unwrap(), 5.0);
/// assert_eq!(evaluate_builtin_function("sqrt", &[16.0]).unwrap(), 4.0);
/// assert_eq!(evaluate_builtin_function("max", &[3.0, 7.0, 2.0]).unwrap(), 7.0);
/// assert!(evaluate_builtin_function("sqrt", &[-1.0]).unwrap().is_nan()); // Returns NaN, not error
/// ```
pub fn evaluate_builtin_function(name: &str, args: &[f64]) -> Result<f64, EvaluationError> {
    match name {
        // Single argument functions
        "abs" => single_arg_function(name, args, |x| x.abs()),
        "sqrt" => single_arg_function(name, args, |x| x.sqrt()),
        "sin" => single_arg_function(name, args, |x| x.sin()),
        "cos" => single_arg_function(name, args, |x| x.cos()),
        "tan" => single_arg_function(name, args, |x| x.tan()),
        "asin" => single_arg_function(name, args, |x| x.asin()),
        "acos" => single_arg_function(name, args, |x| x.acos()),
        "atan" => single_arg_function(name, args, |x| x.atan()),
        "exp" => single_arg_function(name, args, |x| x.exp()),
        "ln" => single_arg_function(name, args, |x| x.ln()),
        "log10" => single_arg_function(name, args, |x| x.log10()),
        "log2" => single_arg_function(name, args, |x| x.log2()),
        "ceil" => single_arg_function(name, args, |x| x.ceil()),
        "floor" => single_arg_function(name, args, |x| x.floor()),
        "round" => single_arg_function(name, args, |x| x.round()),
        
        // Two argument functions
        "pow" => {
            if args.len() != 2 {
                return invalid_arg_count(name, 2, args.len());
            }
            Ok(args[0].powf(args[1]))
        }
        "atan2" => {
            if args.len() != 2 {
                return invalid_arg_count(name, 2, args.len());
            }
            Ok(args[0].atan2(args[1]))
        }
        "min" => {
            if args.len() < 2 {
                return Err(EvaluationError::InvalidFunctionArguments {
                    function: name.to_string(),
                    expected: 2,
                    found: args.len(),
                });
            }
            Ok(args.iter().fold(args[0], |acc, &x| acc.min(x)))
        }
        "max" => {
            if args.len() < 2 {
                return Err(EvaluationError::InvalidFunctionArguments {
                    function: name.to_string(),
                    expected: 2,
                    found: args.len(),
                });
            }
            Ok(args.iter().fold(args[0], |acc, &x| acc.max(x)))
        }
        
        // Variable argument functions
        "sum" => {
            if args.is_empty() {
                return invalid_arg_count(name, 1, 0);
            }
            Ok(args.iter().sum())
        }
        "avg" => {
            if args.is_empty() {
                return invalid_arg_count(name, 1, 0);
            }
            Ok(args.iter().sum::<f64>() / args.len() as f64)
        }
        
        // Conditional function
        "if" => {
            if args.len() != 3 {
                return invalid_arg_count(name, 3, args.len());
            }
            Ok(if args[0] != 0.0 { args[1] } else { args[2] })
        }
        
        _ => Err(EvaluationError::InvalidOperation {
            message: format!("Unknown function: {}", name),
        }),
    }
}

/// Helper function for single-argument mathematical functions.
///
/// This function validates that exactly one argument is provided and then
/// applies the given function to that argument.
///
/// # Arguments
///
/// * `name` - The name of the function being called (for error messages)
/// * `args` - The arguments provided to the function
/// * `f` - A closure that computes the result from a single f64 input
///
/// # Returns
///
/// A `Result` with the computed value or an error if the argument count is wrong.
fn single_arg_function<F>(name: &str, args: &[f64], f: F) -> Result<f64, EvaluationError>
where
    F: Fn(f64) -> f64,
{
    if args.len() != 1 {
        invalid_arg_count(name, 1, args.len())
    } else {
        Ok(f(args[0]))
    }
}

/// Helper function to create invalid argument count errors.
///
/// # Arguments
///
/// * `name` - The name of the function
/// * `expected` - The number of arguments expected
/// * `found` - The number of arguments actually provided
///
/// # Returns
///
/// An `EvaluationError::InvalidFunctionArguments` wrapped in a Result.
fn invalid_arg_count(name: &str, expected: usize, found: usize) -> Result<f64, EvaluationError> {
    Err(EvaluationError::InvalidFunctionArguments {
        function: name.to_string(),
        expected,
        found,
    })
}

