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
