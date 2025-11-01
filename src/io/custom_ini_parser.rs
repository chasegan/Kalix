use std::collections::HashMap;
use indexmap::IndexMap;

#[derive(Debug, Clone)]
pub struct IniProperty {
    pub value: String,                // Clean, joined value
    pub line_number: usize,           // Line where property starts
    pub raw_lines: Vec<String>,       // Original lines for round-tripping
    pub leading_lines: Vec<String>,    // Comments and blank lines before this property
    pub comments: Vec<Option<String>>, // Inline comments indexed by continuation line (0=first line, 1=second line, etc.)
    pub valid: bool,                  // Used for mark-and-sweep updates
}

#[derive(Debug, Clone)]
pub struct IniSection {
    pub properties: IndexMap<String, IniProperty>,
    pub leading_lines: Vec<String>,    // Comments and blank lines before [section]
    pub line_number: usize,            // Line where [section] appears
    pub valid: bool,                   // Used for mark-and-sweep updates
}

#[derive(Debug, Clone)]
pub struct IniDocument {
    pub sections: IndexMap<String, IniSection>,
    pub trailing_comments: Vec<String>, // Comments at end of file
}

#[derive(Debug)]
enum ParseState {
    BetweenSections,
    InSection(String),
}

impl IniDocument {
    /// Create a new empty IniDocument
    pub fn new() -> Self {
        IniDocument {
            sections: IndexMap::new(),
            trailing_comments: Vec::new(),
        }
    }

