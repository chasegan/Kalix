use uuid::Uuid;
use crate::data_cache::DataCache;
use dyn_clone::{clone_trait_object, DynClone};
use crate::nodes::confluence_node::ConfluenceNode;
use crate::nodes::diversion_node::DiversionNode;
use crate::nodes::gr4j_node::Gr4jNode;
use crate::nodes::inflow_node::InflowNode;
use crate::nodes::routing_node::RoutingNode;
use crate::nodes::sacramento_node::SacramentoNode;
use crate::nodes::storage_node::StorageNode;

//List all the sub-modules here
pub mod confluence_node;
pub mod gr4j_node;
pub mod inflow_node;
pub mod storage_node;
pub mod diversion_node;
pub mod routing_node;
pub mod sacramento_node;


pub trait Node: DynClone + Sync + Send {

    //To Initialise node before model run
    fn initialise(&mut self, result_manager: &mut DataCache);

    //Runs the node for the current timestep and updates the node state
    fn run_flow_phase(&mut self, result_manager: &mut DataCache);

    //Gets the unique id of the node
    fn get_id(&self) -> Uuid;

    //Adds water to inlet i of the node
    fn add(&mut self, v: f64, i: i32);

    //Removes water from outlet i of the node
    fn remove_all(&mut self, i: i32) -> f64;
}



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
            NodeEnum::ConfluenceNode(mut confluence_node) => {
                confluence_node.run_flow_phase(result_manager);
            }
            NodeEnum::Gr4jNode(mut gr4j_node) => {
                gr4j_node.run_flow_phase(result_manager);
            }
            NodeEnum::InflowNode(mut inflow_node) => {
                inflow_node.run_flow_phase(result_manager);
            }
            NodeEnum::RoutingNode(mut routing_node) => {
                routing_node.run_flow_phase(result_manager);
            }
            NodeEnum::StorageNode(mut storage_node) => {
                storage_node.run_flow_phase(result_manager);
            }
            NodeEnum::DiversionNode(mut diversion_node) => {
                diversion_node.run_flow_phase(result_manager);
            }
            NodeEnum::SacramentoNode(mut sacramento_node) => {
                sacramento_node.run_flow_phase(result_manager);
            }
        }
    }

    fn remove_all(&mut self, i: i32) -> f64 {
        match self {
            NodeEnum::ConfluenceNode(mut confluence_node) => {
                confluence_node.remove_all(i)
            }
            NodeEnum::Gr4jNode(mut gr4j_node) => {
                gr4j_node.remove_all(i)
            }
            NodeEnum::InflowNode(mut inflow_node) => {
                inflow_node.remove_all(i)
            }
            NodeEnum::RoutingNode(mut routing_node) => {
                routing_node.remove_all(i)
            }
            NodeEnum::StorageNode(mut storage_node) => {
                storage_node.remove_all(i)
            }
            NodeEnum::DiversionNode(mut diversion_node) => {
                diversion_node.remove_all(i)
            }
            NodeEnum::SacramentoNode(mut sacramento_node) => {
                sacramento_node.remove_all(i)
            }
        }
    }

    fn add(&mut self, v: f64, i: i32) {
        match self {
            NodeEnum::ConfluenceNode(mut confluence_node) => {
                confluence_node.add(v, i);
            }
            NodeEnum::Gr4jNode(mut gr4j_node) => {
                gr4j_node.add(v, i);
            }
            NodeEnum::InflowNode(mut inflow_node) => {
                inflow_node.add(v, i);
            }
            NodeEnum::RoutingNode(mut routing_node) => {
                routing_node.add(v, i);
            }
            NodeEnum::StorageNode(mut storage_node) => {
                storage_node.add(v, i);
            }
            NodeEnum::DiversionNode(mut diversion_node) => {
                diversion_node.add(v, i);
            }
            NodeEnum::SacramentoNode(mut sacramento_node) => {
                sacramento_node.add(v, i);
            }
        }
    }

    fn get_id(&self) -> Uuid {
        match &self {
            NodeEnum::ConfluenceNode(mut confluence_node) => {
                confluence_node.get_id()
            }
            NodeEnum::Gr4jNode(mut gr4j_node) => {
                gr4j_node.get_id()
            }
            NodeEnum::InflowNode(mut inflow_node) => {
                inflow_node.get_id()
            }
            NodeEnum::RoutingNode(mut routing_node) => {
                routing_node.get_id()
            }
            NodeEnum::StorageNode(mut storage_node) => {
                storage_node.get_id()
            }
            NodeEnum::DiversionNode(mut diversion_node) => {
                diversion_node.get_id()
            }
            NodeEnum::SacramentoNode(mut sacramento_node) => {
                sacramento_node.get_id()
            }
        }
    }

    fn initialise(&mut self, result_manager: &mut DataCache) {
        match self {
            NodeEnum::ConfluenceNode(mut confluence_node) => {
                confluence_node.initialise(result_manager);
            }
            NodeEnum::Gr4jNode(mut gr4j_node) => {
                gr4j_node.initialise(result_manager);
            }
            NodeEnum::InflowNode(mut inflow_node) => {
                inflow_node.initialise(result_manager);
            }
            NodeEnum::RoutingNode(mut routing_node) => {
                routing_node.initialise(result_manager);
            }
            NodeEnum::StorageNode(mut storage_node) => {
                storage_node.initialise(result_manager);
            }
            NodeEnum::DiversionNode(mut diversion_node) => {
                diversion_node.initialise(result_manager);
            }
            NodeEnum::SacramentoNode(mut sacramento_node) => {
                sacramento_node.initialise(result_manager);
            }
        }
    }
}


fn make_result_name(node_name: &str, parameter: &str) -> String {
    format!("node.{node_name}.{parameter}")
}

clone_trait_object!(Node);


#[derive(Clone)]
#[derive(Default)]
pub struct InputDataDefinition {
    pub name: String,       //The name of the series in the data_cache to use for inflows
    pub idx: Option<usize>, //This is the idx of the series, which will be determined during init and used subsequently
}

impl InputDataDefinition {
    pub fn add_series_to_data_cache_if_required_and_get_idx(&mut self, data_cache: &mut DataCache, flag_as_critical: bool) {
        if !self.name.is_empty() {
            self.idx = Some(data_cache.get_or_add_new_series(self.name.as_str(), flag_as_critical));
        } else {
            self.idx = None;
        }
    }
}

