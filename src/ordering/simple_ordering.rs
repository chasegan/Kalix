// About the ordering system
// =========================================
// I have decided to keep the ordering logic here in one place even though some of it depends
// on the node type. I am using a visitor pattern (matching on node type) which is more okay in
// rust than in other languages because the compiler requires match blocks are exhaustive.
//
// The ordering system thus runs before the flow-phase

use crate::data_management::data_cache::DataCache;
use crate::misc::simulation_context::set_context_node;
use crate::nodes::{Link, Node, NodeEnum};
use crate::numerical::fifo_buffer::FifoBuffer;

#[derive(Clone, Default)]
pub struct SimpleOrderingSystem {
    links_simple_ordering: Vec<LinkInfo>,
    regulated_zone_counter: usize,
    model_has_ordering: bool,
}

impl SimpleOrderingSystem {
    pub fn new() -> SimpleOrderingSystem {
        SimpleOrderingSystem {
            links_simple_ordering: Vec::new(),
            regulated_zone_counter: 0,
            model_has_ordering: false,
        }
    }

    pub fn initialize(&mut self,
                      nodes: &mut Vec<NodeEnum>,
                      links: &Vec<Link>,
                      incoming_links: &Vec<Vec<usize>>) -> () {

        // Start clean
        self.links_simple_ordering.clear();
        self.regulated_zone_counter = 0;

        // This loop does two things:
        // (1) Create the self.links_simple_ordering vector, defining the regulated zones.
        // (2) Initialize the nodes with info they may require to function within regulated zones.
        for idx in 0..links.len() {

            // Create a new link info item (this will go into self.links_simple_ordering)
            let mut new_link_item = LinkInfo {
                link_idx: idx,
                from_node: links[idx].from_node,
                from_outlet: links[idx].from_outlet,
                to_node: links[idx].to_node,
                to_inlet: links[idx].to_inlet,
                zone_idx: None, // To be populated later
                lag: 0f64,      // To be populated later
            };

            // Determine if this is a new zone, or continuation of upstream zone
            match &nodes[new_link_item.from_node] {
                NodeEnum::StorageNode(_) => {
                    // This is a new zone.
                    new_link_item.zone_idx = Some(self.regulated_zone_counter);
                    self.regulated_zone_counter += 1;
                }
                _ => {
                    // Zone info based on upstream link.
                    // If the upstream node has multiple incoming links, we look at the one with the longest lag.
                    for &us_link_idx in &incoming_links[new_link_item.from_node] {
                        let us_zone_idx = self.links_simple_ordering[us_link_idx].zone_idx;

                        // Only look at upstream links that are in regulated zones
                        if us_zone_idx.is_some() {
                            // Upstream is a regulated zone
                            let us_link_lag = self.links_simple_ordering[us_link_idx].lag;
                            if us_link_lag >= new_link_item.lag {
                                new_link_item.lag = us_link_lag;
                                new_link_item.zone_idx = us_zone_idx;
                            }
                        } else {
                            // Upstream is not a regulated zone
                            // Ignore this pathway
                        }
                    }
                }
            }

            // Increase the lag to account for routing in the upstream node if applicable
            match &nodes[new_link_item.from_node] {
                NodeEnum::RoutingNode(routing_node) => {
                    // Find the latency of the routing node, and add it to the previous lag
                    let node_lag = routing_node.estimate_total_lag(routing_node.typical_regulated_flow);
                    new_link_item.lag += node_lag;
                }
                _ => {
                    // Do nothing
                }
            }

            // Initialize node ordering aspects
            // --------------------------------
            // If we are on a regulated reach, some nodes will need to be informed of their
            // travel time. (Opportunity to do initialisation of nodes as needed now that we know
            // their travel time). I think it is okay to do this within the current loop by visiting
            // the node below the current link (if I used the node upstream of the link I would miss
            // out on visiting the node below the last link, but the node upstream of the first link
            // is a storage with travel time zero, so I don't think there's anything to init).
            if new_link_item.zone_idx.is_some() {
                match &mut nodes[new_link_item.to_node] {
                    NodeEnum::RegulatedUserNode(user_node) => {
                        let int_lag = new_link_item.lag.round() as usize;
                        if int_lag > user_node.order_travel_time {
                            // Increase the size of the order buffer. This will mean that in the
                            // unusual case of a user having >1 regulated inlets, the user node
                            // travel time will be based on the longest lag.
                            user_node.order_travel_time = int_lag;
                            user_node.order_buffer = FifoBuffer::new(int_lag);
                        }
                    },
                    NodeEnum::InflowNode(inflow_node) => {
                        // Inflow nodes on regulated branches must be evaluated in the ordering
                        // phase. Setting this flag lets them know how they are going to work.
                        inflow_node.is_regulated = true;
                        inflow_node.order_travel_time = new_link_item.lag.round() as usize;
                        inflow_node.order_travel_time_gt_0 = inflow_node.order_travel_time > 0;
                    },
                    NodeEnum::OrderConstraintNode(n) => {
                        // Order constraint node uses a buffer to remember recent orders so it can
                        // tell the modeller what is expected today
                        let int_lag = new_link_item.lag.round() as usize;
                        n.sent_order_buffer = FifoBuffer::new(int_lag);
                    },
                    NodeEnum::ConfluenceNode(n) => {
                        // Confluence nodes have buffers to delay orders intended for the short
                        // upstream link.
                        let int_lag = new_link_item.lag.round() as usize;
                        if n.us_1_link_idx.is_none() {
                            // We are defining link 1.
                            n.us_1_lag = int_lag;
                            n.us_1_link_idx = Some(new_link_item.link_idx);
                        } else {
                            // Assume we are now defining link 2.
                            // TODO: guarantee no more than 2 upstream links
                            n.us_2_lag = int_lag;
                            if n.us_1_lag < n.us_2_lag {
                                let lag_differential = n.us_2_lag - n.us_1_lag;
                                n.us_1_order_buffer = FifoBuffer::new(lag_differential); //short path
                                n.us_2_order_buffer = FifoBuffer::new(0); //long path
                            } else {
                                let lag_differential = n.us_1_lag - n.us_2_lag;
                                n.us_2_order_buffer = FifoBuffer::new(lag_differential); //short path
                                n.us_1_order_buffer = FifoBuffer::new(0); //long path (or equal)
                            }
                        }
                    },
                    _ => {}
                }
            }

            // Add the new_link_item to the vec
            self.links_simple_ordering.push(new_link_item);
        }

        // Do we ever need to run the ordering phase?
        self.model_has_ordering = self.regulated_zone_counter > 0;
    }

