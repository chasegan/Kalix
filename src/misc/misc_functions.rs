
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


/// Parse a csv string into a (bool, Option<u32>) the input string must start with true or false,
/// but the u32 is optional.
pub fn parse_csv_to_bool_option_u32(input: &str) -> Result<(bool, Option<u32>), String> {
    let parts: Vec<&str> = input.split(',').map(|s| s.trim()).collect();
    if parts.is_empty() {
        return Err("Input string is empty".to_string());
    }
    let bool_val = parts[0].parse::<bool>()
        .map_err(|_| format!("Failed to parse '{}' as bool", parts[0]))?;
    let u32_val = if parts.len() > 1 && !parts[1].is_empty() {
        Some(parts[1].parse::<u32>()
            .map_err(|_| format!("Failed to parse '{}' as u32", parts[1]))?)
    } else {
        None
    };
    Ok((bool_val, u32_val))
}


/// Helper for checking property values
pub fn require_non_empty<'a>(value: &'a str, name: &str, line_number: usize) -> Result<&'a str, String> {
    if value.is_empty() {
        Err(format!("Error on line {}: Missing value for {}", line_number, name))
    } else {
        Ok(value)
    }
}


/// Format a vector of f64 values as a multi-line table for INI files
///
/// # Arguments
///
/// * `values` - The vector of values to format
/// * `n_cols` - Number of columns per line
/// * `n_spaces` - Number of spaces to indent continuation lines
///
/// # Returns
///
/// A formatted string with values separated by ", " and wrapped to n_cols per line.
/// Each value is followed by ", " including the last value.
///
/// # Example
///
/// ```
/// let values = vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0];
/// let result = format_vec_as_multiline_table(&values, 3, 9);
/// // Returns: "1.0, 2.0, 3.0, \n         4.0, 5.0, 6.0, "
/// ```
pub fn format_vec_as_multiline_table(values: &[f64], n_cols: usize, n_spaces: usize) -> String {
    if values.is_empty() {
        return String::new();
    }

    let mut result = String::new();
    let indent = " ".repeat(n_spaces);

    for (i, value) in values.iter().enumerate() {
        // Add indentation for continuation lines (not the first line)
        if i > 0 && i % n_cols == 0 {
            result.push('\n');
            result.push_str(&indent);
        }

        // Add the value with comma and space
        result.push_str(&value.to_string());
        result.push_str(", ");
    }

    result
}