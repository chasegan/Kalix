// Tests for nodes with several incoming regulated links. A node that is not a
// confluence sends its full order up every regulated link (ordering.md,
// "Directing Orders"), and nothing limits how many it may have. A confluence
// directs orders up two branches at most.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

/// One supply storage per branch, each reaching `junction` through a pure lag,
/// with a regulated user below ordering 5. `{JUNCTION}` is the junction's
/// type and properties.
fn rig(branch_lags: &[(&str, usize)], junction: &str) -> String {
    let mut ini = String::from("[kalix]\nstart = 2020-01-01\nend = 2020-01-08\n");
    for (name, lag) in branch_lags {
        ini.push_str(&format!(r#"
[node.dam_{name}]
type = storage
loc = 0, 0
initial_volume = 12000
dimensions = 0,  0,     0,  0,
             10, 12000, 10, 0,
             11, 12100, 10, 1000,
ds_1 = lag_{name}

[node.lag_{name}]
type = routing
loc = 0, 10
lag = {lag}
ds_1 = junction
"#));
    }
    ini.push_str(&format!(r#"
[node.junction]
{junction}
loc = 5, 20
ds_1 = user_1

[node.user_1]
type = regulated_user
loc = 5, 30
order = 5

[outputs]
node.user_1.order_due
"#));
    for (name, _) in branch_lags {
        ini.push_str(&format!("node.dam_{name}.ds_1_order\n"));
    }
    ini
}

fn run(ini: &str) -> Result<Model, String> {
    let mut model = IniModelIO::read_model_string(ini).map_err(|e| e.to_string())?;
    model.configure().map_err(|e| e.to_string())?;
    model.run().map_err(|e| e.to_string())?;
    Ok(model)
}

fn series(model: &mut Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_series_idx(name, false)
        .unwrap_or_else(|| panic!("missing series {}", name));
    model.data_cache.series[idx].values.clone()
}

#[test]
fn test_three_regulated_links_into_a_gauge() {
    // Three regulated links into one node: the model runs, and the full order
    // goes up each of them.
    let ini = rig(&[("a", 1), ("b", 2), ("c", 3)], "type = gauge");
    let mut model = run(&ini).expect("three regulated inlets should run");
    for name in ["a", "b", "c"] {
        assert_eq!(series(&mut model, &format!("node.dam_{name}.ds_1_order"))[0], 5.0,
                   "the full order goes up every regulated link");
    }
    // The user's travel time is the longest branch
    assert_eq!(series(&mut model, "node.user_1.order_due")[..4], [0.0, 0.0, 0.0, 5.0]);
}

#[test]
fn test_three_regulated_links_into_a_confluence_is_an_error() {
    let ini = rig(&[("a", 1), ("b", 2), ("c", 3)], "type = confluence\nharmony_fraction = 0.5");
    let err = run(&ini).err().expect("a third regulated branch must be refused");
    assert!(err.contains("Confluence 'junction' has 3 regulated upstream links"), "got: {}", err);
}

#[test]
fn test_two_regulated_links_into_a_confluence_still_runs() {
    let ini = rig(&[("a", 1), ("b", 2)], "type = confluence\nharmony_fraction = 0.5");
    let mut model = run(&ini).expect("two regulated branches is the supported case");
    assert_eq!(series(&mut model, "node.dam_b.ds_1_order")[0], 2.5);
}
