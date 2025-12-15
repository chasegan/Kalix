# Kalix Custom Functions Module
## Product Requirements Document (PRD)

**Version:** 1.0  
**Date:** September 2025  
**Status:** Draft  
**Module:** `src/functions/`

---

## Executive Summary

The Custom Functions Module enables users to define mathematical expressions as strings that can be parsed once at simulation startup and evaluated efficiently at every timestep. This feature provides flexible, user-defined calculations within Kalix models while maintaining high performance through separation of parsing and evaluation phases.

**Key Value Propositions:**
- **Performance-Optimized**: Parse once, evaluate many times architecture
- **Mathematical Flexibility**: Support for arithmetic, trigonometric, and logical operations
- **Variable Integration**: Dynamic variable resolution from context dictionaries
- **Standard Compliance**: Follows mathematical operator precedence and evaluation rules
- **Type Safety**: Rust-based implementation with comprehensive error handling

---

## Feature Overview

### Core Functionality
Custom functions allow users to define mathematical expressions as strings that:
1. **Parse** into an Abstract Syntax Tree (AST) at simulation initialization
2. **Evaluate** the AST repeatedly during simulation timesteps
3. **Reference variables** resolved from runtime dictionaries
4. **Return f64 values** for integration with Kalix's numerical systems

### Use Cases
- **Dynamic parameter calculations** based on model state
- **Complex mathematical relationships** between variables
- **Custom boundary conditions** and constraints
- **User-defined transformations** of input data
- **Conditional logic** in model behavior

---

## Functional Requirements

### 1. Function Definition & Syntax

#### String-Based Definition
```rust
// Example function definitions
//"2.5 * x + 10.0"
//"sin(theta) * cos(phi) + offset"
//"if(temperature > 25.0, evap_rate * 1.2, evap_rate)"
//"max(0.0, rainfall - losses)"
```

#### Supported Operations
- **Arithmetic**: `+`, `-`, `*`, `/`, `%` (modulo), `^` or `**` (power)
- **Trigonometric**: `sin()`, `cos()`, `tan()`, `asin()`, `acos()`, `atan()`, `atan2()`
- **Exponential/Logarithmic**: `exp()`, `ln()`, `log10()`, `log2()`, `pow()`
- **Mathematical**: `abs()`, `sqrt()`, `ceil()`, `floor()`, `round()`
- **Comparison**: `>`, `<`, `>=`, `<=`, `==`, `!=`
- **Logical**: `&&` (and), `||` (or), `!` (not)
- **Conditional**: `if(condition, true_value, false_value)`
- **Aggregation**: `min()`, `max()`, `sum()`, `avg()`

#### Operator Precedence (High to Low)
1. **Parentheses**: `()`
2. **Functions**: `sin()`, `cos()`, etc.
3. **Unary operators**: `-x`, `+x`, `!x`
4. **Power**: `^`, `**`
5. **Multiplication/Division**: `*`, `/`, `%`
6. **Addition/Subtraction**: `+`, `-`
7. **Comparison**: `>`, `<`, `>=`, `<=`, `==`, `!=`
8. **Logical AND**: `&&`
9. **Logical OR**: `||`
10. **Conditional**: `if()`

#### Variable References
- **Naming**: Alphanumeric identifiers starting with letter or underscore
- **Case sensitivity**: Variables are case-sensitive
- **Scope**: Variables resolved from runtime dictionary
- **Missing variables**: Configurable behavior (error vs. default value)

### 2. Parsing System

#### Parse-Time Operations
```rust
//pub struct FunctionParser {
//    pub fn parse(expression: &str) -> Result<ParsedFunction, ParseError>;
//}

pub struct ParsedFunction {
    ast: Box<dyn ASTNode>,
    variables: HashSet<String>,
}

pub enum ParseError {
    SyntaxError { position: usize, message: String },
    UnknownFunction { name: String, position: usize },
    InvalidExpression { message: String },
}
```

