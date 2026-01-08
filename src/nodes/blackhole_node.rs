use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::data_management::data_cache::DataCache;
use crate::misc::location::Location;

const MAX_DS_LINKS: usize = 5;
const MAX_US_LINKS: usize = 5;

#[derive(Default, Clone)]
pub struct BlackholeNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,

    // Internal state only
    usflow: f64,

    // Orders
    pub dsorders: [f64; MAX_DS_LINKS],
    pub usorders: [f64; MAX_US_LINKS],

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
}

impl BlackholeNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            ..Default::default()
        }
    }
}

impl Node for BlackholeNode {
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;

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

    fn get_name(&self) -> &str { &self.name }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {

        // Update mass balance
        self.mbal -= self.usflow; // All the water goes behind the event horizon

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, 0f64);
        }
        if let Some(idx) = self.recorder_idx_ds_1 {
            data_cache.add_value_at_index(idx, 0f64);
        }

        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }

    fn add_usflow(&mut self, flow: f64, _inlet: u8) {
        self.usflow += flow;
    }

    #[allow(unused_variables)]
    fn remove_dsflow(&mut self, outlet: u8) -> f64 {
        0f64
    }

    fn get_mass_balance(&self) -> f64 {
        self.mbal
    }

    fn dsorders_mut(&mut self) -> &mut [f64] {
        &mut self.dsorders
    }

    fn usorders_mut(&mut self) -> &mut [f64] {
        &mut self.usorders
    }
}