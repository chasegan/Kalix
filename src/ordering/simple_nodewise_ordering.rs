// About the ordering system
// =========================================
// Orders travel upstream, from the nodes that want water to the storages that supply it. A
// supply storage starts a regulated zone, which runs downstream through every node below it.
// Every order in a zone is timed from its supply: an order placed this step reaches the supply
// within this step's order phase, the supply releases this step, and a node whose travel time
// from the supply is T steps acts on the order T steps later, by way of a delay buffer.
//
// initialize() works that out once, in four steps:
//   1. resolve the `regulated =` pathways that confluences name;
//   2. the topology - which links are regulated, and the travel time down each
//      (regulated_topology, which changes nothing);
//   3. size every node's delay buffers from its travel time (size_order_buffers);
//   4. list the nodes the order phase visits (build_visit_list).
// What the ordering system needs to know about each node type is stated in this file, in
// matches that name every type: what does an outlet do to the zone, does the node add routing lag,
// can it originate an order, does it name its order pathways, which delay buffers does it
// keep, and what order does it send upstream. None of those has a wildcard arm, so a new node
// type does not compile until each question has been answered for it. (The only single-type
// patterns left pick out confluences, to resolve their own `regulated =` property.)
//
// run_ordering_phase() runs every step. It visits nodes in reverse definition order (highest
// index to lowest). Since check_execution_order() enforces from_node < to_node for all links,
// that visits downstream nodes before upstream ones.
// - Only nodes that need it are visited (decided in initialize)
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

    model_has_ordering: bool,
}

