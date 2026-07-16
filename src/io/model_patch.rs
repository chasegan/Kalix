//! Applies INI patch strings to a `Model`. Used to modify a model
//! programatically, especially via the Python package.

use crate::io::custom_ini_parser::IniDocument;
use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

fn apply_patch(
    model: &Model,
    patch_string: &str,
    mutate: impl FnOnce(&mut IniDocument, IniDocument),
) -> Result<Model, String> {
    // Clone ensures that we do not modify the original model's ini_document -
    // in case the modification is invalid
    let mut model_ini_doc = model.ini_document.clone().map(Ok).unwrap_or(Err(
        "Patch cannot be called on an empty model - use load instead.",
    ))?;
    let patch_ini = IniDocument::parse(patch_string)?;

    mutate(&mut model_ini_doc, patch_ini);

    let mut patched_model = IniModelIO::read_model_string_with_working_directory(
        &model_ini_doc.to_string(),
        Some(model.working_directory.clone()),
    )?;
    // Reject poorly configured models
    patched_model.configure()?;
    Ok(patched_model)
}

/// Apply `patch_string` to `model`'s `IniDocument`. For each property in the
/// patch, updates it if present or creates it otherwise; sections not yet
/// present are created and appended to the bottom of the file. Properties
/// and sections omitted from the patch are left untouched.
pub fn patch_update(model: &Model, patch_string: &str) -> Result<Model, String> {
    let mutate = |model_ini_doc: &mut IniDocument, patch_ini_doc: IniDocument| {
        for (patch_section_name, patch_ini_section) in patch_ini_doc.sections {
            for (property_name, property_content) in patch_ini_section.properties {
                model_ini_doc.set_property(
                    &patch_section_name,
                    &property_name.to_string(),
                    &property_content.value.to_string(),
                );
            }
        }
    };
    apply_patch(model, patch_string, mutate)
}

/// Apply `patch_string` to `model`'s `IniDocument`, replacing each named
/// section wholesale with its patch definition (properties omitted from the
/// patch are dropped, unlike `patch_update`). An overridden section keeps its
/// original position; a section not yet present is appended to the bottom of
/// the file.
pub fn patch_override(model: &Model, patch_string: &str) -> Result<Model, String> {
    let mutate = |model_ini_doc: &mut IniDocument, patch_ini_doc: IniDocument| {
        for (patch_section_name, patch_ini_section) in patch_ini_doc.sections {
            model_ini_doc
                .sections
                .insert(patch_section_name, patch_ini_section);
        }
    };
    apply_patch(model, patch_string, mutate)
}

