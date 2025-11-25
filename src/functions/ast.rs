/// Abstract Syntax Tree (AST) definitions for mathematical expressions.
///
/// This module defines the AST node types and evaluation logic for parsed
/// mathematical expressions. The AST represents the structure of an expression
/// and can be evaluated efficiently with different variable contexts.

use std::collections::HashSet;
use crate::functions::errors::EvaluationError;
use crate::functions::evaluator::VariableContext;
use crate::functions::operators::{BinaryOperator, UnaryOperator};

/// Trait for all AST nodes that can be evaluated.
///
/// This trait defines the common interface for all nodes in the Abstract Syntax Tree.
/// Every node must be able to evaluate itself given a variable context, report
/// the variables it depends on, and support cloning.
///
/// The trait requires `Send + Sync` to ensure thread safety for concurrent
/// evaluation of expressions across multiple threads.
pub trait ASTNode: Send + Sync + std::fmt::Debug + std::any::Any {
    /// Evaluate this node given a variable context.
    ///
    /// This method recursively evaluates the node and all its children,
    /// returning a single floating-point result. Variables are resolved
    /// from the provided context.
    ///
    /// # Arguments
    ///
    /// * `context` - The variable context containing variable values and evaluation configuration
    ///
    /// # Returns
    ///
    /// A `Result` containing either the evaluated result as `f64` or an `EvaluationError`.
    fn evaluate(&self, context: &VariableContext) -> Result<f64, EvaluationError>;
    
    /// Get all variable names referenced by this node and its children.
    ///
    /// This method performs a depth-first traversal of the AST to collect
    /// all variable names that are referenced. This is useful for validating
    /// that all required variables are available before evaluation.
    ///
    /// # Returns
    ///
    /// A `HashSet<String>` containing all unique variable names referenced by this subtree.
    fn get_variables(&self) -> HashSet<String>;
    
    /// Create a boxed clone of this node.
    ///
    /// This method is used to support cloning of trait objects, which is
    /// necessary for the `Clone` implementation of `Box<dyn ASTNode>`.
    ///
    /// # Returns
    ///
    /// A new boxed instance of this node with the same content.
    fn clone_box(&self) -> Box<dyn ASTNode>;
}

impl Clone for Box<dyn ASTNode> {
    fn clone(&self) -> Self {
        self.clone_box()
    }
}

/// The main expression node enum that represents all possible AST node types.
///
/// This enum covers all the different types of nodes that can appear in a
/// mathematical expression AST. Each variant contains the data needed to
/// represent and evaluate that type of expression component.
#[derive(Debug, Clone)]
pub enum ExpressionNode {
    /// A binary operation with left operand, operator, and right operand.
    ///
    /// Examples: `2 + 3`, `x * y`, `a > b`, `p && q`
    BinaryOp {
        /// The left operand of the operation
        left: Box<dyn ASTNode>,
        /// The binary operator to apply
        op: BinaryOperator,
        /// The right operand of the operation
        right: Box<dyn ASTNode>,
    },
    
    /// A unary operation with operator and single operand.
    ///
    /// Examples: `-x`, `+5`, `!condition`
    UnaryOp {
        /// The unary operator to apply
        op: UnaryOperator,
        /// The operand of the operation
        operand: Box<dyn ASTNode>,
    },
    
    /// A function call with name and arguments.
    ///
    /// Examples: `sin(x)`, `max(a, b, c)`, `if(condition, true_val, false_val)`
    FunctionCall {
        /// The name of the function to call
        name: String,
        /// The arguments to pass to the function
        args: Vec<Box<dyn ASTNode>>,
    },
    
    /// A variable reference.
    ///
    /// Variables are resolved at evaluation time from the variable context.
    /// Examples: `x`, `temperature`, `flow_rate`
    Variable {
        /// The name of the variable
        name: String,
    },

    /// A variable reference with a temporal offset and default value.
    ///
    /// Used for accessing previous timestep values with a fallback when
    /// the offset would go before the start of the simulation.
    /// Examples: `node.dam.ds_1[1, 0.0]` (yesterday, default 0.0)
    VariableWithOffset {
        /// The name of the variable
        name: String,
        /// The temporal offset (0 = current, 1 = previous timestep, etc.)
        offset: usize,
        /// Default value to use when current_step < offset
        default_value: f64,
    },

    /// A constant numerical value.
    ///
    /// Examples: `42`, `3.14159`, `-2.5`
    Constant {
        /// The constant value
        value: f64,
    },
}