impl SimpleNodewiseOrderingSystem {
    pub fn new() -> SimpleNodewiseOrderingSystem {
        SimpleNodewiseOrderingSystem {
            links_simple_ordering: Vec::new(),
            flat_incoming_links: Vec::new(),
            regulated_nodes: Vec::new(),
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

        // Step 1: resolve confluence `regulated =` declarations. Each named
        // node must be an upstream neighbour; the first name becomes us_1
        // (so a two-name harmony_fraction is direction-unambiguous), a
        // single name routes every order up that branch.
        self.resolve_confluence_regulated_pathways(nodes, links, incoming_links)?;

        // Step 2: the topology. Which links are regulated, and the travel time down each
        // from its supply. Reads the nodes and changes nothing.
        self.links_simple_ordering = regulated_topology(nodes, links, incoming_links)?;

        // Step 3: size every node's order buffers from its travel time.
        self.size_order_buffers(nodes)?;

        // Step 4: the list of nodes the order phase visits, with their incoming regulated links.
        self.build_visit_list(nodes);

        // Do we ever need to run the ordering phase?
        self.model_has_ordering = self.links_simple_ordering.iter().any(|li| li.regulated);

        Ok(())
    }

    /// Step 3 of initialize. A node in a regulated zone delays what it does with an order by
    /// its travel time from the supply. That is the travel time of its longest regulated
    /// incoming link (ordering.md, "Travel Times"), worked out once here, so that every node
    /// type is timed by one rule and none depends on the order its links are defined in. It
    /// is rounded to whole steps once, from the accumulated travel time. A node with no
    /// regulated incoming link gets zero-length buffers, which pass orders straight through.
    ///
    /// The match names every node type, with no wildcard: a new node type does not compile
    /// until it says here whether it keeps a delay buffer.
    fn size_order_buffers(&self, nodes: &mut Vec<NodeEnum>) -> Result<(), String> {
        // The travel time to each node, in whole steps: that of its longest regulated
        // incoming link, and zero if it has none. (Rounding keeps order, so the longest
        // rounded travel time is the rounded longest travel time.)
        let mut travel_times: Vec<usize> = vec![0; nodes.len()];
        for li in &self.links_simple_ordering {
            if li.regulated {
                let t = li.travel_time.round() as usize;
                travel_times[li.to_node] = travel_times[li.to_node].max(t);
            }
        }

        for node_idx in 0..nodes.len() {
            let travel_time = travel_times[node_idx];

            match &mut nodes[node_idx] {
                NodeEnum::StorageNode(node) => {
                    if node.order_through {
                        // Delay releases, so that they are made as the ordered water arrives
                        node.ds_order_buffers = std::array::from_fn(|_| FifoBuffer::new(travel_time));
                        node.target_level_order_buffer = FifoBuffer::new(0);
                    } else {
                        // A supply releases immediately: zero-length ds_x_order buffers
                        node.ds_order_buffers = std::array::from_fn(|_| FifoBuffer::new(0));
                        // The buffer that remembers upstream orders placed to meet a target
                        // level, which are en route for the travel time
                        node.target_level_order_buffer = if node.has_target_level {
                            FifoBuffer::new(travel_time)
                        } else {
                            FifoBuffer::new(0)
                        };
                    }
                }
                NodeEnum::RegulatedUserNode(node) => {
                    node.order_travel_time = travel_time;
                    node.order_buffer = FifoBuffer::new(travel_time);
                }
                NodeEnum::FieldNode(node) => {
                    node.order_travel_time = travel_time;
                    node.order_buffer = FifoBuffer::new(travel_time);
                }
                NodeEnum::OrderControlNode(node) => {
                    node.sent_order_buffer = FifoBuffer::new(travel_time);
                }
                NodeEnum::SplitterNode(node) => {
                    // A splitter diverts effluent (ds_2) orders on the step the ordered water
                    // arrives at the splitter, so it holds them for its own travel time.
                    node.ds_2_order_buffer = FifoBuffer::new(travel_time);
                }
                NodeEnum::ConfluenceNode(node) => {
                    // The one node that keeps a travel time per branch, not the longest: its
                    // regulated incoming links in definition order, as (link index, travel
                    // time in whole steps)
                    let regulated_inlets: Vec<(usize, usize)> = self.links_simple_ordering.iter()
                        .filter(|li| li.to_node == node_idx && li.regulated)
                        .map(|li| (li.link_idx, li.travel_time.round() as usize))
                        .collect();
                    size_confluence_order_buffers(node, &regulated_inlets)?;
                }
                // These keep no delay buffer: they act on an order, or pass it on, within the
                // order phase of the step it is placed.
                NodeEnum::BlackholeNode(_) |
                NodeEnum::GaugeNode(_) |
                NodeEnum::LossNode(_) |
                NodeEnum::UnregulatedUserNode(_) |
                NodeEnum::Gr4jNode(_) |
                NodeEnum::InflowNode(_) |
                NodeEnum::RoutingNode(_) |
                NodeEnum::SacramentoNode(_) |
                NodeEnum::AwbmNode(_) |
                NodeEnum::SurmNode(_) => {}
            }
        }
        Ok(())
    }

    /// Step 4 of initialize. The order phase visits only the nodes that need it, in reverse
    /// definition order, each with its incoming regulated links in a flat CSR-style layout.
    fn build_visit_list(&mut self, nodes: &Vec<NodeEnum>) {
        // A node needs visiting if it, or a node below it on regulated links, can originate
        // an order. Nodes below the last such node on any branch only ever see zero dsorders,
        // so visiting them is wasted work.
        let mut needed: Vec<bool> = nodes.iter().map(can_originate_orders).collect();
        // Propagate backward through regulated links: if to_node is needed, from_node is too.
        // Reverse iteration ensures transitivity (links are ordered with from_node < to_node).
        for li in self.links_simple_ordering.iter().rev() {
            if li.regulated && needed[li.to_node] {
                needed[li.from_node] = true;
            }
        }

        let mut per_node_links: Vec<Vec<IncomingRegulatedLink>> = vec![Vec::new(); nodes.len()];
        for li in &self.links_simple_ordering {
            if li.regulated && needed[li.to_node] {
                per_node_links[li.to_node].push(IncomingRegulatedLink {
                    link_idx: li.link_idx,
                    from_node: li.from_node,
                    from_outlet: li.from_outlet,
                });
            }
        }

        // A supply at the top of the network starts a regulated zone but has no incoming
        // regulated link. It has no order to send upstream, but its order phase still has to
        // run, so that its ds_orders_due buffers are updated.
        let mut supplies: Vec<bool> = vec![false; nodes.len()];
        for li in &self.links_simple_ordering {
            if li.regulated && zone_role(&nodes[li.from_node], li.from_outlet) == ZoneRole::Starts {
                supplies[li.from_node] = true;
            }
        }

        // Visit in reverse definition order, every node alike: that is the order the file
        // promises (downstream before upstream), and it is what decides whether an expression
        // may read another node's order-phase result in the same step.
        self.flat_incoming_links.clear();
        self.regulated_nodes.clear();
        for node_idx in (0..nodes.len()).rev() {
            if per_node_links[node_idx].is_empty() && !supplies[node_idx] {
                continue;
            }
            let start = self.flat_incoming_links.len();
            self.flat_incoming_links.extend(per_node_links[node_idx].drain(..));
            let end = self.flat_incoming_links.len(); // an empty range for a supply with no incoming regulated link
            self.regulated_nodes.push(RegulatedNodeEntry {
                node_idx,
                links_start: start,
                links_end: end,
            });
        }
    }

    /// Step 1 of initialize: resolve each confluence's `regulated =` names
    /// to its incoming links, pinning us_1 (and us_2, when two are named) so
    /// the later steps time the right branches. Structural
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
        let mut no_pathway: Vec<usize> = Vec::new();
        for (node_idx, node) in nodes.iter().enumerate() {
            let NodeEnum::ConfluenceNode(confluence) = node else { continue };
            if confluence.regulated_upstream.is_empty() {
                // No names. With a harmony_fraction this is the legacy link-order mode
                // (the default, set in the node's initialise). With neither, nothing
                // says where orders go, so none are sent.
                if matches!(confluence.harmony_fraction, crate::model_inputs::DynamicInput::None { .. }) {
                    no_pathway.push(node_idx);
                }
                continue;
            }

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

        for node_idx in no_pathway {
            let NodeEnum::ConfluenceNode(confluence) = &mut nodes[node_idx] else { unreachable!() };
            confluence.order_split = crate::nodes::confluence_node::OrderSplit::NoPathway;
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

            // Every link points down the file (check_execution_order), so the nodes this one
            // sends orders to all sit before it. Splitting the slice there lets this node be
            // borrowed while its order is written into them, however many incoming links it has.
            let (upstream, rest) = nodes.split_at_mut(node_idx);

            // Run the node's order phase and get the order it sends upstream. Every node type
            // but the confluence sends the same order up each of its incoming regulated links.
            let order: f64 = match &mut rest[0] {
                NodeEnum::StorageNode(node) => {
                    // Pre-order phase
                    node.run_order_phase(data_cache, account_manager);
                    node.us_orders
                },
                NodeEnum::LossNode(node) => {
                    // Pre-order phase
                    node.run_order_phase(data_cache, account_manager);
                    node.usorders
                },
                NodeEnum::InflowNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    node.usorders
                },
                NodeEnum::ConfluenceNode(node) => {
                    node.run_order_phase(data_cache, account_manager);

                    // Evaluate the split once and compute both upstream orders
                    // simultaneously. A single named `regulated` pathway is a
                    // fixed 1.0 to us_1 — no fraction exists where there is
                    // nothing to split. With no pathway stated at all (neither
                    // `regulated` nor `harmony_fraction`), no order goes upstream.
                    let (link_1_harmony, order_to_split) = match node.order_split {
                        crate::nodes::confluence_node::OrderSplit::AllToUs1 => (1.0, node.total_outgoing_order),
                        crate::nodes::confluence_node::OrderSplit::Harmony =>
                            (node.harmony_fraction.get_value(data_cache).clamp(0.0, 1.0), node.total_outgoing_order),
                        crate::nodes::confluence_node::OrderSplit::NoPathway => (0.0, 0.0),
                    };
                    node.harmony_fraction_value = link_1_harmony;
                    node.record_harmony_fraction(data_cache);
                    let link_1_order = link_1_harmony * order_to_split;
                    let link_2_order = (1.0 - link_1_harmony) * order_to_split;

                    // Propagate orders upstream: a different order up each branch
                    for il in incoming {
                        let link_order = if node.us_1_link_idx == Some(il.link_idx) {
                            node.us_1_order_buffer.push(link_1_order)
                        } else {
                            node.us_2_order_buffer.push(link_2_order)
                        };
                        upstream[il.from_node].dsorders_mut()[il.from_outlet as usize] = link_order;
                    }
                    continue;
                },
                NodeEnum::OrderControlNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    node.usorders
                }
                NodeEnum::SplitterNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    node.usorders
                }
                NodeEnum::RegulatedUserNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    node.dsorders[0] + node.order_factor * node.order_value
                }
                NodeEnum::FieldNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    // Only its own order goes upstream: ds_1 is a drain, not a delivery path
                    node.order_value
                }
                NodeEnum::BlackholeNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    0.0
                }
                NodeEnum::GaugeNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    node.dsorders[0]
                }
                NodeEnum::UnregulatedUserNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    node.dsorders[0]
                }
                NodeEnum::Gr4jNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    node.dsorders[0]
                }
                NodeEnum::AwbmNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    node.dsorders[0]
                }
                NodeEnum::SurmNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    node.dsorders[0]
                }
                NodeEnum::RoutingNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    node.dsorders[0]
                }
                NodeEnum::SacramentoNode(node) => {
                    node.run_order_phase(data_cache, account_manager);
                    node.dsorders[0]
                }
            };

            // Propagate the order to upstream nodes
            for il in incoming {
                upstream[il.from_node].dsorders_mut()[il.from_outlet as usize] = order;
            }
        }
    }
}

