use crate::hydrology::rainfall_runoff::gr4j::Gr4j;
use super::{make_result_name, InputDataDefinition, Node};
use uuid::Uuid;
use crate::data_cache::DataCache;

#[derive(Default)]
#[derive(Clone)]
pub struct Gr4jNode {
    //Generic Node stuff
    pub name: String,
    pub id: Uuid,

    //Vars for receiving and transmitting water
    q_rx_0: f64,
    q_tx_0: f64,

    //Inputs
    pub rain_mm_def: InputDataDefinition,
    pub evap_mm_def: InputDataDefinition,

    //Other vars including for calculations
    //and reporting
    us_flow: f64,
    ds_flow: f64,
    storage: f64,
    pub area_km2: f64,
    pub gr4j_model: Gr4j,
    rain: f64,
    pet: f64,
    runoff_depth_mm: f64,
    runoff_volume_megs: f64,

    //Recorders
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_runoff_volume_megs: Option<usize>,
    recorder_idx_runoff_depth_mm: Option<usize>,
}

impl Gr4jNode {
    /*
    Constructor
    */
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            id: Uuid::new_v4(),
            area_km2: 1.0,
            gr4j_model: Gr4j::new(),
            ..Default::default()
        }
    }
}

impl Node for Gr4jNode {
    /*
    Initialise node before model run
    */
    fn initialise(&mut self, data_cache: &mut DataCache) {
        self.q_rx_0 = 0_f64;
        self.q_tx_0 = 0_f64;
        self.us_flow = 0_f64;
        self.ds_flow = 0_f64;
        self.storage = 0_f64;

        //Initialize input series
        self.rain_mm_def.add_series_to_data_cache_if_required_and_get_idx(data_cache, true);
        self.evap_mm_def.add_series_to_data_cache_if_required_and_get_idx(data_cache, true);

        //Initialize result recorders
        let node_name = self.name.clone();
        self.recorder_idx_dsflow = data_cache.get_series_idx(make_result_name(node_name.as_str(), "dsflow").as_str(), false);
        self.recorder_idx_runoff_volume_megs = data_cache.get_series_idx(make_result_name(node_name.as_str(), "runoff_volume").as_str(), false);
        self.recorder_idx_runoff_depth_mm = data_cache.get_series_idx(make_result_name(node_name.as_str(), "runoff_depth").as_str(), false);
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

        // Get driving data
        if let Some(idx) = self.rain_mm_def.idx {
            self.rain = data_cache.get_current_value(idx);
        }
        if let Some(idx) = self.evap_mm_def.idx {
            self.pet = data_cache.get_current_value(idx);
        }

        //For gr4j nodes
        self.runoff_depth_mm = self.gr4j_model.run_step(self.rain, self.pet);
        self.runoff_volume_megs = self.runoff_depth_mm * self.area_km2;
        self.ds_flow = self.us_flow + self.runoff_volume_megs;

        //Give all the ds_flow water to the downstream terminal
        self.q_tx_0 = self.ds_flow;

        //Record results
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.ds_flow)
        }
        if let Some(idx) = self.recorder_idx_runoff_volume_megs {
            data_cache.add_value_at_index(idx, self.runoff_volume_megs)
        }
        if let Some(idx) = self.recorder_idx_runoff_depth_mm {
            data_cache.add_value_at_index(idx, self.runoff_depth_mm)
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
