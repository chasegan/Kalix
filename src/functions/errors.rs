/// Error types for the functions module.
///
/// This module defines the error types that can occur during parsing and evaluation
/// of mathematical expressions. All errors implement the standard [`std::error::Error`]
/// trait and provide detailed information about what went wrong and where.

use std::fmt;

/// Errors that can occur during parsing of mathematical expressions.
///
/// These errors are returned by the parser when it encounters invalid syntax,
/// unknown functions, or other issues that prevent parsing the expression
/// into a valid Abstract Syntax Tree (AST).
#[derive(Debug, Clone, PartialEq)]
pub enum ParseError {
    /// A syntax error occurred at a specific position in the expression.
    ///
    /// This includes issues like invalid tokens, malformed numbers,
    /// or incorrect operator usage.
    SyntaxError {
        /// The character position where the error occurred
        position: usize,
        /// A descriptive message about what went wrong
        message: String,
    },
    
    /// An unknown function name was encountered.
    ///
    /// This occurs when the parser finds a function call with a name
    /// that is not in the list of supported built-in functions.
    UnknownFunction {
        /// The name of the unknown function
        name: String,
        /// The character position where the function was found
        position: usize,
    },
    
    /// The expression is fundamentally invalid.
    ///
    /// This is used for higher-level validation errors that aren't
    /// tied to a specific position, such as empty expressions.
    InvalidExpression {
        /// A descriptive message about the validation failure
        message: String,
    },
    
    /// An unexpected token was encountered.
    ///
    /// This occurs when the parser expects one type of token but
    /// finds another, such as expecting a closing parenthesis but
    /// finding an operator.
    UnexpectedToken {
        /// What the parser expected to find
        expected: String,
        /// What was actually found
        found: String,
        /// The character position where the unexpected token was found
        position: usize,
    },
    
    /// Parentheses are not properly matched.
    ///
    /// This occurs when there are unmatched opening or closing parentheses
    /// in the expression.
    UnmatchedParentheses {
        /// The character position where the unmatched parenthesis was detected
        position: usize,
    },
}

impl fmt::Display for ParseError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            ParseError::SyntaxError { position, message } => {
                write!(f, "Syntax error at position {}: {}", position, message)
            }
            ParseError::UnknownFunction { name, position } => {
                write!(f, "Unknown function '{}' at position {}", name, position)
            }
            ParseError::InvalidExpression { message } => {
                write!(f, "Invalid expression: {}", message)
            }
            ParseError::UnexpectedToken { expected, found, position } => {
                write!(f, "Expected '{}' but found '{}' at position {}", expected, found, position)
            }
            ParseError::UnmatchedParentheses { position } => {
                write!(f, "Unmatched parentheses at position {}", position)
            }
        }
    }
}

impl std::error::Error for ParseError {}

/// Errors that can occur during evaluation of mathematical expressions.
///
/// These errors are returned when evaluating a parsed expression fails due to
/// runtime issues like missing variables, mathematical domain errors, or
/// invalid operations.
#[derive(Debug, Clone, PartialEq)]
pub enum EvaluationError {
    /// A required variable was not found in the variable context.
    ///
    /// This occurs when the expression references a variable that is not
    /// present in the provided variable dictionary, and the evaluation
    /// configuration is set to return an error for missing variables.
    VariableNotFound {
        /// The name of the missing variable
        name: String,
    },
    
    /// Division by zero was attempted.
    ///
    /// This occurs for both division (`/`) and modulo (`%`) operations
    /// when the divisor is zero.
    DivisionByZero,
    
    /// An invalid operation was attempted.
    ///
    /// This is a catch-all for operations that are not mathematically valid
    /// or not supported by the evaluator.
    InvalidOperation {
        /// A descriptive message about the invalid operation
        message: String,
    },
    
    /// A mathematical function was called with arguments outside its valid domain.
    ///
    /// This occurs when functions like `sqrt()` are called with negative numbers,
    /// `ln()` is called with non-positive numbers, or trigonometric inverse
    /// functions are called with out-of-range values.
    MathematicalError {
        /// The name of the function that failed
        function: String,
        /// The arguments that were passed to the function
        args: Vec<f64>,
        /// A descriptive message about the domain error
        message: String,
    },
    
    /// A function was called with the wrong number of arguments.
    ///
    /// This occurs when a function is called with more or fewer arguments
    /// than it expects.
    InvalidFunctionArguments {
        /// The name of the function
        function: String,
        /// The number of arguments the function expects
        expected: usize,
        /// The number of arguments that were actually provided
        found: usize,
    },
}

impl fmt::Display for EvaluationError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            EvaluationError::VariableNotFound { name } => {
                write!(f, "Variable '{}' not found", name)
            }
            EvaluationError::DivisionByZero => {
                write!(f, "Division by zero")
            }
            EvaluationError::InvalidOperation { message } => {
                write!(f, "Invalid operation: {}", message)
            }
            EvaluationError::MathematicalError { function, args, message } => {
                write!(f, "Mathematical error in {}({:?}): {}", function, args, message)
            }
            EvaluationError::InvalidFunctionArguments { function, expected, found } => {
                write!(f, "Function '{}' expects {} arguments, found {}", function, expected, found)
            }
        }
    }
}

impl std::error::Error for EvaluationError {}