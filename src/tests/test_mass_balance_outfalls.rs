// Tests for the mass balance at outlets that have no link. A node's mass
// balance is the water its outgoing links carried away less the water its
// incoming links brought, so water a node puts at an outlet with no link has
// left the model at that node.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;
use std::collections::HashMap;

fn mass_balance(nodes: &str) -> HashMap<String, f64> {
    let ini = format!("[kalix]\nstart = 2020-01-01\nend = 2020-01-10\n\n{nodes}");
    let mut model: Model = IniModelIO::read_model_string(&ini).expect("model should load");
    model.configure().expect("model should configure");
    model.run().expect("simulation should run");
    model.get_mass_balance_data().into_iter().map(|(name, _, v)| (name, v)).collect()
}

const RIVER: &str = "[node.river]\ntype = inflow\nloc = 0, 0\ninflow = 100\nds_1 = next\n\n";

#[test]
fn test_a_gauge_with_a_downstream_link_reads_zero() {
    let mbal = mass_balance(&format!("{RIVER}\
        [node.next]\ntype = gauge\nloc = 0, 10\nds_1 = sink\n\n\
        [node.sink]\ntype = blackhole\nloc = 0, 20\n"));
    assert_eq!(mbal["river"], 100.0);
    assert_eq!(mbal["next"], 0.0);
    assert_eq!(mbal["sink"], -100.0);
}

#[test]
fn test_a_gauge_with_no_downstream_link_loses_its_inflow() {
    let mbal = mass_balance(&format!("{RIVER}[node.next]\ntype = gauge\nloc = 0, 10\n"));
    assert_eq!(mbal["river"], 100.0);
    assert_eq!(mbal["next"], -100.0);
}

#[test]
fn test_a_splitter_loses_the_effluent_that_goes_nowhere() {
    // 25 of every 100 goes down ds_2, which has no link
    let mbal = mass_balance(&format!("{RIVER}\
        [node.next]\ntype = splitter\nloc = 0, 10\ntable = 0, 0, 100, 25\nds_1 = sink\n\n\
        [node.sink]\ntype = blackhole\nloc = 0, 20\n"));
    assert_eq!(mbal["next"], -25.0);
    assert_eq!(mbal["sink"], -75.0);
    assert_eq!(mbal.values().sum::<f64>(), 0.0, "the model balances");
}

#[test]
fn test_a_splitter_with_both_outlets_linked_reads_zero() {
    let mbal = mass_balance(&format!("{RIVER}\
        [node.next]\ntype = splitter\nloc = 0, 10\ntable = 0, 0, 100, 25\nds_1 = sink\nds_2 = drain\n\n\
        [node.sink]\ntype = blackhole\nloc = 0, 20\n\n\
        [node.drain]\ntype = blackhole\nloc = 10, 20\n"));
    assert_eq!(mbal["next"], 0.0);
    assert_eq!(mbal["sink"], -75.0);
    assert_eq!(mbal["drain"], -25.0);
}

#[test]
fn test_an_inflow_with_no_link_adds_nothing_to_the_network() {
    let mbal = mass_balance("[node.river]\ntype = inflow\nloc = 0, 0\ninflow = 100\n");
    assert_eq!(mbal["river"], 0.0);
}
