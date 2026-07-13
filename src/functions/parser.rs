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
use crate::functions::ast::{ExpressionNode, Program, Stmt};
use crate::functions::errors::ParseError;
use crate::functions::functions::BuiltinFunction;
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
    /// Left bracket [ (for offset syntax like node.x.ds_1[1])
    LeftBracket,
    /// Right bracket ]
    RightBracket,
    /// Left brace { (opens a statement block)
    LeftBrace,
    /// Right brace } (closes a statement block)
    RightBrace,
    /// Statement terminator ;
    Semicolon,
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
    /// Start position (char index) of the most recently returned token.
    /// Used by the statement parser to slice statement source text out of
    /// the input for assert messages.
    last_token_start: usize,
}

impl Tokenizer {
    fn new(input: &str) -> Self {
        let chars: Vec<char> = input.chars().collect();
        let current_char = chars.first().copied();
        Self {
            input: chars,
            position: 0,
            current_char,
            last_token_start: 0,
        }
    }

    /// Slice the input text between two char positions (statement source
    /// capture for assert messages). Cold path: only runs at parse time.
    fn slice_text(&self, start: usize, end: usize) -> String {
        self.input[start.min(self.input.len())..end.min(self.input.len())]
            .iter()
            .collect::<String>()
            .trim()
            .to_string()
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
        self.last_token_start = self.position;

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
            Some('[') => {
                self.advance();
                Ok(Token::LeftBracket)
            }
            Some(']') => {
                self.advance();
                Ok(Token::RightBracket)
            }
            Some('{') => {
                self.advance();
                Ok(Token::LeftBrace)
            }
            Some('}') => {
                self.advance();
                Ok(Token::RightBrace)
            }
            Some(';') => {
                self.advance();
                Ok(Token::Semicolon)
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
#[derive(Debug, Clone)]
pub struct ParsedFunction {
    ast: ExpressionNode,
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
    pub fn new(ast: ExpressionNode) -> Self {
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
    /// optimisation and transformation.
    ///
    /// # Returns
    ///
    /// A reference to the AST root node.
    pub fn get_ast(&self) -> &ExpressionNode {
        &self.ast
    }

    /// Check if this function is a single variable reference (no operations).
    ///
    /// Returns `Some(&variable_name)` if the expression is just a simple variable
    /// reference like "data.evap", otherwise returns `None`.
    ///
    /// This is useful for optimisation - a single variable can be optimised to
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
        // A root Variable node is by construction the expression's only variable.
        match &self.ast {
            ExpressionNode::Variable { name } => Some(name.as_str()),
            ExpressionNode::VariableWithOffset { name, .. } => Some(name.as_str()),
            _ => None,
        }
    }

    /// Check if this function is a single variable with an offset.
    ///
    /// Returns `Some((variable_name, offset, default_value))` if the expression is a variable
    /// with offset syntax like "node.x.ds_1[1, 0.0]", otherwise returns `None`.
    ///
    /// # Returns
    ///
    /// `Some((&str, isize, f64))` with the variable name, offset, and default value,
    /// `None` if it's not a single variable with offset.
    /// Offset: -ve = past, 0 = current, +ve = future
    pub fn is_single_variable_with_offset(&self) -> Option<(&str, isize, f64)> {
        if let ExpressionNode::VariableWithOffset { name, offset, default_value } = &self.ast {
            return Some((name.as_str(), *offset, *default_value));
        }

        None
    }
}

/// A parsed `{ ... }` program block, ready for lowering.
///
/// The companion to [`ParsedFunction`] for multi-statement values. Produced
/// by [`FunctionParser::parse_program`]; consumed by the DynamicInput
/// lowering, which resolves locals to arena slots and external references to
/// cache indices.
#[derive(Debug, Clone)]
pub struct ParsedProgram {
    program: Program,
    external_variables: HashSet<String>,
}

impl ParsedProgram {
    /// The parsed statements and result expression.
    pub fn program(&self) -> &Program {
        &self.program
    }

    /// External variable names referenced by the program — everything except
    /// names bound by assignment statements. Bare (dotless) names appearing
    /// here were used before assignment; the lowering rejects them.
    pub fn get_external_variables(&self) -> &HashSet<String> {
        &self.external_variables
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

    /// Parse a `{ statement; statement; ...; result }` program block.
    ///
    /// Grammar (structured_expressions_design.md §3):
    /// - The whole input must be one block: `{` ... `}` with nothing after.
    /// - Statements are `name = expression;` (local assignment, bare names
    ///   only, builtins cannot be shadowed) or `assert(expression);`.
    /// - The final item before `}` must be a bare expression with no `;` —
    ///   it is the block's value. A terminated or missing final expression
    ///   is a parse error, never a silent default.
    pub fn parse_program(&self, text: &str) -> Result<ParsedProgram, ParseError> {
        let mut parser = Self {
            tokenizer: Tokenizer::new(text),
            current_token: Token::EOF,
        };
        parser.current_token = parser.tokenizer.next_token()?;

        if parser.current_token != Token::LeftBrace {
            return Err(ParseError::SyntaxError {
                position: parser.tokenizer.last_token_start,
                message: "a program block must start with '{'".to_string(),
            });
        }
        parser.consume_token()?; // consume '{'

        let mut stmts: Vec<Stmt> = Vec::new();
        let result: ExpressionNode;

        loop {
            match &parser.current_token {
                Token::RightBrace => {
                    return Err(parser.no_result_error());
                }
                Token::EOF => {
                    return Err(ParseError::SyntaxError {
                        position: parser.tokenizer.position,
                        message: "unclosed program block: missing '}'".to_string(),
                    });
                }
                _ => {}
            }

            let stmt_start = parser.tokenizer.last_token_start;

            // assert statement: `assert(cond);`
            if let Token::Identifier(id) = &parser.current_token {
                if id.eq_ignore_ascii_case("assert") {
                    parser.consume_token()?; // consume 'assert'
                    if parser.current_token != Token::LeftParen {
                        return Err(ParseError::SyntaxError {
                            position: parser.tokenizer.last_token_start,
                            message: "'assert' is a statement and is reserved: write assert(condition);".to_string(),
                        });
                    }
                    parser.consume_token()?; // consume '('
                    let cond = parser.parse_expression()?;
                    if parser.current_token != Token::RightParen {
                        return Err(ParseError::UnexpectedToken {
                            expected: ")".to_string(),
                            found: format!("{:?}", parser.current_token),
                            position: parser.tokenizer.position,
                        });
                    }
                    parser.consume_token()?; // consume ')'
                    let stmt_end = parser.tokenizer.last_token_start;
                    match &parser.current_token {
                        Token::Semicolon => {
                            parser.consume_token()?;
                        }
                        Token::RightBrace => return Err(parser.no_result_error()),
                        _ => {
                            return Err(ParseError::SyntaxError {
                                position: parser.tokenizer.last_token_start,
                                message: "expected ';' after assert(...)".to_string(),
                            });
                        }
                    }
                    let source_text = parser.tokenizer.slice_text(stmt_start, stmt_end);
                    stmts.push(Stmt::Assert { expr: cond, source_text });
                    continue;
                }
            }

            // Anything else: parse an expression. A following '=' makes it an
            // assignment target; a following '}' makes it the result.
            let expr = parser.parse_expression()?;

            let is_assign = matches!(&parser.current_token, Token::Operator(op) if op == "=");
            if is_assign {
                let name = match expr {
                    ExpressionNode::Variable { name } if !name.contains('.') => name,
                    ExpressionNode::Variable { name } => {
                        return Err(ParseError::SyntaxError {
                            position: stmt_start,
                            message: format!(
                                "cannot assign to '{}': dotted names are model references; \
                                 local variables are bare names",
                                name
                            ),
                        });
                    }
                    _ => {
                        return Err(ParseError::SyntaxError {
                            position: stmt_start,
                            message: "invalid assignment target: expected a local variable name before '='".to_string(),
                        });
                    }
                };
                let lower = name.to_lowercase();
                // The language owns the bare names (expression-naming §1.3):
                // a local may not shadow ANY reserved tier — builtin,
                // stateful builtin, or keyword — including tiers the
                // language grows later. One registry answers for all of
                // them (owner decision, July 2026).
                if let Some(kind) = crate::functions::functions::reserved_name_kind(&lower) {
                    return Err(ParseError::SyntaxError {
                        position: stmt_start,
                        message: format!(
                            "cannot use {} name '{}' as a local variable",
                            kind, lower
                        ),
                    });
                }
                parser.consume_token()?; // consume '='
                let rhs = parser.parse_expression()?;
                match &parser.current_token {
                    Token::Semicolon => {
                        parser.consume_token()?;
                    }
                    Token::RightBrace => return Err(parser.no_result_error()),
                    _ => {
                        return Err(ParseError::SyntaxError {
                            position: parser.tokenizer.last_token_start,
                            message: format!("expected ';' after assignment to '{}'", name),
                        });
                    }
                }
                stmts.push(Stmt::Assign { name, expr: rhs });
                continue;
            }

            match &parser.current_token {
                Token::RightBrace => {
                    // The bare final expression: the block's value.
                    result = expr;
                    parser.consume_token()?; // consume '}'
                    break;
                }
                Token::Semicolon => {
                    parser.consume_token()?;
                    if parser.current_token == Token::RightBrace {
                        return Err(parser.no_result_error());
                    }
                    return Err(ParseError::SyntaxError {
                        position: stmt_start,
                        message: "statement has no effect: expected 'name = expression;', \
                                  'assert(...);', or the final result expression (no ';')".to_string(),
                    });
                }
                _ => {
                    return Err(ParseError::SyntaxError {
                        position: parser.tokenizer.last_token_start,
                        message: format!("unexpected token in program: {:?}", parser.current_token),
                    });
                }
            }
        }

        if parser.current_token != Token::EOF {
            return Err(ParseError::SyntaxError {
                position: parser.tokenizer.position,
                message: "unexpected tokens after closing '}'".to_string(),
            });
        }

        let program = Program { stmts, result };
        let external_variables = program.get_external_variables();
        Ok(ParsedProgram { program, external_variables })
    }

    /// The load error for a program whose final line is not a bare
    /// expression. One message, used for every way of getting it wrong
    /// (empty block, trailing ';', assignment or assert as the last line),
    /// so modellers meet a single consistent rule.
    fn no_result_error(&self) -> ParseError {
        ParseError::SyntaxError {
            position: self.tokenizer.last_token_start,
            message: "program has no result value: the final line must be a bare \
                      expression without ';' — its value is the block's value".to_string(),
        }
    }

    fn consume_token(&mut self) -> Result<(), ParseError> {
        self.current_token = self.tokenizer.next_token()?;
        Ok(())
    }
    
    fn parse_expression(&mut self) -> Result<ExpressionNode, ParseError> {
        self.parse_or_expression()
    }
    
    fn parse_or_expression(&mut self) -> Result<ExpressionNode, ParseError> {
        let mut left = self.parse_and_expression()?;
        
        while let Token::Operator(ref op) = self.current_token {
            if op == "||" {
                self.consume_token()?;
                let right = self.parse_and_expression()?;
                left = ExpressionNode::BinaryOp {
                    left: Box::new(left),
                    op: BinaryOperator::Or,
                    right: Box::new(right),
                };
            } else {
                break;
            }
        }
        
        Ok(left)
    }
    
    fn parse_and_expression(&mut self) -> Result<ExpressionNode, ParseError> {
        let mut left = self.parse_equality_expression()?;
        
        while let Token::Operator(ref op) = self.current_token {
            if op == "&&" {
                self.consume_token()?;
                let right = self.parse_equality_expression()?;
                left = ExpressionNode::BinaryOp {
                    left: Box::new(left),
                    op: BinaryOperator::And,
                    right: Box::new(right),
                };
            } else {
                break;
            }
        }
        
        Ok(left)
    }
    
    fn parse_equality_expression(&mut self) -> Result<ExpressionNode, ParseError> {
        let mut left = self.parse_comparison_expression()?;
        
        while let Token::Operator(ref op) = self.current_token {
            if let Some(bin_op) = match op.as_str() {
                "==" => Some(BinaryOperator::Equal),
                "!=" => Some(BinaryOperator::NotEqual),
                _ => None,
            } {
                self.consume_token()?;
                let right = self.parse_comparison_expression()?;
                left = ExpressionNode::BinaryOp {
                    left: Box::new(left),
                    op: bin_op,
                    right: Box::new(right),
                };
            } else {
                break;
            }
        }
        
        Ok(left)
    }
    
    fn parse_comparison_expression(&mut self) -> Result<ExpressionNode, ParseError> {
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
                left = ExpressionNode::BinaryOp {
                    left: Box::new(left),
                    op: bin_op,
                    right: Box::new(right),
                };
            } else {
                break;
            }
        }
        
        Ok(left)
    }
    
    fn parse_additive_expression(&mut self) -> Result<ExpressionNode, ParseError> {
        let mut left = self.parse_multiplicative_expression()?;
        
        while let Token::Operator(ref op) = self.current_token {
            if let Some(bin_op) = match op.as_str() {
                "+" => Some(BinaryOperator::Add),
                "-" => Some(BinaryOperator::Subtract),
                _ => None,
            } {
                self.consume_token()?;
                let right = self.parse_multiplicative_expression()?;
                left = ExpressionNode::BinaryOp {
                    left: Box::new(left),
                    op: bin_op,
                    right: Box::new(right),
                };
            } else {
                break;
            }
        }
        
        Ok(left)
    }
    
    fn parse_multiplicative_expression(&mut self) -> Result<ExpressionNode, ParseError> {
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
                left = ExpressionNode::BinaryOp {
                    left: Box::new(left),
                    op: bin_op,
                    right: Box::new(right),
                };
            } else {
                break;
            }
        }
        
        Ok(left)
    }
    
