// The order phase visits nodes in reverse definition order: downstream before
// upstream, the file read from the bottom. That order is what decides whether
// an expression may read another node's order-phase result in the same step,
// so it holds for every node - including a supply storage at the top of the
// network, which has no incoming regulated link.

use crate::io::ini_model_io::IniModelIO;
use crate::model::Model;

/// Two independent supply-and-user systems, `a` defined above `b`.
fn rig(order_a: &str, order_b: &str) -> String {
    let mut ini = String::from("[kalix]\nstart = 2020-01-01\nend = 2020-01-04\n");
    for (name, order) in [("a", order_a), ("b", order_b)] {
        ini.push_str(&format!(r#"
[node.dam_{name}]
type = storage
loc = 0, 0
initial_volume = 12000
dimensions = 0,  0,     0,  0,
             10, 12000, 10, 0,
             11, 12100, 10, 1000,
ds_1 = user_{name}

[node.user_{name}]
type = regulated_user
loc = 0, 10
order = {order}
"#));
    }
    ini.push_str("\n[outputs]\nnode.user_a.order\nnode.user_b.order\nnode.dam_a.ds_1_order\nnode.dam_b.ds_1_order\n");
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
fn test_a_node_can_read_the_order_on_a_supply_defined_below_it() {
    // dam_b is defined below user_a, so it is visited first, and the order on
    // it is there for user_a to read. dam_b is a supply at the top of its
    // network, with no incoming regulated link, and is visited in its place
    // like any other node.
    let mut model = run(&rig("node.dam_b.ds_1_order", "7")).expect("dam_b is visited before user_a");
    assert_eq!(series(&mut model, "node.dam_b.ds_1_order")[0], 7.0);
    assert_eq!(series(&mut model, "node.user_a.order")[0], 7.0);
    assert_eq!(series(&mut model, "node.dam_a.ds_1_order")[0], 7.0);
}

#[test]
fn test_a_node_cannot_read_the_order_on_a_supply_defined_above_it() {
    // dam_a is defined above user_b, so it is visited later: the same rule, read the other way.
    let err = run(&rig("7", "node.dam_a.ds_1_order")).err().expect("dam_a is visited after user_b");
    assert!(err.contains("no value yet"), "got: {}", err);
}
