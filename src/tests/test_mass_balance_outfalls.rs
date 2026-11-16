// Tests for the mass balance at outlets that have no link. A node's mass
// balance is the water its outgoing links carried away less the water its
// incoming links brought, so water a node puts at an outlet with no link has
// left the model at that node.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;
use std::collections::HashMap;

fn run(nodes: &str) -> Model {
    let ini = format!("[kalix]\nstart = 2020-01-01\nend = 2020-01-10\n\n{nodes}");
    let mut model: Model = IniModelIO::read_model_string(&ini).expect("model should load");
    model.configure().expect("model should configure");
    model.run().expect("simulation should run");
    model
}

fn report(model: &Model) -> HashMap<String, f64> {
    model.get_mass_balance_data().into_iter().map(|(name, _, v)| (name, v)).collect()
}

fn mass_balance(nodes: &str) -> HashMap<String, f64> {
    report(&run(nodes))
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

#[test]
fn test_a_run_that_fails_keeps_nothing_from_the_run_before() {
    let mut model = run(&format!("{RIVER}[node.next]\ntype = gauge\nloc = 0, 10\n"));
    assert_eq!(report(&model)["next"], -100.0);
    // A link that points up the file: the next run is refused
    model.add_link(1, 0, 0, 0);
    assert!(model.run().is_err());
    assert_eq!(report(&model)["next"], 0.0, "the earlier run's outfall is not held over");
}

#[test]
fn test_a_user_with_no_downstream_link_loses_its_inflow() {
    // It keeps 20 for itself, and the 80 it passes on has nowhere to go
    let mbal = mass_balance(&format!("{RIVER}[node.next]\ntype = unregulated_user\nloc = 0, 10\ndemand = 20\n"));
    assert_eq!(mbal["next"], -100.0);
    assert_eq!(mbal.values().sum::<f64>(), 0.0, "the model balances");
}

#[test]
fn test_a_storage_loses_a_forced_release_at_an_outlet_with_no_link() {
    // ds_2 and ds_3 release without an order, so they hold water with no link. Nothing
    // leaves the storage through a link, so everything it received is lost to the network.
    let mbal = mass_balance(&format!("{RIVER}\
        [node.next]\ntype = storage\nloc = 0, 10\n\
        dimensions = Level [m], Volume [ML], Area [km2], Spill [ML], 0, 0, 0, 0, 5000, 5000, 1, 0, 5001, 5001, 1, 1,\n\
        ds_2_force_release = 30\nds_3_force_release = 7\n"));
    assert_eq!(mbal["next"], -100.0);
    assert_eq!(mbal.values().sum::<f64>(), 0.0, "the model balances");
}

#[test]
fn test_outfalls_are_counted_in_a_model_with_flow_var_blocks() {
    // The var block puts the flow phase on its interleaved loop
    let mbal = mass_balance(&format!("[var.probe]\nx = 1\n\n{RIVER}[node.next]\ntype = gauge\nloc = 0, 10\n"));
    assert_eq!(mbal["river"], 100.0);
    assert_eq!(mbal["next"], -100.0);
}

#[test]
fn test_a_second_run_starts_its_outfalls_from_nothing() {
    let mut model = run(&format!("{RIVER}[node.next]\ntype = gauge\nloc = 0, 10\n"));
    let first = report(&model);
    model.run().expect("simulation should run again");
    assert_eq!(report(&model), first);
    assert_eq!(first["next"], -100.0);
}
