/// Expression parser for mathematical expressions.
///
/// This module provides a complete recursive descent parser that converts string
/// expressions into Abstract Syntax Trees (AST). The parser handles operator
/// precedence, associativity, function calls, variables, and parentheses.
///
/// The parser is implemented using the recursive descent technique with separate
/// functions for each precedence level, ensuring correct evaluation order
/// according to mathematical conventions.

use std::collections::HashSet;
use crate::functions::ast::{ASTNode, ExpressionNode};
use crate::functions::errors::ParseError;
use crate::functions::evaluator::VariableContext;
use crate::functions::operators::{BinaryOperator, UnaryOperator};

/// Token types produced by the tokenizer.
///
/// These tokens represent the basic lexical elements that make up
/// mathematical expressions.
#[derive(Debug, Clone, PartialEq)]
enum Token {
    /// A numeric literal (e.g., 42, 3.14159, -2.5)
    Number(f64),
    /// An identifier for variables or function names (e.g., x, sin, temperature)
    Identifier(String),
    /// An operator symbol (e.g., +, -, *, ==, &&)
    Operator(String),
    /// Left parenthesis (
    LeftParen,
    /// Right parenthesis )
    RightParen,
    /// Comma separator for function arguments
    Comma,
    /// End of input marker
    EOF,
}

#[derive(Debug)]
struct Tokenizer {
    input: Vec<char>,
    position: usize,
    current_char: Option<char>,
}

impl Tokenizer {
    fn new(input: &str) -> Self {
        let chars: Vec<char> = input.chars().collect();
        let current_char = chars.first().copied();
        Self {
            input: chars,
            position: 0,
            current_char,
        }
    }
    
    fn advance(&mut self) {
        self.position += 1;
        self.current_char = self.input.get(self.position).copied();
    }
    
    #[allow(unused)]
    fn peek(&self) -> Option<char> {
        self.input.get(self.position + 1).copied()
    }
    
    fn skip_whitespace(&mut self) {
        while let Some(ch) = self.current_char {
            if ch.is_whitespace() {
                self.advance();
            } else {
                break;
            }
        }
    }
    
    fn read_number(&mut self) -> Result<f64, ParseError> {
        let start_pos = self.position;
        let mut number_str = String::new();

        // Read mantissa (digits and decimal point)
        while let Some(ch) = self.current_char {
            if ch.is_ascii_digit() || ch == '.' {
                number_str.push(ch);
                self.advance();
            } else {
                break;
            }
        }

        // Check for scientific notation (e.g., 1.5e-3, 2E+10)
        if let Some(ch) = self.current_char {
            if ch == 'e' || ch == 'E' {
                number_str.push(ch);
                self.advance();

                // Optional sign after exponent marker
                if let Some(sign) = self.current_char {
                    if sign == '+' || sign == '-' {
                        number_str.push(sign);
                        self.advance();
                    }
                }

                // Exponent digits
                while let Some(digit) = self.current_char {
                    if digit.is_ascii_digit() {
                        number_str.push(digit);
                        self.advance();
                    } else {
                        break;
                    }
                }
            }
        }

        number_str.parse().map_err(|_| ParseError::SyntaxError {
            position: start_pos,
            message: format!("Invalid number: {}", number_str),
        })
    }
    
    fn read_identifier(&mut self) -> String {
        let mut identifier = String::new();
        let mut last_was_dot = false;

        while let Some(ch) = self.current_char {
            if ch.is_alphanumeric() || ch == '_' {
                identifier.push(ch);
                last_was_dot = false;
                self.advance();
            } else if ch == '.' && !last_was_dot && !identifier.is_empty() {
                // Allow dot in identifier, but not consecutive dots and not at start
                identifier.push(ch);
                last_was_dot = true;
                self.advance();
            } else {
                break;
            }
        }

        // Remove trailing dot if present
        if identifier.ends_with('.') {
            identifier.pop();
        }

        identifier
    }
    
    fn read_operator(&mut self) -> String {
        let mut op = String::new();
        
        match self.current_char {
            Some('*') => {
                op.push('*');
                self.advance();
                if self.current_char == Some('*') {
                    op.push('*');
                    self.advance();
                }
            }
            Some('=') | Some('!') | Some('<') | Some('>') => {
                if let Some(ch) = self.current_char {
                    op.push(ch);
                    self.advance();
                    if self.current_char == Some('=') {
                        op.push('=');
                        self.advance();
                    }
                }
            }
            Some('&') => {
                op.push('&');
                self.advance();
                if self.current_char == Some('&') {
                    op.push('&');
                    self.advance();
                }
            }
            Some('|') => {
                op.push('|');
                self.advance();
                if self.current_char == Some('|') {
                    op.push('|');
                    self.advance();
                }
            }
            Some(ch) => {
                op.push(ch);
                self.advance();
            }
            None => {}
        }
        
        op
    }
    
