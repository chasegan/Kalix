
//TODO:
// 1) During the model's initialization phase, we need to scan through all
//    the nodes and make a list of nodes that need to be visited by the ordering
//    system. Not all nodes need to know about orders. Those that do are:
//        a) Only nodes that are downstream of storages, that are
//        a) Users
//        b) Confluences <--- all nodes can be confluences! So how are those handled?
//        c) Splitters
//        d) Storages
//        e) Order control nodes (min and max) TBD
//        f) Nodes where orders are being recorded
// 2) This list should include references to the nodes (by IDX) and instructions
//    about how to process the orders w.r.t that node. Maybe each record is something
//    like this:
//        - The current node IDX
//        - The node type (?)
//        - The upstream node IDX (?)
// 3) We want to iterate through this list and do all the ordering calcs, leaving the
//    order values in the nodes (where needed) so that they can be handled in the
//    flow phase.
// 4) T
//

use std::fmt::format;
use crate::nodes::{Link, Node, NodeEnum};

#[derive(Clone, Default)]
pub struct SimpleOrderingSystem {
    // "link_branch_info" contains, for each link, the regulated branch number (option), and
    // the lag from top of branch to current link. These are in the same order as "links".
    link_branch_info: Vec<(Option<usize>, f64)>,
}

impl SimpleOrderingSystem {
    pub fn new() -> SimpleOrderingSystem {
        SimpleOrderingSystem {
            link_branch_info: Vec::new(),
        }
    }

    pub fn initialize(&mut self,
                      nodes: &Vec<NodeEnum>,
                      links: &Vec<Link>,
                      incoming_links: &Vec<Vec<usize>>) -> () {

        // Start clean
        self.link_branch_info.clear();
        let mut branch_counter = 0;

        // For each link, I want to know what regulated_branch it is in, and what the lag is to that point.
        //let mut link_idx = 0;
        for link in links {
            let us_node_idx = link.from_node;
            let ds_node_idx = link.to_node;

            let mut option_branch_idx = None;
            let mut lag = 0f64;
            match &nodes[us_node_idx] {
                NodeEnum::StorageNode(storage) => {
                    // This is a new branch
                    option_branch_idx = Some(branch_counter);
                    branch_counter += 1;
                }
                _ => {
                    // Branch info to be based on upstream link.
                    // Note: If the upstream node has multiple incoming links. We use
                    //       look at the one with the longest lag.
                    for ref_us_link_idx in &incoming_links[us_node_idx] {

                        let us_link_idx = *ref_us_link_idx;
                        let us_option_branch_idx = self.link_branch_info[us_link_idx].0;
                        let us_lag = self.link_branch_info[us_link_idx].1;

                        // Only need to look at regulated branches
                        if us_option_branch_idx.is_some() && us_lag >= lag {
                            lag = us_lag;
                            option_branch_idx = us_option_branch_idx;
                        }
                    }
                }
            }

            // Increase the lag if the upstream node is routing
            match &nodes[us_node_idx] {
                NodeEnum::RoutingNode(routing_node) => {
                    // Find the latency of the routing node
                    let index_flow_rate = 100.0; //TODO: this should probably just be a property of the node
                    let node_lag = routing_node.estimate_total_lag(index_flow_rate);

                    // Add that to the lag which was previously
                    lag += node_lag;
                }
                _ => {
                    // Do nothing
                }
            }

            //Add the branch info for this link and move to the next link
            self.link_branch_info.push((option_branch_idx, lag));

            //  !!DEBUG CODE
            // {
            //     let us_node_name= nodes[us_node_idx].get_name();
            //     let ds_node_name = nodes[ds_node_idx].get_name();
            //     let link_idx = option_branch_idx.unwrap_or(9999);
            //     println!("{}, {}, {}", us_node_name, ds_node_name, lag);
            // }
        }
    }

    //pub fn
}
