use super::{make_result_name, InputDataDefinition, Node};
use crate::timeseries::Timeseries;
use uuid::Uuid;
use crate::data_cache::DataCache;

#[derive(Default)]
#[derive(Clone)]
pub struct InflowNode {
    //Generic Node stuff
    pub name: String,
    pub id: Uuid,

    //Vars for receiving and transmitting water
    q_rx_0: f64,
    q_tx_0: f64,

    //Inputs
    pub inflow_def: InputDataDefinition,

    //Other vars including for calculations and reporting
    us_flow: f64,
    ds_flow: f64,
    storage: f64,
    inflow: f64,

    //Recorders
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_usflow: Option<usize>,
    recorder_idx_inflow: Option<usize>,
}

impl InflowNode {
    /*
    Constructor
    */
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            id: Uuid::new_v4(),
            ..Default::default()
        }
    }
}

impl Node for InflowNode {
    /*
    Initialise node before model run
    */
    fn initialise(&mut self, data_cache: &mut DataCache) {
        self.q_rx_0 = 0_f64;
        self.q_tx_0 = 0_f64;
        self.us_flow = 0_f64;
        self.ds_flow = 0_f64;
        self.storage = 0_f64;

        //Initialize inflow series
        self.inflow_def.add_series_to_data_cache_if_required_and_get_idx(data_cache, true);

        //Initialize result recorders
        let node_name = self.name.clone();
        self.recorder_idx_dsflow = data_cache.get_series_idx(make_result_name(node_name.as_str(), "dsflow").as_str(), false);
        self.recorder_idx_usflow = data_cache.get_series_idx(make_result_name(node_name.as_str(), "usflow").as_str(), false);
        self.recorder_idx_inflow = data_cache.get_series_idx(make_result_name(node_name.as_str(), "inflow").as_str(), false);
    }


    /*
    Get the id of the node
     */
    fn get_id(&self) -> Uuid {
        self.id
    }


    /*
    Runs the node for the current timestep and updates the node state
     */
    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {
        //Get flow from the upstream terminal if one has been defined
        self.us_flow = self.q_rx_0;
        self.q_rx_0 = 0_f64;

        //For inflow nodes
        if let Some(idx) = self.inflow_def.idx {
            self.inflow = data_cache.get_current_value(idx);
        }
        self.ds_flow = self.us_flow + self.inflow;

        //Give all the ds_flow water to the downstream terminal
        self.q_tx_0 = self.ds_flow;
        
        //Record results
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.ds_flow)
        }
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.us_flow)
        }
        if let Some(idx) = self.recorder_idx_inflow {
            data_cache.add_value_at_index(idx, self.inflow)
        }
    }

    fn add(&mut self, v: f64, i: i32) {
        if i != 0 { panic!("This node only has q_rx_0, but i = {}", i) }
        self.q_rx_0 += v;
    }

    fn remove_all(&mut self, i: i32) -> f64 {
        let answer = self.q_tx_0;
        self.q_tx_0 = 0_f64;
        answer
    }
}
