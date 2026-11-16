use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::numerical::opt::optimisable_component::OptimisableComponent;
use crate::nodes::{Node, blackhole_node::BlackholeNode, confluence_node::ConfluenceNode, gauge_node::GaugeNode, loss_node::LossNode, splitter_node::SplitterNode, unregulated_user_node::UnregulatedUserNode, regulated_user_node::RegulatedUserNode, field_node::FieldNode, gr4j_node::Gr4jNode, inflow_node::InflowNode, routing_node::RoutingNode, sacramento_node::SacramentoNode, storage_node::StorageNode, order_control_node::OrderControlNode, awbm_node::AwbmNode, surm_node::SurmNode, gr4jsg_node::Gr4jsgNode};

#[derive(Clone)]
pub enum NodeEnum {
    BlackholeNode(BlackholeNode),
    ConfluenceNode(ConfluenceNode),
    GaugeNode(GaugeNode),
    LossNode(LossNode),
    SplitterNode(SplitterNode),
    UnregulatedUserNode(UnregulatedUserNode),
    RegulatedUserNode(RegulatedUserNode),
    FieldNode(FieldNode),
    Gr4jNode(Gr4jNode),
    InflowNode(InflowNode),
    RoutingNode(RoutingNode::<false>),
    RoutingNodeReachLosses(RoutingNode::<true>),
    SacramentoNode(SacramentoNode),
    StorageNode(StorageNode),
    OrderControlNode(OrderControlNode),
    AwbmNode(AwbmNode),
    SurmNode(SurmNode),
    Gr4jsgNode(Gr4jsgNode),
}

/// Dispatch a method call to whichever node variant this is. Expands to the
/// exact per-node-arm match that was previously hand-written per method (same
/// static dispatch, same codegen); adding a node type now means adding one
/// enum variant and one line here instead of editing seven matches.
macro_rules! dispatch {
    ($self:expr, $node:ident => $call:expr) => {
        match $self {
            NodeEnum::BlackholeNode($node) => $call,
            NodeEnum::ConfluenceNode($node) => $call,
            NodeEnum::GaugeNode($node) => $call,
            NodeEnum::LossNode($node) => $call,
            NodeEnum::SplitterNode($node) => $call,
            NodeEnum::UnregulatedUserNode($node) => $call,
            NodeEnum::RegulatedUserNode($node) => $call,
            NodeEnum::FieldNode($node) => $call,
            NodeEnum::Gr4jNode($node) => $call,
            NodeEnum::InflowNode($node) => $call,
            NodeEnum::RoutingNode($node) => $call,
            NodeEnum::RoutingNodeReachLosses($node) => $call,
            NodeEnum::SacramentoNode($node) => $call,
            NodeEnum::StorageNode($node) => $call,
            NodeEnum::OrderControlNode($node) => $call,
            NodeEnum::AwbmNode($node) => $call,
            NodeEnum::SurmNode($node) => $call,
            NodeEnum::Gr4jsgNode($node) => $call,
        }
    };
}

/// The most outlets any node type has (ds_1 to ds_4).
pub const MAX_OUTLETS: u8 = 4;

impl NodeEnum {
    /// Whether this node can put water at an outlet that has no link. The
    /// model asks at setup, for each outlet without a link: water left there
    /// leaves the model, and is counted against the node's mass balance. The
    /// answer can differ between a node's outlets, so the question takes the
    /// outlet (0 is ds_1). Every variant answers, with no wildcard arm
    /// (ADR-0008 §2 and §3).
    pub fn can_strand_water(&self, outlet: u8) -> bool {
        match self {
            // Nothing leaves a blackhole
            NodeEnum::BlackholeNode(_) => false,
            NodeEnum::SplitterNode(_) => outlet < 2,
            // ds_2 carries the return flow, and only when there is a share to return
            NodeEnum::FieldNode(n) => outlet == 0 || (outlet == 1 && n.return_fraction > 0.0),
            // ds_1 spills. ds_2 to ds_4 release to an order, which needs a
            // link, or to a forced release, which does not.
            NodeEnum::StorageNode(n) => outlet == 0 || n.has_forced_release(outlet),
            // A user's supply outlets (ds_2 to ds_4) exist only where they are linked
            NodeEnum::UnregulatedUserNode(_) |
            NodeEnum::RegulatedUserNode(_) |
            NodeEnum::ConfluenceNode(_) |
            NodeEnum::GaugeNode(_) |
            NodeEnum::LossNode(_) |
            NodeEnum::Gr4jNode(_) |
            NodeEnum::InflowNode(_) |
            NodeEnum::RoutingNode(_) |
            NodeEnum::RoutingNodeReachLosses(_) |
            NodeEnum::SacramentoNode(_) |
            NodeEnum::OrderControlNode(_) |
            NodeEnum::AwbmNode(_) |
            NodeEnum::SurmNode(_) |
            NodeEnum::Gr4jsgNode(_) => outlet == 0,
        }
    }

