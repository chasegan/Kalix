use dyn_clone::{clone_trait_object, DynClone};
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;

pub trait Node: DynClone + Sync + Send {
    fn initialise(&mut self, data_cache: &mut DataCache, account_manager: &mut AccountManager) -> Result<(),String>;
    fn get_name(&self) -> &str;
    fn run_order_phase(&mut self, _data_cache: &mut DataCache) {}
    fn run_flow_phase(&mut self, data_cache: &mut DataCache, account_manager: &mut AccountManager);
    fn add_usflow(&mut self, flow: f64, inlet: u8);
    fn remove_dsflow(&mut self, outlet: u8) -> f64;
    fn get_mass_balance(&self) -> f64;
    fn dsorders_mut(&mut self) -> &mut [f64];
    // Note: You can only have as many ds and us nodes as you have outlets/inlets
    fn get_max_us_links(&self) -> usize;
    fn get_max_ds_links(&self) -> usize;
}

clone_trait_object!(Node);

