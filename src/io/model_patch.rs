//! Applies patch strings that mutate a Model's `IniDocument` (the parsed INI DOM),
//! reparsing the result into a brand-new `Model`.

use crate::io::custom_ini_parser::IniDocument;
use crate::io::error::KalixIoError;
use crate::io::ini_model_io::IniModelIO;
use crate::misc::misc_functions::sanitize_name;
use crate::model::Model;
use crate::timeseries_input::TimeseriesInputDefinition;

/// Helper enum to qualify the methods of failure.
#[derive(Debug, thiserror::Error)]
pub enum PatchError {
    #[error("Patch cannot be called on an empty model - use load instead.")]
    EmptyModel,
    #[error("{0}")]
    ValidationError(String),
    #[error("Patch string delete mode specifies non-existent section {0} (`missing_ok=false`)")]
    DeleteKeyErr(String),
    #[error(transparent)]
    IoError(#[from] KalixIoError),
}

/// Helper function for defining the patch functions. Note this is a full
/// reuild/reparse for safety reasons - an invalid patch is rejected and won't
/// invalidate the original model. This function is on the cold path, not called
/// during simulation.
fn apply_patch<F: FnOnce(&mut IniDocument, IniDocument) -> Result<(), PatchError>>(
    model: &Model,
    patch_string: &str,
    mutate: F,
) -> Result<Model, PatchError> {
    // Clone ensures that we do not modify the original model's ini_document -
    // in case the modification is invalid
    let mut model_ini_doc = model
        .ini_document
        .clone()
        .map(Ok)
        .unwrap_or(Err(PatchError::EmptyModel))?;
    let patch_ini = IniDocument::parse(patch_string)
        .map_err(KalixIoError::Parse)?;

    mutate(&mut model_ini_doc, patch_ini)?;

    let mut patched_model = IniModelIO::read_model_string_with_working_directory(
        &model_ini_doc.to_string(),
        Some(model.working_directory.clone()),
    )?;

    // The above rebuild reparses from INI text, which does not preserve runtime
    // set_input() data. Restore that data only onto
    // `TimeseriesInputDefinition::Declaration`s (i.e. no associated file).
    //
    // TODO: this only covers set_input() data supplied for a bare declaration.
    // In-memory data that *overrides an aliased file* is still dropped across a
    // patch (the rebuilt source is a FileDefinition, which we deliberately leave
    // untouched). Address before merging: decide how a file-backed override
    // should survive a patch, and validate its references the same way.
    for source in &mut patched_model.input_sources {
        if let TimeseriesInputDefinition::Declaration { alias } = source {
            let alias_key = sanitize_name(alias);
            let in_memory = model.input_sources.iter().find(|s| {
                matches!(s, TimeseriesInputDefinition::InMemoryDefinition { .. })
                    && s.alias().map(sanitize_name).as_deref() == Some(alias_key.as_str())
            });
            if let Some(in_memory) = in_memory {
                *source = in_memory.clone();
            }
        }
    }

    // Reject poorly configured models
    patched_model
        .validate_model_structure()
        .map_err(PatchError::ValidationError)?;
    Ok(patched_model)
}

/// Apply `patch_string` to `model`'s `IniDocument`. For each property in the
/// patch, updates it if present or creates it otherwise; sections not yet
/// present are created and appended to the bottom of the file. Properties
/// and sections omitted from the patch are left untouched.
pub fn patch_merge(model: &Model, patch_string: &str) -> Result<Model, PatchError> {
    let mutate = |model_ini_doc: &mut IniDocument, patch_ini_doc: IniDocument| {
        for (patch_section_name, patch_ini_section) in patch_ini_doc.sections {
            for (property_name, property_content) in patch_ini_section.properties {
                model_ini_doc.set_property(
                    &patch_section_name,
                    &property_name,
                    &property_content.value,
                );
            }
        }
        Ok(())
    };
    apply_patch(model, patch_string, mutate)
}

/// Apply `patch_string` to `model`'s `IniDocument`, replacing each named
/// section wholesale with its patch definition (properties omitted from the
/// patch are dropped, unlike `patch_merge`). An overridden section keeps its
/// original position; a section not yet present is appended to the bottom of
/// the file.
pub fn patch_replace(model: &Model, patch_string: &str) -> Result<Model, PatchError> {
    let mutate = |model_ini_doc: &mut IniDocument, patch_ini_doc: IniDocument| {
        for (patch_section_name, patch_ini_section) in patch_ini_doc.sections {
            model_ini_doc.set_section(patch_section_name, patch_ini_section);
        }
        Ok(())
    };
    apply_patch(model, patch_string, mutate)
}

/// Apply `patch_string` to `model`'s `IniDocument`, deleting each named
/// section wholesale (property-level deletion is not supported - a patch
/// section must list no properties). If `missing_ok` is `false`, patching a
/// section that does not exist in the original model is an error; if `true`,
/// it is silently ignored.
pub fn patch_delete(
    model: &Model,
    patch_string: &str,
    missing_ok: bool,
) -> Result<Model, PatchError> {
    let mutate = |model_ini_doc: &mut IniDocument, patch_ini_doc: IniDocument| {
        for (patch_section_name, patch_ini_section) in patch_ini_doc.sections {
            if !patch_ini_section.properties.is_empty() {
                return Err(KalixIoError::Parse(format!(
                    "Patch string specifies section {} with non-empty property content.",
                    patch_section_name
                ))
                .into());
            }
            match model_ini_doc.remove_section(&patch_section_name) {
                Ok(_) => {}
                Err(_) if missing_ok => {}
                Err(_) => {
                    return Err(PatchError::DeleteKeyErr(patch_section_name));
                }
            }
        }
        Ok(())
    };
    apply_patch(model, patch_string, mutate)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::io::model_input_swap::{set_input, InMemoryColumn};
    use crate::tid::utils::wrap_to_u64;

    // 2000-01-01T00:00:00Z in Kalix's 2^63-biased unix-seconds, matching the
    // [kalix] start in `model_ini_with_declared_input` (see model_input_swap
    // tests for why set_input() timestamps are biased, not raw epoch).
    fn start() -> u64 {
        wrap_to_u64(946_684_800)
    }
    const DAY: u64 = 86_400;

    /// A model whose only input is a bare declaration (`obs =`), referenced by
    /// name from an inflow node -- something set_input() is expected to fill.
    fn model_ini_with_declared_input() -> &'static str {
        "[kalix]\n\
         start = 2000-01-01T00:00:00\n\
         end = 2000-01-05T00:00:00\n\
         \n\
         [data]\n\
         obs =\n\
         \n\
         [node.src]\n\
         type = inflow\n\
         loc = 0,0\n\
         inflow = data.obs.by_name.flow\n\
         ds_1 = sink\n\
         \n\
         [node.sink]\n\
         type = blackhole\n\
         loc = 1,1\n\
         \n\
         [outputs]\n\
         node.src.dsflow\n"
    }

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
    fn patch_preserves_in_memory_data_supplied_for_a_declaration() {
        // set_input() data lives only in memory, but a patch reparses the model
        // from INI text. The supplied data must survive the rebuild -- copied
        // onto the matching bare declaration -- rather than being silently
        // discarded (which would leave `obs` "declared but not supplied").
        let mut model = IniModelIO::read_model_string(model_ini_with_declared_input())
            .expect("model should parse");
        set_input(
            &mut model,
            "obs",
            start(),
            DAY,
            vec![InMemoryColumn {
                name: "flow".into(),
                values: vec![1.0, 2.0, 3.0, 4.0, 5.0],
            }],
        )
        .expect("set_input should fill the declaration");

        // A patch that touches something unrelated must not drop the input data.
        let mut patched = patch_merge(&model, "[const]\nk = 1\n")
            .expect("patch should apply and preserve the supplied input");

        patched
            .configure()
            .expect("configure should succeed on the patched model");
        patched
            .run()
            .expect("run should succeed on the patched model");
        let idx = patched
            .data_cache
            .get_existing_series_idx("node.src.dsflow")
            .unwrap();
        assert_eq!(
            patched.data_cache.series[idx].values,
            vec![1.0, 2.0, 3.0, 4.0, 5.0]
        );
    }