/// What the ordering system knows about one link.
#[derive(Clone, Default, Debug)]
struct LinkInfo {
    link_idx: usize,
    /// In a regulated zone: orders travel up this link
    regulated: bool,
    /// The estimated time, in steps, for water to travel from the zone's supply to the bottom
    /// of this link. Accumulated as a real number down the network; whoever uses it rounds.
    travel_time: f64,
    from_node: usize,
    from_outlet: u8,
    to_node: usize,
}

/// What a link leaving one of a node's outlets does to the regulated zone the node is in.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
enum ZoneRole {
    /// The link is the top of a zone. Orders stop at this node, which supplies the reach
    /// below, and travel time is counted from here.
    Starts,
    /// The link is regulated if a regulated link comes into the node, and carries on its
    /// travel time. This is what almost every outlet does.
    Continues,
    /// The link is not regulated, whatever comes into the node. No order travels up it, and
    /// it carries no travel time on to the reach below.
    Ends,
}

/// What does a link leaving this outlet do to the regulated zone? A storage that does not
/// order through supplies the reach below it. A field's outlets carry surplus and returns
/// back to the river: they are drains, not delivery paths, so no order travels up them, and
/// the travel time to the field is no part of the travel time to anything below it.
/// Every node type is named, with no wildcard, so a new one does not compile until it answers.
fn zone_role(node: &NodeEnum, _outlet: u8) -> ZoneRole {
    match node {
        NodeEnum::StorageNode(n) => if n.order_through { ZoneRole::Continues } else { ZoneRole::Starts },
        NodeEnum::FieldNode(_) => ZoneRole::Ends,
        NodeEnum::BlackholeNode(_) |
        NodeEnum::ConfluenceNode(_) |
        NodeEnum::GaugeNode(_) |
        NodeEnum::LossNode(_) |
        NodeEnum::SplitterNode(_) |
        NodeEnum::UnregulatedUserNode(_) |
        NodeEnum::RegulatedUserNode(_) |
        NodeEnum::Gr4jNode(_) |
        NodeEnum::InflowNode(_) |
        NodeEnum::RoutingNode(_) |
        NodeEnum::SacramentoNode(_) |
        NodeEnum::OrderControlNode(_) |
        NodeEnum::AwbmNode(_) |
        NodeEnum::SurmNode(_) => ZoneRole::Continues,
    }
}

