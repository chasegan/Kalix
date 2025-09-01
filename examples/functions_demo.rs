use std::collections::HashMap;
use kalix::functions::{evaluate_expression, parse_function, EvaluationConfig, VariableContext};

fn main() {
    println!("Kalix Custom Functions Demo");
    println!("==========================");

    // Simple expressions without variables
    println!("\n1. Basic arithmetic:");
    println!("2 + 3 * 4 = {}", evaluate_expression("2 + 3 * 4", &HashMap::new()).unwrap());
    println!("(2 + 3) * 4 = {}", evaluate_expression("(2 + 3) * 4", &HashMap::new()).unwrap());
    println!("2 ^ 3 ^ 2 = {}", evaluate_expression("2 ^ 3 ^ 2", &HashMap::new()).unwrap());

    // Mathematical functions
    println!("\n2. Mathematical functions:");
    println!("sqrt(16) = {}", evaluate_expression("sqrt(16)", &HashMap::new()).unwrap());
    println!("sin(0) = {}", evaluate_expression("sin(0)", &HashMap::new()).unwrap());
    println!("abs(-5) = {}", evaluate_expression("abs(-5)", &HashMap::new()).unwrap());
    println!("min(3, 7, 2) = {}", evaluate_expression("min(3, 7, 2)", &HashMap::new()).unwrap());

    // Variables
    println!("\n3. Variables:");
    let mut vars = HashMap::new();
    vars.insert("x".to_string(), 10.0);
    vars.insert("y".to_string(), 5.0);
    vars.insert("temperature".to_string(), 25.0);
    
    println!("x = {}, y = {}", vars["x"], vars["y"]);
    println!("x + y = {}", evaluate_expression("x + y", &vars).unwrap());
    println!("x * y = {}", evaluate_expression("x * y", &vars).unwrap());
    
    // Conditional expressions
    println!("\n4. Conditional expressions:");
    println!("if(temperature > 20, 'warm', 'cool') = {}", 
        evaluate_expression("if(temperature > 20, 100, 0)", &vars).unwrap());

    // Complex expressions
    println!("\n5. Complex expressions:");
    vars.insert("rainfall".to_string(), 15.0);
    vars.insert("evaporation".to_string(), 8.0);
    
    let expression = "if(rainfall > evaporation, (rainfall - evaporation) * 0.8, 0)";
    println!("Expression: {}", expression);
    println!("Result: {}", evaluate_expression(expression, &vars).unwrap());

    // Parse once, evaluate many times (efficient for repeated use)
    println!("\n6. Parse once, evaluate multiple times:");
    let function = parse_function("sin(x * 3.14159 / 180)").unwrap();
    
    for angle_degrees in [0.0, 30.0, 45.0, 60.0, 90.0] {
        let mut angle_vars = HashMap::new();
        angle_vars.insert("x".to_string(), angle_degrees);
        
        let config = EvaluationConfig::default();
        let context = VariableContext::new(&angle_vars, &config);
        
        let result = function.evaluate(&context).unwrap();
        println!("sin({}°) = {:.4}", angle_degrees, result);
    }

    // Variable extraction
    println!("\n7. Variable extraction:");
    let complex_function = parse_function("sqrt(x^2 + y^2) + sin(angle) * amplitude").unwrap();
    let variables = complex_function.get_variables();
    println!("Variables in expression: {:?}", variables);

    println!("\nDemo completed successfully!");
}