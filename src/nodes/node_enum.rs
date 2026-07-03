use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::nodes::{Node, blackhole_node::BlackholeNode, confluence_node::ConfluenceNode, gauge_node::GaugeNode, loss_node::LossNode, splitter_node::SplitterNode, unregulated_user_node::UnregulatedUserNode, regulated_user_node::RegulatedUserNode, gr4j_node::Gr4jNode, inflow_node::InflowNode, routing_node::RoutingNode, sacramento_node::SacramentoNode, storage_node::StorageNode, order_control_node::OrderControlNode};

#[derive(Clone)]
pub enum NodeEnum {
    BlackholeNode(BlackholeNode),
    ConfluenceNode(ConfluenceNode),
    GaugeNode(GaugeNode),
    LossNode(LossNode),
    SplitterNode(SplitterNode),
    UnregulatedUserNode(UnregulatedUserNode),
    RegulatedUserNode(RegulatedUserNode),
    Gr4jNode(Gr4jNode),
    InflowNode(InflowNode),
    RoutingNode(RoutingNode),
    SacramentoNode(SacramentoNode),
    StorageNode(StorageNode),
    OrderControlNode(OrderControlNode),
}

/// Dispatch a method call to whichever node variant this is. Expands to the
/// exact 13-arm match that was previously hand-written per method (same
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
            NodeEnum::Gr4jNode($node) => $call,
            NodeEnum::InflowNode($node) => $call,
            NodeEnum::RoutingNode($node) => $call,
            NodeEnum::SacramentoNode($node) => $call,
            NodeEnum::StorageNode($node) => $call,
            NodeEnum::OrderControlNode($node) => $call,
        }
    };
}

impl NodeEnum {
    pub fn get_type_as_string(&self) -> String {
        let name = match self {
            NodeEnum::BlackholeNode(_) => "blackhole",
            NodeEnum::ConfluenceNode(_) => "confluence",
            NodeEnum::GaugeNode(_) => "gauge",
            NodeEnum::LossNode(_) => "loss",
            NodeEnum::SplitterNode(_) => "splitter",
            NodeEnum::UnregulatedUserNode(_) => "unregulated_user",
            NodeEnum::RegulatedUserNode(_) => "regulated_user",
            NodeEnum::Gr4jNode(_) => "gr4j",
            NodeEnum::InflowNode(_) => "inflow",
            NodeEnum::RoutingNode(_) => "routing",
            NodeEnum::SacramentoNode(_) => "sacramento",
            NodeEnum::StorageNode(_) => "storage",
            NodeEnum::OrderControlNode(_) => "order_control",
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

    fn run_order_phase(&mut self, data_cache: &mut DataCache) {
        dispatch!(self, node => node.run_order_phase(data_cache))
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

    fn get_mass_balance(&self) -> f64 {
        dispatch!(self, node => node.get_mass_balance())
    }

    fn dsorders_mut(&mut self) -> &mut [f64] {
        dispatch!(self, node => node.dsorders_mut())
    }
}
