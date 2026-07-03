use super::{recorder, single_outlet_node_impls, Node};
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::model_inputs::DynamicInput;
use crate::misc::location::Location;

const MAX_DS_LINKS: usize = 1;

#[derive(Default, Clone)]
pub struct GaugeNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub force_flow_input: DynamicInput,
    pub reference_flow_input: DynamicInput,

    // Internal state only
    usflow: f64,
    dsflow_primary: f64,

    // Orders
    pub dsorders: [f64; MAX_DS_LINKS],

    // Recorders
    recorder_idx_delta: Option<usize>,
    recorder_idx_usflow: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
    recorder_idx_force_flow: Option<usize>,
    recorder_idx_reference_flow: Option<usize>,
}

impl GaugeNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            ..Default::default()
        }
    }
}

impl Node for GaugeNode {
    single_outlet_node_impls!();

    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;

        //DynamicInput is already initialized during parsing

        // Initialize result recorders
        self.recorder_idx_delta = recorder(data_cache, &self.name, "delta");
        self.recorder_idx_usflow = recorder(data_cache, &self.name, "usflow");
        self.recorder_idx_dsflow = recorder(data_cache, &self.name, "dsflow");
        self.recorder_idx_ds_1 = recorder(data_cache, &self.name, "ds_1");
        self.recorder_idx_ds_1_order = recorder(data_cache, &self.name, "ds_1_order");
        self.recorder_idx_force_flow = recorder(data_cache, &self.name, "force_flow");
        self.recorder_idx_reference_flow = recorder(data_cache, &self.name, "reference_flow");

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str {
        &self.name
    }

    fn run_order_phase(&mut self, data_cache: &mut DataCache) {

        // Record downstream orders
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.dsorders[0]);
        }
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

        // Force flows if required, otherwise pass upstream value
        let force_flow_value = match self.force_flow_input {
            DynamicInput::None { .. } => f64::NAN,
            _ => self.force_flow_input.get_value(data_cache),
        };
        if force_flow_value.is_nan() {
            self.dsflow_primary = self.usflow;
        } else {
            self.dsflow_primary = force_flow_value;
            self.mbal += self.dsflow_primary - self.usflow;
        }

        // Record results
        if let Some(idx) = self.recorder_idx_force_flow {
            data_cache.add_value_at_index(idx, force_flow_value);
        }
        let needs_reference_flow = self.recorder_idx_delta.is_some() || self.recorder_idx_reference_flow.is_some();
        let reference_flow_value = if needs_reference_flow {
            match self.reference_flow_input {
                DynamicInput::None { .. } => f64::NAN,
                _ => self.reference_flow_input.get_value(data_cache),
            }
        } else {
            f64::NAN
        };
        if let Some(idx) = self.recorder_idx_delta {
            data_cache.add_value_at_index(idx, self.usflow - reference_flow_value);
        }
        if let Some(idx) = self.recorder_idx_reference_flow {
            data_cache.add_value_at_index(idx, reference_flow_value);
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