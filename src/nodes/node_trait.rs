use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;

/// The behaviour every node type implements. Nodes are always stored
/// concretely as [`crate::nodes::NodeEnum`] variants (static dispatch via the
/// enum), never as trait objects - so the trait carries no object-safety or
/// clone-boxing machinery.
pub trait Node {
    fn initialise(&mut self, data_cache: &mut DataCache, account_manager: &mut AccountManager) -> Result<(), String>;
    fn get_name(&self) -> &str;
    fn run_order_phase(&mut self, _data_cache: &mut DataCache) {}
    fn run_flow_phase(&mut self, data_cache: &mut DataCache, account_manager: &mut AccountManager);
    fn add_usflow(&mut self, flow: f64, inlet: u8);
    fn remove_dsflow(&mut self, outlet: u8) -> f64;
    fn get_mass_balance(&self) -> f64;
    fn dsorders_mut(&mut self) -> &mut [f64];
}
