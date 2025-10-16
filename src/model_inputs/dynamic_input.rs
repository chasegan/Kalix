/// Dynamic Input - Optimized expression evaluation for model inputs
///
/// This module provides a high-performance input mechanism that allows model nodes
/// to accept constants, direct data references, or complex function expressions with
/// zero or minimal overhead.
///
/// # Performance Characteristics
///
/// - `None`: Zero overhead (returns 0.0)
/// - `DirectReference`: Zero overhead (single array lookup)
/// - `DirectConstantReference`: Zero overhead (single array lookup)
/// - `Constant`: Zero overhead (returns stored value)
/// - `Function`: Minimal overhead (direct array indexing + arithmetic, no HashMap lookups)
///
/// # Error Handling - IEEE 754 Standard
///
/// Mathematical operations follow IEEE 754 floating-point standard:
/// - Division by zero: `x / 0.0` → `+∞` (x > 0), `-∞` (x < 0), `NaN` (x = 0)
/// - Domain errors: `sqrt(-1)` → `NaN`, `ln(0)` → `-∞`, `asin(2)` → `NaN`
/// - Overflow: `exp(1000)` → `+∞`
///
/// This allows simulations to continue running even with problematic data, while making
/// issues clearly visible in the output. Check for NaN/∞ in results to detect problems.

use std::collections::HashMap;
use crate::data_management::data_cache::DataCache;
use crate::functions::{parse_function, EvaluationConfig, VariableContext};
use crate::functions::ast::{ExpressionNode, evaluate_binary_op, evaluate_unary_op};
use crate::functions::operators::{BinaryOperator, UnaryOperator};

/// Optimized AST that uses direct data cache indices instead of variable names
#[derive(Debug, Clone)]
pub enum OptimizedExpressionNode {
    /// A constant value
    Constant {
        value: f64
    },

    /// Direct reference to a data cache series by index
    DataCacheReference {
        cache_index: usize
    },

    /// Direct reference to a constant cache value by index
    ConstantReference {
        cache_index: usize
    },

    /// Binary operation
    BinaryOp {
        left: Box<OptimizedExpressionNode>,
        op: BinaryOperator,
        right: Box<OptimizedExpressionNode>,
    },

    /// Unary operation
    UnaryOp {
        op: UnaryOperator,
        operand: Box<OptimizedExpressionNode>,
    },

    /// Function call with optimized arguments
    FunctionCall {
        name: String,
        args: Vec<Box<OptimizedExpressionNode>>,
    },
}

impl OptimizedExpressionNode {
    /// Evaluate the expression using direct data cache access
    ///
    /// This method provides high-performance evaluation with no HashMap lookups
    /// or string operations - just direct array indexing and arithmetic.
    pub fn evaluate(&self, data_cache: &DataCache) -> Result<f64, String> {
        match self {
            OptimizedExpressionNode::Constant { value } => Ok(*value),

            OptimizedExpressionNode::DataCacheReference { cache_index } => {
                Ok(data_cache.get_current_value(*cache_index))
            }

            OptimizedExpressionNode::ConstantReference { cache_index } => {
                Ok(data_cache.constants.get_value(*cache_index))
            }

            OptimizedExpressionNode::BinaryOp { left, op, right } => {
                let left_val = left.evaluate(data_cache)?;
                let right_val = right.evaluate(data_cache)?;
                evaluate_binary_op(*op, left_val, right_val)
                    .map_err(|e| format!("{}", e))
            }

            OptimizedExpressionNode::UnaryOp { op, operand } => {
                let val = operand.evaluate(data_cache)?;
                evaluate_unary_op(*op, val)
                    .map_err(|e| format!("{}", e))
            }

            OptimizedExpressionNode::FunctionCall { name, args } => {
                let arg_values: Result<Vec<f64>, String> = args
                    .iter()
                    .map(|arg| arg.evaluate(data_cache))
                    .collect();
                let arg_values = arg_values?;
                evaluate_function(name, &arg_values)
            }
        }
    }

