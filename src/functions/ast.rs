/// Abstract Syntax Tree (AST) definitions for mathematical expressions.
///
/// This module defines the AST node types and evaluation logic for parsed
/// mathematical expressions. The AST represents the structure of an expression
/// and can be evaluated efficiently with different variable contexts.
///
/// The AST is **closed by design**: [`ExpressionNode`] is a plain enum, and
/// there is deliberately no node trait to implement. Every consumer of the
/// language (lowering, linear-combination detection, validation) must see
/// expression structure exhaustively, so a new construct is a new enum variant
/// — and the compiler then points at every match that must learn about it.
/// Abstract behind a trait only if a second, genuinely distinct implementor
/// ever exists.

use std::collections::HashSet;
use crate::functions::errors::EvaluationError;
use crate::functions::evaluator::VariableContext;
use crate::functions::functions::BuiltinFunction;
use crate::functions::operators::{BinaryOperator, UnaryOperator};

/// A statement inside a `{ ... }` program block.
///
/// Statements are deliberately few: assignments to locals and asserts. There
/// are no bare expression statements — expressions are pure, so a discarded
/// value could only be a mistake, and the parser rejects it with a message.
#[derive(Debug, Clone)]
pub enum Stmt {
    /// `name = expr;` — bind (or re-bind) a local variable. `name` is always
    /// a bare identifier: dotted names are model references and cannot be
    /// assigned; builtin function names cannot be shadowed.
    Assign { name: String, expr: ExpressionNode },

    /// `assert(expr);` — fail the run, loudly, when `expr` is 0 or NaN.
    /// `source_text` is the statement as written, carried for the panic
    /// message (cold data; never touched on a passing assert).
    Assert { expr: ExpressionNode, source_text: String },
}

/// A parsed `{ ... }` program: zero or more statements and a result
/// expression. The "final line is a bare expression" rule from the design
/// (structured_expressions_design.md §3.2) is encoded structurally — `result`
/// is a separate field, so a Program without a result value cannot be
/// represented, only rejected at parse time.
#[derive(Debug, Clone)]
pub struct Program {
    pub stmts: Vec<Stmt>,
    pub result: ExpressionNode,
}

impl Program {
    /// All *external* variable names referenced by the program: the union of
    /// every statement's and the result's variables, minus names bound by an
    /// `Assign`. Bare names that remain in this set were used without being
    /// assigned — the lowering rejects them (locals must be assigned before
    /// use; dotted names resolve through the model namespaces as usual).
    pub fn get_external_variables(&self) -> HashSet<String> {
        // Names are matched case-insensitively everywhere in the language, so
        // the locals set folds case too — `Total = ...; total` is one local.
        let mut vars = HashSet::new();
        let mut locals = HashSet::new();
        for stmt in &self.stmts {
            match stmt {
                Stmt::Assign { name, expr } => {
                    // The RHS is evaluated before the binding takes effect,
                    // but a bare name in the RHS referencing *this* statement's
                    // own target is only legal if an earlier Assign bound it —
                    // which `locals` already reflects at this point.
                    for v in expr.get_variables() {
                        if !locals.contains(&v.to_lowercase()) {
                            vars.insert(v);
                        }
                    }
                    locals.insert(name.to_lowercase());
                }
                Stmt::Assert { expr, .. } => {
                    for v in expr.get_variables() {
                        if !locals.contains(&v.to_lowercase()) {
                            vars.insert(v);
                        }
                    }
                }
            }
        }
        for v in self.result.get_variables() {
            if !locals.contains(&v.to_lowercase()) {
                vars.insert(v);
            }
        }
        vars
    }
}

/// A function reference resolved at parse time.
///
/// Built-ins resolve to a tagged enum at parse time (fast dispatch via direct match).
/// Names that don't match any built-in resolve to `Named`, deferring lookup to the
/// per-evaluation [`crate::functions::FunctionRegistry`].
#[derive(Debug, Clone)]
pub enum FunctionRef {
    Builtin(BuiltinFunction),
    Named(String),
}

impl FunctionRef {
    /// Construct from a (lowercased) function name. Built-in matches are preferred;
    /// anything else falls through to `Named` for late binding.
    pub fn from_name(name: &str) -> Self {
        match BuiltinFunction::from_name(name) {
            Some(b) => FunctionRef::Builtin(b),
            None => FunctionRef::Named(name.to_string()),
        }
    }
}

