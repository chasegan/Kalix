//! GR4JSG: validation against the Fors node it was ported from, and the
//! node's model-file surface (load, save, optimisable parameters).
//!
//! The reference fixtures come from a line-by-line Python transliteration of
//! `GRSGDPIENode.cs`; see `example_data/gr4jsg/generate_reference.py`.

use crate::hydrology::rainfall_runoff::gr4jsg::Gr4jsg;
use crate::io::csv_io;
use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;
use crate::nodes::NodeEnum;
use crate::numerical::opt::optimisable_component::OptimisableComponent;

/// Agreement tolerance (mm). The two implementations differ only in
/// operation order and in the reference's cap of 13 on the tanh argument;
/// the fixtures are written to 12 significant figures.
const TOL_MM: f64 = 1e-6;

/// Run the fixture's climate through `model` and return the largest
/// deviation from the reference runoff, snow store and ice store.
fn max_deviation(fixture: &str, model: &mut Gr4jsg) -> f64 {
    let data = csv_io::read_ts(fixture).unwrap_or_else(|e| panic!("could not read {fixture}: {e}"));
    let (rain, pet, tmax, tmin) = (&data[0], &data[1], &data[2], &data[3]);
    let (runoff, snow, ice) = (&data[4], &data[5], &data[6]);
    model.initialize();
    let mut max_abs = 0.0_f64;
    for i in 0..rain.len() {
        let q = model.run_step(rain.values[i], pet.values[i], tmax.values[i], tmin.values[i]);
        max_abs = max_abs
            .max((q - runoff.values[i]).abs())
            .max((model.snow_store - snow.values[i]).abs())
            .max((model.ice_store - ice.values[i]).abs());
    }
    max_abs
}

fn reference_model() -> Gr4jsg {
    let mut m = Gr4jsg::new();
    m.gr4j.x1 = 320.0;
    m.gr4j.x2 = -1.5;
    m.gr4j.x3 = 75.0;
    m.gr4j.x4 = 1.7;
    m.tfrac = 0.4;
    m.taccum = 0.5;
    m.m_rainfall = 3.38;
    m.base_rainfall = 1.3;
    m.m_nonrainfall = 2.2;
    m
}

#[test]
fn snow_only_matches_fors_reference() {
    let mut m = reference_model();
    let max_abs = max_deviation("./src/tests/example_data/gr4jsg/gr4jsg_snow_reference.csv", &mut m);
    println!("GR4JSG (snow only) vs Fors: max_abs_dev = {max_abs:.3e} mm");
    assert!(max_abs < TOL_MM, "GR4JSG diverges from the Fors reference: max_abs={max_abs:e} mm");
}

#[test]
fn glacier_matches_fors_reference() {
    // The glacier melts out part-way through, so both regimes are covered.
    let mut m = reference_model();
    m.glacier = true;
    m.initial_ice = 80000.0;
    m.ddfi = 5.0;
    m.tmelt = -0.5;
    m.return_flow = 3.2;
    m.accumulation = 2.0;
    let max_abs = max_deviation("./src/tests/example_data/gr4jsg/gr4jsg_glacier_reference.csv", &mut m);
    println!("GR4JSG (glacier) vs Fors: max_abs_dev = {max_abs:.3e} mm");
    assert!(max_abs < TOL_MM, "GR4JSG diverges from the Fors reference: max_abs={max_abs:e} mm");
}

// ----------------------------------------------------------------------------
// The node in a model file
// ----------------------------------------------------------------------------

