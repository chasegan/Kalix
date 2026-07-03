pub use node_trait::Node;
pub use link::Link;
pub use node_enum::NodeEnum;

//List all the submodules here
pub mod blackhole_node;
pub mod confluence_node;
pub mod gauge_node;
pub mod loss_node;
pub mod splitter_node;
pub mod gr4j_node;
pub mod inflow_node;
pub mod storage_node;
pub mod regulated_user_node;
pub mod routing_node;
pub mod sacramento_node;
pub mod node_enum;
pub mod node_trait;
pub mod link;
pub mod rainfall_weights;
pub mod unregulated_user_node;
pub mod order_control_node;



use crate::data_management::data_cache::DataCache;

/// Look up the recorder index for one of this node's output series
/// (`node.<name>.<output>`). Returns None when the series isn't registered
/// (i.e. nobody asked to record it). Replaces the
/// `get_series_idx(make_result_name(...))` incantation each node repeated
/// for every recorder.
pub(crate) fn recorder(data_cache: &mut DataCache, node_name: &str, output: &str) -> Option<usize> {
    let series_name = crate::misc::misc_functions::make_result_name(node_name, output);
    data_cache.get_series_idx(series_name.as_str(), false)
}

/// The four Node methods that are identical for every node with a single
/// primary outlet (fields: usflow, dsflow_primary, mbal, dsorders). Expands
/// to exactly the code it replaced in each node - this is notation, not
/// abstraction; read it here once instead of nine times.
macro_rules! single_outlet_node_impls {
    () => {
        fn add_usflow(&mut self, flow: f64, _inlet: u8) {
            self.usflow += flow;
        }

        fn remove_dsflow(&mut self, outlet: u8) -> f64 {
            match outlet {
                0 => {
                    let outflow = self.dsflow_primary;
                    self.dsflow_primary = 0.0;
                    outflow
                }
                _ => 0.0,
            }
        }

        fn get_mass_balance(&self) -> f64 {
            self.mbal
        }

        fn dsorders_mut(&mut self) -> &mut [f64] {
            &mut self.dsorders
        }
    };
}
pub(crate) use single_outlet_node_impls;
