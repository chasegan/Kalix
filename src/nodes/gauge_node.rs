use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::data_management::data_cache::DataCache;
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
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;

        //DynamicInput is already initialized during parsing

        // Initialize result recorders
        self.recorder_idx_delta = data_cache.get_series_idx(
            make_result_name(&self.name, "delta").as_str(), false
        );
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

    fn get_name(&self) -> &str {
        &self.name
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {

        // Force flows if required, otherwise pass upstream value
        match self.force_flow_input {
            DynamicInput::None { .. } => {
                self.dsflow_primary = self.usflow;
            },
            _ => {
                let force_flow_value = self.force_flow_input.get_value(data_cache);
                if force_flow_value.is_nan() {
                    self.dsflow_primary = self.usflow;
                } else {
                    self.dsflow_primary = force_flow_value;
                    self.mbal += (self.dsflow_primary - self.usflow);
                }
            }
        }

        // Record results
        if let Some(idx) = self.recorder_idx_delta {
            let mut reference_flow_value = f64::NAN;
            match self.reference_flow_input {
                DynamicInput::None { .. } => {},
                _ => { reference_flow_value = self.reference_flow_input.get_value(data_cache); }
            }
            let delta = self.usflow - reference_flow_value;
            data_cache.add_value_at_index(idx, delta);
        }
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_ds_1 {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.dsorders[0]);
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

    fn get_mass_balance(&self) -> f64 {
        self.mbal
    }

    fn dsorders_mut(&mut self) -> &mut [f64] {
        &mut self.dsorders
    }
}