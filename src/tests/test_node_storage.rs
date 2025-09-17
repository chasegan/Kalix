use crate::model::Model;
use crate::nodes::inflow_node::InflowNode;
use crate::nodes::{Node, NodeEnum};


/*
Create a new inflow node and check out it's ID (GUID)
 */
#[test]
fn test_create_storage_node() {
    let i = InflowNode::new();
    let name = i.get_name();
    println!("Name = {}", i.get_name());
    let mut m = Model::new();
    //m.node_network.add_node(Box::new(i));
    m.add_node(NodeEnum::InflowNode(i));
    
    //And here is how you can get an immutable reference to a node again.
    let n = m.get_node(&name).unwrap();
    println!("Name = {}", n.get_name());
}

