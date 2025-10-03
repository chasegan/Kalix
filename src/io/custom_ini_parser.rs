use std::collections::HashMap;

#[derive(Debug, Clone)]
pub struct IniProperty {
    pub value: String,           // Clean, joined value
    pub line_number: usize,      // Line where property starts
    pub raw_lines: Vec<String>,  // Original lines for round-tripping
    pub comments: Vec<String>,   // Comments from all continuation lines
}

#[derive(Debug, Clone)]
pub struct IniSection {
    pub properties: HashMap<String, IniProperty>,
    pub leading_comments: Vec<String>, // Comments before [section]
    pub line_number: usize,            // Line where [section] appears
}

#[derive(Debug)]
pub struct IniDocument {
    pub sections: HashMap<String, IniSection>,
    pub trailing_comments: Vec<String>, // Comments at end of file
}

#[derive(Debug)]
enum ParseState {
    BetweenSections,
    InSection(String),
}

impl IniDocument {
    pub fn parse(content: &str) -> Result<Self, String> {
        let mut sections = HashMap::new();
        let mut trailing_comments = Vec::new();
        let mut state = ParseState::BetweenSections;
        let mut pending_comments = Vec::new();

        let lines: Vec<&str> = content.lines().collect();
        let mut line_idx = 0;

        while line_idx < lines.len() {
            let line = lines[line_idx];
            let line_number = line_idx + 1;
            let trimmed = line.trim();

            // Skip empty lines
            if trimmed.is_empty() {
                line_idx += 1;
                continue;
            }

            // Handle comments
            if trimmed.starts_with('#') || trimmed.starts_with(';') {
                pending_comments.push(trimmed.to_string());
                line_idx += 1;
                continue;
            }

            // Handle section headers
            if trimmed.starts_with('[') && trimmed.ends_with(']') {
                let section_name = trimmed[1..trimmed.len()-1].to_string();

                sections.insert(section_name.clone(), IniSection {
                    properties: HashMap::new(),
                    leading_comments: pending_comments.clone(),
                    line_number,
                });

                pending_comments.clear();
                state = ParseState::InSection(section_name);
                line_idx += 1;
                continue;
            }

            // Handle properties or list items
            if let Some(eq_pos) = trimmed.find('=') {
                // Key=value property
                let key = trimmed[..eq_pos].trim().to_string();
                let mut value_part = trimmed[eq_pos + 1..].trim();

                // Check for inline comment
                let mut inline_comment = None;
                if let Some(comment_pos) = Self::find_comment_start(value_part) {
                    inline_comment = Some(value_part[comment_pos..].trim().to_string());
                    value_part = value_part[..comment_pos].trim();
                }

                let mut raw_lines = vec![line.to_string()];
                let mut comments = Vec::new();
                if let Some(comment) = inline_comment {
                    comments.push(comment);
                }
                let mut joined_value = value_part.to_string();

                // Look for continuation lines
                let mut next_line_idx = line_idx + 1;
                while next_line_idx < lines.len() {
                    let next_line = lines[next_line_idx];

                    // Check if this is a continuation line (starts with whitespace)
                    if next_line.starts_with(' ') || next_line.starts_with('\t') {
                        let continued_trimmed = next_line.trim();

                        // Skip empty continuation lines
                        if continued_trimmed.is_empty() {
                            raw_lines.push(next_line.to_string());
                            next_line_idx += 1;
                            continue;
                        }

                        let mut continued_value = continued_trimmed;

                        // Check for inline comment on continuation line
                        if let Some(comment_pos) = Self::find_comment_start(continued_trimmed) {
                            comments.push(continued_trimmed[comment_pos..].trim().to_string());
                            continued_value = continued_trimmed[..comment_pos].trim();
                        }

                        // Join the value (add space if both parts are non-empty)
                        if !joined_value.is_empty() && !continued_value.is_empty() {
                            joined_value.push(' ');
                        }
                        joined_value.push_str(continued_value);

                        raw_lines.push(next_line.to_string());
                        next_line_idx += 1;
                    } else {
                        break;
                    }
                }

                // Create the property
                let property = IniProperty {
                    value: joined_value,
                    line_number,
                    raw_lines,
                    comments,
                };

                // Add to current section
                match &state {
                    ParseState::InSection(section_name) => {
                        if let Some(section) = sections.get_mut(section_name) {
                            section.properties.insert(key, property);
                        }
                    }
                    _ => {
                        return Err(format!("Property '{}' found outside of section at line {}", key, line_number));
                    }
                }

                line_idx = next_line_idx;
                continue;
            } else {
                // Handle list items (lines without = in sections like [inputs] or [outputs])
                match &state {
                    ParseState::InSection(section_name) => {
                        // Use the line content itself as the key (matching configparser behavior)
                        let section = sections.get_mut(section_name).unwrap();

                        let property = IniProperty {
                            value: trimmed.to_string(),
                            line_number,
                            raw_lines: vec![line.to_string()],
                            comments: Vec::new(),
                        };

                        section.properties.insert(trimmed.to_string(), property);
                        line_idx += 1;
                        continue;
                    }
                    _ => {
                        return Err(format!("List item found outside of section at line {}: {}", line_number, line));
                    }
                }
            }

        }

        // Any remaining comments become trailing comments
        trailing_comments.extend(pending_comments);

        Ok(IniDocument {
            sections,
            trailing_comments,
        })
    }

    fn find_comment_start(line: &str) -> Option<usize> {
        // Find the first # or ; that's not inside quotes
        let mut in_quotes = false;
        let mut escape_next = false;

        for (i, ch) in line.char_indices() {
            if escape_next {
                escape_next = false;
                continue;
            }

            match ch {
                '\\' => escape_next = true,
                '"' => in_quotes = !in_quotes,
                '#' | ';' if !in_quotes => return Some(i),
                _ => {}
            }
        }

        None
    }

