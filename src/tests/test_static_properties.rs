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

/// A reference to a node's static property must resolve even when it appears
/// *before* that node's [node.*] section in the file - static properties are
/// registered in a pre-pass, not inline as each section is parsed, so file
/// order can't leave a forward reference silently unresolved (reading 0
/// instead of the declared value).
#[test]
fn static_property_resolves_when_referenced_before_its_node_section() {
    let ini = format!("{HEADER}
[var.calc]
scaled = node.later_catchment.area * 0.1

[node.later_catchment]
type = gr4j
loc = 0, 0
area = 100
rain = 0
evap = 0
params = 350, 0, 90, 1.7
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 10

[outputs]
var.calc.scaled
");
    let model = run_model(&ini);
    let vals = series(&model, "var.calc.scaled");
    assert_eq!(vals, vec![10.0; vals.len()],
        "node.later_catchment.area should resolve to 100 even though [var.calc] is declared first, not silently read as 0");
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

/// A group's `size` is the sum of its members' declared sizes, also a static
/// property, also directly output-able with no [var.*] detour.
#[test]
fn group_size_is_the_static_sum_of_member_sizes() {
    let ini = format!("{HEADER}
[acc.g1]
accounts = name, size,
           a1,   100,
           a2,   50,

[node.src]
type = inflow
loc = 0, 0
inflow = 0
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 10

[outputs]
acc.g1.size
");
    let model = run_model(&ini);
    let vals = series(&model, "acc.g1.size");
    assert_eq!(vals, vec![150.0; vals.len()]);
}

/// A group's `initial` is the sum of its members' declared opening balances -
/// same static-aggregate treatment as size.
#[test]
fn group_initial_is_the_static_sum_of_member_initials() {
    let ini = format!("{HEADER}
[acc.g1]
accounts = name, size, initial,
           a1,   100,  10,
           a2,   50,   5,
           a3,   20,   0,

[node.src]
type = inflow
loc = 0, 0
inflow = 0
ds_1 = sink

[node.sink]
type = blackhole
loc = 0, 10

[outputs]
acc.g1.initial
");
    let model = run_model(&ini);
    let vals = series(&model, "acc.g1.initial");
    assert_eq!(vals, vec![15.0; vals.len()]);
}

// ============================================================================
// Guard rails
// ============================================================================

/// A static property has no series behind it, so offset syntax cannot mean
/// anything — and the modeller must be told *that*, not "not found in variable
/// maps", which reads like the name is unknown when it plainly is not. Same
/// restriction, and the same shape of message, as `const.*`.
#[test]
fn offset_syntax_on_a_static_property_explains_itself() {
    let expect_offset_error = |expr: &str| {
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

[node.dam]
type = storage
loc = 0, 20
initial_volume = 500
dimensions = Level [m], Volume [ML], Area [km2], Spill [ML],
             0.0      , 0.0        , 0.0       , 0.0,
             1.0      , 1000.0     , 0.1       , 0.0,
             2.0      , 2000.0     , 0.1       , 1.0E9,

[var.calc]
v = {expr}

[outputs]
var.calc.v
");
        match IniModelIO::read_model_string(&ini) {
            Ok(_) => panic!("'{expr}' should be rejected at load"),
            Err(e) => {
                let msg = e.to_string();
                assert!(msg.contains("Offset syntax not supported for static properties"),
                    "'{expr}' should say why, got: {msg}");
                assert!(!msg.contains("not found in variable maps"),
                    "'{expr}' must not claim the name is unknown, got: {msg}");
                msg
            }
        }
    };

    // Accounts and nodes, backward and forward: all four take the same route.
    expect_offset_error("acc.a1.size[-1, 0]");
    expect_offset_error("acc.a1.initial[-1, 0]");
    expect_offset_error("node.dam.initial_volume[-1, 0]");
    // Forward offsets too: the static guard runs before the forward-lookup
    // guard, so the message names the real reason rather than the direction.
    let forward = expect_offset_error("node.dam.initial_volume[1, 0]");
    assert!(!forward.contains("Forward lookup"),
        "a static property's message should not blame the direction: {forward}");
}

/// Static properties are captured once, from the INI text, and never
/// refreshed. The optimiser sets node parameters directly (`node.<n>.<param>`
/// addresses, supported by gr4j/sacramento/routing — the same node types this
/// list draws from), so a property that were both static AND optimisable would
/// report its declared value while the run used the candidate value: a wrong
/// number with no signal, which `performance.md §6.1-6.2` rules out.
///
/// Nothing structural prevents that pairing; this test is the guard. If a
/// property ever needs to be both, the fix is NOT to delete an entry here —
/// it is to refresh `DataCache::static_properties` when parameters change (at
/// run start, and after each optimiser candidate is applied) instead of
/// filling it at load, and then to retire this test deliberately.
#[test]
fn static_properties_are_disjoint_from_optimisable_params() {
    use crate::io::ini_model_io_versions::ini_doc_model_io_0_0_1::NODE_STATIC_F64_PROPERTIES;
    use crate::numerical::opt::optimisable_component::OptimisableComponent;

    // Routing reports a different parameter set per mode, so check both.
    let mut pwl_routing = crate::nodes::routing_node::RoutingNode::new();
    pwl_routing.set_routing_table(vec![0.0, 50.0, 500.0], vec![2.0, 1.5, 1.0]);
    let nlm_routing = crate::nodes::routing_node::RoutingNode::new();

    let optimisable: Vec<(&str, Vec<String>)> = vec![
        ("gr4j", crate::nodes::gr4j_node::Gr4jNode::new().list_params()),
        ("sacramento", crate::nodes::sacramento_node::SacramentoNode::new().list_params()),
        ("routing", nlm_routing.list_params()),
        ("routing", pwl_routing.list_params()),
    ];

    for (node_type, params) in &optimisable {
        let statics: Vec<&str> = NODE_STATIC_F64_PROPERTIES.iter()
            .filter(|(t, _)| t == node_type)
            .map(|(_, p)| *p)
            .collect();
        for param in params {
            assert!(!statics.contains(&param.to_lowercase().as_str()),
                "'{param}' is both an optimisable parameter and a static property of '{node_type}'. \
                 Static properties are captured at load and never refreshed, so expressions reading \
                 node.<name>.{param} would report the declared value while the run used the \
                 optimiser's candidate. Refresh static_properties on parameter change instead of \
                 removing it from one of the lists.");
        }
    }

    // Sanity: the lists are actually populated, so this test cannot pass vacuously.
    assert!(optimisable.iter().all(|(_, p)| !p.is_empty()), "expected optimisable params to enumerate");
    assert!(!NODE_STATIC_F64_PROPERTIES.is_empty());
}