    fn parse_power_expression(&mut self) -> Result<ExpressionNode, ParseError> {
        let left = self.parse_unary_expression()?;
        
        if let Token::Operator(ref op) = self.current_token {
            if op == "^" || op == "**" {
                self.consume_token()?;
                let right = self.parse_power_expression()?; // Right associative
                return Ok(ExpressionNode::BinaryOp {
                    left: Box::new(left),
                    op: BinaryOperator::Power,
                    right: Box::new(right),
                });
            }
        }
        
        Ok(left)
    }
    
    fn parse_unary_expression(&mut self) -> Result<ExpressionNode, ParseError> {
        if let Token::Operator(ref op) = self.current_token {
            if let Some(unary_op) = match op.as_str() {
                "+" => Some(UnaryOperator::Plus),
                "-" => Some(UnaryOperator::Minus),
                "!" => Some(UnaryOperator::Not),
                _ => None,
            } {
                self.consume_token()?;
                let operand = self.parse_unary_expression()?;
                // Fold unary +/- over a numeric literal at parse, so signed
                // literals ARE literals: `-1` is a Constant, not a UnaryOp
                // over one. Semantics are identical everywhere; it matters
                // where the language requires a load-time literal — a
                // moving_* element default of -1 must be accepted
                // (lower_stateful_call::constant_arg matches Constant only).
                if let ExpressionNode::Constant { value } = operand {
                    match unary_op {
                        UnaryOperator::Minus => return Ok(ExpressionNode::Constant { value: -value }),
                        UnaryOperator::Plus => return Ok(ExpressionNode::Constant { value }),
                        UnaryOperator::Not => {}
                    }
                }
                return Ok(ExpressionNode::UnaryOp {
                    op: unary_op,
                    operand: Box::new(operand),
                });
            }
        }
        
        self.parse_primary_expression()
    }
    
