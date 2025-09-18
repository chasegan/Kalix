use dyn_clone::{clone_trait_object, DynClone};
use crate::data_cache::DataCache;

pub trait Node: DynClone + Sync + Send {
    fn initialise(&mut self, data_cache: &mut DataCache);
    fn run_flow_phase(&mut self, data_cache: &mut DataCache);
    fn get_name(&self) -> &str;  // Returns reference for efficiency
    fn add_inflow(&mut self, flow: f64, inlet: u8);
    fn get_outflow(&mut self, outlet: u8) -> f64;
    // Links managed centrally - no get_ds_links()
}

clone_trait_object!(Node);