#### Abstract Syntax Tree (AST)
- **Binary operations**: Left operand, operator, right operand
- **Unary operations**: Operator, operand
- **Function calls**: Function name, arguments list
- **Variables**: Variable name for runtime resolution
- **Constants**: Literal numerical values

#### Validation Requirements
- **Syntax validation**: Proper parentheses matching, operator placement
- **Function validation**: Known function names, correct argument counts
- **Variable tracking**: Catalog all referenced variables for runtime validation

### 3. Evaluation System

#### Runtime Evaluation
```rust
//pub trait Evaluatable {
//    fn evaluate(&self, variables: &HashMap<String, f64>) -> Result<f64, EvaluationError>;
//}
//
//pub enum EvaluationError {
//    VariableNotFound { name: String },
//    DivisionByZero,
//    InvalidOperation { message: String },
//    MathematicalError { function: String, args: Vec<f64> },
//}
```

#### Performance Requirements
- **Sub-microsecond evaluation** for typical expressions
- **Memory efficiency**: Minimal allocations during evaluation
- **Thread safety**: Concurrent evaluation support
- **Cache friendly**: AST traversal optimized for CPU cache

#### Variable Resolution
- **Dynamic lookup**: Variables resolved from runtime dictionary
- **Type coercion**: All variables treated as f64
- **Error handling**: Configurable behavior for missing variables
- **Validation**: Optional pre-evaluation variable existence check

---

## Technical Architecture

### Module Structure
```
src/functions/
├── mod.rs              # Public module interface
├── parser.rs           # String parsing and AST generation
├── ast.rs              # Abstract Syntax Tree definitions
├── evaluator.rs        # Runtime evaluation engine
├── operators.rs        # Operator definitions and precedence
├── functions.rs        # Built-in mathematical functions
├── errors.rs           # Error types and handling
└── tests/
    ├── parser_tests.rs
    ├── evaluator_tests.rs
    └── integration_tests.rs
```

### Core Components

#### 1. Parser (`parser.rs`)
```rust
//pub struct FunctionParser {
//    // Tokenizer for string processing
//    // Recursive descent parser implementation
//    // AST construction logic
//}
//
//// Public parsing interface
//impl FunctionParser {
//    pub fn new() -> Self;
//    pub fn parse(&self, expression: &str) -> Result<ParsedFunction, ParseError>;
//}
```

#### 2. AST Nodes (`ast.rs`)
```rust
//pub trait ASTNode: Send + Sync {
//    fn evaluate(&self, context: &VariableContext) -> Result<f64, EvaluationError>;
//    fn get_variables(&self) -> HashSet<String>;
//}
//
//pub enum ExpressionNode {
//    BinaryOp { left: Box<dyn ASTNode>, op: Operator, right: Box<dyn ASTNode> },
//    UnaryOp { op: UnaryOperator, operand: Box<dyn ASTNode> },
//    FunctionCall { name: String, args: Vec<Box<dyn ASTNode>> },
//    Variable { name: String },
//    Constant { value: f64 },
//}
```

#### 3. Evaluator (`evaluator.rs`)
```rust
//pub struct VariableContext<'a> {
//    variables: &'a HashMap<String, f64>,
//    config: &'a EvaluationConfig,
//}
//
//pub struct EvaluationConfig {
//    pub missing_variable_behavior: MissingVariableBehavior,
//    pub division_by_zero_behavior: DivisionByZeroBehavior,
//    pub math_error_behavior: MathErrorBehavior,
//}
```

### Integration with Kalix Core

#### Model Integration
```rust
//// In model nodes
//pub struct CustomFunction {
//    parsed: ParsedFunction,
//    required_variables: HashSet<String>,
//}
//
//impl CustomFunction {
//    pub fn new(expression: &str) -> Result<Self, ParseError>;
//    pub fn evaluate(&self, model_state: &ModelState) -> Result<f64, EvaluationError>;
//}
```