    /// Transform an ExpressionNode to an OptimizedExpressionNode by resolving variables to indices
    fn from_expression_node(
        node: &ExpressionNode,
        data_variable_map: &HashMap<String, usize>,
        constant_variable_map: &HashMap<String, usize>
    ) -> Result<Self, String> {
        match node {
            ExpressionNode::Constant { value } => {
                Ok(OptimizedExpressionNode::Constant { value: *value })
            }
            ExpressionNode::Variable { name } => {
                // Try constant first (c.* variables)
                if let Some(&idx) = constant_variable_map.get(name) {
                    return Ok(OptimizedExpressionNode::ConstantReference { cache_index: idx });
                }
                // Try data cache (data.* variables)
                if let Some(&idx) = data_variable_map.get(name) {
                    return Ok(OptimizedExpressionNode::DataCacheReference { cache_index: idx });
                }
                Err(format!("Variable '{}' not found in variable maps", name))
            }
            ExpressionNode::BinaryOp { left, op, right } => {
                // Need to downcast the boxed ASTNode children to ExpressionNode
                let left_expr = (left.as_ref() as &dyn std::any::Any)
                    .downcast_ref::<ExpressionNode>()
                    .ok_or("Failed to downcast left operand")?;
                let right_expr = (right.as_ref() as &dyn std::any::Any)
                    .downcast_ref::<ExpressionNode>()
                    .ok_or("Failed to downcast right operand")?;

                let left_opt = Self::from_expression_node(left_expr, data_variable_map, constant_variable_map)?;
                let right_opt = Self::from_expression_node(right_expr, data_variable_map, constant_variable_map)?;

                Ok(OptimizedExpressionNode::BinaryOp {
                    left: Box::new(left_opt),
                    op: *op,
                    right: Box::new(right_opt),
                })
            }
            ExpressionNode::UnaryOp { op, operand } => {
                let operand_expr = (operand.as_ref() as &dyn std::any::Any)
                    .downcast_ref::<ExpressionNode>()
                    .ok_or("Failed to downcast operand")?;

                let operand_opt = Self::from_expression_node(operand_expr, data_variable_map, constant_variable_map)?;

                Ok(OptimizedExpressionNode::UnaryOp {
                    op: *op,
                    operand: Box::new(operand_opt),
                })
            }
            ExpressionNode::FunctionCall { name, args } => {
                let args_opt: Result<Vec<_>, String> = args
                    .iter()
                    .map(|arg| {
                        let arg_expr = (arg.as_ref() as &dyn std::any::Any)
                            .downcast_ref::<ExpressionNode>()
                            .ok_or("Failed to downcast function argument")?;
                        Self::from_expression_node(arg_expr, data_variable_map, constant_variable_map)
                    })
                    .collect();
                let args_opt = args_opt?;

                Ok(OptimizedExpressionNode::FunctionCall {
                    name: name.clone(),
                    args: args_opt.into_iter().map(Box::new).collect(),
                })
            }
        }
    }
}

/// DynamicInput supports constants, data references, and function expressions
///
/// This enum is optimized for performance with five variants:
/// - `None`: No input (returns 0.0)
/// - `DirectReference`: Pure data reference (zero overhead)
/// - `DirectConstantReference`: Pure constant reference (zero overhead)
/// - `Constant`: Constant value (zero overhead)
/// - `Function`: Complex expression (minimal overhead)
#[derive(Clone, Debug)]
pub enum DynamicInput {
    /// No input specified
    None,

    /// Direct reference to a data cache series
    DirectReference {
        idx: usize
    },

    /// Direct reference to a constant cache value
    DirectConstantReference {
        idx: usize
    },

    /// Constant value (evaluated once at initialization)
    Constant {
        value: f64
    },

    /// Function expression (optimized for performance)
    Function {
        expression: String,  // Original expression for error messages
        optimized_ast: OptimizedExpressionNode
    },
}

impl Default for DynamicInput {
    fn default() -> Self {
        DynamicInput::None
    }
}

