// About the ordering system
// =========================================
// This system iterates over nodes in reverse definition order (highest index to lowest). Since
// check_execution_order() enforces from_node < to_node for all links, reverse node order guarantees
// that downstream nodes are always processed before upstream nodes.
//
// The initialize() method - zone propagation and lag computation depend on forward link iteration.
//
// The run_ordering_phase() - iterates nodes.
// - Only regulated nodes are visited (pre-filtered during initialize)
// - Incoming regulated links are stored in a flat CSR-style layout for cache locality

use crate::data_management::data_cache::DataCache;
use crate::misc::simulation_context::set_context_node;
use crate::nodes::{Link, Node, NodeEnum};
use crate::numerical::fifo_buffer::FifoBuffer;

/// Pre-computed information about an incoming regulated link to a node.
#[derive(Clone, Default, Debug)]
struct IncomingRegulatedLink {
    link_idx: usize,
    from_node: usize,
    from_outlet: u8,
}

/// Entry in the regulated node list, pointing into the flat incoming-links vec.
#[derive(Clone, Debug)]
struct RegulatedNodeEntry {
    node_idx: usize,
    links_start: usize,  // start index into flat_incoming_links
    links_end: usize,    // end index (exclusive) into flat_incoming_links
}

#[derive(Clone, Default)]
pub struct SimpleNodewiseOrderingSystem {
    links_simple_ordering: Vec<LinkInfo>,

    /// Flat contiguous storage for all incoming regulated links, grouped by node.
    flat_incoming_links: Vec<IncomingRegulatedLink>,

    /// One entry per regulated node (in reverse definition order), pointing into flat_incoming_links.
    regulated_nodes: Vec<RegulatedNodeEntry>,

    regulated_zone_counter: usize,
    model_has_ordering: bool,
}

impl SimpleNodewiseOrderingSystem {
    pub fn new() -> SimpleNodewiseOrderingSystem {
        SimpleNodewiseOrderingSystem {
            links_simple_ordering: Vec::new(),
            flat_incoming_links: Vec::new(),
            regulated_nodes: Vec::new(),
            regulated_zone_counter: 0,
            model_has_ordering: false,
        }
    }

