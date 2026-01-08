// About the ordering system
// =========================================
// I have decided to keep the ordering logic here in one place even though some of it depends
// on the node type. I am using a visitor pattern (matching on node type) which is more okay in
// rust than in other languages because the compiler requires match blocks are exhaustive.
//
// The ordering system thus runs before the flow-phase

use crate::data_management::data_cache::DataCache;
use crate::nodes::{Link, Node, NodeEnum};
use crate::numerical::fifo_buffer::FifoBuffer;

#[derive(Clone, Default)]
pub struct SimpleOrderingSystem {
    link_ordering_info: Vec<LinkOrderingInfoItem>,
    regulated_branch_counter: usize,
    model_has_ordering: bool,
}

impl SimpleOrderingSystem {
    pub fn new() -> SimpleOrderingSystem {
        SimpleOrderingSystem {
            link_ordering_info: Vec::new(),
            regulated_branch_counter: 0,
            model_has_ordering: false,
        }
    }

    pub fn initialize(&mut self,
                      nodes: &mut Vec<NodeEnum>,
                      links: &Vec<Link>,
                      incoming_links: &Vec<Vec<usize>>) -> () {

        // Start clean
        self.link_ordering_info.clear();
        self.regulated_branch_counter = 0;

        // For each link, I want to know the following info: what regulated_branch it is in,
        // what the lag is to that point, what are the upstream and downstream nodes (and the
        // number of this link on that node).
        let mut link_idx = 0;
        for link in links {

            // Create a new LinkBranchInfoItem with some defaults:
            let mut new_item = LinkOrderingInfoItem {
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
                    new_item.branch_idx = Some(self.regulated_branch_counter);
                    self.regulated_branch_counter += 1;
                }
                _ => {
                    // Branch info to be based on upstream link.
                    // Note: If the upstream node has multiple incoming links. We use
                    //       look at the one with the longest lag.
                    for ref_us_link_idx in &incoming_links[new_item.from_node] {

                        let us_link_idx = *ref_us_link_idx;
                        let us_option_branch_idx = self.link_ordering_info[us_link_idx].branch_idx;
                        let us_lag = self.link_ordering_info[us_link_idx].lag;

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

            // If we are on a regulated reach, some nodes will need to be informed of their
            // travel time. (Opportunity to do initialisation of nodes as needed now that we know
            // their travel time). I think it is okay to do this within the current loop by visiting
            // the node below the current link (if I used the node upstream of the link I would miss
            // out on visiting the node below the last link, but the node upstream of the first link
            // is a storage with travel time zero so I don't think there's anything to init).
            if new_item.branch_idx.is_some() {
                match &mut nodes[new_item.to_node] {
                    NodeEnum::UserNode(user_node) => {
                        if user_node.is_regulated {
                            let int_lag = new_item.lag.round() as usize;
                            if int_lag > user_node.order_travel_time {
                                // Increase the size of the order buffer. This will mean that in the
                                // unusual case of a user having >1 regulated inlets, the user node
                                // travel time will be based on the longest lag.
                                user_node.order_travel_time = int_lag;
                                user_node.order_buffer = FifoBuffer::new(int_lag);
                            }
                        }
                    },
                    NodeEnum::InflowNode(inflow_node) => {
                        // Inflow nodes on regulated branches must be evaluated in the ordering
                        // phase. Setting this flag lets them know how they are going to work.
                        inflow_node.is_regulated = true;
                        inflow_node.order_travel_time = new_item.lag.round() as usize;
                        inflow_node.order_travel_time_gt_0 = inflow_node.order_travel_time > 0;
                    },
                    _ => {}
                }
            }

             // !!DEBUG CODE
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
            self.link_ordering_info.push(new_item);

            // Next link
            link_idx += 1;
        }

        // Do we ever need to run the ordering phase?
        self.model_has_ordering = self.regulated_branch_counter > 0;
    }

    /// This function is to be run each day, before the flow phase, and it's job is to resolve
    /// orders and set today's intended operations (property values) in the nodes. The nodes can
    /// then follow these intended operations during the flow phase without further intervention
    /// from the "simple_ordering" struct during the flow phase.
    pub fn run_ordering_phase(&mut self, nodes: &mut Vec<NodeEnum>, data_cache: &DataCache) -> () {

        // Guard to save computation time if there is no ordering!
        if !self.model_has_ordering {
            return;
        }

        // Iterate over all the link_branch_info items. Each time, calculate the orders from the
        // downstream node, and inform them to the upstream node. The details depend on the node
        // type.
        for li in self.link_ordering_info.iter().rev() {

            // Variable for calculating order on this link
            let mut order = 0f64;

            // Skip links that aren't in regulated branches
            if li.branch_idx.is_none() {
                continue;
            }

            // Determine the order on this link (depends on the downstream node)
            match &mut nodes[li.to_node] {
                NodeEnum::StorageNode(_) => {
                    // Order from storages is zero
                    // The value is already zero so I dont need to do anything.
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
                    // Orders are reduced based on known inflows
                    let inflow_value = inflow_node.inflow_input.get_value(data_cache);
                    inflow_node.order_phase_inflow_value = inflow_value;
                    let anticipated_inflow_on_delivery_timestep = inflow_value *
                        if inflow_node.order_travel_time_gt_0 { inflow_node.recession_factor } else { 1.0 };
                    order = (inflow_node.dsorders[0] - anticipated_inflow_on_delivery_timestep).max(0f64);
                },
                NodeEnum::ConfluenceNode(confluence_node) => {
                    // We must decide how to apportion the orders between upstream links (of which this
                    // is just one).
                    // TODO: Do ordering through confluences. For now I'm sending all orders all ways!
                    order = confluence_node.dsorders[0];
                },
                NodeEnum::SplitterNode(splitter_node) => {
                    // Splitter may have multiple downstream links. All orders propagate here.
                    order = splitter_node.dsorders.iter().sum();
                },
                NodeEnum::UserNode(user_node) => {
                    if user_node.is_regulated {
                        user_node.order_phase_demand_value = user_node.demand_input.get_value(data_cache);
                        order = user_node.dsorders[0] + user_node.order_phase_demand_value;
                    } else {
                        // Pass order straight through - same as most benign node types
                        order = user_node.dsorders[0];
                    }
                },
                other => {
                    // For all other nodes, we follow a greedy philosophy, propagating all orders
                    // up all pathways. This may allow interesting flow-phase solutions whereby
                    // we can make late decisions about which branch delivers the orders.
                    //
                    // NodeEnum::BlackholeNode(_) => {}
                    // NodeEnum::GaugeNode(_) => {}
                    // NodeEnum::Gr4jNode(_) => {}
                    // NodeEnum::RoutingNode(_) => {}
                    // NodeEnum::SacramentoNode(_) => {}
                    //
                    // Assume these node types only have 1 downstream link at most.
                    order = other.dsorders_mut()[0];
                },
            }

            // Propagate the order to the downstream node inlet, and upstream node outlet
            nodes[li.to_node].usorders_mut()[li.to_inlet as usize] = order; //TODO: I dont know if I need this. Maybe if I want to record it at confluences?
            nodes[li.from_node].dsorders_mut()[li.from_outlet as usize] = order;
        }
    }
}

#[derive(Clone, Default, Debug)]
pub struct LinkOrderingInfoItem {
    link_idx: usize,
    branch_idx: Option<usize>,
    lag: f64,
    from_node: usize,
    from_outlet: u8,
    to_node: usize,
    to_inlet: u8,
}
