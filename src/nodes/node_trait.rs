use std::collections::HashMap;
use dyn_clone::{clone_trait_object, DynClone};
use crate::data_cache::DataCache;
use crate::nodes::Link;

pub trait Node: DynClone + Sync + Send {

    //To Initialise node before model run
    fn initialise(&mut self, result_manager: &mut DataCache, node_dictionary: &HashMap<String, usize>);

    //Runs the node for the current timestep and updates the node state
    fn run_flow_phase(&mut self, result_manager: &mut DataCache);

    //Gets the unique name of the node
    fn get_name(&self) -> String;

    //Adds inflow to inlet i of the node
    fn add_inflow(&mut self, v: f64, i: i32);

    //Removes water from outlet i of the node
    fn remove_outflow(&mut self, i: i32) -> f64;

    //Returns copies of the node's links. The primary link is guaranteed to be first in the list.
    fn get_ds_links(&self) -> [Link; 2];
}

clone_trait_object!(Node);

