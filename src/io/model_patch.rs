//! Applies INI patch strings to a `Model`. Used to modify a model
//! programatically, especially via the Python package.

use crate::io::custom_ini_parser::IniDocument;
use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

/// Apply `patch_string` to `model`'s `IniDocument` and rebuild a new `Model`
/// from the result.
pub fn patch_update(model: Model, patch_string: &str) -> Result<Model, String> {
    // Clone ensures that we do not modify the original model's ini_document -
    // in case the modification is invalid
    let mut model_ini_doc = model.ini_document.clone().unwrap_or_default();

    let patch_ini = IniDocument::parse(patch_string)?;

    for (patch_section_name, patch_ini_section) in patch_ini.sections {
        for (property_name, property_content) in patch_ini_section.properties {
            model_ini_doc.set_property(
                &patch_section_name,
                &property_name.to_string(),
                &property_content.value.to_string(),
            );
        }
    }
    IniModelIO::read_model_string_with_working_directory(
        &model_ini_doc.to_string(),
        Some(model.working_directory),
    )
}
