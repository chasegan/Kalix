
/// Format an f64 value intelligently based on its magnitude
///
/// This function provides a compact, human-readable string representation of any f64 value
/// that is guaranteed to be parsable and non-empty.
///
/// # Formatting rules:
/// - Very small values (< 1e-4) use scientific notation
/// - Very large values (>= 1e6) use scientific notation
/// - Values between 0.0001 and 999999 use normal notation
/// - Trailing zeros after decimal point are removed
/// - Maximum of 10 significant figures for precision
///
/// # Examples:
/// - 0.5 → "0.5"
/// - 0.3333333 → "0.3333333"
/// - 0.00001 → "1e-5"
/// - 1000000 → "1e+6"
/// - 1.0 → "1"
/// - 0.0 → "0"
pub fn format_f64(value: f64) -> String {
    let abs_value = value.abs();

    // Choose format based on magnitude
    let formatted = if abs_value == 0.0 {
        "0".to_string()
    } else if abs_value < 1e-4 || abs_value >= 1e6 {
        // Use scientific notation
        // For very large numbers, use more precision to avoid rounding
        let precision = if abs_value >= 1e10 { 13 } else { 10 };
        let s = format!("{:.prec$e}", value, prec = precision);
        // Remove trailing zeros from the mantissa only (not the exponent)
        if let Some(e_pos) = s.find('e') {
            let (mantissa, exponent_part) = s.split_at(e_pos);
            let cleaned_mantissa = mantissa.trim_end_matches('0').trim_end_matches('.');

            // Parse the exponent (skip the 'e')
            let exp_str = &exponent_part[1..];
            if let Ok(exp_num) = exp_str.parse::<i32>() {
                // Format with explicit sign
                if exp_num >= 0 {
                    format!("{}e+{}", cleaned_mantissa, exp_num)
                } else {
                    format!("{}e{}", cleaned_mantissa, exp_num)
                }
            } else {
                // Fallback - shouldn't happen with valid scientific notation
                format!("{}{}", cleaned_mantissa, exponent_part)
            }
        } else {
            s
        }
    } else if abs_value >= 1.0 {
        // For values >= 1, use up to 10 significant figures
        let s = format!("{:.10}", value);
        // If it has a decimal point, remove trailing zeros
        if s.contains('.') {
            s.trim_end_matches('0').trim_end_matches('.').to_string()
        } else {
            s
        }
    } else {
        // For values between 0.0001 and 1, show up to 10 decimal places
        let s = format!("{:.10}", value);
        // Remove trailing zeros
        s.trim_end_matches('0').trim_end_matches('.').to_string()
    };

    formatted
}

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


/// Set a property in an INI document only if the value is not empty
pub fn set_property_if_not_empty(ini_doc: &mut crate::io::custom_ini_parser::IniDocument,
                                   section: &str,
                                   property: &str,
                                   value: &str) {
    if !value.is_empty() {
        ini_doc.set_property(section, property, value);
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
/// use kalix::misc::misc_functions::format_vec_as_multiline_table;
///
/// let values = vec![1.0, 2.0, 3.0, 4.0, 5.0, 6.0];
/// let result = format_vec_as_multiline_table(&values, 3, 9);
/// assert_eq!(result, "1, 2, 3, \n         4, 5, 6, ");
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

