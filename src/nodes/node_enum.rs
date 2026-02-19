use crate::data_management::data_cache::DataCache;
use crate::nodes::{Node, blackhole_node::BlackholeNode, confluence_node::ConfluenceNode, gauge_node::GaugeNode, loss_node::LossNode, splitter_node::SplitterNode, unregulated_user_node::UnregulatedUserNode, regulated_user_node::RegulatedUserNode, gr4j_node::Gr4jNode, inflow_node::InflowNode, routing_node::RoutingNode, sacramento_node::SacramentoNode, storage_node::StorageNode, order_constraint_node::OrderConstraintNode};

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
    OrderConstraintNode(OrderConstraintNode),
}

impl NodeEnum {
    pub fn get_type_as_string(&self) -> String {
        match self {
            NodeEnum::BlackholeNode(_) => "blackhole".to_string(),
            NodeEnum::ConfluenceNode(_) => "confluence".to_string(),
            NodeEnum::GaugeNode(_) => "gauge".to_string(),
            NodeEnum::LossNode(_) => "loss".to_string(),
            NodeEnum::SplitterNode(_) => "splitter".to_string(),
            NodeEnum::UnregulatedUserNode(_) => "unregulated_user".to_string(),
            NodeEnum::RegulatedUserNode(_) => "regulated_user".to_string(),
            NodeEnum::Gr4jNode(_) => "gr4j".to_string(),
            NodeEnum::InflowNode(_) => "inflow".to_string(),
            NodeEnum::RoutingNode(_) => "routing".to_string(),
            NodeEnum::SacramentoNode(_) => "sacramento".to_string(),
            NodeEnum::StorageNode(_) => "storage".to_string(),
            NodeEnum::OrderConstraintNode(_) => "order_constraint".to_string(),
        }
    }
}