#### Serialization Support
```rust
// JSON/TOML representation
//{
//    "function_expression": "sin(x * pi / 180) * amplitude",
//    "description": "Sine wave with amplitude scaling"
//}
```

---

## Performance Requirements

### Parsing Performance
- **Initialization time**: < 1ms for typical expressions (< 100 characters)
- **Memory usage**: < 1KB per parsed function
- **Scalability**: Support 1000+ functions without performance degradation

### Evaluation Performance
- **Execution time**: < 1 microsecond for simple expressions (< 10 operations)
- **Complex expressions**: < 10 microseconds for expressions with 50+ operations
- **Memory allocation**: Zero allocations during evaluation
- **Throughput**: > 1M evaluations/second on modern hardware

### Memory Management
- **AST size**: Minimal memory footprint for parsed expressions
- **Variable context**: Efficient dictionary lookups
- **Thread safety**: Lock-free evaluation where possible

---

## Error Handling & Validation

### Parse-Time Errors
- **Syntax errors**: Clear error messages with position information
- **Invalid functions**: Unknown function names with suggestions
- **Malformed expressions**: Detailed diagnostic information
- **Recovery**: Attempt to continue parsing for multiple error reporting

### Runtime Errors
- **Variable resolution**: Configurable missing variable handling
- **Mathematical errors**: Domain errors, overflow, underflow
- **Division by zero**: Configurable behavior (error, infinity, NaN)
- **Function errors**: Invalid arguments to mathematical functions

### Error Reporting
```rust
//#[derive(Debug)]
//pub struct ParseError {
//    pub position: usize,
//    pub line: usize,
//    pub column: usize,
//    pub message: String,
//    pub suggestion: Option<String>,
//}
//
//#[derive(Debug)]
//pub struct EvaluationError {
//    pub error_type: EvaluationErrorType,
//    pub context: String,
//    pub variable_name: Option<String>,
//}
```

---

## Security Considerations

### Input Validation
- **Expression length limits**: Prevent extremely long expressions
- **Recursion depth**: Limit AST depth to prevent stack overflow
- **Function call limits**: Prevent excessive nested function calls
- **Variable name validation**: Sanitize variable names

### Resource Protection
- **Evaluation timeouts**: Prevent infinite loops in complex expressions
- **Memory limits**: Cap memory usage during parsing and evaluation
- **CPU protection**: Limit computational complexity

### Safe Defaults
- **Conservative parsing**: Reject ambiguous expressions
- **Explicit operators**: Require explicit multiplication operators
- **Controlled function set**: Limited, well-tested mathematical functions

---

## Testing Strategy

### Unit Testing
- **Parser tests**: All operators, functions, edge cases
- **AST tests**: Node construction, traversal, evaluation
- **Evaluator tests**: Mathematical accuracy, error conditions
- **Error handling**: All error conditions and recovery scenarios

### Integration Testing
- **Model integration**: Functions within Kalix model context
- **Performance testing**: Benchmark parsing and evaluation speeds
- **Memory testing**: Leak detection and usage profiling
- **Concurrency testing**: Thread safety validation

### Validation Testing
- **Mathematical accuracy**: Compare against known mathematical libraries
- **Precision testing**: Floating-point accuracy and consistency
- **Edge cases**: Boundary conditions, special values (NaN, infinity)
- **Stress testing**: Large expressions, many variables

### Test Coverage Requirements
- **Line coverage**: > 95%
- **Branch coverage**: > 90%
- **Function coverage**: 100%
- **Integration coverage**: All public API paths

---

## Documentation Requirements

### User Documentation
- **Function syntax guide**: Complete operator and function reference
- **Examples**: Common use cases and patterns
- **Best practices**: Performance tips and recommendations
- **Troubleshooting**: Common errors and solutions

### Developer Documentation
- **API reference**: Complete interface documentation
- **Architecture guide**: Module design and interaction patterns
- **Extension guide**: Adding new functions and operators
- **Performance guide**: Optimization techniques and benchmarks