/// The time, in steps, that water takes to pass through this node: the routing node's
/// estimate of its own lag at its typical regulated flow, and nothing for every other type.
fn routing_lag(node: &NodeEnum) -> f64 {
    match node {
        NodeEnum::RoutingNode(n) => n.estimate_total_lag(n.typical_regulated_flow),
        NodeEnum::BlackholeNode(_) |
        NodeEnum::ConfluenceNode(_) |
        NodeEnum::GaugeNode(_) |
        NodeEnum::LossNode(_) |
        NodeEnum::SplitterNode(_) |
        NodeEnum::UnregulatedUserNode(_) |
        NodeEnum::RegulatedUserNode(_) |
        NodeEnum::FieldNode(_) |
        NodeEnum::Gr4jNode(_) |
        NodeEnum::InflowNode(_) |
        NodeEnum::SacramentoNode(_) |
        NodeEnum::StorageNode(_) |
        NodeEnum::OrderControlNode(_) |
        NodeEnum::AwbmNode(_) |
        NodeEnum::SurmNode(_) => 0.0,
    }
}

/// Can this node originate an order, as opposed to passing on or adjusting one that reaches
/// it from below? A storage can (to meet a target level), and so can a regulated user, a field
/// and an order control (a minimum order). These seed the list of nodes the order phase visits.
fn can_originate_orders(node: &NodeEnum) -> bool {
    match node {
        NodeEnum::StorageNode(_) |
        NodeEnum::RegulatedUserNode(_) |
        NodeEnum::FieldNode(_) |
        NodeEnum::OrderControlNode(_) => true,
        NodeEnum::BlackholeNode(_) |
        NodeEnum::ConfluenceNode(_) |
        NodeEnum::GaugeNode(_) |
        NodeEnum::LossNode(_) |
        NodeEnum::SplitterNode(_) |
        NodeEnum::UnregulatedUserNode(_) |
        NodeEnum::Gr4jNode(_) |
        NodeEnum::InflowNode(_) |
        NodeEnum::RoutingNode(_) |
        NodeEnum::SacramentoNode(_) |
        NodeEnum::AwbmNode(_) |
        NodeEnum::SurmNode(_) => false,
    }
}