    pub fn initialize(&mut self,
                      nodes: &mut Vec<NodeEnum>,
                      links: &Vec<Link>,
                      incoming_links: &Vec<Vec<usize>>) -> Result<(), String> {
        // 'nodes' is a borrowed vector of all nodes (as NodeEnums) in definition order
        // 'links' is a borrowed vector of all links, where a link has from_node, from_outlet,
        //         to_node, to_inlet.
        // 'incoming_links' is a derived adjacency list where
        //         incoming_links[node_idx] = vec of indices for link coming into node idx. This
        //         is handy for navigating up the network.

        // Start clean
        self.links_simple_ordering.clear();
        self.regulated_zone_counter = 0;

        // Phase 0: resolve confluence `regulated =` declarations. Each named
        // node must be an upstream neighbour; the first name becomes us_1
        // (so a two-name harmony_fraction is direction-unambiguous), a
        // single name routes every order up that branch.
        self.resolve_confluence_regulated_pathways(nodes, links, incoming_links)?;

        // Phase 1: Build the links_simple_ordering vector and initialize nodes.
        // This is identical to SimpleOrderingSystem::initialize().
        for idx in 0..links.len() {

            // Create a new link info item
            let mut new_link_item = LinkInfo {
                link_idx: idx,
                from_node: links[idx].from_node,
                from_outlet: links[idx].from_outlet,
                to_node: links[idx].to_node,
                to_inlet: links[idx].to_inlet,
                zone_idx: None,
                lag: 0f64,
            };

            // Determine if this is a new zone, or continuation of upstream zone.
            // Basically if it is a storage without 'order through', then it is a new zone.
            let is_new_zone = match &nodes[new_link_item.from_node] {
                NodeEnum::StorageNode(n) => { !n.order_through }
                _ => { false }
            };
            if is_new_zone {
                // This is a new zone.
                new_link_item.zone_idx = Some(self.regulated_zone_counter);
                self.regulated_zone_counter += 1;
            } else {
                // Zone info based on upstream link.
                // If the upstream node has multiple incoming links, we look at the one with the longest lag.
                for &us_link_idx in &incoming_links[new_link_item.from_node] {
                    let us_zone_idx = self.links_simple_ordering[us_link_idx].zone_idx;

                    // Only look at upstream links that are in regulated zones
                    if us_zone_idx.is_some() {
                        let us_link_lag = self.links_simple_ordering[us_link_idx].lag;
                        if us_link_lag >= new_link_item.lag {
                            new_link_item.lag = us_link_lag;
                            new_link_item.zone_idx = us_zone_idx;
                        }
                    }
                }
            }

            // Increase the lag to account for routing in the upstream node if applicable
            match &nodes[new_link_item.from_node] {
                NodeEnum::RoutingNode(routing_node) => {
                    let node_lag = routing_node.estimate_total_lag(routing_node.typical_regulated_flow);
                    new_link_item.lag += node_lag;
                }
                _ => {}
            }

            // Initialize node ordering aspects
            if new_link_item.zone_idx.is_some() {
                match &mut nodes[new_link_item.to_node] {
                    NodeEnum::StorageNode(node) => {
                        let int_lag = new_link_item.lag.round() as usize;
                        if node.order_through {
                            // Set order buffers to delay releases.
                            node.ds_order_buffers = std::array::from_fn(|_| FifoBuffer::new(int_lag));
                            // Probably not necessary:
                            node.target_level_order_buffer = FifoBuffer::new(0);
                        } else {
                            // If order_through == false, then we are supplying immediately. Do
                            // not delay releases; set ds_x_order buffers to zero length.
                            node.ds_order_buffers = std::array::from_fn(|_| FifoBuffer::new(0));
                            // Initialize the buffer that remembers upstream orders associated
                            // with ordering to meet target level.
                            
                            if node.has_target_level {
                                node.target_level_order_buffer = FifoBuffer::new(int_lag);
                            } else {
                                // Probably not necessary:
                                node.target_level_order_buffer = FifoBuffer::new(0);
                            }
                        }
                    },
                    NodeEnum::RegulatedUserNode(node) => {
                        let int_lag = new_link_item.lag.round() as usize;
                        if int_lag > node.order_travel_time {
                            // TODO: why do I have the above clause? I cant remember? If you remember, make a note.
                            //  It might be to do with making sure I pick up the longest travel time in which
                            //  case we probably need to use the same approach for all other node types too.
                            node.order_travel_time = int_lag;
                            node.order_buffer = FifoBuffer::new(int_lag);
                        }
                    }
                    NodeEnum::OrderControlNode(node) => {
                        let int_lag = new_link_item.lag.round() as usize;
                        node.sent_order_buffer = FifoBuffer::new(int_lag);
                    }
                    NodeEnum::ConfluenceNode(node) => {
                        let int_lag = new_link_item.lag.round() as usize;
                        if !node.regulated_upstream.is_empty() {
                            // Named pathways (resolved in Phase 0): record the
                            // lag against its pinned slot. With one name there
                            // is nothing to synchronise, so buffers stay
                            // zero-length (orders propagate immediately); with
                            // two, rebuild the lag-differential buffers as each
                            // lag lands (idempotent — the last rebuild, with
                            // both lags known, stands).
                            if node.us_1_link_idx == Some(new_link_item.link_idx) {
                                node.us_1_lag = int_lag;
                            } else if node.us_2_link_idx == Some(new_link_item.link_idx) {
                                node.us_2_lag = int_lag;
                            }
                            if node.us_2_link_idx.is_some() {
                                if node.us_1_lag < node.us_2_lag {
                                    node.us_1_order_buffer = FifoBuffer::new(node.us_2_lag - node.us_1_lag);
                                    node.us_2_order_buffer = FifoBuffer::new(0);
                                } else {
                                    node.us_2_order_buffer = FifoBuffer::new(node.us_1_lag - node.us_2_lag);
                                    node.us_1_order_buffer = FifoBuffer::new(0);
                                }
                            }
                        } else if node.us_1_link_idx.is_none() {
                            node.us_1_lag = int_lag;
                            node.us_1_link_idx = Some(new_link_item.link_idx);
                        } else {
                            node.us_2_lag = int_lag;
                            if node.us_1_lag < node.us_2_lag {
                                let lag_differential = node.us_2_lag - node.us_1_lag;
                                node.us_1_order_buffer = FifoBuffer::new(lag_differential);
                                node.us_2_order_buffer = FifoBuffer::new(0);
                            } else {
                                let lag_differential = node.us_1_lag - node.us_2_lag;
                                node.us_2_order_buffer = FifoBuffer::new(lag_differential);
                                node.us_1_order_buffer = FifoBuffer::new(0);
                            }
                        }
                    }
                    _ => {}
                }
            }

            // Add the new_link_item to the vec
            self.links_simple_ordering.push(new_link_item);
        }

        // Phase 2: Determine which regulated nodes actually need to be visited.
        // A node only needs ordering if it (or a downstream node reachable through
        // regulated links) is an order-generating type: storage, regulated_user, or
        // order_control. Nodes below the last order-generating node on any branch
        // will only ever see zero dsorders, so visiting them is wasted work.
        let mut needed = vec![false; nodes.len()];
        for (i, node) in nodes.iter().enumerate() {
            match node {
                NodeEnum::StorageNode(_) |
                NodeEnum::RegulatedUserNode(_) |
                NodeEnum::OrderControlNode(_) => needed[i] = true,
                _ => {}
            }
        }
        // Propagate backward through regulated links: if to_node is needed, from_node is too.
        // Reverse iteration ensures transitivity (links are ordered with from_node < to_node).
        for li in self.links_simple_ordering.iter().rev() {
            if li.zone_idx.is_some() && needed[li.to_node] {
                needed[li.from_node] = true;
            }
        }

        // Phase 3: Build CSR-style regulated node list and flat incoming links vec.
        // Only include nodes that are both regulated and needed.
        let mut per_node_links: Vec<Vec<IncomingRegulatedLink>> = vec![Vec::new(); nodes.len()];
        for li in &self.links_simple_ordering {
            if li.zone_idx.is_some() && needed[li.to_node] {
                per_node_links[li.to_node].push(IncomingRegulatedLink {
                    link_idx: li.link_idx,
                    from_node: li.from_node,
                    from_outlet: li.from_outlet,
                });
            }
        }

        self.flat_incoming_links.clear();
        self.regulated_nodes.clear();
        for node_idx in (0..nodes.len()).rev() {
            if per_node_links[node_idx].is_empty() {
                continue;
            }
            let start = self.flat_incoming_links.len();
            self.flat_incoming_links.extend(per_node_links[node_idx].drain(..));
            let end = self.flat_incoming_links.len();
            self.regulated_nodes.push(RegulatedNodeEntry {
                node_idx,
                links_start: start,
                links_end: end,
            });
        }

        // Phase 4: Include supply storages that define regulated zones but have no incoming
        // regulated links (i.e. they are at the top of the network). These nodes still need
        // run_order_phase() called so that their ds_orders_due buffers are updated, even though
        // they have no upstream orders to propagate.
        for li in &self.links_simple_ordering {
            if li.zone_idx.is_some() {
                if let NodeEnum::StorageNode(_) = &nodes[li.from_node] {
                    if !self.regulated_nodes.iter().any(|e| e.node_idx == li.from_node) {
                        let start = self.flat_incoming_links.len();
                        self.regulated_nodes.push(RegulatedNodeEntry {
                            node_idx: li.from_node,
                            links_start: start,
                            links_end: start, // empty range: no incoming regulated links
                        });
                    }
                }
            }
        }

        // Do we ever need to run the ordering phase?
        self.model_has_ordering = self.regulated_zone_counter > 0;

        Ok(())
    }