### Example Usage
```rust
//// Basic usage example
//let parser = FunctionParser::new();
//let function = parser.parse("2 * x + sin(y)")?;
//
//let mut variables = HashMap::new();
//variables.insert("x".to_string(), 3.14);
//variables.insert("y".to_string(), 1.57);
//
//let result = function.evaluate(&variables)?;
//println!("Result: {}", result); // Result: 7.28
```

---

## Future Enhancements

### Phase 2 Features
- **User-defined functions**: Allow users to define reusable functions
- **Array operations**: Support for vector/array mathematical operations
- **Statistical functions**: Distributions, statistical tests
- **Time series functions**: Lag, moving averages, derivatives

### Phase 3 Features
- **Optimization**: JIT compilation for frequently used expressions
- **Debugging**: Expression step-through and variable inspection
- **Profiling**: Performance analysis and bottleneck identification
- **Symbolic math**: Symbolic differentiation and simplification

### Integration Opportunities
- **AI assistance**: Natural language to expression conversion
- **Visual editor**: Graphical function building interface
- **External libraries**: Integration with specialized math libraries
- **GPU acceleration**: CUDA/OpenCL for complex computations

---

## Success Criteria

### Functional Success
- **Complete parsing**: Handle all specified operators and functions
- **Accurate evaluation**: Mathematical correctness for all operations
- **Error handling**: Graceful handling of all error conditions
- **Integration**: Seamless use within Kalix models

### Performance Success
- **Parsing speed**: Meet sub-millisecond parsing requirements
- **Evaluation speed**: Achieve sub-microsecond evaluation targets
- **Memory efficiency**: Minimal memory footprint and zero runtime allocations
- **Scalability**: Support large numbers of concurrent function evaluations

### Quality Success
- **Test coverage**: Meet coverage requirements across all test types
- **Documentation**: Complete user and developer documentation
- **Reliability**: Zero crashes or data corruption under normal usage
- **Maintainability**: Clean, well-documented code architecture

---

## Development Phases

### Phase 1: Core Implementation (4-6 weeks)
**Priority: Critical**
- Basic parser with arithmetic operations
- AST construction and evaluation
- Variable resolution system
- Error handling framework
- Unit tests for core functionality

### Phase 2: Extended Operations (2-3 weeks)
**Priority: High**
- Trigonometric and mathematical functions
- Comparison and logical operators
- Conditional expressions (if/then/else)
- Integration testing with Kalix models

### Phase 3: Polish & Documentation (2 weeks)
**Priority: Medium**
- Performance optimization
- Comprehensive error messages
- Complete documentation
- Integration examples

### Development Approach
- **Test-driven development**: Write tests before implementation
- **Incremental delivery**: Working functionality at each phase
- **Performance focus**: Continuous benchmarking and optimization
- **Security review**: Regular security analysis of parsing logic

---

## Risk Mitigation

### Technical Risks
- **Performance bottlenecks**: Early prototyping and benchmarking
- **Parsing complexity**: Incremental parser development with extensive testing
- **Mathematical accuracy**: Validation against established mathematical libraries

### Integration Risks
- **Kalix compatibility**: Close coordination with core model development
- **Breaking changes**: Versioned API with backward compatibility
- **Memory management**: Careful attention to Rust ownership patterns

### Security Risks
- **Expression injection**: Input validation and sandboxing
- **Resource exhaustion**: Limits and timeouts for all operations
- **Parser vulnerabilities**: Security-focused code review

---

## Conclusion

The Custom Functions Module represents a significant enhancement to Kalix's flexibility and user control. The parse-once, evaluate-many architecture ensures high performance while providing users with powerful mathematical expression capabilities.

Success depends on careful attention to performance requirements, comprehensive testing, and seamless integration with existing Kalix systems. The modular design allows for incremental development and future enhancements while maintaining code quality and performance standards.