    fn next_token(&mut self) -> Result<Token, ParseError> {
        self.skip_whitespace();
        
        match self.current_char {
            None => Ok(Token::EOF),
            Some('(') => {
                self.advance();
                Ok(Token::LeftParen)
            }
            Some(')') => {
                self.advance();
                Ok(Token::RightParen)
            }
            Some(',') => {
                self.advance();
                Ok(Token::Comma)
            }
            Some(ch) if ch.is_ascii_digit() || ch == '.' => {
                let number = self.read_number()?;
                Ok(Token::Number(number))
            }
            Some(ch) if ch.is_alphabetic() || ch == '_' => {
                let identifier = self.read_identifier();
                Ok(Token::Identifier(identifier))
            }
            Some(_) => {
                let op = self.read_operator();
                Ok(Token::Operator(op))
            }
        }
    }
}

/// The main parser struct that converts expressions to ASTs.
///
/// This parser uses recursive descent parsing to handle operator precedence
/// and associativity correctly. It maintains the current token state and
/// delegates to different parsing methods based on precedence levels.
pub struct FunctionParser {
    tokenizer: Tokenizer,
    current_token: Token,
}

/// A parsed mathematical function ready for evaluation.
///
/// This struct represents a successfully parsed expression that has been
/// converted to an Abstract Syntax Tree. It can be evaluated multiple times
/// with different variable contexts for optimal performance.
#[derive(Debug)]
pub struct ParsedFunction {
    ast: Box<dyn ASTNode>,
    variables: HashSet<String>,
}

impl ParsedFunction {
    /// Create a new ParsedFunction from an AST node.
    ///
    /// This constructor automatically extracts all variable names referenced
    /// by the expression for later validation.
    ///
    /// # Arguments
    ///
    /// * `ast` - The root AST node representing the parsed expression
    ///
    /// # Returns
    ///
    /// A new ParsedFunction ready for evaluation.
    pub fn new(ast: Box<dyn ASTNode>) -> Self {
        let variables = ast.get_variables();
        Self { ast, variables }
    }
    
    /// Evaluate the parsed function with the given variable context.
    ///
    /// This method evaluates the AST with the provided variables and
    /// configuration settings. It can be called multiple times with
    /// different contexts for efficient repeated evaluation.
    ///
    /// # Arguments
    ///
    /// * `context` - The variable context containing values and evaluation config
    ///
    /// # Returns
    ///
    /// A Result containing either the computed value or an evaluation error.
    pub fn evaluate(&self, context: &VariableContext) -> Result<f64, crate::functions::errors::EvaluationError> {
        self.ast.evaluate(context)
    }
    
    /// Get the set of all variables referenced by this function.
    ///
    /// This method returns the variable names that were extracted during
    /// parsing. It's useful for validating that all required variables
    /// are available before evaluation.
    ///
    /// # Returns
    ///
    /// A reference to a HashSet containing all variable names.
    pub fn get_variables(&self) -> &HashSet<String> {
        &self.variables
    }

    /// Get a reference to the internal AST.
    ///
    /// This method exposes the internal AST for advanced use cases like
    /// optimization and transformation.
    ///
    /// # Returns
    ///
    /// A reference to the AST root node.
    pub fn get_ast(&self) -> &dyn ASTNode {
        self.ast.as_ref()
    }

    /// Check if this function is a single variable reference (no operations).
    ///
    /// Returns `Some(&variable_name)` if the expression is just a simple variable
    /// reference like "data.evap", otherwise returns `None`.
    ///
    /// This is useful for optimization - a single variable can be optimized to
    /// a direct data cache lookup instead of AST evaluation.
    ///
    /// # Returns
    ///
    /// `Some(&str)` with the variable name if this is a single variable,
    /// `None` if it's a more complex expression.
    ///
    /// # Examples
    ///
    /// ```ignore
    /// use kalix::functions::parse_function;
    ///
    /// let f1 = parse_function("data.evap").unwrap();
    /// assert_eq!(f1.is_single_variable(), Some("data.evap"));
    ///
    /// let f2 = parse_function("data.evap * 1.2").unwrap();
    /// assert_eq!(f2.is_single_variable(), None);
    /// ```
    pub fn is_single_variable(&self) -> Option<&str> {
        // Check if we have exactly one variable
        if self.variables.len() != 1 {
            return None;
        }

        // Downcast to ExpressionNode to inspect the AST structure
        if let Some(expr_node) = (self.ast.as_ref() as &dyn std::any::Any).downcast_ref::<ExpressionNode>() {
            // Check if the root node is a simple Variable node
            if let ExpressionNode::Variable { name } = expr_node {
                return Some(name.as_str());
            }
        }

        None
    }
}