    /// This function is to be run each day, before the flow phase, and it's job is to resolve
    /// orders and set today's intended operations (property values) in the nodes. The nodes can
    /// then follow these intended operations during the flow phase without further intervention
    /// from the "simple_ordering" struct during the flow phase.
    pub fn run_ordering_phase(&mut self, nodes: &mut Vec<NodeEnum>, data_cache: &DataCache) {

        // Guard to save computation time if there is no ordering!
        if !self.model_has_ordering {
            return;
        }

        // Iterate over all the link_branch_info items. Each time, calculate the orders from the
        // downstream node, and inform them to the upstream node. The details depend on the node
        // type.
        for li in self.links_simple_ordering.iter().rev() {

            // Variable for calculating order on this link
            let mut order = 0.0;

            // Skip links that aren't in regulated branches
            if li.zone_idx.is_none() {
                continue;
            }

            // Set node context for error reporting (just stores the index)
            set_context_node(li.to_node);

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
                NodeEnum::LossNode(node_below_link) => {
                    // Assume all orders on outlet 0 are propagated here.
                    order = node_below_link.order_tranlation_table.interpolate(0, 1, node_below_link.dsorders[0]);
                }
                NodeEnum::InflowNode(node_below_link) => {
                    // Orders are reduced based on known inflows
                    let inflow_value = node_below_link.inflow_input.get_value(data_cache);
                    node_below_link.order_phase_inflow_value = inflow_value;
                    let anticipated_inflow_on_delivery_timestep = inflow_value *
                        if node_below_link.order_travel_time_gt_0 { node_below_link.recession_factor } else { 1.0 };
                    order = (node_below_link.dsorders[0] - anticipated_inflow_on_delivery_timestep).max(0f64);
                }
                NodeEnum::ConfluenceNode(node_below_link) => {
                    // We must decide how to apportion the orders between upstream links (of which
                    // this is just one).
                    if node_below_link.us_1_link_idx == Some(li.link_idx) {
                        // We are on link 1
                        if let Some(value) = node_below_link.order_prepared_for_us_1_buffer {
                            // We already have a value available
                            order = node_below_link.us_1_order_buffer.push(value);
                            node_below_link.order_prepared_for_us_1_buffer = None;
                        } else {
                            // Evaluate the harmony function
                            let link_1_harmony = node_below_link.harmony_fraction.get_value(data_cache)
                                .clamp(0.0, 1.0);
                            node_below_link.harmony_fraction_value = link_1_harmony;
                            // Calculate orders for buffers
                            let link_1_order = link_1_harmony * node_below_link.dsorders[0];
                            let link_2_order = (1.0 - link_1_harmony) * node_below_link.dsorders[0];
                            // Now use the link 1 value and save the link 2 value for later
                            order = node_below_link.us_1_order_buffer.push(link_1_order);
                            node_below_link.order_prepared_for_us_1_buffer = None;
                            node_below_link.order_prepared_for_us_2_buffer = Some(link_2_order);
                        }
                    } else {
                        // Assume we are on link 2
                        // But this assumes that we have already visited link1 this timestep and that's the problem.
                        // order = node_below_link.us_2_order_buffer.push(node_below_link.remaining_order);
                        // println!("Visiting link2 dsorder={}, sent={}, remaining={}", node_below_link.dsorders[0], order, node_below_link.remaining_order);
                        if let Some(value) = node_below_link.order_prepared_for_us_2_buffer {
                            // We already have a value available
                            order = node_below_link.us_2_order_buffer.push(value);
                            node_below_link.order_prepared_for_us_2_buffer = None;
                        } else {
                            // Evaluate the harmony function
                            let link_1_harmony = node_below_link.harmony_fraction.get_value(data_cache)
                                .clamp(0.0, 1.0);
                            node_below_link.harmony_fraction_value = link_1_harmony;
                            // Calculate orders for buffers
                            let link_1_order = link_1_harmony * node_below_link.dsorders[0];
                            let link_2_order = (1.0 - link_1_harmony) * node_below_link.dsorders[0];
                            // Now use the link 2 value and save the link 1 value for later
                            order = node_below_link.us_2_order_buffer.push(link_2_order);
                            node_below_link.order_prepared_for_us_2_buffer = None;
                            node_below_link.order_prepared_for_us_1_buffer = Some(link_1_order);
                        }
                    }
                }
                NodeEnum::OrderConstraintNode(n) => {
                    order = n.delay_order_buffer.push(n.dsorders[0]);
                    if n.set_order_defined {
                        // Set order
                        n.set_order_value = n.set_order_input.get_value(data_cache);
                        order = n.set_order_value;
                    } else {
                        if n.min_order_defined {
                            // Min order
                            n.min_order_value = n.min_order_input.get_value(data_cache);
                            order = order.max(n.min_order_value);
                        }
                        if n.max_order_defined {
                            // Max order
                            n.max_order_value = n.max_order_input.get_value(data_cache);
                            order = order.min(n.max_order_value);
                        }
                    }
                    n.sent_order_value = order;
                },
                NodeEnum::SplitterNode(splitter_node) => {
                    // Splitter may have multiple downstream links. All orders propagate here.
                    order = splitter_node.dsorders.iter().sum();
                },
                NodeEnum::RegulatedUserNode(n) => {
                    n.order_value = n.order_input.get_value(data_cache);
                    order = n.dsorders[0] + n.order_value;
                },
                other => {
                    // For all other nodes, we follow a greedy philosophy, propagating all orders
                    // up all pathways.
                    //
                    // Assume these node types only have 1 downstream link at most.
                    //
                    // NodeEnum::BlackholeNode(_) => {}
                    // NodeEnum::GaugeNode(_) => {}
                    // NodeEnum::Gr4jNode(_) => {}
                    // NodeEnum::RoutingNode(_) => {}
                    // NodeEnum::SacramentoNode(_) => {}
                    // NodeEnum::UnregulatedUserNode(_) => {}
                    order = other.dsorders_mut()[0];
                },
            }

            // Propagate the order to the upstream node outlet
            nodes[li.from_node].dsorders_mut()[li.from_outlet as usize] = order;
        }
    }
}

#[derive(Clone, Default, Debug)]
pub struct LinkInfo {
    link_idx: usize,
    zone_idx: Option<usize>,
    lag: f64,
    from_node: usize,
    from_outlet: u8,
    to_node: usize,
    to_inlet: u8,
}