impl DynamicInput {
    /// Create a DynamicInput from a string expression
    ///
    /// This method parses the expression but does NOT resolve variables.
    /// You must call `initialize()` afterwards to resolve data cache references.
    ///
    /// # Arguments
    ///
    /// * `expression` - The expression string (e.g., "data.evap", "1.2 * data.evap", "100.0")
    ///
    /// # Returns
    ///
    /// A DynamicInput that needs initialization, or an error if parsing fails
    pub fn from_string(expression: &str, data_cache: &mut DataCache, flag_as_critical: bool) -> Result<Self, String> {
        let trimmed = expression.trim();

        if trimmed.is_empty() {
            return Ok(DynamicInput::None);
        }

        // Parse the expression
        let parsed = parse_function(trimmed)
            .map_err(|e| format!("Failed to parse expression '{}': {}", trimmed, e))?;

        // Get all variables referenced
        let variables = parsed.get_variables();

        // Separate variables into data cache and constants based on prefix
        let mut data_variable_map = HashMap::new();
        let mut constant_variable_map = HashMap::new();

        for var_name in variables.iter() {
            let lower_name = var_name.to_lowercase();

            if lower_name.starts_with("c.") {
                // Resolve to constants cache
                let idx = data_cache.constants.add_if_needed_and_get_idx(&lower_name);
                constant_variable_map.insert(var_name.clone(), idx);
            } else {
                // Resolve to data cache (existing logic)
                let idx = data_cache.get_or_add_new_series(lower_name.as_str(), flag_as_critical);
                data_variable_map.insert(var_name.clone(), idx);
            }
        }

        // Optimize based on expression type
        if variables.is_empty() {
            // No variables -> constant expression
            // Evaluate once and store the value
            let config = EvaluationConfig::default();
            let empty_vars = HashMap::new();
            let context = VariableContext::new(&empty_vars, &config);
            let value = parsed.evaluate(&context)
                .map_err(|e| format!("Failed to evaluate constant expression: {}", e))?;

            Ok(DynamicInput::Constant { value })

        } else if let Some(var_name) = parsed.is_single_variable() {
            // It's a direct reference to a single variable (no operations)
            // Check if it's a constant or data reference
            if let Some(&idx) = constant_variable_map.get(var_name) {
                Ok(DynamicInput::DirectConstantReference { idx })
            } else if let Some(&idx) = data_variable_map.get(var_name) {
                Ok(DynamicInput::DirectReference { idx })
            } else {
                Err(format!("Variable '{}' not found in variable maps", var_name))
            }
        } else {
            // Multiple variables or complex expression -> function expression
            let optimized_ast = transform_to_optimized_ast(&parsed, &data_variable_map, &constant_variable_map)?;
            Ok(DynamicInput::Function {
                expression: trimmed.to_string(),
                optimized_ast
            })
        }
    }

    /// Get the current value
    ///
    /// # Arguments
    ///
    /// * `data_cache` - The data cache to read values from
    ///
    /// # Returns
    ///
    /// The evaluated value as f64. Returns 0.0 for `None` variant.
    ///
    /// # Error Handling
    ///
    /// Mathematical domain errors (division by zero, sqrt of negative, etc.) return
    /// NaN or ∞ following IEEE 754 standard - they do NOT cause this function to fail.
    ///
    /// Programming errors (unknown function names, wrong argument counts) are extremely
    /// rare and indicate bugs in the parser. If they occur, this function prints an
    /// error to stderr and returns 0.0 to allow the simulation to continue.
    pub fn get_value(&self, data_cache: &DataCache) -> f64 {
        match self {
            DynamicInput::None => 0.0,
            DynamicInput::DirectReference { idx } => {
                data_cache.get_current_value(*idx)
            }
            DynamicInput::DirectConstantReference { idx } => {
                data_cache.constants.get_value(*idx)
            }
            DynamicInput::Constant { value } => *value,
            DynamicInput::Function { expression, optimized_ast } => {
                optimized_ast.evaluate(data_cache).unwrap_or_else(|e| {
                    eprintln!("ERROR: Critical evaluation failure in expression '{}': {}. Returning 0.0. This indicates a parser bug.", expression, e);
                    0.0
                })
            }
        }
    }
}

/// Transform a ParsedFunction to an OptimizedExpressionNode
fn transform_to_optimized_ast(
    parsed: &crate::functions::parser::ParsedFunction,
    data_variable_map: &HashMap<String, usize>,
    constant_variable_map: &HashMap<String, usize>
) -> Result<OptimizedExpressionNode, String> {
    let ast = parsed.get_ast();

    // Downcast to ExpressionNode
    if let Some(expr_node) = (ast as &dyn std::any::Any).downcast_ref::<ExpressionNode>() {
        OptimizedExpressionNode::from_expression_node(expr_node, data_variable_map, constant_variable_map)
    } else {
        Err("Failed to downcast AST node".to_string())
    }
}

// Function evaluation
fn evaluate_function(name: &str, args: &[f64]) -> Result<f64, String> {
    crate::functions::functions::evaluate_builtin_function(name, args)
        .map_err(|e| format!("Function error: {}", e))
}
