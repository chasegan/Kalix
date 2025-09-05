use dyn_clone::{clone_trait_object, DynClone};
use uuid::Uuid;
use crate::data_cache::DataCache;

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

clone_trait_object!(Node);

