use dyn_clone::{clone_trait_object, DynClone};
use crate::data_management::data_cache::DataCache;

pub trait Node: DynClone + Sync + Send {
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(),String>;
    fn get_name(&self) -> &str;
    fn run_flow_phase(&mut self, data_cache: &mut DataCache);
    fn add_usflow(&mut self, flow: f64, inlet: u8);
    fn remove_dsflow(&mut self, outlet: u8) -> f64;
    fn get_mass_balance(&self) -> f64;
}

clone_trait_object!(Node);