/// The incoming links that this node names as its order pathways, if it names any. Only a
/// confluence can (`regulated =`, pinned to links in step 1); every other node type sends its
/// order up all of its regulated incoming links, and names none.
fn named_order_pathways(node: &NodeEnum) -> [Option<usize>; 2] {
    match node {
        NodeEnum::ConfluenceNode(n) => {
            if n.regulated_upstream.is_empty() { [None, None] } else { [n.us_1_link_idx, n.us_2_link_idx] }
        }
        NodeEnum::BlackholeNode(_) |
        NodeEnum::GaugeNode(_) |
        NodeEnum::LossNode(_) |
        NodeEnum::SplitterNode(_) |
        NodeEnum::UnregulatedUserNode(_) |
        NodeEnum::RegulatedUserNode(_) |
        NodeEnum::FieldNode(_) |
        NodeEnum::Gr4jNode(_) |
        NodeEnum::InflowNode(_) |
        NodeEnum::RoutingNode(_) |
        NodeEnum::SacramentoNode(_) |
        NodeEnum::StorageNode(_) |
        NodeEnum::OrderControlNode(_) |
        NodeEnum::AwbmNode(_) |
        NodeEnum::SurmNode(_) => [None, None],
    }
}

/// Step 2 of initialize: which links are regulated, and the travel time down each. Pure: it
/// reads the nodes and links and returns one LinkInfo per link, in link order.
///
/// A link is regulated if it leaves an outlet that starts a regulated zone, or if it leaves a
/// node that a regulated link comes into. Its travel time is that of the longest regulated
/// link into its upstream node, plus the routing lag of that node.
fn regulated_topology(nodes: &Vec<NodeEnum>,
                      links: &Vec<Link>,
                      incoming_links: &Vec<Vec<usize>>) -> Result<Vec<LinkInfo>, String> {
    let mut link_infos: Vec<LinkInfo> = Vec::with_capacity(links.len());
    for idx in 0..links.len() {
        let from_node = &nodes[links[idx].from_node];
        let mut link_info = LinkInfo {
            link_idx: idx,
            regulated: false,
            travel_time: 0.0,
            from_node: links[idx].from_node,
            from_outlet: links[idx].from_outlet,
            to_node: links[idx].to_node,
        };

        match zone_role(from_node, link_info.from_outlet) {
            // The top of a zone: travel time is counted from here
            ZoneRole::Starts => link_info.regulated = true,
            // Not regulated, whatever comes into the upstream node
            ZoneRole::Ends => {}
            ZoneRole::Continues => {
                // Continue the zone of the links coming into the upstream node, taking the
                // longest travel time among them.
                //
                // A confluence that names its `regulated` pathway(s) sends orders up those
                // branches alone, so they alone set the travel time below it: water ordered
                // down a 1-step branch arrives after 1 step however long the other branch is.
                // (Step 1 has pinned the named links.) If no named branch is regulated there
                // is no order pathway to time, and the longest regulated branch stands.
                let named_pathways = named_order_pathways(from_node);
                // Every link into a node is scanned before any link out of it, because links are
                // created in definition order and every link points down the file
                // (check_execution_order). Say so, rather than index out of bounds, if that changes.
                if let Some(&unscanned) = incoming_links[link_info.from_node].iter().find(|&&l| l >= idx) {
                    return Err(format!(
                        "Ordering system: link {} into node '{}' comes after link {} out of it. Links must be in upstream-first order.",
                        unscanned, from_node.get_name(), idx));
                }
                let a_named_pathway_is_regulated = named_pathways.iter().flatten()
                    .any(|&l| link_infos[l].regulated);
                for &us_link_idx in &incoming_links[link_info.from_node] {
                    if a_named_pathway_is_regulated && !named_pathways.contains(&Some(us_link_idx)) {
                        continue;
                    }
                    let us_link = &link_infos[us_link_idx];
                    if us_link.regulated && us_link.travel_time >= link_info.travel_time {
                        link_info.travel_time = us_link.travel_time;
                        link_info.regulated = true;
                    }
                }
            }
        }

        // Water takes time to pass through the upstream node, if it routes
        link_info.travel_time += routing_lag(from_node);

        link_infos.push(link_info);
    }
    Ok(link_infos)
}

