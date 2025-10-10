use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::model_inputs::DynamicInput;
use crate::data_cache::DataCache;
use crate::misc::location::Location;

#[derive(Default, Clone)]
pub struct InflowNode {
    pub name: String,
    pub location: Location,
    pub inflow_input: DynamicInput,

    // Internal state only
    usflow: f64,
    lateral_inflow: f64,
    dsflow_primary: f64,
    storage: f64,

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_inflow: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
}

impl InflowNode {

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

impl Node for InflowNode {
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(), String> {
        // Initialize only internal state
        self.usflow = 0.0;
        self.lateral_inflow = 0.0;
        self.dsflow_primary = 0.0;
        self.storage = 0.0;

        // DynamicInput is already initialized during parsing

        // Initialize result recorders
        self.recorder_idx_usflow = data_cache.get_series_idx(
            make_result_name(&self.name, "usflow").as_str(), false
        );
        self.recorder_idx_inflow = data_cache.get_series_idx(
            make_result_name(&self.name, "inflow").as_str(), false
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
        &self.name  // Return reference, not owned String
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {
        // Get lateral inflow from input data
        self.lateral_inflow = self.inflow_input.get_value(data_cache);

        // Compute outflow based on inflow
        self.dsflow_primary = self.usflow + self.lateral_inflow;

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }
        if let Some(idx) = self.recorder_idx_inflow {
            data_cache.add_value_at_index(idx, self.lateral_inflow);
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
