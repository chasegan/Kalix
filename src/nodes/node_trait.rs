use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;

/// The behaviour every node type implements. Nodes are always stored
/// concretely as [`crate::nodes::NodeEnum`] variants (static dispatch via the
/// enum), never as trait objects - so the trait carries no object-safety or
/// clone-boxing machinery.
pub trait Node {
    fn initialise(&mut self, data_cache: &mut DataCache, account_manager: &mut AccountManager) -> Result<(), String>;
    fn get_name(&self) -> &str;
    fn run_order_phase(&mut self, _data_cache: &mut DataCache, _account_manager: &mut AccountManager) {}
    fn run_flow_phase(&mut self, data_cache: &mut DataCache, account_manager: &mut AccountManager);
    fn add_usflow(&mut self, flow: f64, inlet: u8);
    fn remove_dsflow(&mut self, outlet: u8) -> f64;
    /// `remove_dsflow(0)`, for the flow phase's transfers. Almost every link
    /// leaves a node's primary outlet, and with the outlet fixed this is one
    /// field read and one store in every node type, so it inlines into the
    /// loop where `remove_dsflow` is a call.
    fn remove_dsflow_primary(&mut self) -> f64;
    /// The node's own account of its mass balance over the run so far [ML]:
    /// the water it put at its outlets less the water it received, whether or
    /// not a link took that water away. It is not the reported mass balance.
    /// The model takes off what was left at outlets with no link
    /// (`Model::get_mass_balance_data`).
    fn get_internal_mbal(&self) -> f64;
    fn dsorders_mut(&mut self) -> &mut [f64];
}
