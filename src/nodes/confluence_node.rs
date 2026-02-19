use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::data_management::data_cache::DataCache;
use crate::misc::location::Location;
use crate::model_inputs::DynamicInput;
use crate::numerical::fifo_buffer::FifoBuffer;

const MAX_US_LINKS: usize = 2; //TODO: not sure how to police this
const MAX_DS_LINKS: usize = 1;

#[derive(Default, Clone)]
pub struct ConfluenceNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,

    // Harmony fraction
    pub harmony_fraction: DynamicInput,
    pub harmony_fraction_value: f64,
    pub remaining_order: f64,

    // Internal state for order delays
    pub us_1_link_idx: Option<usize>,
    pub us_1_lag: usize,
    pub us_2_lag: usize,
    pub us_1_order_buffer: FifoBuffer, //The order buffers are used to lag orders directed up the short
    pub us_2_order_buffer: FifoBuffer, //pathway. At least one of these buffers will have zero length.
    pub order_prepared_for_us_1_buffer: Option<f64>,
    pub order_prepared_for_us_2_buffer: Option<f64>,

    // Internal state only
    usflow: f64,
    dsflow_primary: f64,

    // Orders
    pub dsorders: [f64; MAX_DS_LINKS],

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
}

impl ConfluenceNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            ..Default::default()
        }
    }
}

impl Node for ConfluenceNode {
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;

        // Harmony
        self.harmony_fraction_value = 1.0; //100% for link 1. This will be overwritten anyway.
        self.remaining_order = 0.0;

        // State
        self.us_1_link_idx = None;
        self.us_1_lag = 0;
        self.us_2_lag = 0;
        self.us_1_order_buffer = FifoBuffer::default();
        self.us_2_order_buffer = FifoBuffer::default();

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
        self.recorder_idx_ds_1_order = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_1_order").as_str(), false
        );

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str { &self.name }

    fn run_pre_order_phase(&mut self, data_cache: &mut DataCache) {

        // Record downstream orders
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.dsorders[0]);
        }
    }

    fn run_post_order_phase(&mut self, data_cache: &mut DataCache) {
        // Nothing
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

        // For confluence nodes, outflow equals upstream inflow
        self.dsflow_primary = self.usflow;

        // Update mass balance
        // self.mbal = 0.0; // This is always zero for Confluence nodes

        // Record results
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_ds_1 {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        // if let Some(idx) = self.recorder_idx_ds_1_order {
        //     data_cache.add_value_at_index(idx, self.dsorders[0]);
        // }

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

    fn get_mass_balance(&self) -> f64 {
        self.mbal
    }

    fn dsorders_mut(&mut self) -> &mut [f64] {
        &mut self.dsorders
    }
}

