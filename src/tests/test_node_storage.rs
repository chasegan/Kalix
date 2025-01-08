use crate::model::Model;
use crate::nodes::inflow_node::InflowNode;
use crate::nodes::Node;


/*
Create a new inflow node and check out it's ID (GUID)
 */
#[test]
fn test_create_storage_node() {
    let mut i = InflowNode::new();
    let id = i.get_id();
    println!("ID = {}", i.get_id());
    let mut m = Model::new();
    //m.node_network.add_node(Box::new(i));
    m.add_node(Box::new(i));
    
    //And here is how you can get an immutable reference to a node again.
    let n = m.get_node(id).unwrap();
    println!("ID = {}", n.get_id());
}