impl FunctionParser {
    /// Create a new function parser.
    ///
    /// # Returns
    ///
    /// A new FunctionParser ready to parse expressions.
    pub fn new() -> Self {
        // Placeholder - will be properly initialized in parse()
        Self {
            tokenizer: Tokenizer::new(""),
            current_token: Token::EOF,
        }
    }
    
    /// Parse a mathematical expression string into a ParsedFunction.
    ///
    /// This is the main entry point for parsing. It tokenizes the input,
    /// builds an AST using recursive descent parsing, and validates that
    /// the entire input was consumed.
    ///
    /// # Arguments
    ///
    /// * `expression` - The mathematical expression to parse
    ///
    /// # Returns
    ///
    /// A Result containing either a ParsedFunction or a ParseError.
    ///
    /// # Examples
    ///
    /// use kalix::functions::parser::FunctionParser;
    ///
    /// let parser = FunctionParser::new();
    /// let function = parser.parse("2 * x + sin(y)").unwrap();
    /// let vars = function.get_variables(); // Contains {"x", "y"}
    pub fn parse(&self, expression: &str) -> Result<ParsedFunction, ParseError> {
        let mut parser = Self {
            tokenizer: Tokenizer::new(expression),
            current_token: Token::EOF,
        };
        
        parser.current_token = parser.tokenizer.next_token()?;
        let ast = parser.parse_expression()?;
        
        if parser.current_token != Token::EOF {
            return Err(ParseError::SyntaxError {
                position: parser.tokenizer.position,
                message: "Unexpected tokens after expression".to_string(),
            });
        }
        
        Ok(ParsedFunction::new(ast))
    }
    
    fn consume_token(&mut self) -> Result<(), ParseError> {
        self.current_token = self.tokenizer.next_token()?;
        Ok(())
    }
    
    fn parse_expression(&mut self) -> Result<Box<dyn ASTNode>, ParseError> {
        self.parse_or_expression()
    }
    
    fn parse_or_expression(&mut self) -> Result<Box<dyn ASTNode>, ParseError> {
        let mut left = self.parse_and_expression()?;
        
        while let Token::Operator(ref op) = self.current_token {
            if op == "||" {
                self.consume_token()?;
                let right = self.parse_and_expression()?;
                left = Box::new(ExpressionNode::BinaryOp {
                    left,
                    op: BinaryOperator::Or,
                    right,
                });
            } else {
                break;
            }
        }
        
        Ok(left)
    }
    
    fn parse_and_expression(&mut self) -> Result<Box<dyn ASTNode>, ParseError> {
        let mut left = self.parse_equality_expression()?;
        
        while let Token::Operator(ref op) = self.current_token {
            if op == "&&" {
                self.consume_token()?;
                let right = self.parse_equality_expression()?;
                left = Box::new(ExpressionNode::BinaryOp {
                    left,
                    op: BinaryOperator::And,
                    right,
                });
            } else {
                break;
            }
        }
        
        Ok(left)
    }
    
    fn parse_equality_expression(&mut self) -> Result<Box<dyn ASTNode>, ParseError> {
        let mut left = self.parse_comparison_expression()?;
        
        while let Token::Operator(ref op) = self.current_token {
            if let Some(bin_op) = match op.as_str() {
                "==" => Some(BinaryOperator::Equal),
                "!=" => Some(BinaryOperator::NotEqual),
                _ => None,
            } {
                self.consume_token()?;
                let right = self.parse_comparison_expression()?;
                left = Box::new(ExpressionNode::BinaryOp {
                    left,
                    op: bin_op,
                    right,
                });
            } else {
                break;
            }
        }
        
        Ok(left)
    }
    
    fn parse_comparison_expression(&mut self) -> Result<Box<dyn ASTNode>, ParseError> {
        let mut left = self.parse_additive_expression()?;
        
        while let Token::Operator(ref op) = self.current_token {
            if let Some(bin_op) = match op.as_str() {
                "<" => Some(BinaryOperator::LessThan),
                "<=" => Some(BinaryOperator::LessThanOrEqual),
                ">" => Some(BinaryOperator::GreaterThan),
                ">=" => Some(BinaryOperator::GreaterThanOrEqual),
                _ => None,
            } {
                self.consume_token()?;
                let right = self.parse_additive_expression()?;
                left = Box::new(ExpressionNode::BinaryOp {
                    left,
                    op: bin_op,
                    right,
                });
            } else {
                break;
            }
        }
        
        Ok(left)
    }
    
