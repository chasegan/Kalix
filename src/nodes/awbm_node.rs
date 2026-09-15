use super::{recorder, single_outlet_node_impls, Node};
use super::rainfall_weights::RainfallWeightHandler;
use crate::hydrology::rainfall_runoff::awbm::Awbm;
use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::misc::location::Location;
use crate::numerical::opt::optimisable_component::OptimisableComponent;

const MAX_DS_LINKS: usize = 1;

/// Optimisable parameter names, in the order used by `Awbm::set_params_by_vec`.
const PARAM_NAMES: [&str; 8] = ["a1", "a2", "c1", "c2", "c3", "bfi", "k_base", "k_surf"];

#[derive(Clone)]
pub struct AwbmNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub rain_mm_input: DynamicInput,
    pub evap_mm_input: DynamicInput,
    pub area_km2: f64,
    pub awbm_model: Awbm,

    // Internal state only
    usflow: f64,
    dsflow_primary: f64,

    // Orders
    pub dsorders: [f64; MAX_DS_LINKS],

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_runoff_volume_megs: Option<usize>,
    recorder_idx_runoff_depth_mm: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
    recorder_idx_rain_mm: Option<usize>,
    recorder_idx_evap_mm: Option<usize>,
}

impl Default for AwbmNode {
    fn default() -> Self {
        Self {
            name: String::new(),
            location: Location::default(),
            mbal: 0.0,
            rain_mm_input: DynamicInput::default(),
            evap_mm_input: DynamicInput::default(),
            area_km2: 1.0,
            awbm_model: Awbm::new(),
            usflow: 0.0,
            dsflow_primary: 0.0,
            dsorders: [0.0; MAX_DS_LINKS],
            recorder_idx_usflow: None,
            recorder_idx_runoff_volume_megs: None,
            recorder_idx_runoff_depth_mm: None,
            recorder_idx_dsflow: None,
            recorder_idx_ds_1: None,
            recorder_idx_ds_1_order: None,
            recorder_idx_rain_mm: None,
            recorder_idx_evap_mm: None,
        }
    }
}

impl AwbmNode {

    /// Base constructor
    pub fn new() -> Self {
        Self::default()
    }
}

impl Node for AwbmNode {
    single_outlet_node_impls!();

    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;

        // Initialize the AWBM model
        self.awbm_model.reset();

        // DynamicInput fields are already initialized during parsing

        // Checks
        if self.area_km2 < 0.0 {
            let message = format!("Error in node '{}'. Catchment area cannot be negative, but was {}.", self.name, self.area_km2);
            return Err(message);
        }
        if let Err(message) = self.awbm_model.validate_params() {
            return Err(format!("Error in node '{}'. {}", self.name, message));
        }

        // Initialize result recorders
        self.recorder_idx_usflow = recorder(data_cache, &self.name, "usflow");
        self.recorder_idx_runoff_volume_megs = recorder(data_cache, &self.name, "runoff_volume");
        self.recorder_idx_runoff_depth_mm = recorder(data_cache, &self.name, "runoff_depth");
        self.recorder_idx_dsflow = recorder(data_cache, &self.name, "dsflow");
        self.recorder_idx_ds_1 = recorder(data_cache, &self.name, "ds_1");
        self.recorder_idx_ds_1_order = recorder(data_cache, &self.name, "ds_1_order");
        self.recorder_idx_rain_mm = recorder(data_cache, &self.name, "rain");
        self.recorder_idx_evap_mm = recorder(data_cache, &self.name, "evap");

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str {
        &self.name
    }

    fn run_order_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

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

        // Get driving data
        let rain = self.rain_mm_input.get_value(data_cache);
        let pet = self.evap_mm_input.get_value(data_cache);

        // Run AWBM model to get runoff
        let runoff_depth_mm = self.awbm_model.run_step(rain, pet);
        let runoff_volume_megs = runoff_depth_mm * self.area_km2;
        self.dsflow_primary = self.usflow + runoff_volume_megs;

        // Update mass balance
        self.mbal += runoff_volume_megs;

        // Record results
        if let Some(idx) = self.recorder_idx_runoff_volume_megs {
            data_cache.add_value_at_index(idx, runoff_volume_megs);
        }
        if let Some(idx) = self.recorder_idx_runoff_depth_mm {
            data_cache.add_value_at_index(idx, runoff_depth_mm);
        }
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_ds_1 {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_rain_mm {
            data_cache.add_value_at_index(idx, rain);
        }
        if let Some(idx) = self.recorder_idx_evap_mm {
            data_cache.add_value_at_index(idx, pet);
        }

        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }
}

// ============================================================================
// OptimisableComponent Implementation
// ============================================================================

impl OptimisableComponent for AwbmNode {
    fn set_param(&mut self, name: &str, value: f64) -> Result<(), String> {
        // Try to handle as rainfall weight parameter first
        match RainfallWeightHandler::try_set_param(&mut self.rain_mm_input, name, value, &self.name)? {
            true => return Ok(()), // Parameter was handled
            false => {} // Not a rainfall parameter, continue to standard parameters
        }

        // Standard AWBM parameters
        match PARAM_NAMES.iter().position(|&n| n == name) {
            Some(i) => {
                let mut params = self.awbm_model.get_params_as_vec();
                params[i] = value;
                self.awbm_model.set_params_by_vec(&params);
                Ok(())
            },
            None => Err(format!("Unknown AWBM parameter: {}", name)),
        }
    }

    fn get_param(&self, name: &str) -> Result<f64, String> {
        // Try to handle as rainfall weight parameter first
        if let Some(value) = RainfallWeightHandler::try_get_param(&self.rain_mm_input, name, &self.name)? {
            return Ok(value);
        }

        // Standard AWBM parameters
        match PARAM_NAMES.iter().position(|&n| n == name) {
            Some(i) => Ok(self.awbm_model.get_params_as_vec()[i]),
            None => Err(format!("Unknown AWBM parameter: {}", name)),
        }
    }

    fn list_params(&self) -> Vec<String> {
        let mut params = PARAM_NAMES
            .iter()
            .map(|s| s.to_string())
            .collect::<Vec<_>>();

        // Add rainfall parameters if using linear combination
        params.extend(RainfallWeightHandler::list_params(&self.rain_mm_input));

        params
    }
}