    /// The node's optimisable parameters, or `None` for a node type that has none.
    /// Every variant answers here, with no wildcard arm, so a new node type or
    /// sub-variant does not compile until it has said (ADR-0008 §2). The three
    /// callers, the optimiser's target resolution and parameter setting and the
    /// STDIO parameter list, used to each keep their own list of the optimisable
    /// types ahead of a wildcard, where a sub-variant left out was a wrong error,
    /// a panic, or silently missing from the IDE's list.
    #[cold]
    pub fn optimisable(&self) -> Option<&dyn OptimisableComponent> {
        match self {
            NodeEnum::SacramentoNode(n) => Some(n),
            NodeEnum::Gr4jNode(n) => Some(n),
            NodeEnum::AwbmNode(n) => Some(n),
            NodeEnum::SurmNode(n) => Some(n),
            NodeEnum::Gr4jsgNode(n) => Some(n),
            NodeEnum::RoutingNode(n) => Some(n),
            NodeEnum::RoutingNodeReachLosses(n) => Some(n),
            NodeEnum::BlackholeNode(_)
            | NodeEnum::ConfluenceNode(_)
            | NodeEnum::GaugeNode(_)
            | NodeEnum::LossNode(_)
            | NodeEnum::SplitterNode(_)
            | NodeEnum::UnregulatedUserNode(_)
            | NodeEnum::RegulatedUserNode(_)
            | NodeEnum::FieldNode(_)
            | NodeEnum::InflowNode(_)
            | NodeEnum::StorageNode(_)
            | NodeEnum::OrderControlNode(_) => None,
        }
    }

    /// [`optimisable`](Self::optimisable), mutably. The arms are the same list.
    #[cold]
    pub fn optimisable_mut(&mut self) -> Option<&mut dyn OptimisableComponent> {
        match self {
            NodeEnum::SacramentoNode(n) => Some(n),
            NodeEnum::Gr4jNode(n) => Some(n),
            NodeEnum::AwbmNode(n) => Some(n),
            NodeEnum::SurmNode(n) => Some(n),
            NodeEnum::Gr4jsgNode(n) => Some(n),
            NodeEnum::RoutingNode(n) => Some(n),
            NodeEnum::RoutingNodeReachLosses(n) => Some(n),
            NodeEnum::BlackholeNode(_)
            | NodeEnum::ConfluenceNode(_)
            | NodeEnum::GaugeNode(_)
            | NodeEnum::LossNode(_)
            | NodeEnum::SplitterNode(_)
            | NodeEnum::UnregulatedUserNode(_)
            | NodeEnum::RegulatedUserNode(_)
            | NodeEnum::FieldNode(_)
            | NodeEnum::InflowNode(_)
            | NodeEnum::StorageNode(_)
            | NodeEnum::OrderControlNode(_) => None,
        }
    }

    pub fn get_type_as_string(&self) -> String {
        let name = match self {
            NodeEnum::BlackholeNode(_) => "blackhole",
            NodeEnum::ConfluenceNode(_) => "confluence",
            NodeEnum::GaugeNode(_) => "gauge",
            NodeEnum::LossNode(_) => "loss",
            NodeEnum::SplitterNode(_) => "splitter",
            NodeEnum::UnregulatedUserNode(_) => "unregulated_user",
            NodeEnum::RegulatedUserNode(_) => "regulated_user",
            NodeEnum::FieldNode(_) => "field",
            NodeEnum::Gr4jNode(_) => "gr4j",
            NodeEnum::InflowNode(_) => "inflow",
            NodeEnum::RoutingNode(_) => "routing",
            NodeEnum::RoutingNodeReachLosses(_) => "routing", // Still a routing node, but distinguished by the use of the reach loss features.
            NodeEnum::SacramentoNode(_) => "sacramento",
            NodeEnum::StorageNode(_) => "storage",
            NodeEnum::OrderControlNode(_) => "order_control",
            NodeEnum::AwbmNode(_) => "awbm",
            NodeEnum::SurmNode(_) => "surm",
            NodeEnum::Gr4jsgNode(_) => "gr4jsg",
        };
        name.to_string()
    }
}

impl Node for NodeEnum {
    fn initialise(&mut self, data_cache: &mut DataCache, account_manager: &mut AccountManager) -> Result<(), String> {
        dispatch!(self, node => node.initialise(data_cache, account_manager))
    }

    fn get_name(&self) -> &str {
        dispatch!(self, node => node.get_name())
    }

    fn run_order_phase(&mut self, data_cache: &mut DataCache, account_manager: &mut AccountManager) {
        dispatch!(self, node => node.run_order_phase(data_cache, account_manager))
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache, account_manager: &mut AccountManager) {
        dispatch!(self, node => node.run_flow_phase(data_cache, account_manager))
    }

    fn add_usflow(&mut self, flow: f64, inlet: u8) {
        dispatch!(self, node => node.add_usflow(flow, inlet))
    }

    fn remove_dsflow(&mut self, outlet: u8) -> f64 {
        dispatch!(self, node => node.remove_dsflow(outlet))
    }

    #[inline(always)]
    fn remove_dsflow_primary(&mut self) -> f64 {
        dispatch!(self, node => node.remove_dsflow_primary())
    }

    fn get_mass_balance(&self) -> f64 {
        dispatch!(self, node => node.get_mass_balance())
    }

    fn dsorders_mut(&mut self) -> &mut [f64] {
        dispatch!(self, node => node.dsorders_mut())
    }
}
