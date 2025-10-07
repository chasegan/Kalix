use crate::data_cache::DataCache;
use crate::nodes::{Node, blackhole_node::BlackholeNode, confluence_node::ConfluenceNode, gauge_node::GaugeNode, loss_node::LossNode, splitter_node::SplitterNode, user_node::UserNode, gr4j_node::Gr4jNode, inflow_node::InflowNode, routing_node::RoutingNode, sacramento_node::SacramentoNode, storage_node::StorageNode};

#[derive(Clone)]
pub enum NodeEnum {
    BlackholeNode(BlackholeNode),
    ConfluenceNode(ConfluenceNode),
    GaugeNode(GaugeNode),
    LossNode(LossNode),
    SplitterNode(SplitterNode),
    UserNode(UserNode),
    Gr4jNode(Gr4jNode),
    InflowNode(InflowNode),
    RoutingNode(RoutingNode),
    SacramentoNode(SacramentoNode),
    StorageNode(StorageNode),
}

impl Node for NodeEnum {
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(),String> {
        match self {
            NodeEnum::BlackholeNode(node) => node.initialise(data_cache),
            NodeEnum::ConfluenceNode(node) => node.initialise(data_cache),
            NodeEnum::GaugeNode(node) => node.initialise(data_cache),
            NodeEnum::LossNode(node) => node.initialise(data_cache),
            NodeEnum::SplitterNode(node) => node.initialise(data_cache),
            NodeEnum::UserNode(node) => node.initialise(data_cache),
            NodeEnum::Gr4jNode(node) => node.initialise(data_cache),
            NodeEnum::InflowNode(node) => node.initialise(data_cache),
            NodeEnum::RoutingNode(node) => node.initialise(data_cache),
            NodeEnum::SacramentoNode(node) => node.initialise(data_cache),
            NodeEnum::StorageNode(node) => node.initialise(data_cache),
        }
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {
        match self {
            NodeEnum::BlackholeNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::ConfluenceNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::GaugeNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::LossNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::SplitterNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::UserNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::Gr4jNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::InflowNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::RoutingNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::SacramentoNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::StorageNode(node) => node.run_flow_phase(data_cache),
        }
    }

    fn get_name(&self) -> &str {
        match self {
            NodeEnum::BlackholeNode(node) => node.get_name(),
            NodeEnum::ConfluenceNode(node) => node.get_name(),
            NodeEnum::GaugeNode(node) => node.get_name(),
            NodeEnum::LossNode(node) => node.get_name(),
            NodeEnum::SplitterNode(node) => node.get_name(),
            NodeEnum::UserNode(node) => node.get_name(),
            NodeEnum::Gr4jNode(node) => node.get_name(),
            NodeEnum::InflowNode(node) => node.get_name(),
            NodeEnum::RoutingNode(node) => node.get_name(),
            NodeEnum::SacramentoNode(node) => node.get_name(),
            NodeEnum::StorageNode(node) => node.get_name(),
        }
    }

    fn add_usflow(&mut self, flow: f64, inlet: u8) {
        match self {
            NodeEnum::BlackholeNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::ConfluenceNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::GaugeNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::LossNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::SplitterNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::UserNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::Gr4jNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::InflowNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::RoutingNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::SacramentoNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::StorageNode(node) => node.add_usflow(flow, inlet),
        }
    }

    fn remove_dsflow(&mut self, outlet: u8) -> f64 {
        match self {
            NodeEnum::BlackholeNode(node) => node.remove_dsflow(outlet),
            NodeEnum::ConfluenceNode(node) => node.remove_dsflow(outlet),
            NodeEnum::GaugeNode(node) => node.remove_dsflow(outlet),
            NodeEnum::LossNode(node) => node.remove_dsflow(outlet),
            NodeEnum::SplitterNode(node) => node.remove_dsflow(outlet),
            NodeEnum::UserNode(node) => node.remove_dsflow(outlet),
            NodeEnum::Gr4jNode(node) => node.remove_dsflow(outlet),
            NodeEnum::InflowNode(node) => node.remove_dsflow(outlet),
            NodeEnum::RoutingNode(node) => node.remove_dsflow(outlet),
            NodeEnum::SacramentoNode(node) => node.remove_dsflow(outlet),
            NodeEnum::StorageNode(node) => node.remove_dsflow(outlet),
        }
    }
}