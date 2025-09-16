use dyn_clone::{DynClone};
pub use node_trait::Node;
pub use link::Link;
pub use node_enum::NodeEnum;

//List all the submodules here
pub mod confluence_node;
pub mod gr4j_node;
pub mod inflow_node;
pub mod storage_node;
pub mod diversion_node;
pub mod routing_node;
pub mod sacramento_node;
pub mod node_enum;
pub mod node_trait;
pub mod link;


