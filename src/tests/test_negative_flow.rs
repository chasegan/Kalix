// Tests for inputs that ask for negative water. No node puts negative water
// at an outlet: each is written so that it cannot. Two inputs can ask for it,
// an inflow and a gauge's forced flow, and their nodes treat a negative value
// as none. The result of the same name records the value as it was given, so
// a modeller can see it. A value that is not a number is left as it is, and
// no link carries it.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;
use std::collections::HashMap;

fn run(nodes: &str) -> Model {
    let ini = format!("[kalix]\nstart = 2020-01-01\nend = 2020-01-10\n\n{nodes}\n[outputs]\nnode.river.inflow\nnode.mid.inflow\nnode.mid.force_flow\nnode.mid.dsflow\nnode.sink.usflow\n");
    let mut model = IniModelIO::read_model_string(&ini).expect("model should load");
    model.configure().expect("model should configure");
    model.run().expect("simulation should run");
    model
}

fn series(model: &mut Model, name: &str) -> Vec<f64> {
    let idx = model.data_cache.get_series_idx(name, false)
        .unwrap_or_else(|| panic!("missing series {}", name));
    model.data_cache.series[idx].values.clone()
}

fn mass_balance(model: &Model) -> HashMap<String, f64> {
    model.get_mass_balance_data().into_iter().map(|(name, _, v)| (name, v)).collect()
}

const RIVER: &str = "[node.river]\ntype = inflow\nloc = 0, 0\ninflow = 100\nds_1 = mid\n\n";
const SINK: &str = "[node.sink]\ntype = blackhole\nloc = 0, 20\n";

#[test]
fn test_a_negative_inflow_is_no_inflow() {
    // The node does not take water from the flow passing through
    let mut model = run(&format!("{RIVER}[node.mid]\ntype = inflow\nloc = 0, 10\ninflow = -30\nds_1 = sink\n\n{SINK}"));
    assert!(series(&mut model, "node.mid.dsflow").iter().all(|&v| v == 100.0));
    assert!(series(&mut model, "node.mid.inflow").iter().all(|&v| v == -30.0), "the result shows what was asked for");
    let mbal = mass_balance(&model);
    assert_eq!(mbal["mid"], 0.0);
    assert_eq!(mbal["sink"], -100.0);
}

#[test]
fn test_a_negative_inflow_at_the_head_of_a_river_puts_nothing_at_the_outlet() {
    let mut model = run(&format!("[node.river]\ntype = inflow\nloc = 0, 0\ninflow = -5\nds_1 = mid\n\n\
        [node.mid]\ntype = gauge\nloc = 0, 10\nds_1 = sink\n\n{SINK}"));
    assert!(series(&mut model, "node.mid.dsflow").iter().all(|&v| v == 0.0));
    let mbal = mass_balance(&model);
    assert_eq!(mbal["river"], 0.0);
    assert_eq!(mbal.values().sum::<f64>(), 0.0, "the model balances");
}

#[test]
fn test_a_negative_forced_flow_at_a_gauge_is_no_flow() {
    let mut model = run(&format!("{RIVER}[node.mid]\ntype = gauge\nloc = 0, 10\nforce_flow = -5\nds_1 = sink\n\n{SINK}"));
    assert!(series(&mut model, "node.mid.dsflow").iter().all(|&v| v == 0.0));
    assert!(series(&mut model, "node.sink.usflow").iter().all(|&v| v == 0.0));
    let mbal = mass_balance(&model);
    assert_eq!(mbal["mid"], -100.0);
    assert_eq!(mbal.values().sum::<f64>(), 0.0, "the model balances");
}

#[test]
fn test_an_inflow_that_is_not_a_number_is_carried_by_no_link() {
    let mut model = run(&format!("[node.river]\ntype = inflow\nloc = 0, 0\ninflow = 0 / 0\nds_1 = mid\n\n\
        [node.mid]\ntype = gauge\nloc = 0, 10\nds_1 = sink\n\n{SINK}"));
    assert!(series(&mut model, "node.mid.dsflow").iter().all(|&v| v == 0.0), "nothing reaches the gauge");
    assert!(series(&mut model, "node.sink.usflow").iter().all(|&v| v == 0.0));
    // It is not taken as zero: the node's own results still say it is not a number
    assert!(series(&mut model, "node.river.inflow").iter().all(|v| v.is_nan()));
    assert!(mass_balance(&model)["river"].is_nan());
}
