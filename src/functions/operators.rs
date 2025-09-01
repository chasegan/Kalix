//! Mathematical and logical operators for the functions module.
//!
//! This module defines the binary and unary operators supported by the expression
//! parser, along with their precedence rules and string representations. The
//! precedence values follow standard mathematical conventions.

/// Binary operators that operate on two operands.
///
/// These operators include arithmetic, comparison, and logical operations.
/// Each operator has an associated precedence value that determines the
/// order of evaluation when parsing expressions.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BinaryOperator {
    // Arithmetic operators
    /// Addition operator (+)
    Add,
    /// Subtraction operator (-)
    Subtract,
    /// Multiplication operator (*)
    Multiply,
    /// Division operator (/)
    Divide,
    /// Modulo operator (%)
    Modulo,
    /// Power/exponentiation operator (^ or **)
    Power,
    
    // Comparison operators
    /// Equality operator (==)
    Equal,
    /// Inequality operator (!=)
    NotEqual,
    /// Less than operator (<)
    LessThan,
    /// Less than or equal operator (<=)
    LessThanOrEqual,
    /// Greater than operator (>)
    GreaterThan,
    /// Greater than or equal operator (>=)
    GreaterThanOrEqual,
    
    // Logical operators
    /// Logical AND operator (&&)
    And,
    /// Logical OR operator (||)
    Or,
}

/// Unary operators that operate on a single operand.
///
/// These operators are applied to a single value and include arithmetic
/// signs and logical negation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum UnaryOperator {
    /// Unary plus operator (+)
    Plus,
    /// Unary minus operator (-)
    Minus,
    /// Logical NOT operator (!)
    Not,
}

impl BinaryOperator {
    /// Get the precedence of the operator.
    ///
    /// Returns a numeric value where higher numbers indicate higher precedence.
    /// This follows standard mathematical operator precedence rules:
    ///
    /// 1. Logical OR (||) - lowest precedence
    /// 2. Logical AND (&&)
    /// 3. Equality/Inequality (==, !=)
    /// 4. Comparison (<, <=, >, >=)
    /// 5. Addition/Subtraction (+, -)
    /// 6. Multiplication/Division/Modulo (*, /, %)
    /// 7. Power (^, **) - highest precedence
    ///
    /// # Returns
    ///
    /// An unsigned 8-bit integer representing the precedence level.
    ///
    /// # Examples
    ///
    /// ```rust
    /// use kalix::functions::operators::BinaryOperator;
    ///
    /// assert!(BinaryOperator::Power.precedence() > BinaryOperator::Multiply.precedence());
    /// assert!(BinaryOperator::Multiply.precedence() > BinaryOperator::Add.precedence());
    /// ```
    pub fn precedence(&self) -> u8 {
        match self {
            BinaryOperator::Or => 1,
            BinaryOperator::And => 2,
            BinaryOperator::Equal | BinaryOperator::NotEqual => 3,
            BinaryOperator::LessThan 
            | BinaryOperator::LessThanOrEqual 
            | BinaryOperator::GreaterThan 
            | BinaryOperator::GreaterThanOrEqual => 4,
            BinaryOperator::Add | BinaryOperator::Subtract => 5,
            BinaryOperator::Multiply | BinaryOperator::Divide | BinaryOperator::Modulo => 6,
            BinaryOperator::Power => 7,
        }
    }
    
    /// Check if the operator is right-associative.
    ///
    /// Most operators are left-associative (e.g., `2 + 3 + 4` is evaluated as `(2 + 3) + 4`),
    /// but some operators like power are right-associative (`2 ^ 3 ^ 4` is `2 ^ (3 ^ 4)`).
    ///
    /// # Returns
    ///
    /// `true` if the operator is right-associative, `false` if left-associative.
    ///
    /// # Examples
    ///
    /// ```rust
    /// use kalix::functions::operators::BinaryOperator;
    ///
    /// assert!(BinaryOperator::Power.is_right_associative());
    /// assert!(!BinaryOperator::Add.is_right_associative());
    /// ```
    pub fn is_right_associative(&self) -> bool {
        matches!(self, BinaryOperator::Power)
    }
    
