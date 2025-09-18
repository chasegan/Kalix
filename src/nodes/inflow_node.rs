use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::misc::input_data_definition::InputDataDefinition;
use crate::data_cache::DataCache;
use crate::misc::location::Location;

#[derive(Default, Clone)]
pub struct InflowNode {
    pub name: String,
    pub location: Location,
    pub inflow_def: InputDataDefinition,

    // Internal state only
    upstream_inflow: f64,
    lateral_inflow: f64,
    outflow_primary: f64,
    storage: f64,

    // Recorders
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
            ..Default::default()
        }
    }
}

impl Node for InflowNode {
    fn initialise(&mut self, data_cache: &mut DataCache) {
        // Initialize only internal state
        self.upstream_inflow = 0.0;
        self.lateral_inflow = 0.0;
        self.outflow_primary = 0.0;
        self.storage = 0.0;

        // Initialize inflow series
        self.inflow_def.add_series_to_data_cache_if_required_and_get_idx(data_cache, true);

        // Initialize result recorders
        self.recorder_idx_dsflow = data_cache.get_series_idx(
            make_result_name(&self.name, "dsflow").as_str(), false
        );
        self.recorder_idx_usflow = data_cache.get_series_idx(
            make_result_name(&self.name, "usflow").as_str(), false
        );
        self.recorder_idx_inflow = data_cache.get_series_idx(
            make_result_name(&self.name, "inflow").as_str(), false
        );
    }

    fn get_name(&self) -> &str {
        &self.name  // Return reference, not owned String
    }

    fn add_inflow(&mut self, flow: f64, _inlet: u8) {
        self.upstream_inflow += flow;
    }

    fn get_outflow(&mut self, outlet: u8) -> f64 {
        match outlet {
            0 => {
                let outflow = self.outflow_primary;
                self.outflow_primary = 0.0;
                outflow
            }
            _ => 0.0,
        }
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {
        // Get lateral inflow from input data
        if let Some(idx) = self.inflow_def.idx {
            self.lateral_inflow = data_cache.get_current_value(idx);
        }

        // Compute outflow based on inflow
        self.outflow_primary = self.upstream_inflow + self.lateral_inflow;

        // Record results
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.outflow_primary);
        }
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.upstream_inflow);
        }
        if let Some(idx) = self.recorder_idx_inflow {
            data_cache.add_value_at_index(idx, self.lateral_inflow);
        }

        // Reset upstream inflow for next timestep
        self.upstream_inflow = 0.0;
    }
}