/// Apply `patch_string` to `model`'s `IniDocument`. For each section in the
/// patch: if it lists properties, only those properties are removed from the
/// model (the section itself is kept, even if left empty); if it lists none,
/// the entire section is removed. Sections/properties not present in the
/// model are silently ignored.
pub fn patch_delete(model: &Model, patch_string: &str) -> Result<Model, String> {
    let mutate = |model_ini_doc: &mut IniDocument, patch_ini_doc: IniDocument| {
            for (patch_section_name, patch_ini_section) in patch_ini_doc.sections {
            if patch_ini_section.properties.is_empty() {
                model_ini_doc
                    .sections
                    .shift_remove(&patch_section_name.to_string());
            } else {
                if let Some(model_ini_section) = model_ini_doc
                    .sections
                    .get_mut(&patch_section_name.to_string())
                {
                    for (property_name, _) in patch_ini_section.properties {
                        model_ini_section
                            .properties
                            .shift_remove(&property_name.to_string());
                    }
                }
            }
        }
    };
    apply_patch(model, patch_string, mutate)
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

    /// Like `model_ini`, but with an extra leaf node (`node.bh2`) that
    /// nothing links to downstream, so it can be deleted without breaking
    /// the model - unlike `node.bh`, which `node.g` depends on.
    fn model_ini_with_extra_node() -> &'static str {
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
         [node.bh2]\n\
         type = blackhole\n\
         loc = 5, 5\n"
    }

    #[test]
    fn patch_update_overrides_existing_property() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let patched = patch_update(&model, "[node.g]\narea = 99\n").expect("patch should apply");

        let ini_doc = patched
            .ini_document
            .as_ref()
            .expect("patched model should have an ini_document");
        assert_eq!(ini_doc.get_property("node.g", "area"), Some("99"));
    }

    #[test]
    fn patch_update_adds_new_section() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let patched = patch_update(&model, "[node.bh2]\ntype = blackhole\nloc = 5, 5\n")
            .expect("patch should apply");

        let ini_doc = patched
            .ini_document
            .as_ref()
            .expect("patched model should have an ini_document");
        assert_eq!(ini_doc.get_property("node.bh2", "type"), Some("blackhole"));
        assert_eq!(patched.nodes.len(), 3);
    }

    #[test]
    fn patch_update_does_not_mutate_original_model() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        let original = model.clone();

        let _patched = patch_update(&model, "[node.g]\narea = 99\n").expect("patch should apply");

        let original_ini_doc = original
            .ini_document
            .as_ref()
            .expect("original model should have an ini_document");
        assert_eq!(original_ini_doc.get_property("node.g", "area"), Some("30"));
    }

    #[test]
    fn patch_update_preserves_working_directory() {
        let mut model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        model.working_directory = std::path::PathBuf::from("some/working/dir");
        let working_directory = model.working_directory.clone();

        let patched = patch_update(&model, "[node.g]\narea = 99\n").expect("patch should apply");

        assert_eq!(patched.working_directory, working_directory);
    }

    #[test]
    fn patch_update_rejects_invalid_patch_string() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let result = patch_update(&model, "not valid ini [[[");
        assert!(result.is_err());
    }

    #[test]
    fn patch_override_replaces_whole_section() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        // Omits `loc`, unlike patch_update this must not survive the override
        let patched =
            patch_override(&model, "[node.bh]\ntype = blackhole\n").expect("patch should apply");

        let ini_doc = patched
            .ini_document
            .as_ref()
            .expect("patched model should have an ini_document");
        assert_eq!(ini_doc.get_property("node.bh", "type"), Some("blackhole"));
        assert_eq!(ini_doc.get_property("node.bh", "loc"), None);
    }

    #[test]
    fn patch_override_adds_new_section() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let patched = patch_override(&model, "[node.bh2]\ntype = blackhole\nloc = 5, 5\n")
            .expect("patch should apply");

        let ini_doc = patched
            .ini_document
            .as_ref()
            .expect("patched model should have an ini_document");
        assert_eq!(ini_doc.get_property("node.bh2", "type"), Some("blackhole"));
        assert_eq!(patched.nodes.len(), 3);
    }

    #[test]
    fn patch_override_preserves_section_position() {
        // node.bh is upstream-linked from node.g; overriding it must not move
        // it below node.g in the file, or execution order would break -
        // per manifestos/node-definition-order.md
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let patched = patch_override(&model, "[node.bh]\ntype = blackhole\nloc = 9, 9\n")
            .expect("patch should apply");

        let ini_doc = patched
            .ini_document
            .as_ref()
            .expect("patched model should have an ini_document");
        let section_names: Vec<&String> = ini_doc.sections.keys().collect();
        let g_pos = section_names
            .iter()
            .position(|s| s.as_str() == "node.g")
            .unwrap();
        let bh_pos = section_names
            .iter()
            .position(|s| s.as_str() == "node.bh")
            .unwrap();
        assert!(g_pos < bh_pos);
    }

    #[test]
    fn patch_override_does_not_mutate_original_model() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        let original = model.clone();

        let _patched =
            patch_override(&model, "[node.bh]\ntype = blackhole\n").expect("patch should apply");

        let original_ini_doc = original
            .ini_document
            .as_ref()
            .expect("original model should have an ini_document");
        assert_eq!(
            original_ini_doc.get_property("node.bh", "loc"),
            Some("1, 2")
        );
    }

    #[test]
    fn patch_override_preserves_working_directory() {
        let mut model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        model.working_directory = std::path::PathBuf::from("some/working/dir");
        let working_directory = model.working_directory.clone();

        let patched = patch_override(&model, "[node.bh]\ntype = blackhole\nloc = 1, 2\n")
            .expect("patch should apply");

        assert_eq!(patched.working_directory, working_directory);
    }

    #[test]
    fn patch_override_rejects_invalid_patch_string() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let result = patch_override(&model, "not valid ini [[[");
        assert!(result.is_err());
    }

    #[test]
    fn patch_delete_removes_listed_properties_but_keeps_section() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let patched = patch_delete(&model, "[node.g]\nparams =\n").expect("patch should apply");

        let ini_doc = patched
            .ini_document
            .as_ref()
            .expect("patched model should have an ini_document");
        assert_eq!(ini_doc.get_property("node.g", "params"), None);
        assert_eq!(ini_doc.get_property("node.g", "type"), Some("gr4j"));
    }

    #[test]
    fn patch_delete_removes_entire_section_when_no_properties_listed() {
        let model =
            IniModelIO::read_model_string(model_ini_with_extra_node()).expect("model should parse");

        let patched = patch_delete(&model, "[node.bh2]\n").expect("patch should apply");

        let ini_doc = patched
            .ini_document
            .as_ref()
            .expect("patched model should have an ini_document");
        assert!(ini_doc.get_property("node.bh2", "type").is_none());
        assert!(!ini_doc.sections.contains_key("node.bh2"));
        assert_eq!(patched.nodes.len(), 2);
    }

    #[test]
    fn patch_delete_ignores_missing_section() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let patched = patch_delete(&model, "[node.missing]\n").expect("patch should apply");

        assert_eq!(patched.nodes.len(), 2);
    }

    #[test]
    fn patch_delete_ignores_missing_property() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let patched =
            patch_delete(&model, "[node.g]\nnot_a_real_property =\n").expect("patch should apply");

        let ini_doc = patched
            .ini_document
            .as_ref()
            .expect("patched model should have an ini_document");
        assert_eq!(ini_doc.get_property("node.g", "type"), Some("gr4j"));
    }

    #[test]
    fn patch_delete_does_not_mutate_original_model() {
        let model =
            IniModelIO::read_model_string(model_ini_with_extra_node()).expect("model should parse");
        let original = model.clone();

        let _patched = patch_delete(&model, "[node.bh2]\n").expect("patch should apply");

        let original_ini_doc = original
            .ini_document
            .as_ref()
            .expect("original model should have an ini_document");
        assert_eq!(
            original_ini_doc.get_property("node.bh2", "type"),
            Some("blackhole")
        );
    }

    #[test]
    fn patch_delete_preserves_working_directory() {
        let mut model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        model.working_directory = std::path::PathBuf::from("some/working/dir");
        let working_directory = model.working_directory.clone();

        let patched = patch_delete(&model, "[node.g]\nparams =\n").expect("patch should apply");

        assert_eq!(patched.working_directory, working_directory);
    }

    #[test]
    fn patch_delete_rejects_invalid_patch_string() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let result = patch_delete(&model, "not valid ini [[[");
        assert!(result.is_err());
    }

    /// Like `model_ini`, but with a storage node whose `dimensions` table has
    /// no safe fallback if removed (unlike gr4j's `params`, which silently
    /// keeps working `Gr4j::new()` defaults) - deleting it is only caught by
    /// `configure()`, not by ini parsing alone.
    fn model_ini_with_storage_node() -> &'static str {
        "[kalix]\n\
         start = 2000-01-01T00:00:00\n\
         end = 2000-01-10T00:00:00\n\
         \n\
         [node.s]\n\
         type = storage\n\
         loc = 10, 20\n\
         dimensions = 90, 0, 0, 0,\n         91, 100, 1, 0,\n\
         ds_1 = bh\n\
         \n\
         [node.bh]\n\
         type = blackhole\n\
         loc = 1, 2\n"
    }

    #[test]
    fn patch_update_rejects_patch_that_fails_configure() {
        // Syntactically fine (`area` parses as a number), but `configure()`
        // rejects a negative catchment area - a patch that parses cleanly
        // must still be rejected if it produces a model that can't run.
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let result = patch_update(&model, "[node.g]\narea = -5\n");
        assert!(result.is_err());
    }

    #[test]
    fn patch_override_rejects_patch_that_fails_configure() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let result = patch_override(
            &model,
            "[node.g]\ntype = gr4j\nloc = 10, 20\narea = -5\nparams = 350, 0, 90, 1.7\nds_1 = bh\n",
        );
        assert!(result.is_err());
    }

    #[test]
    fn patch_delete_rejects_patch_that_fails_configure() {
        let model = IniModelIO::read_model_string(model_ini_with_storage_node())
            .expect("model should parse");

        let result = patch_delete(&model, "[node.s]\ndimensions =\n");
        assert!(result.is_err());
    }
}
