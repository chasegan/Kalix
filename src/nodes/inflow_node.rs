use super::{recorder, single_outlet_node_impls, Node};
use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::misc::location::Location;

const MAX_DS_LINKS: usize = 1;

#[derive(Default, Clone)]
pub struct InflowNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub inflow_input: DynamicInput,
    pub expected_inflow_input: DynamicInput,

    // Internal state only
    usflow: f64,
    inflow_value: f64,
    dsflow_primary: f64,

    // Properties and internal state - regulated demands and ordering
    pub dsorders: [f64; MAX_DS_LINKS],
    pub usorders: f64,

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_inflow: Option<usize>,
    recorder_idx_expected_inflow: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
}

impl InflowNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            //recession_factor: 0.0,
            ..Default::default()
        }
    }
}

impl Node for InflowNode {
    single_outlet_node_impls!();

    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.inflow_value = 0.0;
        self.dsflow_primary = 0.0;

        // DynamicInput is already initialized during parsing

        // Initialize result recorders
        self.recorder_idx_usflow = recorder(data_cache, &self.name, "usflow");
        self.recorder_idx_inflow = recorder(data_cache, &self.name, "inflow");
        self.recorder_idx_expected_inflow = recorder(data_cache, &self.name, "expected_inflow");
        self.recorder_idx_dsflow = recorder(data_cache, &self.name, "dsflow");
        self.recorder_idx_ds_1 = recorder(data_cache, &self.name, "ds_1");
        self.recorder_idx_ds_1_order = recorder(data_cache, &self.name, "ds_1_order");

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str {
        &self.name  // Return reference, not owned String
    }

    fn run_order_phase(&mut self, data_cache: &mut DataCache) {

        // Record downstream orders
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.dsorders[0]);
        }

        // Evaluate expected inflow
        let expected_inflow_value_on_delivery_timestep = self.expected_inflow_input.get_value(data_cache);
        if let Some(idx) = self.recorder_idx_expected_inflow {
            data_cache.add_value_at_index(idx, expected_inflow_value_on_delivery_timestep);
        }

        // Calculate upstream order
        self.usorders = (self.dsorders[0] - expected_inflow_value_on_delivery_timestep).max(0f64);
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

        // Get lateral inflow
        self.inflow_value = self.inflow_input.get_value(data_cache);

        // Compute outflow based on inflow
        self.dsflow_primary = self.usflow + self.inflow_value;

        // Update mass balance
        self.mbal += self.inflow_value;
        
        // Record results
        if let Some(idx) = self.recorder_idx_inflow {
            data_cache.add_value_at_index(idx, self.inflow_value);
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




}
