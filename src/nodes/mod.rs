use uuid::Uuid;
use crate::data_cache::DataCache;
use dyn_clone::{clone_trait_object, DynClone};
pub use node_trait::Node;
pub use node_enum::NodeEnum;

//List all the sub-modules here
pub mod confluence_node;
pub mod gr4j_node;
pub mod inflow_node;
pub mod storage_node;
pub mod diversion_node;
pub mod routing_node;
pub mod sacramento_node;
pub mod node_enum;
pub mod node_trait;


fn make_result_name(node_name: &str, parameter: &str) -> String {
    format!("node.{node_name}.{parameter}")
}


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

