//! Applies INI patch strings to a `Model`. Used to modify a model
//! programatically, especially via the Python package.

use crate::io::custom_ini_parser::IniDocument;
use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

/// Apply `patch_string` to `model`'s `IniDocument`. For each property in the
/// patch, updates it if present or creates it otherwise; sections not yet
/// present are created and appended to the bottom of the file. Properties
/// and sections omitted from the patch are left untouched.
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
    let mut patched_model = IniModelIO::read_model_string_with_working_directory(
        &model_ini_doc.to_string(),
        Some(model.working_directory),
    )?;
    // Reject poorly configured models
    patched_model.configure()?;
    Ok(patched_model)
}

/// Apply `patch_string` to `model`'s `IniDocument`, replacing each named
/// section wholesale with its patch definition (properties omitted from the
/// patch are dropped, unlike `patch_update`). An overridden section keeps its
/// original position; a section not yet present is appended to the bottom of
/// the file.
pub fn patch_override(model: Model, patch_string: &str) -> Result<Model, String> {
    // Clone ensures that we do not modify the original model's ini_document -
    // in case the modification is invalid
    let mut model_ini_doc = model.ini_document.clone().unwrap_or_default();
    let patch_ini = IniDocument::parse(patch_string)?;
    for (patch_section_name, patch_ini_section) in patch_ini.sections {
        model_ini_doc.sections.insert(patch_section_name, patch_ini_section);
    }
    let mut patched_model = IniModelIO::read_model_string_with_working_directory(
        &model_ini_doc.to_string(),
        Some(model.working_directory),
    )?;
    // Reject poorly configured models
    patched_model.configure()?;
    Ok(patched_model)
}

/// Apply `patch_string` to `model`'s `IniDocument`. For each section in the
/// patch: if it lists properties, only those properties are removed from the
/// model (the section itself is kept, even if left empty); if it lists none,
/// the entire section is removed. Sections/properties not present in the
/// model are silently ignored.
pub fn patch_delete(model: Model, patch_string: &str) -> Result<Model, String> {
    // Clone ensures that we do not modify the original model's ini_document -
    // in case the modification is invalid
    let mut model_ini_doc = model.ini_document.clone().unwrap_or_default();
    let patch_ini = IniDocument::parse(patch_string)?;
    // Delete properties if listed 
    // If no properties, delete entire section
    for (patch_section_name, patch_ini_section) in patch_ini.sections {
        if patch_ini_section.properties.is_empty() {
            model_ini_doc.sections.shift_remove(&patch_section_name.to_string());
        } else {
            if let Some(model_ini_section) = model_ini_doc.sections.get_mut(&patch_section_name.to_string()) {
                for (property_name, _) in patch_ini_section.properties {
                    model_ini_section.properties.shift_remove(&property_name.to_string());
                }
            }
        }
    }
    let mut patched_model = IniModelIO::read_model_string_with_working_directory(
        &model_ini_doc.to_string(),
        Some(model.working_directory),
    )?;
    // Reject poorly configured models
    patched_model.configure()?;
    Ok(patched_model)
}



#[cfg(test)]
mod tests {
    use super::*;

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
         loc = 1, 2\n"
    }

    #[test]
    fn patch_update_overrides_existing_property() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let patched = patch_update(model, "[node.g]\narea = 99\n")
            .expect("patch should apply");

        let ini_doc = patched.ini_document.as_ref().expect("patched model should have an ini_document");
        assert_eq!(ini_doc.get_property("node.g", "area"), Some("99"));
    }

    #[test]
    fn patch_update_adds_new_section() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let patched = patch_update(model, "[node.bh2]\ntype = blackhole\nloc = 5, 5\n")
            .expect("patch should apply");

        let ini_doc = patched.ini_document.as_ref().expect("patched model should have an ini_document");
        assert_eq!(ini_doc.get_property("node.bh2", "type"), Some("blackhole"));
        assert_eq!(patched.nodes.len(), 3);
    }

    #[test]
    fn patch_update_does_not_mutate_original_model() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        let original = model.clone();

        let _patched = patch_update(model, "[node.g]\narea = 99\n")
            .expect("patch should apply");

        let original_ini_doc = original.ini_document.as_ref().expect("original model should have an ini_document");
        assert_eq!(original_ini_doc.get_property("node.g", "area"), Some("30"));
    }

    #[test]
    fn patch_update_preserves_working_directory() {
        let mut model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        model.working_directory = std::path::PathBuf::from("some/working/dir");
        let working_directory = model.working_directory.clone();

        let patched = patch_update(model, "[node.g]\narea = 99\n")
            .expect("patch should apply");

        assert_eq!(patched.working_directory, working_directory);
    }

    #[test]
    fn patch_update_rejects_invalid_patch_string() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let result = patch_update(model, "not valid ini [[[");
        assert!(result.is_err());
    }
}