impl Node for NodeEnum {
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(),String> {
        match self {
            NodeEnum::BlackholeNode(node) => node.initialise(data_cache),
            NodeEnum::ConfluenceNode(node) => node.initialise(data_cache),
            NodeEnum::GaugeNode(node) => node.initialise(data_cache),
            NodeEnum::LossNode(node) => node.initialise(data_cache),
            NodeEnum::SplitterNode(node) => node.initialise(data_cache),
            NodeEnum::UnregulatedUserNode(node) => node.initialise(data_cache),
            NodeEnum::RegulatedUserNode(node) => node.initialise(data_cache),
            NodeEnum::Gr4jNode(node) => node.initialise(data_cache),
            NodeEnum::InflowNode(node) => node.initialise(data_cache),
            NodeEnum::RoutingNode(node) => node.initialise(data_cache),
            NodeEnum::SacramentoNode(node) => node.initialise(data_cache),
            NodeEnum::StorageNode(node) => node.initialise(data_cache),
            NodeEnum::OrderConstraintNode(node) => node.initialise(data_cache),
        }
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {
        match self {
            NodeEnum::BlackholeNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::ConfluenceNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::GaugeNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::LossNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::SplitterNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::UnregulatedUserNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::RegulatedUserNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::Gr4jNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::InflowNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::RoutingNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::SacramentoNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::StorageNode(node) => node.run_flow_phase(data_cache),
            NodeEnum::OrderConstraintNode(node) => node.run_flow_phase(data_cache),
        }
    }

    fn get_name(&self) -> &str {
        match self {
            NodeEnum::BlackholeNode(node) => node.get_name(),
            NodeEnum::ConfluenceNode(node) => node.get_name(),
            NodeEnum::GaugeNode(node) => node.get_name(),
            NodeEnum::LossNode(node) => node.get_name(),
            NodeEnum::SplitterNode(node) => node.get_name(),
            NodeEnum::UnregulatedUserNode(node) => node.get_name(),
            NodeEnum::RegulatedUserNode(node) => node.get_name(),
            NodeEnum::Gr4jNode(node) => node.get_name(),
            NodeEnum::InflowNode(node) => node.get_name(),
            NodeEnum::RoutingNode(node) => node.get_name(),
            NodeEnum::SacramentoNode(node) => node.get_name(),
            NodeEnum::StorageNode(node) => node.get_name(),
            NodeEnum::OrderConstraintNode(node) => node.get_name(),
        }
    }

    fn add_usflow(&mut self, flow: f64, inlet: u8) {
        match self {
            NodeEnum::BlackholeNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::ConfluenceNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::GaugeNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::LossNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::SplitterNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::UnregulatedUserNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::RegulatedUserNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::Gr4jNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::InflowNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::RoutingNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::SacramentoNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::StorageNode(node) => node.add_usflow(flow, inlet),
            NodeEnum::OrderConstraintNode(node) => node.add_usflow(flow, inlet),
        }
    }

    fn remove_dsflow(&mut self, outlet: u8) -> f64 {
        match self {
            NodeEnum::BlackholeNode(node) => node.remove_dsflow(outlet),
            NodeEnum::ConfluenceNode(node) => node.remove_dsflow(outlet),
            NodeEnum::GaugeNode(node) => node.remove_dsflow(outlet),
            NodeEnum::LossNode(node) => node.remove_dsflow(outlet),
            NodeEnum::SplitterNode(node) => node.remove_dsflow(outlet),
            NodeEnum::UnregulatedUserNode(node) => node.remove_dsflow(outlet),
            NodeEnum::RegulatedUserNode(node) => node.remove_dsflow(outlet),
            NodeEnum::Gr4jNode(node) => node.remove_dsflow(outlet),
            NodeEnum::InflowNode(node) => node.remove_dsflow(outlet),
            NodeEnum::RoutingNode(node) => node.remove_dsflow(outlet),
            NodeEnum::SacramentoNode(node) => node.remove_dsflow(outlet),
            NodeEnum::StorageNode(node) => node.remove_dsflow(outlet),
            NodeEnum::OrderConstraintNode(node) => node.remove_dsflow(outlet),
        }
    }

    fn get_mass_balance(&self) -> f64 {
        match self {
            NodeEnum::BlackholeNode(node) => node.get_mass_balance(),
            NodeEnum::ConfluenceNode(node) => node.get_mass_balance(),
            NodeEnum::GaugeNode(node) => node.get_mass_balance(),
            NodeEnum::LossNode(node) => node.get_mass_balance(),
            NodeEnum::SplitterNode(node) => node.get_mass_balance(),
            NodeEnum::UnregulatedUserNode(node) => node.get_mass_balance(),
            NodeEnum::RegulatedUserNode(node) => node.get_mass_balance(),
            NodeEnum::Gr4jNode(node) => node.get_mass_balance(),
            NodeEnum::InflowNode(node) => node.get_mass_balance(),
            NodeEnum::RoutingNode(node) => node.get_mass_balance(),
            NodeEnum::SacramentoNode(node) => node.get_mass_balance(),
            NodeEnum::StorageNode(node) => node.get_mass_balance(),
            NodeEnum::OrderConstraintNode(node) => node.get_mass_balance(),
        }
    }

    fn run_order_phase(&mut self, data_cache: &mut DataCache) {
        match self {
            NodeEnum::BlackholeNode(node) => node.run_order_phase(data_cache),
            NodeEnum::ConfluenceNode(node) => node.run_order_phase(data_cache),
            NodeEnum::GaugeNode(node) => node.run_order_phase(data_cache),
            NodeEnum::LossNode(node) => node.run_order_phase(data_cache),
            NodeEnum::SplitterNode(node) => node.run_order_phase(data_cache),
            NodeEnum::UnregulatedUserNode(node) => node.run_order_phase(data_cache),
            NodeEnum::RegulatedUserNode(node) => node.run_order_phase(data_cache),
            NodeEnum::Gr4jNode(node) => node.run_order_phase(data_cache),
            NodeEnum::InflowNode(node) => node.run_order_phase(data_cache),
            NodeEnum::RoutingNode(node) => node.run_order_phase(data_cache),
            NodeEnum::SacramentoNode(node) => node.run_order_phase(data_cache),
            NodeEnum::StorageNode(node) => node.run_order_phase(data_cache),
            NodeEnum::OrderConstraintNode(node) => node.run_order_phase(data_cache),
        }
    }

    fn dsorders_mut(&mut self) -> &mut [f64] {
        match self {
            NodeEnum::BlackholeNode(node) => node.dsorders_mut(),
            NodeEnum::ConfluenceNode(node) => node.dsorders_mut(),
            NodeEnum::GaugeNode(node) => node.dsorders_mut(),
            NodeEnum::LossNode(node) => node.dsorders_mut(),
            NodeEnum::SplitterNode(node) => node.dsorders_mut(),
            NodeEnum::UnregulatedUserNode(node) => node.dsorders_mut(),
            NodeEnum::RegulatedUserNode(node) => node.dsorders_mut(),
            NodeEnum::Gr4jNode(node) => node.dsorders_mut(),
            NodeEnum::InflowNode(node) => node.dsorders_mut(),
            NodeEnum::RoutingNode(node) => node.dsorders_mut(),
            NodeEnum::SacramentoNode(node) => node.dsorders_mut(),
            NodeEnum::StorageNode(node) => node.dsorders_mut(),
            NodeEnum::OrderConstraintNode(node) => node.dsorders_mut(),
        }
    }

    fn run_pre_order_phase(&mut self, data_cache: &mut DataCache) {
        match self {
            NodeEnum::BlackholeNode(node) => node.run_pre_order_phase(data_cache),
            NodeEnum::ConfluenceNode(node) => node.run_pre_order_phase(data_cache),
            NodeEnum::GaugeNode(node) => node.run_pre_order_phase(data_cache),
            NodeEnum::LossNode(node) => node.run_pre_order_phase(data_cache),
            NodeEnum::SplitterNode(node) => node.run_pre_order_phase(data_cache),
            NodeEnum::UnregulatedUserNode(node) => node.run_pre_order_phase(data_cache),
            NodeEnum::RegulatedUserNode(node) => node.run_pre_order_phase(data_cache),
            NodeEnum::Gr4jNode(node) => node.run_pre_order_phase(data_cache),
            NodeEnum::InflowNode(node) => node.run_pre_order_phase(data_cache),
            NodeEnum::RoutingNode(node) => node.run_pre_order_phase(data_cache),
            NodeEnum::SacramentoNode(node) => node.run_pre_order_phase(data_cache),
            NodeEnum::StorageNode(node) => node.run_pre_order_phase(data_cache),
            NodeEnum::OrderConstraintNode(node) => node.run_pre_order_phase(data_cache),
        }
    }

    fn run_post_order_phase(&mut self, data_cache: &mut DataCache) {
        match self {
            NodeEnum::BlackholeNode(node) => node.run_post_order_phase(data_cache),
            NodeEnum::ConfluenceNode(node) => node.run_post_order_phase(data_cache),
            NodeEnum::GaugeNode(node) => node.run_post_order_phase(data_cache),
            NodeEnum::LossNode(node) => node.run_post_order_phase(data_cache),
            NodeEnum::SplitterNode(node) => node.run_post_order_phase(data_cache),
            NodeEnum::UnregulatedUserNode(node) => node.run_post_order_phase(data_cache),
            NodeEnum::RegulatedUserNode(node) => node.run_post_order_phase(data_cache),
            NodeEnum::Gr4jNode(node) => node.run_post_order_phase(data_cache),
            NodeEnum::InflowNode(node) => node.run_post_order_phase(data_cache),
            NodeEnum::RoutingNode(node) => node.run_post_order_phase(data_cache),
            NodeEnum::SacramentoNode(node) => node.run_post_order_phase(data_cache),
            NodeEnum::StorageNode(node) => node.run_post_order_phase(data_cache),
            NodeEnum::OrderConstraintNode(node) => node.run_post_order_phase(data_cache),
        }
    }
}