/// Size a confluence's two order buffers. A confluence directs orders up two branches, and
/// where their travel times differ it holds back the orders for the shorter branch by the
/// difference, so that water from both arrives together. `regulated_inlets` is the
/// confluence's regulated incoming links in definition order, as (link index, travel time in
/// whole steps).
fn size_confluence_order_buffers(node: &mut crate::nodes::confluence_node::ConfluenceNode,
                                 regulated_inlets: &[(usize, usize)]) -> Result<(), String> {
    // A third regulated branch would share us_2's delay buffer and push it twice a step
    if regulated_inlets.len() > 2 {
        return Err(format!(
            "Confluence '{}' has {} regulated upstream links. A confluence directs orders up two branches at most: join the others at another node upstream of it.",
            node.name, regulated_inlets.len()));
    }

    if !node.regulated_upstream.is_empty() {
        // Named pathways (pinned in step 1): record each one's travel time against its slot.
        // A named branch that is not regulated keeps a travel time of zero.
        for &(link_idx, travel_time) in regulated_inlets {
            if node.us_1_link_idx == Some(link_idx) {
                node.us_1_lag = travel_time;
            } else if node.us_2_link_idx == Some(link_idx) {
                node.us_2_lag = travel_time;
            }
        }
        // With one name there is nothing to synchronise, so the buffers stay zero-length
        // (orders propagate immediately). With two, delay the shorter branch.
        if node.us_2_link_idx.is_none() {
            return Ok(());
        }
    } else {
        // Legacy link-order mode: the first regulated link defined is us_1, the second us_2.
        // With one regulated branch there is nothing to synchronise.
        let Some(&(us_1_link_idx, us_1_travel_time)) = regulated_inlets.first() else { return Ok(()) };
        node.us_1_link_idx = Some(us_1_link_idx);
        node.us_1_lag = us_1_travel_time;
        let Some(&(_, us_2_travel_time)) = regulated_inlets.get(1) else { return Ok(()) };
        node.us_2_lag = us_2_travel_time;
    }

    if node.us_1_lag < node.us_2_lag {
        node.us_1_order_buffer = FifoBuffer::new(node.us_2_lag - node.us_1_lag);
        node.us_2_order_buffer = FifoBuffer::new(0);
    } else {
        node.us_2_order_buffer = FifoBuffer::new(node.us_1_lag - node.us_2_lag);
        node.us_1_order_buffer = FifoBuffer::new(0);
    }
    Ok(())
}
