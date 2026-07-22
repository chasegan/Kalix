//! This file contains code relating to the extraction of information from
//! Models and the contained IniDocuments.

use crate::{io::custom_ini_parser::IniSection, model::Model};

pub fn list_sections(model: &Model) -> Vec<String> {
    match model.ini_document {
        Some(ref ini_document) => ini_document.get_section_names(),
        None => Vec::new(),
    }
}

pub fn has_section(model: &Model, section_name: &str) -> bool {
    model
        .ini_document
        .as_ref()
        .is_some_and(|doc| doc.has_section(section_name))
}

pub fn get_section<'a>(model: &'a Model, section_name: &str) -> Option<&'a IniSection> {
    model
        .ini_document
        .as_ref()
        .and_then(|doc| doc.get_section(section_name))
}

pub fn get_property(model: &Model, section_name: &str, property_name: &str) -> Option<String> {
    model
        .ini_document
        .as_ref()
        .and_then(|doc| doc.get_property(section_name, property_name))
        .map(str::to_string)
}

/// Why a dotted `"<section>.<property>"` lookup failed.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PropertyLookupError {
    /// `property_designation` had no `.` separator, so no section name
    /// could be extracted from it.
    InvalidFormat(String),
    /// The section named by `property_designation` doesn't exist.
    NoSuchSection(String),
    /// The section exists but has no property by that name.
    NoSuchProperty {
        section_name: String,
        property_name: String,
    },
}

pub fn get_property_by_designation(
    model: &Model,
    property_designation: &str,
) -> Result<String, PropertyLookupError> {
    let Some((section_name, property_name)) = property_designation.rsplit_once(".") else {
        return Err(PropertyLookupError::InvalidFormat(
            property_designation.to_string(),
        ));
    };
    if !has_section(model, section_name) {
        return Err(PropertyLookupError::NoSuchSection(
            section_name.to_string(),
        ));
    }
    get_property(model, section_name, property_name).ok_or_else(|| {
        PropertyLookupError::NoSuchProperty {
            section_name: section_name.to_string(),
            property_name: property_name.to_string(),
        }
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::io::ini_model_io::IniModelIO;

    fn model_ini() -> &'static str {
        "[kalix]\n\
         start = 2000-01-01T00:00:00\n\
         end = 2000-01-10T00:00:00\n\
         \n\
         [node.g]\n\
         type = gr4j\n\
         loc = 10, 20\n\
         area = 30\n\
         params = 350, 0, 90, 1.7\n\
         ds_1 = bh\n\
         \n\
         [node.bh]\n\
         type = blackhole\n\
         loc = 1, 2\n\
         \n\
         [outputs]\n\
         node.g.dsflow\n\
         node.bh.dsflow\n"
    }

    fn empty_model() -> Model {
        Model::default()
    }

    #[test]
    fn list_sections_returns_names_in_file_order() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        assert_eq!(
            list_sections(&model),
            vec!["kalix", "node.g", "node.bh", "outputs"]
        );
    }

    #[test]
    fn list_sections_on_model_without_ini_document_is_empty() {
        assert_eq!(list_sections(&empty_model()), Vec::<String>::new());
    }

    #[test]
    fn has_section_true_for_existing_section() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        assert!(has_section(&model, "node.g"));
    }

    #[test]
    fn has_section_false_for_missing_section() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        assert!(!has_section(&model, "node.nonexistent"));
    }

    #[test]
    fn has_section_false_on_model_without_ini_document() {
        assert!(!has_section(&empty_model(), "node.g"));
    }

    #[test]
    fn get_section_returns_properties_for_existing_section() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        let section = get_section(&model, "node.g").expect("section should exist");
        assert_eq!(
            section.properties.get("area").map(|p| p.value.as_str()),
            Some("30")
        );
    }

    #[test]
    fn get_section_none_for_missing_section() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        assert!(get_section(&model, "node.nonexistent").is_none());
    }

    #[test]
    fn get_section_bare_lines_have_empty_string_value() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        let section = get_section(&model, "outputs").expect("section should exist");
        assert_eq!(
            section.properties.get("node.g.dsflow").map(|p| p.value.as_str()),
            Some("")
        );
    }

    #[test]
    fn get_property_returns_value_for_existing_property() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        assert_eq!(
            get_property(&model, "node.g", "area"),
            Some("30".to_string())
        );
    }

    #[test]
    fn get_property_none_for_missing_section() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        assert_eq!(get_property(&model, "node.nonexistent", "area"), None);
    }

    #[test]
    fn get_property_none_for_missing_property() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        assert_eq!(get_property(&model, "node.g", "nonexistent"), None);
    }

    #[test]
    fn get_property_by_designation_returns_value() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        assert_eq!(
            get_property_by_designation(&model, "node.g.area"),
            Ok("30".to_string())
        );
    }

    #[test]
    fn get_property_by_designation_splits_on_last_dot_only() {
        // Section names themselves contain dots (e.g. "node.g"), so the
        // designation must split on the *last* separator, not the first --
        // splitting on the first dot would look for a "node" section (which
        // doesn't exist) and a "g.area" property.
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        assert_eq!(
            get_property_by_designation(&model, "node.g.area"),
            Ok("30".to_string())
        );
        assert_eq!(
            get_property_by_designation(&model, "node.bh.type"),
            Ok("blackhole".to_string())
        );
    }

    #[test]
    fn get_property_by_designation_invalid_format_when_no_dot() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        assert_eq!(
            get_property_by_designation(&model, "no_dot_here"),
            Err(PropertyLookupError::InvalidFormat("no_dot_here".to_string()))
        );
    }

    #[test]
    fn get_property_by_designation_no_such_section() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        assert_eq!(
            get_property_by_designation(&model, "node.nonexistent.area"),
            Err(PropertyLookupError::NoSuchSection(
                "node.nonexistent".to_string()
            ))
        );
    }

    #[test]
    fn get_property_by_designation_no_such_property() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        assert_eq!(
            get_property_by_designation(&model, "node.g.nonexistent"),
            Err(PropertyLookupError::NoSuchProperty {
                section_name: "node.g".to_string(),
                property_name: "nonexistent".to_string(),
            })
        );
    }
}