fn model_ini(extra_lines: &str) -> String {
    format!("\
[kalix]
start = 2020-01-01
end = 2020-01-10

[node.snowy]
type = gr4jsg
loc = 0, 0
area = 100
rain = 20
evap = 1
tmax = -1
tmin = -9
params = 350, 0, 90, 1.7
snow_params = 0.5, 0, 3.38, 1.3, 3
{extra_lines}
[outputs]
node.snowy.runoff_depth
node.snowy.snowfall
node.snowy.snow_store
node.snowy.ice_store
node.snowy.ice_melt
")
}

fn snowy(model: &Model) -> &crate::nodes::gr4jsg_node::Gr4jsgNode {
    match model.get_node("snowy").expect("node not found") {
        NodeEnum::Gr4jsgNode(n) => n,
        other => panic!("node 'snowy' is not a gr4jsg node: {}", other.get_type_as_string()),
    }
}

fn series(model: &Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_existing_series_idx(name)
        .unwrap_or_else(|| panic!("series '{name}' should exist"));
    model.data_cache.series[idx].values.clone()
}

#[test]
fn node_runs_and_stores_cold_precipitation_as_snow() {
    let mut model = IniModelIO::read_model_string(&model_ini("")).unwrap();
    model.configure().unwrap();
    model.run().unwrap();
    assert_eq!(series(&model, "node.snowy.snowfall"), vec![20.0; 10]);
    assert_eq!(series(&model, "node.snowy.snow_store").last(), Some(&200.0));
    assert_eq!(series(&model, "node.snowy.runoff_depth"), vec![0.0; 10]);
    // A snow-only node still reports the ice outputs, as zero.
    assert_eq!(series(&model, "node.snowy.ice_store"), vec![0.0; 10]);
}

#[test]
fn ice_params_gives_the_node_a_glacier() {
    let snow_only = IniModelIO::read_model_string(&model_ini("")).unwrap();
    assert!(!snowy(&snow_only).gr4jsg_model.glacier);

    let mut glacier = IniModelIO::read_model_string(&model_ini("ice_params = 5000, 6, -12, 0.5, 0\n")).unwrap();
    let m = &snowy(&glacier).gr4jsg_model;
    assert!(m.glacier);
    assert_eq!((m.initial_ice, m.ddfi, m.tmelt, m.return_flow, m.accumulation), (5000.0, 6.0, -12.0, 0.5, 0.0));

    // rep temp is -5 and tmelt is -12, so the ice melts under the snow that never melts.
    glacier.configure().unwrap();
    glacier.run().unwrap();
    assert_eq!(series(&glacier, "node.snowy.ice_melt"), vec![42.0; 10]);
    assert_eq!(series(&glacier, "node.snowy.runoff_depth"), vec![42.0; 10]);
}

#[test]
fn glacier_parameters_are_optimisable_only_with_a_glacier() {
    let snow_only = IniModelIO::read_model_string(&model_ini("")).unwrap();
    let mut node = snowy(&snow_only).clone();
    assert_eq!(node.list_params(), ["x1", "x2", "x3", "x4", "tfrac", "taccum", "m_rainfall", "base_rainfall", "m_nonrainfall"]);
    assert!(node.set_param("ddfi", 4.0).unwrap_err().contains("ice_params"));
    assert!(node.get_param("return_flow").unwrap_err().contains("ice_params"));
    node.set_param("taccum", 1.5).unwrap();
    assert_eq!(node.get_param("taccum").unwrap(), 1.5);

    let glacier = IniModelIO::read_model_string(&model_ini("ice_params = 5000, 6, 0, 0.5, 0\n")).unwrap();
    let mut node = snowy(&glacier).clone();
    assert_eq!(&node.list_params()[9..], ["ddfi", "tmelt", "return_flow", "accumulation"]);
    node.set_param("return_flow", 12.0).unwrap();
    assert_eq!(node.get_param("return_flow").unwrap(), 12.0);
    // The starting ice depth is not a calibration parameter.
    assert!(node.set_param("initial_ice", 1.0).is_err());
}

#[test]
fn node_survives_a_round_trip_and_writes_ice_params_only_for_a_glacier() {
    let out = IniModelIO::model_to_string(&IniModelIO::read_model_string(&model_ini("")).unwrap());
    assert!(out.contains("snow_params = 0.5, 0, 3.38, 1.3, 3"), "{out}");
    assert!(out.contains("tmax = -1") && out.contains("tmin = -9"), "{out}");
    assert!(!out.contains("ice_params"), "a snow-only node should not write ice_params:\n{out}");

    let m1 = IniModelIO::read_model_string(&model_ini("ice_params = 5000, 6, 0.5, 12, 2\n")).unwrap();
    let out = IniModelIO::model_to_string(&m1);
    assert!(out.contains("ice_params = 5000, 6, 0.5, 12, 2"), "{out}");
    let m2 = IniModelIO::read_model_string(&out).unwrap();
    assert!(snowy(&m2).gr4jsg_model.glacier);
    assert_eq!(snowy(&m2).gr4jsg_model.return_flow, 12.0);
}

#[test]
fn bad_definitions_are_refused_with_the_reason() {
    let short = IniModelIO::read_model_string(&model_ini("ice_params = 5000, 6, 0\n"));
    assert!(short.err().expect("three ice_params should be refused").to_string().contains("ice_params must have 5 values"));

    let no_tmin = model_ini("").replace("tmin = -9\n", "");
    let mut model = IniModelIO::read_model_string(&no_tmin).unwrap();
    let err = model.configure().and_then(|_| model.run()).expect_err("a missing tmin should be refused");
    assert!(err.to_string().contains("tmin"), "{err}");

    let bad_tfrac = model_ini("").replace("snow_params = 0.5", "snow_params = 1.5");
    let mut model = IniModelIO::read_model_string(&bad_tfrac).unwrap();
    let err = model.configure().and_then(|_| model.run()).expect_err("tfrac of 1.5 should be refused");
    assert!(err.to_string().contains("tfrac"), "{err}");
}