    #[test]
    fn patch_catches_in_memory_column_mismatch_for_a_declaration() {
        // The preserved in-memory data is validated at the patch stage: the
        // model references `data.obs.by_name.flow`, but the supplied column is
        // named `value`. validate_model_structure() (run inside apply_patch)
        // must reject it, rather than letting it slip through to a run-time
        // lookup failure.
        let mut model = IniModelIO::read_model_string(model_ini_with_declared_input())
            .expect("model should parse");
        set_input(
            &mut model,
            "obs",
            start(),
            DAY,
            vec![InMemoryColumn {
                name: "value".into(),
                values: vec![1.0, 2.0, 3.0, 4.0, 5.0],
            }],
        )
        .expect("set_input supplies data regardless of whether a reference matches");

        let err = patch_merge(&model, "[const]\nk = 1\n")
            .err()
            .expect("patch must reject a reference the supplied columns don't satisfy")
            .to_string();
        assert!(
            err.contains("data.obs.by_name.flow") && err.contains("not found in any input file"),
            "error should name the unmatched reference. Got: {err}"
        );
    }

    #[test]
    fn patch_merge_overrides_existing_property() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let patched = patch_merge(&model, "[node.g]\narea = 99\n").expect("patch should apply");

        let ini_doc = patched
            .ini_document
            .as_ref()
            .expect("patched model should have an ini_document");
        assert_eq!(ini_doc.get_property("node.g", "area"), Some("99"));
    }

    #[test]
    fn patch_merge_adds_new_section() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let patched = patch_merge(&model, "[node.bh2]\ntype = blackhole\nloc = 5, 5\n")
            .expect("patch should apply");

        let ini_doc = patched
            .ini_document
            .as_ref()
            .expect("patched model should have an ini_document");
        assert_eq!(ini_doc.get_property("node.bh2", "type"), Some("blackhole"));
        assert_eq!(patched.nodes.len(), 3);
    }

    #[test]
    fn patch_merge_does_not_mutate_original_model() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        let original = model.clone();

        let _patched = patch_merge(&model, "[node.g]\narea = 99\n").expect("patch should apply");

        let original_ini_doc = original
            .ini_document
            .as_ref()
            .expect("original model should have an ini_document");
        assert_eq!(original_ini_doc.get_property("node.g", "area"), Some("30"));
    }

    #[test]
    fn patch_merge_preserves_working_directory() {
        let mut model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        model.working_directory = std::path::PathBuf::from("some/working/dir");
        let working_directory = model.working_directory.clone();

        let patched = patch_merge(&model, "[node.g]\narea = 99\n").expect("patch should apply");

        assert_eq!(patched.working_directory, working_directory);
    }

    #[test]
    fn patch_merge_rejects_invalid_patch_string() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let result = patch_merge(&model, "not valid ini [[[");
        assert!(result.is_err());
    }

    #[test]
    fn patch_replace_replaces_whole_section() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        // Omits `loc`, unlike patch_merge this must not survive the override
        let patched =
            patch_replace(&model, "[node.bh]\ntype = blackhole\n").expect("patch should apply");

        let ini_doc = patched
            .ini_document
            .as_ref()
            .expect("patched model should have an ini_document");
        assert_eq!(ini_doc.get_property("node.bh", "type"), Some("blackhole"));
        assert_eq!(ini_doc.get_property("node.bh", "loc"), None);
    }

    #[test]
    fn patch_replace_adds_new_section() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let patched = patch_replace(&model, "[node.bh2]\ntype = blackhole\nloc = 5, 5\n")
            .expect("patch should apply");

        let ini_doc = patched
            .ini_document
            .as_ref()
            .expect("patched model should have an ini_document");
        assert_eq!(ini_doc.get_property("node.bh2", "type"), Some("blackhole"));
        assert_eq!(patched.nodes.len(), 3);
    }

    #[test]
    fn patch_replace_preserves_section_position() {
        // node.bh is upstream-linked from node.g; overriding it must not move
        // it below node.g in the file, or execution order would break -
        // per manifestos/node-definition-order.md
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let patched = patch_replace(&model, "[node.bh]\ntype = blackhole\nloc = 9, 9\n")
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
    fn patch_replace_does_not_mutate_original_model() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        let original = model.clone();

        let _patched =
            patch_replace(&model, "[node.bh]\ntype = blackhole\n").expect("patch should apply");

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
    fn patch_replace_preserves_working_directory() {
        let mut model = IniModelIO::read_model_string(model_ini()).expect("model should parse");
        model.working_directory = std::path::PathBuf::from("some/working/dir");
        let working_directory = model.working_directory.clone();

        let patched = patch_replace(&model, "[node.bh]\ntype = blackhole\nloc = 1, 2\n")
            .expect("patch should apply");

        assert_eq!(patched.working_directory, working_directory);
    }

    #[test]
    fn patch_replace_rejects_invalid_patch_string() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let result = patch_replace(&model, "not valid ini [[[");
        assert!(result.is_err());
    }

    #[test]
    fn patch_delete_rejects_properties() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let result = patch_delete(&model, "[node.g]\nparams =\n", false);
        assert!(result.is_err());
    }

    #[test]
    fn patch_delete_removes_entire_section_when_no_properties_listed() {
        let model =
            IniModelIO::read_model_string(model_ini_with_extra_node()).expect("model should parse");

        let patched = patch_delete(&model, "[node.bh2]\n", false).expect("patch should apply");

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

        let patched = patch_delete(&model, "[node.missing]\n", true).expect("patch should apply");

        assert_eq!(patched.nodes.len(), 2);
    }

    #[test]
    fn patch_delete_rejects_missing_section_when_not_missing_ok() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let result = patch_delete(&model, "[node.missing]\n", false);
        assert!(result.is_err());
    }

    #[test]
    fn patch_delete_does_not_mutate_original_model() {
        let model =
            IniModelIO::read_model_string(model_ini_with_extra_node()).expect("model should parse");
        let original = model.clone();

        let _patched = patch_delete(&model, "[node.bh2]\n", false).expect("patch should apply");

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
        let mut model =
            IniModelIO::read_model_string(model_ini_with_extra_node()).expect("model should parse");
        model.working_directory = std::path::PathBuf::from("some/working/dir");
        let working_directory = model.working_directory.clone();

        let patched = patch_delete(&model, "[node.bh2]\n", false).expect("patch should apply");

        assert_eq!(patched.working_directory, working_directory);
    }

    #[test]
    fn patch_delete_rejects_invalid_patch_string() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let result = patch_delete(&model, "not valid ini [[[", false);
        assert!(result.is_err());
    }

    #[test]
    fn patch_merge_rejects_patch_that_fails_configure() {
        // Syntactically fine (`area` parses as a number), but `configure()`
        // rejects a negative catchment area - a patch that parses cleanly
        // must still be rejected if it produces a model that can't run.
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let result = patch_merge(&model, "[node.g]\narea = -5\n");
        assert!(result.is_err());
    }

    #[test]
    fn patch_replace_rejects_patch_that_fails_configure() {
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let result = patch_replace(
            &model,
            "[node.g]\ntype = gr4j\nloc = 10, 20\narea = -5\nparams = 350, 0, 90, 1.7\nds_1 = bh\n",
        );
        assert!(result.is_err());
    }

    #[test]
    fn patch_delete_rejects_patch_that_fails_configure() {
        // node.g's `ds_1 = bh` references node.bh - deleting node.bh leaves a
        // dangling downstream reference, which is only caught by
        // `configure()`, not by ini parsing alone.
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let result = patch_delete(&model, "[node.bh]\n", false);
        assert!(result.is_err());
    }

    #[test]
    fn patch_update_referencing_missing_input_file_is_io_error() {
        // A patch that introduces an [data] entry pointing at a file that
        // doesn't exist must surface as KalixIoError::Io, not ::Parse - same
        // distinction as the top-level load path (see ini_model_io.rs tests).
        let model = IniModelIO::read_model_string(model_ini()).expect("model should parse");

        let result = patch_merge(&model, "[data]\n./does_not_exist_patch_test.csv\n");
        match result {
            Ok(_) => panic!("expected an error, got Ok"),
            Err(PatchError::IoError(KalixIoError::Io(_))) => {}
            Err(other) => panic!("expected PatchError::IoError(KalixIoError::Io), got {:?}", other),
        }
    }
}
