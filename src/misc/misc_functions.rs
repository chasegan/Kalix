
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


pub fn is_valid_variable_name(s: &str) -> bool {
    if let Some(b) = s.bytes().next() {
        // First char must be lowercase alphabetic
        if !b.is_ascii_lowercase() { return false; }
    }
    for b in s.bytes() {
        // All chars must be lowercase alphabetic, or a digit, or '_' or '.'
        if !(b.is_ascii_lowercase() || b.is_ascii_digit() || b == b'_' || b == b'.') {
            return false;
        }
    }
    // Last element cannot be a '.'
    if s.bytes().last() == Some(b'.') { return false; }
    // Looks okay
    true
}


/// Sanitize a name for use in data references within function expressions.
///
/// Converts the input to lowercase and replaces any character that is not
/// a-z, 0-9, or underscore with an underscore.
pub fn sanitize_name(name: &str) -> String {
    name.to_lowercase()
        .chars()
        .map(|ch| {
            if ch.is_ascii_lowercase() || ch.is_ascii_digit() || ch == '_' {
                ch
            } else {
                '_'
            }
        })
        .collect()
}

