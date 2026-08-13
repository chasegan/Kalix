//! End-to-end INI-pipeline tests for static scalar node/account properties
//! (`node.<name>.<property>`, `acc.<name>.size`) - the values registered into
//! `DataCache::static_properties` while nodes/accounts are parsed, readable
//! from any expression thereafter.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

/// Load, configure and run a model from INI, panicking with context on any
/// failure.
fn run_model(ini: &str) -> Model {
    let mut model = IniModelIO::read_model_string(ini)
        .unwrap_or_else(|e| panic!("model should load: {e}"));
    model.configure().unwrap_or_else(|e| panic!("configure should succeed: {e}"));
    model.run().unwrap_or_else(|e| panic!("run should succeed: {e}"));
    model
}

/// Read a series' values by name, panicking if the series does not exist.
fn series(model: &Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_existing_series_idx(name)
        .unwrap_or_else(|| panic!("series '{name}' should exist"));
    model.data_cache.series[idx].values.clone()
}

const HEADER: &str = "\
[kalix]
start = 2020-01-30
end = 2020-02-01
";

/// A storage node whose INI section name has uppercase letters must still
/// resolve its own static properties under the (always-lowercase) expression
/// spelling of its name - node names are case-insensitive everywhere else.
#[test]
fn uppercase_node_name_resolves_its_own_static_properties() {
    let ini = format!("{HEADER}
[node.MyStore]
type = storage
loc = 0, 0
initial_volume = 500
dimensions = Level [m], Volume [ML], Area [km2], Spill [ML],
             0.0      , 0.0        , 0.0       , 0.0,
             1.0      , 1000.0     , 0.1       , 0.0,
             2.0      , 2000.0     , 0.1       , 1.0e9,
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 10

[var.calc]
initial = node.mystore.initial_volume

[outputs]
var.calc.initial
");
    let model = run_model(&ini);
    let vals = series(&model, "var.calc.initial");
    assert_eq!(vals, vec![500.0; vals.len()],
        "node.mystore.initial_volume should resolve to the value declared under [node.MyStore], every step");
}

/// A static property can be listed directly in [outputs], with no [var.*]
/// detour, and comes back filled for the whole simulation horizon.
#[test]
fn static_property_listed_directly_in_outputs_is_filled() {
    let ini = format!("{HEADER}
[node.MyStore]
type = storage
loc = 0, 0
initial_volume = 500
dimensions = Level [m], Volume [ML], Area [km2], Spill [ML],
             0.0      , 0.0        , 0.0       , 0.0,
             1.0      , 1000.0     , 0.1       , 0.0,
             2.0      , 2000.0     , 0.1       , 1.0e9,
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 10

[outputs]
node.mystore.initial_volume
");
    let model = run_model(&ini);
    let vals = series(&model, "node.mystore.initial_volume");
    // 2020-01-30 .. 2020-02-01 inclusive, daily: Jan30, Jan31, Feb1 = 3 steps.
    assert_eq!(vals.len(), 3, "static-property output should span the whole simulation horizon");
    assert_eq!(vals, vec![500.0; 3]);
}

/// A routing node's static properties (`x`, `typical_regulated_flow`) resolve
/// the same way, under a mixed-case section name.
#[test]
fn uppercase_routing_node_resolves_x_and_typical_regulated_flow() {
    let ini = format!("{HEADER}
[node.Src]
type = inflow
loc = 0, 0
inflow = 10
ds_1 = Rte

[node.Rte]
type = routing
loc = 0, 10
lag = 0
n_divs = 1
x = 0.25
nlm = 1.0, 1.0
typical_regulated_flow = 42
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 20

[var.calc]
x_val = node.rte.x
trf_val = node.rte.typical_regulated_flow

[outputs]
var.calc.x_val
var.calc.trf_val
");
    let model = run_model(&ini);
    let x_vals = series(&model, "var.calc.x_val");
    let trf_vals = series(&model, "var.calc.trf_val");
    assert_eq!(x_vals, vec![0.25; x_vals.len()]);
    assert_eq!(trf_vals, vec![42.0; trf_vals.len()]);
}

/// gr4j and sacramento both expose their catchment area under the same
/// `node.<name>.area` key for the same INI `area` property - previously
/// sacramento registered it as `node.<name>.area_km2` instead.
#[test]
fn gr4j_and_sacramento_expose_area_under_the_same_key() {
    let ini = format!("{HEADER}
[node.g]
type = gr4j
loc = 0, 0
area = 100
rain = 0
evap = 0
params = 350, 0, 90, 1.7
ds_1 = sink

[node.s]
type = sacramento
loc = 10, 0
area = 80
rain = 0
evap = 0
params = 0.01, 40.0, 23.0, 0.009,
         0.043, 130.0, 0.01, 0.063,
         1.0, 0.01, 0.0, 0.0,
         40.0, 0.245, 50.0, 40.0,
         0.1
ds_1 = sink

[node.sink]
type = blackhole
loc = 5, 10

[var.calc]
g_area = node.g.area
s_area = node.s.area

[outputs]
var.calc.g_area
var.calc.s_area
");
    let model = run_model(&ini);
    let g_area = series(&model, "var.calc.g_area");
    let s_area = series(&model, "var.calc.s_area");
    assert_eq!(g_area, vec![100.0; g_area.len()]);
    assert_eq!(s_area, vec![80.0; s_area.len()],
        "node.s.area should resolve sacramento's area under the same key gr4j uses, not node.s.area_km2");
}

/// An account's `size` is registered as a static property under
/// `acc.<name>.size`, resolvable regardless of the casing used to declare
/// the account name.
#[test]
fn account_size_resolves_as_a_static_property() {
    let ini = format!("{HEADER}
[acc.g1]
accounts = name, size, initial,
           A1, 100, 10,

[node.src]
type = inflow
loc = 0, 0
inflow = 0
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 10

[var.calc]
a1_size = acc.a1.size

[outputs]
var.calc.a1_size
");
    let model = run_model(&ini);
    let vals = series(&model, "var.calc.a1_size");
    assert_eq!(vals, vec![100.0; vals.len()]);
}
