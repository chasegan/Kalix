
pub fn make_result_name(node_name: &str, parameter: &str) -> String {
    format!("node.{node_name}.{parameter}")
}

pub fn split_interleaved(interleaved: &[f64]) -> (Vec<f64>, Vec<f64>) {
    let x_values: Vec<f64> = interleaved.iter().step_by(2).copied().collect();
    let y_values: Vec<f64> = interleaved.iter().skip(1).step_by(2).copied().collect();
    (x_values, y_values)
}


pub fn true_or_false(s: &str) -> Result<bool, String> {
    let temp = s.trim().to_lowercase();
    if temp == "true" {
        Ok(true)
    } else if temp == "false" {
        Ok(false)
    } else {
        Err(format!("Expected 'true' or 'false': {}", s))
    }
}


pub fn starts_with_numeric_char(s: &str) -> bool {
    match s.bytes().next() {
        //Some(b) => b.is_ascii_digit() || b == b'.',
        Some(b) => b.is_ascii_digit(),
        None => false,
    }
}