/// The expression node enum that represents all possible AST node types.
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
        left: Box<ExpressionNode>,
        /// The binary operator to apply
        op: BinaryOperator,
        /// The right operand of the operation
        right: Box<ExpressionNode>,
    },

    /// A unary operation with operator and single operand.
    ///
    /// Examples: `-x`, `+5`, `!condition`
    UnaryOp {
        /// The unary operator to apply
        op: UnaryOperator,
        /// The operand of the operation
        operand: Box<ExpressionNode>,
    },

    /// A function call with a pre-resolved reference and arguments.
    ///
    /// Function names are resolved against the built-in set at **parse time**, so the
    /// AST stores either a tagged enum [`crate::functions::functions::BuiltinFunction`]
    /// (direct jump-table dispatch at eval time) or a `Named` string (for late binding
    /// against the per-evaluation [`crate::functions::FunctionRegistry`]).
    ///
    /// Examples: `sin(x)`, `max(a, b, c)`, `lin_range(g(1), 0, 100)`
    FunctionCall {
        /// The resolved function reference (built-in or named context function)
        func: FunctionRef,
        /// The arguments to pass to the function
        args: Vec<ExpressionNode>,
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
    /// Used for accessing past or future timestep values with a fallback when
    /// the offset would go outside the available data range.
    ///
    /// Offset convention:
    /// - Negative = past (e.g., -1 = yesterday)
    /// - Zero = current timestep
    /// - Positive = future (e.g., +1 = tomorrow) - only valid for data.* inputs
    ///
    /// Examples: `data.flow[-1, 0.0]` (yesterday, default 0.0)
    VariableWithOffset {
        /// The name of the variable
        name: String,
        /// The temporal offset (-ve = past, 0 = current, +ve = future)
        offset: isize,
        /// Default value to use when offset goes outside available data range
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

impl ExpressionNode {
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
    pub fn evaluate(&self, context: &VariableContext) -> Result<f64, EvaluationError> {
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
                Ok(evaluate_binary_op(*op, left_val, right_val))
            }

            ExpressionNode::UnaryOp { op, operand } => {
                let val = operand.evaluate(context)?;
                Ok(evaluate_unary_op(*op, val))
            }

            ExpressionNode::FunctionCall { func, args } => {
                let arg_values: Result<Vec<f64>, EvaluationError> = args
                    .iter()
                    .map(|arg| arg.evaluate(context))
                    .collect();
                let arg_values = arg_values?;
                evaluate_function(func, &arg_values, context)
            }
        }
    }

    /// Get all variable names referenced by this node and its children.
    ///
    /// This method performs a depth-first traversal of the AST to collect
    /// all variable names that are referenced. This is useful for validating
    /// that all required variables are available before evaluation.
    ///
    /// # Returns
    ///
    /// A `HashSet<String>` containing all unique variable names referenced by this subtree.
    pub fn get_variables(&self) -> HashSet<String> {
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
}

/// Evaluate a binary operation. Infallible: every operator is total over f64
/// (IEEE 754 semantics for division by zero etc.), so no Result plumbing is
/// needed on the hot path.
///
/// This function is public to allow reuse in optimised evaluation contexts.
pub fn evaluate_binary_op(op: BinaryOperator, left: f64, right: f64) -> f64 {
    match op {
        BinaryOperator::Add => left + right,
        BinaryOperator::Subtract => left - right,
        BinaryOperator::Multiply => left * right,
        BinaryOperator::Divide => left / right,
        BinaryOperator::Modulo => left % right,
        BinaryOperator::Power => left.powf(right),
        BinaryOperator::Equal => if (left - right).abs() < f64::EPSILON { 1.0 } else { 0.0 },
        BinaryOperator::NotEqual => if (left - right).abs() >= f64::EPSILON { 1.0 } else { 0.0 },
        BinaryOperator::LessThan => if left < right { 1.0 } else { 0.0 },
        BinaryOperator::LessThanOrEqual => if left <= right { 1.0 } else { 0.0 },
        BinaryOperator::GreaterThan => if left > right { 1.0 } else { 0.0 },
        BinaryOperator::GreaterThanOrEqual => if left >= right { 1.0 } else { 0.0 },
        BinaryOperator::And => if left != 0.0 && right != 0.0 { 1.0 } else { 0.0 },
        BinaryOperator::Or => if left != 0.0 || right != 0.0 { 1.0 } else { 0.0 },
    }
}

/// Evaluate a unary operation. Infallible (see `evaluate_binary_op`).
///
/// This function is public to allow reuse in optimised evaluation contexts.
pub fn evaluate_unary_op(op: UnaryOperator, operand: f64) -> f64 {
    match op {
        UnaryOperator::Plus => operand,
        UnaryOperator::Minus => -operand,
        UnaryOperator::Not => if operand == 0.0 { 1.0 } else { 0.0 },
    }
}

/// Evaluate a function call.
///
/// Built-ins were resolved at parse time and dispatch directly via enum match —
/// the hot path for model simulation. Named (unresolved) references fall back to
/// the per-evaluation [`crate::functions::FunctionRegistry`], used for context
/// functions like `lin_range`/`log_range`/`g` in optimisation parameter expressions.
fn evaluate_function(func: &FunctionRef, args: &[f64], context: &VariableContext) -> Result<f64, EvaluationError> {
    match func {
        FunctionRef::Builtin(b) => b.call(args),
        FunctionRef::Named(name) => {
            if let Some(registry) = context.functions() {
                if let Some(result) = registry.call(name, args) {
                    return result;
                }
            }
            Err(EvaluationError::InvalidOperation {
                message: format!("Unknown function: {}", name),
            })
        }
    }
}