    /// Get the string representation of the operator.
    ///
    /// Returns the symbol used to represent this operator in expressions.
    ///
    /// # Returns
    ///
    /// A string slice containing the operator symbol.
    ///
    /// # Examples
    ///
    /// ```rust
    /// use kalix::functions::operators::BinaryOperator;
    ///
    /// assert_eq!(BinaryOperator::Add.symbol(), "+");
    /// assert_eq!(BinaryOperator::Equal.symbol(), "==");
    /// assert_eq!(BinaryOperator::Power.symbol(), "^");
    /// ```
    pub fn symbol(&self) -> &'static str {
        match self {
            BinaryOperator::Add => "+",
            BinaryOperator::Subtract => "-",
            BinaryOperator::Multiply => "*",
            BinaryOperator::Divide => "/",
            BinaryOperator::Modulo => "%",
            BinaryOperator::Power => "^",
            BinaryOperator::Equal => "==",
            BinaryOperator::NotEqual => "!=",
            BinaryOperator::LessThan => "<",
            BinaryOperator::LessThanOrEqual => "<=",
            BinaryOperator::GreaterThan => ">",
            BinaryOperator::GreaterThanOrEqual => ">=",
            BinaryOperator::And => "&&",
            BinaryOperator::Or => "||",
        }
    }
    
    /// Parse a binary operator from its string representation.
    ///
    /// This function attempts to convert a string slice into a [`BinaryOperator`].
    /// It supports both single-character operators (like `+`, `-`) and multi-character
    /// operators (like `==`, `>=`). The power operator can be represented as either
    /// `^` or `**`.
    ///
    /// # Arguments
    ///
    /// * `s` - The string slice to parse
    ///
    /// # Returns
    ///
    /// `Some(BinaryOperator)` if the string is a valid operator, `None` otherwise.
    ///
    /// # Examples
    ///
    /// ```rust
    /// use kalix::functions::operators::BinaryOperator;
    ///
    /// assert_eq!(BinaryOperator::from_str("+"), Some(BinaryOperator::Add));
    /// assert_eq!(BinaryOperator::from_str("=="), Some(BinaryOperator::Equal));
    /// assert_eq!(BinaryOperator::from_str("**"), Some(BinaryOperator::Power));
    /// assert_eq!(BinaryOperator::from_str("xyz"), None);
    /// ```
    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "+" => Some(BinaryOperator::Add),
            "-" => Some(BinaryOperator::Subtract),
            "*" => Some(BinaryOperator::Multiply),
            "/" => Some(BinaryOperator::Divide),
            "%" => Some(BinaryOperator::Modulo),
            "^" | "**" => Some(BinaryOperator::Power),
            "==" => Some(BinaryOperator::Equal),
            "!=" => Some(BinaryOperator::NotEqual),
            "<" => Some(BinaryOperator::LessThan),
            "<=" => Some(BinaryOperator::LessThanOrEqual),
            ">" => Some(BinaryOperator::GreaterThan),
            ">=" => Some(BinaryOperator::GreaterThanOrEqual),
            "&&" => Some(BinaryOperator::And),
            "||" => Some(BinaryOperator::Or),
            _ => None,
        }
    }
}

impl UnaryOperator {
    /// Get the string representation of the unary operator.
    ///
    /// Returns the symbol used to represent this operator in expressions.
    ///
    /// # Returns
    ///
    /// A string slice containing the operator symbol.
    ///
    /// # Examples
    ///
    /// ```rust
    /// use kalix::functions::operators::UnaryOperator;
    ///
    /// assert_eq!(UnaryOperator::Minus.symbol(), "-");
    /// assert_eq!(UnaryOperator::Not.symbol(), "!");
    /// ```
    pub fn symbol(&self) -> &'static str {
        match self {
            UnaryOperator::Plus => "+",
            UnaryOperator::Minus => "-",
            UnaryOperator::Not => "!",
        }
    }
    
    /// Parse a unary operator from its string representation.
    ///
    /// This function attempts to convert a string slice into a [`UnaryOperator`].
    ///
    /// # Arguments
    ///
    /// * `s` - The string slice to parse
    ///
    /// # Returns
    ///
    /// `Some(UnaryOperator)` if the string is a valid unary operator, `None` otherwise.
    ///
    /// # Examples
    ///
    /// ```rust
    /// use kalix::functions::operators::UnaryOperator;
    ///
    /// assert_eq!(UnaryOperator::from_str("-"), Some(UnaryOperator::Minus));
    /// assert_eq!(UnaryOperator::from_str("!"), Some(UnaryOperator::Not));
    /// assert_eq!(UnaryOperator::from_str("xyz"), None);
    /// ```
    pub fn from_str(s: &str) -> Option<Self> {
        match s {
            "+" => Some(UnaryOperator::Plus),
            "-" => Some(UnaryOperator::Minus),
            "!" => Some(UnaryOperator::Not),
            _ => None,
        }
    }
}