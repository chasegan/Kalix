//! Parsing and serialising the storage node's `forced_level` parameter.
//!
//! `forced_level` pins the storage to a supplied end-of-timestep level and
//! reports the water that pinning created or destroyed as `adjustment_volume`
//! (and, for the interior of a forced run only, as `sid_flux`). Behaviour is
//! covered by regression model 33; these tests cover the INI reader and
//! writer, plus the one non-obvious wiring decision the reader makes.

use crate::io::ini_model_io::IniModelIO;
use crate::model_inputs::DynamicInput;
use crate::nodes::NodeEnum;

/// Helper: borrow the `forced_level` input of a named storage node.
fn forced_level_of<'a>(model: &'a crate::model::Model, node_name: &str) -> &'a DynamicInput {
    match model.get_node(node_name).expect("node not found") {
        NodeEnum::StorageNode(n) => &n.forced_level_input,
        other => panic!("node '{}' is not a storage node: {}", node_name, other.get_type_as_string()),
    }
}

fn model_ini(forced_level_line: &str) -> String {
    format!(
        "[kalix]\n\
         \n\
         [node.test_storage]\n\
         type = storage\n\
         loc = 0, 0\n\
         initial_volume = 100\n\
         {forced_level_line}\
         dimensions = Level [m], Volume [ML], Area [km2], Spill [ML],\n\
         \x200        , 0          , 0         , 0,\n\
         \x2010       , 2000       , 10        , 0,\n\
         \x2011       , 2100       , 10        , 1000,\n"
    )
}

#[test]
fn forced_level_absent_leaves_the_input_unset() {
    let m = IniModelIO::read_model_string(&model_ini("")).unwrap();
    assert!(matches!(forced_level_of(&m, "test_storage"), DynamicInput::None { .. }),
            "an absent `forced_level` must stay unset, so the storage is never forced");
}

#[test]
fn forced_level_constant_parses() {
    let m = IniModelIO::read_model_string(&model_ini("forced_level = 5.0\n")).unwrap();
    assert!(matches!(forced_level_of(&m, "test_storage"), DynamicInput::Constant { .. }),
            "`forced_level = 5.0` must parse into a configured input (constant)");
}

/// An absent `forced_level` must not be materialised into the canonical form —
/// that would write a parameter the modeller never typed, and an unconfigured
/// input reads as 0.0 rather than NaN, which would force the storage to the
/// bottom of its table.
#[test]
fn absent_forced_level_is_not_emitted() {
    let m = IniModelIO::read_model_string(&model_ini("")).unwrap();
    let serialised = IniModelIO::model_to_string(&m);
    assert!(!serialised.contains("forced_level"),
            "absent `forced_level` should not be serialised, got:\n{}", serialised);
}

/// Pins a writer bug found by regression model 33: `forced_level` was read but
/// never written, so a canonical save silently turned a forced storage back
/// into an ordinary one. The model still ran — it just produced entirely
/// different results, with no warning.
#[test]
fn forced_level_survives_a_full_round_trip() {
    let m1 = IniModelIO::read_model_string(&model_ini("forced_level = 5.0\n")).unwrap();
    let serialised = IniModelIO::model_to_string(&m1);
    let m2 = IniModelIO::read_model_string(&serialised).unwrap();

    let before = forced_level_of(&m1, "test_storage").to_string();
    let after = forced_level_of(&m2, "test_storage").to_string();
    assert_eq!(before, after, "`forced_level` should be preserved through write -> read");
    assert!(!after.is_empty(), "`forced_level` should survive as a configured input");
}

/// A forced-level series is sparse by design: gaps are how the modeller says
/// "not forced on this timestep", and a calibration typically supplies observed
/// levels for only part of a longer run.
///
/// `Model::auto_determine_simulation_period` truncates the run at the first NaN
/// across every *critical* column, so if `forced_level` were critical a blank
/// field would abort the whole model with "Specified start/end inconsistent
/// with input data" rather than simply running unforced on those steps. Same
/// rule, and the same reason, as `expected_inflow` in `test_node_inflow`.
#[test]
fn forced_level_is_not_a_critical_input() {
    let ini = r#"
[kalix]
start = 2020-01-01
end = 2020-01-04

[node.inf]
type = inflow
loc = 0, 0
inflow = data.src_csv.by_name.driver
ds_1 = store

[node.store]
type = storage
loc = 0, 10
dimensions = Level [m], Volume [ML], Area [km2], Spill [ML],
             0        , 0          , 0.0       , 0,
             2        , 200        , 0.1       , 0,
forced_level = data.src_csv.by_name.observed_level
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 20
"#;
    let model = IniModelIO::read_model_string(ini).expect("model should load");
    let critical = model.data_cache.get_critical_input_names();

    assert!(critical.contains(&"data.src_csv.by_name.driver"),
        "`inflow` is driving data and must stay critical, got {:?}", critical);
    assert!(!critical.contains(&"data.src_csv.by_name.observed_level"),
        "`forced_level` is sparse by design and must not set the simulation period, got {:?}", critical);
}