    fn parse_additive_expression(&mut self) -> Result<Box<dyn ASTNode>, ParseError> {
        let mut left = self.parse_multiplicative_expression()?;
        
        while let Token::Operator(ref op) = self.current_token {
            if let Some(bin_op) = match op.as_str() {
                "+" => Some(BinaryOperator::Add),
                "-" => Some(BinaryOperator::Subtract),
                _ => None,
            } {
                self.consume_token()?;
                let right = self.parse_multiplicative_expression()?;
                left = Box::new(ExpressionNode::BinaryOp {
                    left,
                    op: bin_op,
                    right,
                });
            } else {
                break;
            }
        }
        
        Ok(left)
    }
    
    fn parse_multiplicative_expression(&mut self) -> Result<Box<dyn ASTNode>, ParseError> {
        let mut left = self.parse_power_expression()?;
        
        while let Token::Operator(ref op) = self.current_token {
            if let Some(bin_op) = match op.as_str() {
                "*" => Some(BinaryOperator::Multiply),
                "/" => Some(BinaryOperator::Divide),
                "%" => Some(BinaryOperator::Modulo),
                _ => None,
            } {
                self.consume_token()?;
                let right = self.parse_power_expression()?;
                left = Box::new(ExpressionNode::BinaryOp {
                    left,
                    op: bin_op,
                    right,
                });
            } else {
                break;
            }
        }
        
        Ok(left)
    }
    
    fn parse_power_expression(&mut self) -> Result<Box<dyn ASTNode>, ParseError> {
        let left = self.parse_unary_expression()?;
        
        if let Token::Operator(ref op) = self.current_token {
            if op == "^" || op == "**" {
                self.consume_token()?;
                let right = self.parse_power_expression()?; // Right associative
                return Ok(Box::new(ExpressionNode::BinaryOp {
                    left,
                    op: BinaryOperator::Power,
                    right,
                }));
            }
        }
        
        Ok(left)
    }
    
    fn parse_unary_expression(&mut self) -> Result<Box<dyn ASTNode>, ParseError> {
        if let Token::Operator(ref op) = self.current_token {
            if let Some(unary_op) = match op.as_str() {
                "+" => Some(UnaryOperator::Plus),
                "-" => Some(UnaryOperator::Minus),
                "!" => Some(UnaryOperator::Not),
                _ => None,
            } {
                self.consume_token()?;
                let operand = self.parse_unary_expression()?;
                return Ok(Box::new(ExpressionNode::UnaryOp {
                    op: unary_op,
                    operand,
                }));
            }
        }
        
        self.parse_primary_expression()
    }
    
    fn parse_primary_expression(&mut self) -> Result<Box<dyn ASTNode>, ParseError> {
        match &self.current_token {
            Token::Number(value) => {
                let value = *value;
                self.consume_token()?;
                Ok(Box::new(ExpressionNode::Constant { value }))
            }
            Token::Identifier(name) => {
                let name = name.clone();
                self.consume_token()?;
                
                if self.current_token == Token::LeftParen {
                    // Function call
                    self.consume_token()?; // consume '('
                    let mut args = Vec::new();
                    
                    if self.current_token != Token::RightParen {
                        args.push(self.parse_expression()?);
                        
                        while self.current_token == Token::Comma {
                            self.consume_token()?; // consume ','
                            args.push(self.parse_expression()?);
                        }
                    }
                    
                    if self.current_token != Token::RightParen {
                        return Err(ParseError::UnexpectedToken {
                            expected: ")".to_string(),
                            found: format!("{:?}", self.current_token),
                            position: self.tokenizer.position,
                        });
                    }
                    
                    self.consume_token()?; // consume ')'
                    Ok(Box::new(ExpressionNode::FunctionCall { name: name.to_lowercase(), args }))
                } else {
                    // Variable
                    Ok(Box::new(ExpressionNode::Variable { name }))
                }
            }
            Token::LeftParen => {
                self.consume_token()?; // consume '('
                let expr = self.parse_expression()?;
                
                if self.current_token != Token::RightParen {
                    return Err(ParseError::UnmatchedParentheses {
                        position: self.tokenizer.position,
                    });
                }
                
                self.consume_token()?; // consume ')'
                Ok(expr)
            }
            _ => Err(ParseError::SyntaxError {
                position: self.tokenizer.position,
                message: format!("Unexpected token: {:?}", self.current_token),
            }),
        }
    }
}