    fn parse_primary_expression(&mut self) -> Result<ExpressionNode, ParseError> {
        match &self.current_token {
            Token::Number(value) => {
                let value = *value;
                self.consume_token()?;
                Ok(ExpressionNode::Constant { value })
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
                    Ok(ExpressionNode::FunctionCall {
                        func: crate::functions::ast::FunctionRef::from_name(&name.to_lowercase()),
                        args,
                    })
                } else if self.current_token == Token::LeftBracket {
                    // Variable with offset: node.x.ds_1[offset, default]
                    // Both offset and default are required
                    self.consume_token()?; // consume '['

                    // Expect a number (the offset) - can be negative (past) or positive (future)
                    let offset: isize = match &self.current_token {
                        Token::Number(n) => {
                            let offset_val = *n;
                            if offset_val.fract() != 0.0 {
                                return Err(ParseError::SyntaxError {
                                    position: self.tokenizer.position,
                                    message: format!("Offset must be an integer, got {}", offset_val),
                                });
                            }
                            offset_val as isize
                        }
                        Token::Operator(op) if op == "-" => {
                            // Handle negative offset: consume '-' then number
                            self.consume_token()?;
                            match &self.current_token {
                                Token::Number(n) => {
                                    let offset_val = *n;
                                    if offset_val.fract() != 0.0 {
                                        return Err(ParseError::SyntaxError {
                                            position: self.tokenizer.position,
                                            message: format!("Offset must be an integer, got -{}", offset_val),
                                        });
                                    }
                                    -(offset_val as isize)
                                }
                                _ => {
                                    return Err(ParseError::UnexpectedToken {
                                        expected: "integer after minus sign".to_string(),
                                        found: format!("{:?}", self.current_token),
                                        position: self.tokenizer.position,
                                    });
                                }
                            }
                        }
                        _ => {
                            return Err(ParseError::UnexpectedToken {
                                expected: "integer (negative for past, positive for future)".to_string(),
                                found: format!("{:?}", self.current_token),
                                position: self.tokenizer.position,
                            });
                        }
                    };
                    self.consume_token()?; // consume the offset number

                    // Expect comma
                    if self.current_token != Token::Comma {
                        return Err(ParseError::SyntaxError {
                            position: self.tokenizer.position,
                            message: "Offset syntax requires default value: [offset, default]. Example: data.flow[-1, 0.0] for yesterday's value".to_string(),
                        });
                    }
                    self.consume_token()?; // consume ','

                    // Expect a number (the default value) - can be negative or nan
                    let default_value = match &self.current_token {
                        Token::Number(n) => *n,
                        Token::Identifier(id) if id.to_lowercase() == "nan" => f64::NAN,
                        Token::Operator(op) if op == "-" => {
                            // Handle negative default: consume '-' then number
                            self.consume_token()?;
                            match &self.current_token {
                                Token::Number(n) => -(*n),
                                _ => {
                                    return Err(ParseError::UnexpectedToken {
                                        expected: "number after minus sign".to_string(),
                                        found: format!("{:?}", self.current_token),
                                        position: self.tokenizer.position,
                                    });
                                }
                            }
                        }
                        _ => {
                            return Err(ParseError::UnexpectedToken {
                                expected: "default value (number or nan)".to_string(),
                                found: format!("{:?}", self.current_token),
                                position: self.tokenizer.position,
                            });
                        }
                    };
                    self.consume_token()?; // consume the default number/nan

                    if self.current_token != Token::RightBracket {
                        return Err(ParseError::UnexpectedToken {
                            expected: "]".to_string(),
                            found: format!("{:?}", self.current_token),
                            position: self.tokenizer.position,
                        });
                    }
                    self.consume_token()?; // consume ']'

                    Ok(ExpressionNode::VariableWithOffset { name, offset, default_value })
                } else {
                    // Variable (no offset)
                    Ok(ExpressionNode::Variable { name })
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