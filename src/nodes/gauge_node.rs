use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::data_cache::DataCache;
use crate::model_inputs::InputDataDefinition;
use crate::misc::location::Location;

#[derive(Default, Clone)]
pub struct GaugeNode {
    pub name: String,
    pub location: Location,
    pub observed_flow_def: InputDataDefinition,

    // Internal state only
    usflow: f64,
    dsflow_primary: f64,

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
}

impl GaugeNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            ..Default::default()
        }
    }

    /// Base constructor with node name
    pub fn new_named(name: &str) -> Self {
        Self {
            name: name.to_string(),
            ..Default::default()
        }
    }
}

impl Node for GaugeNode {
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(), String> {
        // Initialize only internal state
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;

        //Initialize input series
        self.observed_flow_def.add_series_to_data_cache_if_required_and_get_idx(data_cache, true);

        // Initialize result recorders
        self.recorder_idx_usflow = data_cache.get_series_idx(
            make_result_name(&self.name, "usflow").as_str(), false
        );
        self.recorder_idx_dsflow = data_cache.get_series_idx(
            make_result_name(&self.name, "dsflow").as_str(), false
        );
        self.recorder_idx_ds_1 = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_1").as_str(), false
        );

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str {
        &self.name
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {
        // For gauge nodes, outflow equals upstream inflow (same as confluence)
        self.dsflow_primary = self.usflow;

        // // Get the observed flows
        // let mut observed_flow = 0_f64;
        // if let Some(idx) = self.observed_flow_def.idx {
        //     observed_flow = data_cache.get_current_value(idx);
        // }

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_ds_1 {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }

        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }

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
}