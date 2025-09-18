use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::data_cache::DataCache;
use crate::misc::location::Location;

#[derive(Default, Clone)]
pub struct ConfluenceNode {
    pub name: String,
    pub location: Location,

    // Internal state only
    upstream_inflow: f64,
    outflow_primary: f64,
    storage: f64,

    // Recorders
    recorder_idx_dsflow: Option<usize>,
}

impl ConfluenceNode {
    /*
    Constructor
    */
    pub fn new() -> ConfluenceNode {
        ConfluenceNode {
            name: "".to_string(),
            ..Default::default()
        }
    }
}

impl Node for ConfluenceNode {
    fn initialise(&mut self, data_cache: &mut DataCache) {
        // Initialize only internal state
        self.upstream_inflow = 0.0;
        self.outflow_primary = 0.0;
        self.storage = 0.0;

        // Initialize result recorders
        self.recorder_idx_dsflow = data_cache.get_series_idx(
            make_result_name(&self.name, "dsflow").as_str(), false
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
        // For confluence nodes, outflow equals upstream inflow
        self.outflow_primary = self.upstream_inflow;

        // Record results
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.outflow_primary);
        }

        // Reset upstream inflow for next timestep
        self.upstream_inflow = 0.0;
    }
}