    /// Phase 0 of initialize: resolve each confluence's `regulated =` names
    /// to its incoming links, pinning us_1 (and us_2, when two are named) so
    /// the Phase 1 link scan records lags against the right slots. Structural
    /// validation lives here — every name must be an upstream neighbour of
    /// its confluence — because this is the first point where the links are
    /// known. Whether a named branch is actually regulated is deliberately
    /// NOT validated: defensively naming the pathway at a confluence no
    /// order ever crosses is good practice, not an error.
    fn resolve_confluence_regulated_pathways(&self,
                                             nodes: &mut Vec<NodeEnum>,
                                             links: &Vec<Link>,
                                             incoming_links: &Vec<Vec<usize>>) -> Result<(), String> {
        // Collect (confluence_idx, us_1 link, optional us_2 link) immutably
        // first; apply mutably after.
        let mut resolved: Vec<(usize, usize, Option<usize>)> = Vec::new();
        for (node_idx, node) in nodes.iter().enumerate() {
            let NodeEnum::ConfluenceNode(confluence) = node else { continue };
            if confluence.regulated_upstream.is_empty() { continue; }

            let mut resolved_links: Vec<usize> = Vec::with_capacity(2);
            for name in &confluence.regulated_upstream {
                let named_idx = nodes.iter().position(|n| n.get_name().eq_ignore_ascii_case(name))
                    .ok_or_else(|| format!(
                        "Confluence '{}': 'regulated' names unknown node '{}'",
                        confluence.name, name))?;
                let link_idx = incoming_links[node_idx].iter().copied()
                    .find(|&l| links[l].from_node == named_idx)
                    .ok_or_else(|| format!(
                        "Confluence '{}': 'regulated' names '{}', which is not one of its upstream nodes",
                        confluence.name, name))?;
                resolved_links.push(link_idx);
            }
            resolved.push((node_idx, resolved_links[0], resolved_links.get(1).copied()));
        }

        for (node_idx, us_1_link, us_2_link) in resolved {
            let NodeEnum::ConfluenceNode(confluence) = &mut nodes[node_idx] else { unreachable!() };
            confluence.us_1_link_idx = Some(us_1_link);
            confluence.us_2_link_idx = us_2_link;
            confluence.order_split = if us_2_link.is_some() {
                crate::nodes::confluence_node::OrderSplit::Harmony
            } else {
                crate::nodes::confluence_node::OrderSplit::AllToUs1
            };
        }
        Ok(())
    }

