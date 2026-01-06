
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

// About the ordering system
// =========================================
// I have decided to keep the ordering logic here in one place even though some of it depends
// on the node type. I am using a visitor pattern (matching on node type) which is more okay in
// rust than in other languages because the compiler requires match blocks are exhaustive.
//
// The ordering system thus runs before the flow-phase

use crate::data_management::data_cache::DataCache;
use crate::misc::misc_functions::require_non_empty;
use crate::nodes::{Link, Node, NodeEnum};

#[derive(Clone, Default)]
pub struct SimpleOrderingSystem {
    // "link_branch_info" contains, for each link, the regulated branch number (option), and
    // the lag from top of branch to current link. These are in the same order as "links".
    //link_branch_info: Vec<(Option<usize>, f64)>,
    link_branch_info: Vec<LinkBranchInfoItem>,
    branch_counter: usize,
}

impl SimpleOrderingSystem {
    pub fn new() -> SimpleOrderingSystem {
        SimpleOrderingSystem {
            link_branch_info: Vec::new(),
            branch_counter: 0,
        }
    }

    pub fn initialize(&mut self,
                      nodes: &Vec<NodeEnum>,
                      links: &Vec<Link>,
                      incoming_links: &Vec<Vec<usize>>) -> () {

        // Start clean
        self.link_branch_info.clear();
        self.branch_counter = 0;

        // For each link, I want to know the following info: what regulated_branch it is in,
        // what the lag is to that point, what are the upstream and downstream nodes (and the
        // number of this link on that node).
        let mut link_idx = 0;
        for link in links {

            // Create a new LinkBranchInfoItem with some defaults:
            let mut new_item = LinkBranchInfoItem {
                link_idx: link_idx,
                branch_idx: None,
                lag: 0f64,
                from_node: link.from_node,
                from_outlet: link.from_outlet,
                to_node: link.to_node,
                to_inlet: link.to_inlet,
            };

            // Determine if this is a new branch, or if we should copy info from the upstream link
            match &nodes[new_item.from_node] {
                NodeEnum::StorageNode(storage) => {
                    // This is a new branch
                    new_item.branch_idx = Some(self.branch_counter);
                    self.branch_counter += 1;
                }
                _ => {
                    // Branch info to be based on upstream link.
                    // Note: If the upstream node has multiple incoming links. We use
                    //       look at the one with the longest lag.
                    for ref_us_link_idx in &incoming_links[new_item.from_node] {

                        let us_link_idx = *ref_us_link_idx;
                        let us_option_branch_idx = self.link_branch_info[us_link_idx].branch_idx;
                        let us_lag = self.link_branch_info[us_link_idx].lag;

                        // Only need to look at regulated branches
                        if us_option_branch_idx.is_some() && us_lag >= new_item.lag {
                            new_item.lag = us_lag;
                            new_item.branch_idx = us_option_branch_idx;
                        }
                    }
                }
            }

            // Increase the lag to account for routing in the upstream node if applicable
            match &nodes[new_item.from_node] {
                NodeEnum::RoutingNode(routing_node) => {
                    // Find the latency of the routing node, and add it to the previous lag
                    let index_flow_rate = 100.0; //TODO: this should probably just be a property of the node
                    let node_lag = routing_node.estimate_total_lag(index_flow_rate);
                    new_item.lag += node_lag;
                }
                _ => {
                    // Do nothing
                }
            }

            //  !!DEBUG CODE
            // {
            //     let branch_idx = new_item.branch_idx.unwrap_or(9999);
            //     let from_node_name= nodes[new_item.from_node].get_name();
            //     let to_node_name = nodes[new_item.to_node].get_name();
            //     println!("{}, {}, {}, {}, {}, {}, {}",
            //              new_item.link_idx,
            //              branch_idx,
            //              new_item.lag,
            //              from_node_name,
            //              new_item.from_outlet,
            //              to_node_name,
            //              new_item.to_inlet);
            // }

            // Add the branch info for this link and move to the next link
            self.link_branch_info.push(new_item);

            // Next link
            link_idx += 1;
        }
    }

    /// This function is to be run each day, before the flow phase, and it's job is to resolve
    /// orders and set today's intended operations (property values) in the nodes. The nodes can
    /// then follow these intended operations during the flow phase without further intervention
    /// from the "simple_ordering" struct during the flow phase.
    pub fn run_ordering_phase(&mut self, nodes: &mut Vec<NodeEnum>, data_cache: &DataCache) -> () {

        // Iterate over all the link_branch_info items. Each time, calculate the orders from the
        // downstream node, and inform them to the upstream node. The details depend on the node
        // type.
        for li in self.link_branch_info.iter().rev() {

            // Variable for calculating order on this link
            let mut order = 0f64;

            // Skip links that aren't in regulated branches
            if li.branch_idx.is_none() {
                continue;
            }

            // Determine the order on this link (depends on the downstream node)
            match &nodes[li.to_node] {
                NodeEnum::StorageNode(storage_node) => {
                    // Order from storages is zero
                    // order = 0f64;
                    // TODO: allow storages to place orders
                    //   (1) to propagate orders through the storage if they opt out of delivering orders
                    //   (2) to meet target operating levels
                },
                NodeEnum::LossNode(loss_node) => {
                    // Assume all orders on outlet 0 are propagated here.
                    order = loss_node.flow_table.interpolate(0, 1, loss_node.dsorders[0]);
                },
                NodeEnum::InflowNode(inflow_node) => {
                    // TODO: Allow orders to be discounted based on expected inflows
                    order = nodes[li.to_node].dsorders_mut()[0];
                },
                NodeEnum::ConfluenceNode(confluence_node) => {
                    // We must decide how to apportion the orders between upstream links (of which this
                    // is just one).
                    // TODO: Do ordering through confluences. For now I'm sending all orders all ways!
                    order = nodes[li.to_node].dsorders_mut()[0];
                },
                NodeEnum::SplitterNode(splitter_node) => {
                    // Splitter may have multiple downstream links. All orders propagate here.
                    order = nodes[li.to_node].dsorders_mut().iter().sum();
                },
                NodeEnum::UserNode(user_node) => {
                    let user_node_is_regulated = true; //TODO: make this a node property
                    if user_node_is_regulated {
                        let demand = user_node.demand_input.get_value(data_cache); //TODO: this does not account for travel time yet.
                        order = nodes[li.to_node].dsorders_mut()[0] + demand;
                    } else {
                        order = nodes[li.to_node].dsorders_mut()[0];
                    }
                },
                _ => {
                    // NodeEnum::BlackholeNode(_) => {}
                    // NodeEnum::GaugeNode(_) => {}
                    // NodeEnum::Gr4jNode(_) => {}
                    // NodeEnum::RoutingNode(_) => {}
                    // NodeEnum::SacramentoNode(_) => {}
                    // Assume all orders on outlet 0 are propagated here.
                    order = nodes[li.to_node].dsorders_mut()[0];
                },
            }

            // Propagate the order to the downstream node inlet, and upstream node outlet
            nodes[li.to_node].usorders_mut()[li.to_inlet as usize] = order;
            nodes[li.from_node].dsorders_mut()[li.from_outlet as usize] = order;
        }
    }
}

#[derive(Clone, Default, Debug)]
pub struct LinkBranchInfoItem {
    link_idx: usize,
    branch_idx: Option<usize>,
    lag: f64,
    from_node: usize,
    from_outlet: u8,
    to_node: usize,
    to_inlet: u8,
}