    /// Convert to the HashMap format expected by existing model loading code
    pub fn to_legacy_format(&self) -> HashMap<String, HashMap<String, Option<String>>> {
        let mut result = HashMap::new();

        for (section_name, section) in &self.sections {
            let mut section_map = HashMap::new();

            for (key, property) in &section.properties {
                // For list sections (inputs/outputs), use None like configparser
                // For regular properties, use Some(value)
                if key == &property.value {
                    // This is a list item (key equals value)
                    section_map.insert(key.clone(), None);
                } else {
                    // This is a regular key=value property
                    section_map.insert(key.clone(), Some(property.value.clone()));
                }
            }

            result.insert(section_name.clone(), section_map);
        }

        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Instant;

    #[test]
    fn test_basic_parsing() {
        let content = r#"
[section1]
key1 = value1
key2 = value2

[section2]
key3 = value3
"#;

        let doc = IniDocument::parse(content).unwrap();
        assert_eq!(doc.sections.len(), 2);

        let section1 = &doc.sections["section1"];
        assert_eq!(section1.properties["key1"].value, "value1");
        assert_eq!(section1.properties["key2"].value, "value2");

        let section2 = &doc.sections["section2"];
        assert_eq!(section2.properties["key3"].value, "value3");
    }

    #[test]
    fn test_line_continuation() {
        let content = r#"
[node.test]
params = 0.01, 40.0, 23.0,
         0.009, 0.043, 130.0,
         0.01, 0.063, 1.0
"#;

        let doc = IniDocument::parse(content).unwrap();
        let section = &doc.sections["node.test"];
        let property = &section.properties["params"];

        assert_eq!(property.value, "0.01, 40.0, 23.0, 0.009, 0.043, 130.0, 0.01, 0.063, 1.0");
        assert_eq!(property.raw_lines.len(), 3);
        assert_eq!(property.line_number, 3);
    }

    #[test]
    fn test_comments() {
        let content = r#"
# Leading comment
[section1]
key1 = value1  # Inline comment
key2 = value2,  # First part
       value3   # Second part
"#;

        let doc = IniDocument::parse(content).unwrap();
        let section = &doc.sections["section1"];

        assert_eq!(section.leading_comments.len(), 1);
        assert_eq!(section.leading_comments[0], "# Leading comment");

        let prop1 = &section.properties["key1"];
        assert_eq!(prop1.comments.len(), 1);
        assert_eq!(prop1.comments[0], "# Inline comment");

        let prop2 = &section.properties["key2"];
        assert_eq!(prop2.value, "value2, value3");
        assert_eq!(prop2.comments.len(), 2);
    }

    #[test]
    fn test_actual_model_file() {
        let path = "/Users/chas/github/Kalix/kalixide/example_models/model_with_every_node_type.ini";
        if let Ok(content) = std::fs::read_to_string(path) {
            let start = Instant::now();
            let doc = IniDocument::parse(&content).unwrap();
            let duration = start.elapsed();

            println!("Parsed {} sections in {:?}", doc.sections.len(), duration);

            // Verify compatibility with legacy format
            let legacy = doc.to_legacy_format();
            assert!(legacy.contains_key("attributes"));
            assert!(legacy.contains_key("inputs"));
            assert!(legacy.contains_key("outputs"));

            // Check a node was parsed correctly
            if let Some(node2) = legacy.get("node.node2_sacramento") {
                assert_eq!(node2.get("type"), Some(&Some("sacramento".to_string())));
                assert!(node2.get("params").is_some());
            }
        }
    }

    #[test]
    fn test_large_model_file() {
        let path = "/Users/chas/github/Kalix/src/tests/example_models/3/model_500_nodes.ini";
        if let Ok(content) = std::fs::read_to_string(path) {
            let start = Instant::now();
            let doc = IniDocument::parse(&content).unwrap();
            let duration = start.elapsed();

            println!("Parsed {} sections in {:?}", doc.sections.len(), duration);
            assert!(doc.sections.len() > 400); // Should have many nodes

            // Test performance - should be very fast
            assert!(duration.as_millis() < 100); // Should parse in under 100ms
        }
    }

    #[test]
    fn test_legacy_format_conversion() {
        let content = r#"
[attributes]
ini_version = 0.0.1

[node.test]
type = inflow
params = 1.0, 2.0, 3.0
"#;

        let doc = IniDocument::parse(content).unwrap();
        let legacy = doc.to_legacy_format();

        assert_eq!(legacy.len(), 2);
        assert_eq!(legacy["attributes"]["ini_version"], Some("0.0.1".to_string()));
        assert_eq!(legacy["node.test"]["type"], Some("inflow".to_string()));
        assert_eq!(legacy["node.test"]["params"], Some("1.0, 2.0, 3.0".to_string()));
    }

    #[test]
    fn test_configparser_comparison() {
        use configparser::ini::Ini;

        let content = r#"
[inputs]
/path/to/file1.csv
/path/to/file2.csv

[outputs]
node.test.output1
node.test.output2
"#;

        // Test with configparser
        let configparser_result = Ini::new().read(content.to_string()).unwrap();
        println!("Configparser inputs: {:#?}", configparser_result.get("inputs"));

        // Test with our parser
        let our_result = IniDocument::parse(content).unwrap();
        let legacy = our_result.to_legacy_format();
        println!("Our parser inputs: {:#?}", legacy.get("inputs"));
    }
}