impl ASTNode for ExpressionNode {
    fn evaluate(&self, context: &VariableContext) -> Result<f64, EvaluationError> {
        match self {
            ExpressionNode::Constant { value } => Ok(*value),

            ExpressionNode::Variable { name } => {
                context.get_variable(name)
            }

            ExpressionNode::VariableWithOffset { name, offset, .. } => {
                // For now, offset evaluation through VariableContext is not supported
                // This variant is mainly used for optimised evaluation via DataCache
                // If offset is 0, treat as regular variable
                if *offset == 0 {
                    context.get_variable(name)
                } else {
                    Err(EvaluationError::InvalidOperation {
                        message: format!("Offset access [{}] not supported in this evaluation context", offset),
                    })
                }
            }
            
            ExpressionNode::BinaryOp { left, op, right } => {
                let left_val = left.evaluate(context)?;
                let right_val = right.evaluate(context)?;
                evaluate_binary_op(*op, left_val, right_val)
            }
            
            ExpressionNode::UnaryOp { op, operand } => {
                let val = operand.evaluate(context)?;
                evaluate_unary_op(*op, val)
            }
            
            ExpressionNode::FunctionCall { name, args } => {
                let arg_values: Result<Vec<f64>, EvaluationError> = args
                    .iter()
                    .map(|arg| arg.evaluate(context))
                    .collect();
                let arg_values = arg_values?;
                evaluate_function(name, &arg_values)
            }
        }
    }
    
    fn get_variables(&self) -> HashSet<String> {
        match self {
            ExpressionNode::Constant { .. } => HashSet::new(),

            ExpressionNode::Variable { name } => {
                let mut vars = HashSet::new();
                vars.insert(name.clone());
                vars
            }

            ExpressionNode::VariableWithOffset { name, .. } => {
                let mut vars = HashSet::new();
                vars.insert(name.clone());
                vars
            }

            ExpressionNode::BinaryOp { left, right, .. } => {
                let mut vars = left.get_variables();
                vars.extend(right.get_variables());
                vars
            }

            ExpressionNode::UnaryOp { operand, .. } => {
                operand.get_variables()
            }

            ExpressionNode::FunctionCall { args, .. } => {
                let mut vars = HashSet::new();
                for arg in args {
                    vars.extend(arg.get_variables());
                }
                vars
            }
        }
    }
    
    fn clone_box(&self) -> Box<dyn ASTNode> {
        Box::new(self.clone())
    }
}

/// Evaluate a binary operation
///
/// This function is public to allow reuse in optimised evaluation contexts.
pub fn evaluate_binary_op(op: BinaryOperator, left: f64, right: f64) -> Result<f64, EvaluationError> {
    match op {
        BinaryOperator::Add => Ok(left + right),
        BinaryOperator::Subtract => Ok(left - right),
        BinaryOperator::Multiply => Ok(left * right),
        BinaryOperator::Divide => Ok(left / right),
        BinaryOperator::Modulo => Ok(left % right),
        BinaryOperator::Power => Ok(left.powf(right)),
        BinaryOperator::Equal => Ok(if (left - right).abs() < f64::EPSILON { 1.0 } else { 0.0 }),
        BinaryOperator::NotEqual => Ok(if (left - right).abs() >= f64::EPSILON { 1.0 } else { 0.0 }),
        BinaryOperator::LessThan => Ok(if left < right { 1.0 } else { 0.0 }),
        BinaryOperator::LessThanOrEqual => Ok(if left <= right { 1.0 } else { 0.0 }),
        BinaryOperator::GreaterThan => Ok(if left > right { 1.0 } else { 0.0 }),
        BinaryOperator::GreaterThanOrEqual => Ok(if left >= right { 1.0 } else { 0.0 }),
        BinaryOperator::And => Ok(if left != 0.0 && right != 0.0 { 1.0 } else { 0.0 }),
        BinaryOperator::Or => Ok(if left != 0.0 || right != 0.0 { 1.0 } else { 0.0 }),
    }
}

/// Evaluate a unary operation
///
/// This function is public to allow reuse in optimised evaluation contexts.
pub fn evaluate_unary_op(op: UnaryOperator, operand: f64) -> Result<f64, EvaluationError> {
    match op {
        UnaryOperator::Plus => Ok(operand),
        UnaryOperator::Minus => Ok(-operand),
        UnaryOperator::Not => Ok(if operand == 0.0 { 1.0 } else { 0.0 }),
    }
}

/// Evaluate a function call
fn evaluate_function(name: &str, args: &[f64]) -> Result<f64, EvaluationError> {
    crate::functions::functions::evaluate_builtin_function(name, args)
}