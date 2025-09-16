use uuid::Uuid;
use crate::data_cache::DataCache;
use crate::nodes::{Node, confluence_node::ConfluenceNode, diversion_node::DiversionNode, gr4j_node::Gr4jNode, inflow_node::InflowNode, routing_node::RoutingNode, sacramento_node::SacramentoNode, storage_node::StorageNode};

#[derive(Clone)]
pub enum NodeEnum {
    ConfluenceNode(ConfluenceNode),
    DiversionNode(DiversionNode),
    Gr4jNode(Gr4jNode),
    InflowNode(InflowNode),
    RoutingNode(RoutingNode),
    SacramentoNode(SacramentoNode),
    StorageNode(StorageNode),
}

impl Node for NodeEnum {
    fn run_flow_phase(&mut self, result_manager: &mut DataCache) {
        //Run node i
        match self {
            NodeEnum::ConfluenceNode(ref mut confluence_node) => {
                confluence_node.run_flow_phase(result_manager);
            }
            NodeEnum::Gr4jNode(ref mut gr4j_node) => {
                gr4j_node.run_flow_phase(result_manager);
            }
            NodeEnum::InflowNode(ref mut inflow_node) => {
                inflow_node.run_flow_phase(result_manager);
            }
            NodeEnum::RoutingNode(ref mut routing_node) => {
                routing_node.run_flow_phase(result_manager);
            }
            NodeEnum::StorageNode(ref mut storage_node) => {
                storage_node.run_flow_phase(result_manager);
            }
            NodeEnum::DiversionNode(ref mut diversion_node) => {
                diversion_node.run_flow_phase(result_manager);
            }
            NodeEnum::SacramentoNode(ref mut sacramento_node) => {
                sacramento_node.run_flow_phase(result_manager);
            }
        }
    }

    fn remove_outflow(&mut self, i: i32) -> f64 {
        match self {
            NodeEnum::ConfluenceNode(ref mut confluence_node) => {
                confluence_node.remove_outflow(i)
            }
            NodeEnum::Gr4jNode(ref mut gr4j_node) => {
                gr4j_node.remove_outflow(i)
            }
            NodeEnum::InflowNode(ref mut inflow_node) => {
                inflow_node.remove_outflow(i)
            }
            NodeEnum::RoutingNode(ref mut routing_node) => {
                routing_node.remove_outflow(i)
            }
            NodeEnum::StorageNode(ref mut storage_node) => {
                storage_node.remove_outflow(i)
            }
            NodeEnum::DiversionNode(ref mut diversion_node) => {
                diversion_node.remove_outflow(i)
            }
            NodeEnum::SacramentoNode(ref mut sacramento_node) => {
                sacramento_node.remove_outflow(i)
            }
        }
    }

    fn add_inflow(&mut self, v: f64, i: i32) {
        match self {
            NodeEnum::ConfluenceNode(ref mut confluence_node) => {
                confluence_node.add_inflow(v, i);
            }
            NodeEnum::Gr4jNode(ref mut gr4j_node) => {
                gr4j_node.add_inflow(v, i);
            }
            NodeEnum::InflowNode(ref mut inflow_node) => {
                inflow_node.add_inflow(v, i);
            }
            NodeEnum::RoutingNode(ref mut routing_node) => {
                routing_node.add_inflow(v, i);
            }
            NodeEnum::StorageNode(ref mut storage_node) => {
                storage_node.add_inflow(v, i);
            }
            NodeEnum::DiversionNode(ref mut diversion_node) => {
                diversion_node.add_inflow(v, i);
            }
            NodeEnum::SacramentoNode(ref mut sacramento_node) => {
                sacramento_node.add_inflow(v, i);
            }
        }
    }

    fn get_id(&self) -> Uuid {
        match &self {
            NodeEnum::ConfluenceNode(confluence_node) => {
                confluence_node.get_id()
            }
            NodeEnum::Gr4jNode(gr4j_node) => {
                gr4j_node.get_id()
            }
            NodeEnum::InflowNode(inflow_node) => {
                inflow_node.get_id()
            }
            NodeEnum::RoutingNode(routing_node) => {
                routing_node.get_id()
            }
            NodeEnum::StorageNode(storage_node) => {
                storage_node.get_id()
            }
            NodeEnum::DiversionNode(diversion_node) => {
                diversion_node.get_id()
            }
            NodeEnum::SacramentoNode(sacramento_node) => {
                sacramento_node.get_id()
            }
        }
    }

    fn initialise(&mut self, result_manager: &mut DataCache) {
        match self {
            NodeEnum::ConfluenceNode(ref mut confluence_node) => {
                confluence_node.initialise(result_manager);
            }
            NodeEnum::Gr4jNode(ref mut gr4j_node) => {
                gr4j_node.initialise(result_manager);
            }
            NodeEnum::InflowNode(ref mut inflow_node) => {
                inflow_node.initialise(result_manager);
            }
            NodeEnum::RoutingNode(ref mut routing_node) => {
                routing_node.initialise(result_manager);
            }
            NodeEnum::StorageNode(ref mut storage_node) => {
                storage_node.initialise(result_manager);
            }
            NodeEnum::DiversionNode(ref mut diversion_node) => {
                diversion_node.initialise(result_manager);
            }
            NodeEnum::SacramentoNode(ref mut sacramento_node) => {
                sacramento_node.initialise(result_manager);
            }
        }
    }
}