    /// This function is to be run each day, before the flow phase, and it's job is to resolve
    /// orders and set today's intended operations (property values) in the nodes. The nodes can
    /// then follow these intended operations during the flow phase without further intervention
    /// from this struct during the flow phase.
    ///
    /// Unlike simple_ordering.rs which iterates links in reverse, this method iterates only
    /// regulated nodes in reverse definition order, with incoming links stored in a flat
    /// contiguous vec for cache locality.
    pub fn run_ordering_phase(&mut self, nodes: &mut Vec<NodeEnum>, data_cache: &mut DataCache, account_manager: &mut crate::hydrology::accounts::account_manager::AccountManager) {

        // Guard to save computation time if there is no ordering!
        if !self.model_has_ordering {
            return;
        }

        // Iterate only regulated nodes (already in reverse definition order)
        for entry in &self.regulated_nodes {
            let node_idx = entry.node_idx;
            let incoming = &self.flat_incoming_links[entry.links_start..entry.links_end];

            // Set node context for error reporting
            set_context_node(node_idx);

            // Compute order(s) for upstream links. We store them in a small buffer and propagate
            // after the match block releases the mutable borrow on nodes[node_idx].
            // Max incoming regulated links is 2 (confluence), so a fixed array suffices.
            let mut upstream_orders: [(usize, u8, f64); 2] = [(0, 0, 0.0); 2];
            let mut n_orders: usize = 0;

            match &mut nodes[node_idx] {
                NodeEnum::StorageNode(node) => {
                    // Pre-order phase
                    node.run_order_phase(data_cache, account_manager);
                    // Propagate orders upstream
                    for il in incoming {
                        upstream_orders[n_orders] = (il.from_node, il.from_outlet, node.us_orders);
                        n_orders += 1;
                    }
                },
                NodeEnum::LossNode(node) => {
                    // Pre-order phase
                    node.run_order_phase(data_cache, account_manager);
                    // Propagate orders upstream
                    for il in incoming {
                        upstream_orders[n_orders] = (il.from_node, il.from_outlet, node.usorders);
                        n_orders += 1;
                    }
                },
                NodeEnum::InflowNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    // Propagate orders upstream
                    for il in incoming {
                        upstream_orders[n_orders] = (il.from_node, il.from_outlet, node.usorders);
                        n_orders += 1;
                    }
                },
                NodeEnum::ConfluenceNode(node) => {
                    node.run_order_phase(data_cache, account_manager);

                    // Evaluate the split once and compute both upstream orders
                    // simultaneously. A single named `regulated` pathway is a
                    // fixed 1.0 to us_1 — no fraction exists where there is
                    // nothing to split.
                    let link_1_harmony = match node.order_split {
                        crate::nodes::confluence_node::OrderSplit::AllToUs1 => 1.0,
                        crate::nodes::confluence_node::OrderSplit::Harmony =>
                            node.harmony_fraction.get_value(data_cache).clamp(0.0, 1.0),
                    };
                    node.harmony_fraction_value = link_1_harmony;
                    let link_1_order = link_1_harmony * node.dsorders[0];
                    let link_2_order = (1.0 - link_1_harmony) * node.dsorders[0];

                    // Propagate orders upstream
                    for il in incoming {
                        if node.us_1_link_idx == Some(il.link_idx) {
                            upstream_orders[n_orders] = (il.from_node, il.from_outlet,
                                node.us_1_order_buffer.push(link_1_order));
                        } else {
                            upstream_orders[n_orders] = (il.from_node, il.from_outlet,
                                node.us_2_order_buffer.push(link_2_order));
                        }
                        n_orders += 1;
                    }
                },
                NodeEnum::OrderControlNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    // Propagate orders upstream
                    for il in incoming {
                        upstream_orders[n_orders] = (il.from_node, il.from_outlet, node.usorders);
                        n_orders += 1;
                    }
                }
                NodeEnum::SplitterNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    // Propagate orders upstream
                    for il in incoming {
                        upstream_orders[n_orders] = (il.from_node, il.from_outlet, node.dsorders.iter().sum());
                        n_orders += 1;
                    }
                }
                NodeEnum::RegulatedUserNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    // Propagate orders upstream
                    for il in incoming {
                        upstream_orders[n_orders] = (il.from_node, il.from_outlet, node.dsorders[0] + node.order_value);
                        n_orders += 1;
                    }
                }
                NodeEnum::BlackholeNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    // Propagate orders upstream. zero.
                    for il in incoming {
                        upstream_orders[n_orders] = (il.from_node, il.from_outlet, 0.0);
                        n_orders += 1;
                    }
                }
                NodeEnum::GaugeNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    // Propagate orders upstream.
                    for il in incoming {
                        upstream_orders[n_orders] = (il.from_node, il.from_outlet, node.dsorders[0]);
                        n_orders += 1;
                    }
                }
                NodeEnum::UnregulatedUserNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    // Propagate orders upstream.
                    for il in incoming {
                        upstream_orders[n_orders] = (il.from_node, il.from_outlet, node.dsorders[0]);
                        n_orders += 1;
                    }
                }
                NodeEnum::Gr4jNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    // Propagate orders upstream.
                    for il in incoming {
                        upstream_orders[n_orders] = (il.from_node, il.from_outlet, node.dsorders[0]);
                        n_orders += 1;
                    }
                }
                NodeEnum::RoutingNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    // Propagate orders upstream.
                    for il in incoming {
                        upstream_orders[n_orders] = (il.from_node, il.from_outlet, node.dsorders[0]);
                        n_orders += 1;
                    }
                }
                NodeEnum::SacramentoNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    // Propagate orders upstream.
                    for il in incoming {
                        upstream_orders[n_orders] = (il.from_node, il.from_outlet, node.dsorders[0]);
                        n_orders += 1;
                    }
                }
            }

            // Propagate computed orders to upstream nodes
            for i in 0..n_orders {
                let (from_node, from_outlet, order) = upstream_orders[i];
                nodes[from_node].dsorders_mut()[from_outlet as usize] = order;
            }
        }
    }
}

#[derive(Clone, Default, Debug)]
struct LinkInfo {
    link_idx: usize,
    zone_idx: Option<usize>,
    lag: f64,
    from_node: usize,
    from_outlet: u8,
    to_node: usize,
    to_inlet: u8,
}
