use std::collections::HashMap;
use crate::functions::{evaluate_expression, parse_function};

#[test]
fn test_basic_arithmetic() {
    let vars = HashMap::new();
    
    assert_eq!(evaluate_expression("2 + 3", &vars).unwrap(), 5.0);
    assert_eq!(evaluate_expression("10 - 4", &vars).unwrap(), 6.0);
    assert_eq!(evaluate_expression("3 * 7", &vars).unwrap(), 21.0);
    assert_eq!(evaluate_expression("15 / 3", &vars).unwrap(), 5.0);
    assert_eq!(evaluate_expression("2 ^ 3", &vars).unwrap(), 8.0);
}

#[test]
fn test_operator_precedence() {
    let vars = HashMap::new();
    
    // Multiplication before addition
    assert_eq!(evaluate_expression("2 + 3 * 4", &vars).unwrap(), 14.0);
    
    // Power before multiplication
    assert_eq!(evaluate_expression("2 * 3 ^ 2", &vars).unwrap(), 18.0);
    
    // Parentheses override precedence
    assert_eq!(evaluate_expression("(2 + 3) * 4", &vars).unwrap(), 20.0);
}

#[test]
fn test_variables() {
    let mut vars = HashMap::new();
    vars.insert("x".to_string(), 5.0);
    vars.insert("y".to_string(), 3.0);
    
    assert_eq!(evaluate_expression("x + y", &vars).unwrap(), 8.0);
    assert_eq!(evaluate_expression("x * y", &vars).unwrap(), 15.0);
    assert_eq!(evaluate_expression("x ^ y", &vars).unwrap(), 125.0);
}

#[test]
fn test_mathematical_functions() {
    let vars = HashMap::new();
    
    assert_eq!(evaluate_expression("abs(-5)", &vars).unwrap(), 5.0);
    assert_eq!(evaluate_expression("sqrt(16)", &vars).unwrap(), 4.0);
    assert_eq!(evaluate_expression("min(3, 7, 2)", &vars).unwrap(), 2.0);
    assert_eq!(evaluate_expression("max(3, 7, 2)", &vars).unwrap(), 7.0);
    assert_eq!(evaluate_expression("if(1, 100, 200)", &vars).unwrap(), 100.0);
    assert_eq!(evaluate_expression("if(0, 100, 200)", &vars).unwrap(), 200.0);
}

#[test]
fn test_comparison_operators() {
    let vars = HashMap::new();
    
    assert_eq!(evaluate_expression("5 > 3", &vars).unwrap(), 1.0);
    assert_eq!(evaluate_expression("3 > 5", &vars).unwrap(), 0.0);
    assert_eq!(evaluate_expression("5 == 5", &vars).unwrap(), 1.0);
    assert_eq!(evaluate_expression("5 != 3", &vars).unwrap(), 1.0);
}

#[test]
fn test_logical_operators() {
    let vars = HashMap::new();
    
    assert_eq!(evaluate_expression("1 && 1", &vars).unwrap(), 1.0);
    assert_eq!(evaluate_expression("1 && 0", &vars).unwrap(), 0.0);
    assert_eq!(evaluate_expression("1 || 0", &vars).unwrap(), 1.0);
    assert_eq!(evaluate_expression("!0", &vars).unwrap(), 1.0);
    assert_eq!(evaluate_expression("!1", &vars).unwrap(), 0.0);
}

#[test]
fn test_complex_expressions() {
    let mut vars = HashMap::new();
    vars.insert("x".to_string(), 10.0);
    vars.insert("y".to_string(), 5.0);
    vars.insert("z".to_string(), 2.0);
    
    // Complex arithmetic with variables
    let result = evaluate_expression("(x + y) * z", &vars).unwrap();
    assert_eq!(result, 30.0);
    
    // Conditional with comparison
    let result = evaluate_expression("if(x > y, x * 2, y * 2)", &vars).unwrap();
    assert_eq!(result, 20.0);
    
    // Mixed operations
    let result = evaluate_expression("sqrt(x) + abs(y - z * 5)", &vars).unwrap();
    assert!((result - (10.0_f64.sqrt() + (5.0_f64 - 10.0_f64).abs())).abs() < 1e-10);
}

#[test]
fn test_trigonometric_functions() {
    let vars = HashMap::new();
    
    let result = evaluate_expression("sin(0)", &vars).unwrap();
    assert!((result - 0.0).abs() < 1e-10);
    
    let result = evaluate_expression("cos(0)", &vars).unwrap();
    assert!((result - 1.0).abs() < 1e-10);
    
    // Using pi/2 approximation
    let pi_half = std::f64::consts::PI / 2.0;
    let expr = format!("sin({})", pi_half);
    let result = evaluate_expression(&expr, &vars).unwrap();
    assert!((result - 1.0).abs() < 1e-10);
}

#[test]
fn test_error_handling() {
    let vars = HashMap::new();
    
    // Missing variable should error
    assert!(evaluate_expression("x + 5", &vars).is_err());
    
    // Division by zero should error  
    assert!(evaluate_expression("5 / 0", &vars).is_err());
    
    // Square root of negative should error
    assert!(evaluate_expression("sqrt(-1)", &vars).is_err());
    
    // Invalid syntax should error
    assert!(parse_function("2 + * 3").is_err());
}

#[test]
fn test_variable_extraction() {
    let function = parse_function("x + y * sin(z)").unwrap();
    let variables = function.get_variables();
    
    assert_eq!(variables.len(), 3);
    assert!(variables.contains("x"));
    assert!(variables.contains("y"));
    assert!(variables.contains("z"));
}

#[test]
fn test_edge_cases() {
    let vars = HashMap::new();
    
    // Empty parentheses for functions
    assert_eq!(evaluate_expression("sum()", &vars).is_err(), true);
    
    // Single number
    assert_eq!(evaluate_expression("42", &vars).unwrap(), 42.0);
    
    // Unary operators
    assert_eq!(evaluate_expression("-5", &vars).unwrap(), -5.0);
    assert_eq!(evaluate_expression("+7", &vars).unwrap(), 7.0);
    
    // Nested function calls
    assert_eq!(evaluate_expression("abs(sin(0))", &vars).unwrap(), 0.0);
}