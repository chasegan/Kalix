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

/// The junction as an order-through weir, or as an order control.
const WEIR: &str = "type = storage\ninitial_volume = 5000\norder_through = true\ndimensions = 0,  0,     0,  0,\n             10, 12000, 10, 0,\n             11, 12100, 10, 1000,";

#[test]
fn test_storage_delay_does_not_depend_on_link_definition_order() {
    // An order-through weir delays its release by the travel time from the
    // supply, so that it releases as the ordered water arrives. With a lag-1
    // and a lag-3 branch that is 3 steps, the longest, as it is for the link
    // leaving the weir and for the user below. The result is the same
    // whichever order the branches are defined in.
    for branches in [[("a", 1), ("b", 3)], [("b", 3), ("a", 1)]] {
        let ini = rig(&branches, WEIR).replace("[outputs]", "[outputs]\nnode.junction.ds_1_order_due");
        let mut model = run(&ini).expect("model should run");
        assert_eq!(series(&mut model, "node.junction.ds_1_order_due")[..5], [0.0, 0.0, 0.0, 5.0, 5.0],
                   "branches defined {:?}", branches);
        assert_eq!(series(&mut model, "node.user_1.order_due")[..5], [0.0, 0.0, 0.0, 5.0, 5.0]);
    }
}

#[test]
fn test_order_control_delay_does_not_depend_on_link_definition_order() {
    for branches in [[("a", 1), ("b", 3)], [("b", 3), ("a", 1)]] {
        let ini = rig(&branches, "type = order_control").replace("[outputs]", "[outputs]\nnode.junction.order_due");
        let mut model = run(&ini).expect("model should run");
        assert_eq!(series(&mut model, "node.junction.order_due")[..5], [0.0, 0.0, 0.0, 5.0, 5.0],
                   "branches defined {:?}", branches);
    }
}

#[test]
fn test_travel_time_below_a_one_name_confluence_is_the_named_branch() {
    // `regulated = lag_a` sends every order up the lag-1 branch, so the ordered
    // water reaches the user after 1 step. The user's travel time is therefore
    // 1 step, whatever the lag of the unnamed branch and whichever order the
    // branches are defined in.
    for branches in [[("a", 1), ("b", 3)], [("b", 3), ("a", 1)]] {
        let ini = rig(&branches, "type = confluence\nregulated = lag_a");
        let mut model = run(&ini).expect("model should run");
        assert_eq!(series(&mut model, "node.dam_a.ds_1_order")[0], 5.0);
        assert_eq!(series(&mut model, "node.dam_b.ds_1_order")[0], 0.0, "no order travels up the unnamed branch");
        assert_eq!(series(&mut model, "node.user_1.order_due")[..3], [0.0, 5.0, 5.0],
                   "branches defined {:?}", branches);
    }
}

#[test]
fn test_travel_time_below_a_two_name_confluence_is_the_longest_branch() {
    let ini = rig(&[("a", 1), ("b", 3)], "type = confluence\nregulated = lag_a, lag_b\nharmony_fraction = 0.5");
    let mut model = run(&ini).expect("model should run");
    assert_eq!(series(&mut model, "node.user_1.order_due")[..5], [0.0, 0.0, 0.0, 5.0, 5.0]);
}

#[test]
fn test_harmony_fraction_is_recorded_for_the_step_it_was_used() {
    // The fraction steps from 0 to 1 on the third step. The recorded series
    // shows each value on the step the ordering system used it.
    let ini = rig(&[("a", 1), ("b", 1)], "type = confluence\nregulated = lag_a, lag_b\nharmony_fraction = if(var.count.n > 2, 1, 0)")
        .replace("end = 2020-01-08\n", "end = 2020-01-08\n\n[var.count]\nphase = ras\nn = var.count.n[-1, 0] + 1\n")
        .replace("[outputs]", "[outputs]\nnode.junction.harmony_fraction");
    let mut model = run(&ini).expect("model should run");
    assert_eq!(series(&mut model, "node.junction.harmony_fraction")[..4], [0.0, 0.0, 1.0, 1.0]);
    // ...and it is the split that was used: all of the order goes to dam_a from the third step
    assert_eq!(series(&mut model, "node.dam_a.ds_1_order")[..4], [0.0, 0.0, 5.0, 5.0]);
    assert_eq!(series(&mut model, "node.dam_b.ds_1_order")[..4], [5.0, 5.0, 0.0, 0.0]);
}
