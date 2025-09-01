/// Expression evaluation context and configuration.
///
/// This module provides the runtime environment for evaluating parsed mathematical
/// expressions. It includes configuration options for handling edge cases like
/// missing variables, division by zero, and mathematical errors.

use std::collections::HashMap;
use crate::functions::errors::EvaluationError;

/// Defines how to handle references to variables that are not in the variable context.
///
/// This enum allows for flexible handling of missing variables during evaluation,
/// supporting different use cases from strict error checking to graceful fallbacks.
#[derive(Debug, Clone)]
pub enum MissingVariableBehavior {
    /// Return an error when a variable is not found.
    ///
    /// This is the most strict option and ensures that all variables referenced
    /// in an expression must be provided in the variable context.
    Error,
    
    /// Use a default value when a variable is not found.
    ///
    /// The provided `f64` value will be used for any missing variables.
    /// This is useful for expressions where some variables are optional.
    DefaultValue(f64),
    
    /// Use zero (0.0) when a variable is not found.
    ///
    /// This is a convenience option equivalent to `DefaultValue(0.0)` and
    /// can be useful in mathematical contexts where missing values should
    /// be treated as zero.
    Zero,
}

/// Defines how to handle division by zero operations.
///
/// Different mathematical contexts may require different approaches to
/// handling division by zero, from strict error checking to IEEE 754
/// floating-point behavior.
#[derive(Debug, Clone)]
pub enum DivisionByZeroBehavior {
    /// Return an error when division by zero occurs.
    ///
    /// This is the safest option and ensures that division by zero
    /// is explicitly handled by the calling code.
    Error,
    
    /// Return positive or negative infinity for division by zero.
    ///
    /// This follows IEEE 754 floating-point behavior where x/0 = ±∞
    /// depending on the sign of x.
    Infinity,
    
    /// Return NaN (Not a Number) for division by zero.
    ///
    /// This is another IEEE 754 compliant option that treats
    /// division by zero as an undefined operation.
    NaN,
}

/// Defines how to handle mathematical errors in function calls.
///
/// Mathematical functions can encounter domain errors (like sqrt of negative
/// numbers) or range errors (like overflow). This enum defines the behavior
/// for such cases.
#[derive(Debug, Clone)]
pub enum MathErrorBehavior {
    /// Return an error when a mathematical error occurs.
    ///
    /// This provides explicit error handling and allows the calling
    /// code to decide how to handle the error condition.
    Error,
    
    /// Return NaN (Not a Number) when a mathematical error occurs.
    ///
    /// This follows IEEE 754 behavior for undefined mathematical
    /// operations and allows calculations to continue.
    NaN,
}

/// Configuration for expression evaluation behavior.
///
/// This struct groups together all the configuration options that control
/// how expressions are evaluated, particularly how edge cases and error
/// conditions are handled.
///
/// # Examples
///
/// use kalix::functions::evaluator::{EvaluationConfig, MissingVariableBehavior};
///
/// // Strict configuration (default)
/// let strict_config = EvaluationConfig::default();
///
/// // Permissive configuration
/// let permissive_config = EvaluationConfig {
///     missing_variable_behavior: MissingVariableBehavior::Zero,
///     ..Default::default()
/// };
#[derive(Debug, Clone)]
pub struct EvaluationConfig {
    /// How to handle missing variables
    pub missing_variable_behavior: MissingVariableBehavior,
    /// How to handle division by zero
    pub division_by_zero_behavior: DivisionByZeroBehavior,
    /// How to handle mathematical errors in functions
    pub math_error_behavior: MathErrorBehavior,
}

impl Default for EvaluationConfig {
    /// Create a default configuration with strict error handling.
    ///
    /// The default configuration returns errors for missing variables,
    /// division by zero, and mathematical errors. This is the safest
    /// option for most use cases.
    ///
    /// # Returns
    ///
    /// An `EvaluationConfig` with all behaviors set to `Error`.
    fn default() -> Self {
        Self {
            missing_variable_behavior: MissingVariableBehavior::Error,
            division_by_zero_behavior: DivisionByZeroBehavior::Error,
            math_error_behavior: MathErrorBehavior::Error,
        }
    }
}

/// Runtime context for evaluating expressions with variables.
///
/// This struct provides the execution environment for evaluating parsed
/// expressions. It combines a variable dictionary with evaluation configuration
/// to control how the expression is evaluated.
///
/// The context has a lifetime parameter because it holds references to
/// both the variable dictionary and the configuration, avoiding unnecessary
/// copying of data during evaluation.
///
/// # Examples
///
/// use std::collections::HashMap;
/// use kalix::functions::evaluator::{VariableContext, EvaluationConfig};
///
/// let mut vars = HashMap::new();
/// vars.insert("x".to_string(), 10.0);
/// vars.insert("y".to_string(), 5.0);
///
/// let config = EvaluationConfig::default();
/// let context = VariableContext::new(&vars, &config);
///
/// // Now the context can be used to evaluate expressions
pub struct VariableContext<'a> {
    variables: &'a HashMap<String, f64>,
    config: &'a EvaluationConfig,
}

impl<'a> VariableContext<'a> {
    /// Create a new variable context.
    ///
    /// # Arguments
    ///
    /// * `variables` - A HashMap containing variable names and their values
    /// * `config` - The evaluation configuration to use
    ///
    /// # Returns
    ///
    /// A new `VariableContext` that can be used for expression evaluation.
    ///
    /// # Examples
    ///
    /// use std::collections::HashMap;
    /// use kalix::functions::evaluator::{VariableContext, EvaluationConfig};
    ///
    /// let vars = HashMap::new();
    /// let config = EvaluationConfig::default();
    /// let context = VariableContext::new(&vars, &config);
    pub fn new(variables: &'a HashMap<String, f64>, config: &'a EvaluationConfig) -> Self {
        Self { variables, config }
    }
    
    /// Get the value of a variable from the context.
    ///
    /// This method looks up a variable by name in the variable dictionary.
    /// If the variable is not found, the behavior is determined by the
    /// `missing_variable_behavior` setting in the evaluation configuration.
    ///
    /// # Arguments
    ///
    /// * `name` - The name of the variable to look up
    ///
    /// # Returns
    ///
    /// A `Result` containing either the variable value as `f64` or an
    /// `EvaluationError` if the variable is not found and error behavior
    /// is configured.
    ///
    /// # Examples
    ///
    /// use std::collections::HashMap;
    /// use kalix::functions::evaluator::{VariableContext, EvaluationConfig};
    ///
    /// let mut vars = HashMap::new();
    /// vars.insert("temperature".to_string(), 25.0);
    ///
    /// let config = EvaluationConfig::default();
    /// let context = VariableContext::new(&vars, &config);
    ///
    /// assert_eq!(context.get_variable("temperature").unwrap(), 25.0);
    /// assert!(context.get_variable("humidity").is_err()); // Missing variable
    pub fn get_variable(&self, name: &str) -> Result<f64, EvaluationError> {
        if let Some(&value) = self.variables.get(name) {
            Ok(value)
        } else {
            match &self.config.missing_variable_behavior {
                MissingVariableBehavior::Error => {
                    Err(EvaluationError::VariableNotFound {
                        name: name.to_string(),
                    })
                }
                MissingVariableBehavior::DefaultValue(val) => Ok(*val),
                MissingVariableBehavior::Zero => Ok(0.0),
            }
        }
    }
}