    pub fn parse(content: &str) -> Result<Self, String> {
        let mut sections = IndexMap::new();
        let mut trailing_comments = Vec::new();
        let mut state = ParseState::BetweenSections;
        let mut pending_comments = Vec::new();

        let lines: Vec<&str> = content.lines().collect();
        let mut line_idx = 0;

        while line_idx < lines.len() {
            let line = lines[line_idx];
            let line_number = line_idx + 1;
            let trimmed = line.trim();

            // Handle empty lines - store as blank leading lines
            if trimmed.is_empty() {
                pending_comments.push(line.to_string());
                line_idx += 1;
                continue;
            }

            // Handle comments
            if trimmed.starts_with('#') || trimmed.starts_with(';') {
                pending_comments.push(line.to_string());
                line_idx += 1;
                continue;
            }

            // Handle section headers
            if trimmed.starts_with('[') && trimmed.ends_with(']') {
                let section_name = trimmed[1..trimmed.len()-1].to_string();

                sections.insert(section_name.clone(), IniSection {
                    properties: IndexMap::new(),
                    leading_lines: pending_comments.clone(),
                    line_number,
                    valid: true,
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
                // Store comment for first line (Some if exists, None if not)
                comments.push(inline_comment.map(|s| s.to_string()));
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
                            comments.push(None); // Empty line has no comment
                            next_line_idx += 1;
                            continue;
                        }

                        let mut continued_value = continued_trimmed;
                        let mut line_comment = None;

                        // Check for inline comment on continuation line
                        if let Some(comment_pos) = Self::find_comment_start(continued_trimmed) {
                            line_comment = Some(continued_trimmed[comment_pos..].trim().to_string());
                            continued_value = continued_trimmed[..comment_pos].trim();
                        }

                        // Join the value (add space if both parts are non-empty)
                        if !joined_value.is_empty() && !continued_value.is_empty() {
                            joined_value.push(' ');
                        }
                        joined_value.push_str(continued_value);

                        raw_lines.push(next_line.to_string());
                        comments.push(line_comment); // Store comment (or None) for this continuation line
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
                    leading_lines: pending_comments.clone(),
                    comments,
                    valid: true,
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

                pending_comments.clear();
                line_idx = next_line_idx;
                continue;
            } else {
                // Handle list items (lines without = in sections like [inputs] or [outputs])
                match &state {
                    ParseState::InSection(section_name) => {
                        // Use the line content as the key, empty value indicates list item
                        let section = sections.get_mut(section_name).unwrap();

                        let property = IniProperty {
                            value: String::new(),  // Empty value for list items
                            line_number,
                            raw_lines: vec![line.to_string()],
                            leading_lines: pending_comments.clone(),
                            comments: Vec::new(),
                            valid: true,
                        };

                        section.properties.insert(trimmed.to_string(), property);
                        pending_comments.clear();
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

    /// Set a property value in a section
    /// Creates the section and/or property if it doesn't exist
    /// Updates the value while preserving line numbers for unchanged properties
    /// Marks both section and property as valid
    /// Returns a mutable reference to the section for further modifications
    pub fn set_property(&mut self, section_name: &str, key: &str, new_value: &str) -> &mut IniSection {
        // Get or create the section
        let section = self.sections.entry(section_name.to_string()).or_insert_with(|| {
            IniSection {
                properties: IndexMap::new(),
                leading_lines: Vec::new(),
                line_number: 0, // New sections don't have a line number from original file
                valid: true,
            }
        });

        // Mark section as valid
        section.valid = true;

        // Get or create the property
        if let Some(property) = section.properties.get_mut(key) {
            // Update existing property - preserve line_number and comments
            property.value = new_value.to_string();
            property.raw_lines.clear(); // Clear raw_lines to indicate this was modified
            // Preserve comments for round-trip
            property.valid = true; // Mark as valid
        } else {
            // Create new property
            section.properties.insert(key.to_string(), IniProperty {
                value: new_value.to_string(),
                line_number: 0, // New properties don't have a line number from original file
                raw_lines: Vec::new(), // Empty indicates newly created
                leading_lines: Vec::new(),
                comments: Vec::new(),
                valid: true,
            });
        }

        section
    }

    /// Get a property value from a section
    pub fn get_property(&self, section_name: &str, key: &str) -> Option<&str> {
        self.sections.get(section_name)
            .and_then(|section| section.properties.get(key))
            .map(|property| property.value.as_str())
    }

    /// Mark all sections and properties as invalid
    /// Used for mark-and-sweep updates: invalidate all, update what you need, then remove invalids
    pub fn invalidate_all(&mut self) {
        for section in self.sections.values_mut() {
            section.valid = false;
            for property in section.properties.values_mut() {
                property.valid = false;
            }
        }
    }

    /// Remove all sections and properties marked as invalid
    /// Used after invalidate_all() and selective updates via set_property()
    pub fn remove_invalid_sections_and_properties(&mut self) {
        // Remove invalid properties from each section
        for section in self.sections.values_mut() {
            section.properties.retain(|_, property| property.valid);
        }

        // Remove invalid sections (or sections that are now empty)
        self.sections.retain(|_, section| section.valid && !section.properties.is_empty());
    }

    /// Convert the IniDocument back to an INI string
    /// Uses raw_lines for unchanged properties to preserve original formatting
    /// Formats modified properties in a canonical way
    pub fn to_string(&self) -> String {
        let mut result = String::new();

        // Sort sections for consistent output
        // Iterate in insertion order (preserved by IndexMap)
        for section_name in self.sections.keys() {
            let section = &self.sections[section_name];

            // Add leading comments
            for comment in &section.leading_lines {
                result.push_str(comment);
                result.push('\n');
            }

            // Add section header
            result.push_str(&format!("[{}]\n", section_name));

            // Iterate properties in insertion order (preserved by IndexMap)
            for prop_name in section.properties.keys() {
                let property = &section.properties[prop_name];

                // Add leading comments before this property
                for comment in &property.leading_lines {
                    result.push_str(comment);
                    result.push('\n');
                }

                if property.raw_lines.is_empty() {
                    // Property was modified or newly created - format canonically
                    // Check if this is a list item (empty value = no key=value syntax)
                    if property.value.is_empty() {
                        // List item (no key=value syntax)
                        result.push_str(prop_name);
                        result.push('\n');
                    } else {
                        // Regular property - may be multi-line
                        let value_lines: Vec<&str> = property.value.split('\n').collect();

                        for (i, value_line) in value_lines.iter().enumerate() {
                            if i == 0 {
                                // First line: "key = value"
                                result.push_str(&format!("{} = {}", prop_name, value_line));
                            } else {
                                // Continuation lines: just the value (already indented)
                                result.push_str(value_line);
                            }

                            // Add comment if one exists for this line
                            if i < property.comments.len() {
                                if let Some(ref comment) = property.comments[i] {
                                    result.push_str("  ");  // Add spacing before comment
                                    result.push_str(comment);
                                }
                            }

                            result.push('\n');
                        }
                    }
                } else {
                    // Property unchanged - use original raw_lines
                    for raw_line in &property.raw_lines {
                        result.push_str(raw_line);
                        result.push('\n');
                    }
                }
            }
        }

        // Add trailing comments
        for comment in &self.trailing_comments {
            result.push_str(comment);
            result.push('\n');
        }

        result
    }

    /// Convert to the HashMap format expected by existing model loading code
    pub fn to_legacy_format(&self) -> HashMap<String, HashMap<String, Option<String>>> {
        let mut result = HashMap::new();

        for (section_name, section) in &self.sections {
            let mut section_map = HashMap::new();

            for (key, property) in &section.properties {
                // For list sections (inputs/outputs), use None like configparser
                // For regular properties, use Some(value)
                if property.value.is_empty() {
                    // This is a list item (empty value)
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

        assert_eq!(section.leading_lines.len(), 2);
        assert_eq!(section.leading_lines[0], ""); // Blank line at start
        assert_eq!(section.leading_lines[1], "# Leading comment");

        let prop1 = &section.properties["key1"];
        assert_eq!(prop1.comments.len(), 1);
        assert_eq!(prop1.comments[0], Some("# Inline comment".to_string()));

        let prop2 = &section.properties["key2"];
        assert_eq!(prop2.value, "value2, value3");
        assert_eq!(prop2.comments.len(), 2);
        assert_eq!(prop2.comments[0], Some("# First part".to_string()));
        assert_eq!(prop2.comments[1], Some("# Second part".to_string()));
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
    fn test_set_property() {
        let content = r#"
[node.test]
type = gr4j
params = 100.0, 2.0, 50.0, 0.5
"#;

        let mut doc = IniDocument::parse(content).unwrap();

        // Test updating existing property
        doc.set_property("node.test", "params", "200.0, 3.0, 60.0, 0.6");
        assert_eq!(doc.get_property("node.test", "params"), Some("200.0, 3.0, 60.0, 0.6"));

        // Verify raw_lines was cleared (indicates modification)
        let section = &doc.sections["node.test"];
        let property = &section.properties["params"];
        assert!(property.raw_lines.is_empty());
        assert_eq!(property.line_number, 4); // Original line number preserved

        // Test adding new property to existing section
        doc.set_property("node.test", "new_param", "42");
        assert_eq!(doc.get_property("node.test", "new_param"), Some("42"));

        // Test creating new section with property
        doc.set_property("node.new_node", "type", "inflow");
        assert_eq!(doc.get_property("node.new_node", "type"), Some("inflow"));

        // Verify unchanged property still has raw_lines
        let type_property = &doc.sections["node.test"].properties["type"];
        assert!(!type_property.raw_lines.is_empty()); // Not modified, should have raw_lines
        assert_eq!(type_property.value, "gr4j");
    }

    #[test]
    fn test_get_property() {
        let content = r#"
[section1]
key1 = value1
key2 = value2
"#;

        let doc = IniDocument::parse(content).unwrap();

        assert_eq!(doc.get_property("section1", "key1"), Some("value1"));
        assert_eq!(doc.get_property("section1", "key2"), Some("value2"));
        assert_eq!(doc.get_property("section1", "nonexistent"), None);
        assert_eq!(doc.get_property("nonexistent", "key1"), None);
    }

    #[test]
    fn test_comment_preservation_on_multiline_property() {
        let content = r#"
[node.catchment]
params = 100.0, 2.0, 50.0,  # Production store capacity
         0.5, 0.8,          # Groundwater exchange
         200.0              # Routing store capacity
"#;

        let mut doc = IniDocument::parse(content).unwrap();

        // Verify original parsing captured comments
        let section = &doc.sections["node.catchment"];
        let property = &section.properties["params"];
        assert_eq!(property.comments.len(), 3);
        assert_eq!(property.comments[0], Some("# Production store capacity".to_string()));
        assert_eq!(property.comments[1], Some("# Groundwater exchange".to_string()));
        assert_eq!(property.comments[2], Some("# Routing store capacity".to_string()));

        // Modify to a new multi-line value (3 lines, same as original)
        let new_value = "150.0, 3.0, 60.0,\n         0.7, 1.2,\n         250.0";
        doc.set_property("node.catchment", "params", new_value);

        // Convert back to string
        let output = doc.to_string();

        // Verify all three comments are preserved on their respective lines
        assert!(output.contains("150.0, 3.0, 60.0,  # Production store capacity"));
        assert!(output.contains("0.7, 1.2,  # Groundwater exchange"));
        assert!(output.contains("250.0  # Routing store capacity"));
    }

    #[test]
    fn test_comment_preservation_fewer_lines_than_comments() {
        let content = r#"
[node.test]
params = 100.0, 2.0,  # First comment
         50.0,        # Second comment
         0.5          # Third comment
"#;

        let mut doc = IniDocument::parse(content).unwrap();

        // Modify to a value with only 2 lines (fewer than the 3 comments)
        let new_value = "200.0, 4.0,\n         75.0";
        doc.set_property("node.test", "params", new_value);

        let output = doc.to_string();

        // First two comments should be preserved
        assert!(output.contains("200.0, 4.0,  # First comment"));
        assert!(output.contains("75.0  # Second comment"));

        // Third comment should be discarded (not appear in output)
        let param_section = output.split("[node.test]").nth(1).unwrap();
        let param_section = param_section.split("\n\n").next().unwrap(); // Get just this section
        assert_eq!(param_section.matches("# Third comment").count(), 0);
    }

    #[test]
    fn test_comment_preservation_more_lines_than_comments() {
        let content = r#"
[node.test]
params = 100.0, 2.0,  # First comment
         50.0         # Second comment
"#;

        let mut doc = IniDocument::parse(content).unwrap();

        // Modify to a value with 3 lines (more than the 2 comments)
        let new_value = "200.0, 4.0,\n         75.0,\n         1.5";
        doc.set_property("node.test", "params", new_value);

        let output = doc.to_string();

        // First two lines should have comments, third line should not
        assert!(output.contains("200.0, 4.0,  # First comment"));
        assert!(output.contains("75.0,  # Second comment"));
        assert!(output.contains("1.5\n")); // Third line with no comment
    }

    #[test]
    fn test_property_leading_lines() {
        let content = r#"
[section1]
# This is a leading comment for param1
# It spans multiple lines
param1 = 100

# Leading comment for param2
param2 = 200
"#;

        let doc = IniDocument::parse(content).unwrap();
        let section = &doc.sections["section1"];

        // Check param1 leading comments
        let param1 = &section.properties["param1"];
        assert_eq!(param1.leading_lines.len(), 2);
        assert_eq!(param1.leading_lines[0], "# This is a leading comment for param1");
        assert_eq!(param1.leading_lines[1], "# It spans multiple lines");
        assert_eq!(param1.value, "100");

        // Check param2 leading comments
        let param2 = &section.properties["param2"];
        assert_eq!(param2.leading_lines.len(), 2);
        assert_eq!(param2.leading_lines[0], ""); // Blank line between param1 and param2
        assert_eq!(param2.leading_lines[1], "# Leading comment for param2");
        assert_eq!(param2.value, "200");

        // Verify round-trip preserves leading comments
        let output = doc.to_string();
        assert!(output.contains("# This is a leading comment for param1"));
        assert!(output.contains("# It spans multiple lines"));
        assert!(output.contains("# Leading comment for param2"));
    }
}