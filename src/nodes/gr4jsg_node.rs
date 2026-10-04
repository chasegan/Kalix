use super::{recorder, single_outlet_node_impls, Node};
use super::rainfall_weights::RainfallWeightHandler;
use crate::hydrology::rainfall_runoff::gr4jsg::Gr4jsg;
use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::misc::location::Location;
use crate::numerical::opt::optimisable_component::OptimisableComponent;

const MAX_DS_LINKS: usize = 1;

/// Optimisable parameter names of every gr4jsg node.
const PARAM_NAMES: [&str; 9] = ["x1", "x2", "x3", "x4", "tfrac", "taccum", "m_rainfall", "base_rainfall", "m_nonrainfall"];

/// Optimisable parameter names of a node with a glacier (`ice_params`) only.
const GLACIER_PARAM_NAMES: [&str; 4] = ["ddfi", "tmelt", "return_flow", "accumulation"];

#[derive(Default, Clone)]
pub struct Gr4jsgNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub rain_mm_input: DynamicInput,
    pub evap_mm_input: DynamicInput,
    pub tmax_input: DynamicInput,
    pub tmin_input: DynamicInput,
    pub area_km2: f64,
    pub gr4jsg_model: Gr4jsg,

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
    recorder_idx_evap_mm: Option<usize>,
    recorder_idx_rain_mm: Option<usize>,
    recorder_idx_production_store_mm: Option<usize>,
    recorder_idx_routing_store_mm: Option<usize>,
    recorder_idx_snow_store_mm: Option<usize>,
    recorder_idx_snowfall_mm: Option<usize>,
    recorder_idx_snow_melt_mm: Option<usize>,
    recorder_idx_ice_store_mm: Option<usize>,
    recorder_idx_ice_melt_mm: Option<usize>,
}

impl Gr4jsgNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            area_km2: 1.0,
            gr4jsg_model: Gr4jsg::new(),
            ..Default::default()
        }
    }

    /// The glacier parameters are only addressable on a node that has a glacier.
    fn check_glacier_param(&self, name: &str) -> Result<(), String> {
        if GLACIER_PARAM_NAMES.contains(&name) && !self.gr4jsg_model.glacier {
            return Err(format!("GR4JSG parameter '{}' needs a glacier, but node '{}' has no ice_params", name, self.name));
        }
        Ok(())
    }
}

impl Node for Gr4jsgNode {
    single_outlet_node_impls!();

    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;

        // DynamicInput fields are already initialized during parsing

        // Checks
        if self.area_km2 < 0.0 {
            let message = format!("Error in node '{}'. Catchment area cannot be negative, but was {}.", self.name, self.area_km2);
            return Err(message);
        }
        // A missing temperature would otherwise read as 0 °C
        if self.tmax_input.to_string().is_empty() || self.tmin_input.to_string().is_empty() {
            return Err(format!("Error in node '{}'. A gr4jsg node needs both 'tmax' and 'tmin'.", self.name));
        }
        if let Err(message) = self.gr4jsg_model.validate_params() {
            return Err(format!("Error in node '{}'. {}", self.name, message));
        }

        // Initialize the GR4JSG model (after the checks: the unit hydrographs depend on the parameters)
        self.gr4jsg_model.initialize();

        // Initialize result recorders
        self.recorder_idx_usflow = recorder(data_cache, &self.name, "usflow");
        self.recorder_idx_runoff_volume_megs = recorder(data_cache, &self.name, "runoff_volume");
        self.recorder_idx_runoff_depth_mm = recorder(data_cache, &self.name, "runoff_depth");
        self.recorder_idx_dsflow = recorder(data_cache, &self.name, "dsflow");
        self.recorder_idx_ds_1 = recorder(data_cache, &self.name, "ds_1");
        self.recorder_idx_ds_1_order = recorder(data_cache, &self.name, "ds_1_order");
        self.recorder_idx_rain_mm = recorder(data_cache, &self.name, "rain");
        self.recorder_idx_evap_mm = recorder(data_cache, &self.name, "evap");
        self.recorder_idx_production_store_mm = recorder(data_cache, &self.name, "production_store");
        self.recorder_idx_routing_store_mm = recorder(data_cache, &self.name, "routing_store");
        self.recorder_idx_snow_store_mm = recorder(data_cache, &self.name, "snow_store");
        self.recorder_idx_snowfall_mm = recorder(data_cache, &self.name, "snowfall");
        self.recorder_idx_snow_melt_mm = recorder(data_cache, &self.name, "snow_melt");
        self.recorder_idx_ice_store_mm = recorder(data_cache, &self.name, "ice_store");
        self.recorder_idx_ice_melt_mm = recorder(data_cache, &self.name, "ice_melt");

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
        let tmax = self.tmax_input.get_value(data_cache);
        let tmin = self.tmin_input.get_value(data_cache);

        // Run GR4JSG model to get runoff
        let runoff_depth_mm = self.gr4jsg_model.run_step(rain, pet, tmax, tmin);
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
        if let Some(idx) = self.recorder_idx_production_store_mm {
            data_cache.add_value_at_index(idx, self.gr4jsg_model.gr4j.production_store);
        }
        if let Some(idx) = self.recorder_idx_routing_store_mm {
            data_cache.add_value_at_index(idx, self.gr4jsg_model.gr4j.routing_store);
        }
        if let Some(idx) = self.recorder_idx_snow_store_mm {
            data_cache.add_value_at_index(idx, self.gr4jsg_model.snow_store);
        }
        if let Some(idx) = self.recorder_idx_snowfall_mm {
            data_cache.add_value_at_index(idx, self.gr4jsg_model.snowfall);
        }
        if let Some(idx) = self.recorder_idx_snow_melt_mm {
            data_cache.add_value_at_index(idx, self.gr4jsg_model.snow_melt);
        }
        if let Some(idx) = self.recorder_idx_ice_store_mm {
            data_cache.add_value_at_index(idx, self.gr4jsg_model.ice_store);
        }
        if let Some(idx) = self.recorder_idx_ice_melt_mm {
            data_cache.add_value_at_index(idx, self.gr4jsg_model.ice_melt);
        }

        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }
}

// ============================================================================
// OptimisableComponent Implementation
// ============================================================================

impl OptimisableComponent for Gr4jsgNode {
    fn set_param(&mut self, name: &str, value: f64) -> Result<(), String> {
        // Try to handle as rainfall weight parameter first
        match RainfallWeightHandler::try_set_param(&mut self.rain_mm_input, name, value, &self.name)? {
            true => return Ok(()), // Parameter was handled
            false => {} // Not a rainfall parameter, continue to standard parameters
        }

        // Standard GR4JSG parameters. The unit hydrographs are rebuilt in initialise.
        self.check_glacier_param(name)?;
        let m = &mut self.gr4jsg_model;
        match name {
            "x1" => m.gr4j.x1 = value,
            "x2" => m.gr4j.x2 = value,
            "x3" => m.gr4j.x3 = value,
            "x4" => m.gr4j.x4 = value,
            "tfrac" => m.tfrac = value,
            "taccum" => m.taccum = value,
            "m_rainfall" => m.m_rainfall = value,
            "base_rainfall" => m.base_rainfall = value,
            "m_nonrainfall" => m.m_nonrainfall = value,
            "ddfi" => m.ddfi = value,
            "tmelt" => m.tmelt = value,
            "return_flow" => m.return_flow = value,
            "accumulation" => m.accumulation = value,
            _ => return Err(format!("Unknown GR4JSG parameter: {}", name)),
        }
        Ok(())
    }

    fn get_param(&self, name: &str) -> Result<f64, String> {
        // Try to handle as rainfall weight parameter first
        if let Some(value) = RainfallWeightHandler::try_get_param(&self.rain_mm_input, name, &self.name)? {
            return Ok(value);
        }

        // Standard GR4JSG parameters
        self.check_glacier_param(name)?;
        let m = &self.gr4jsg_model;
        match name {
            "x1" => Ok(m.gr4j.x1),
            "x2" => Ok(m.gr4j.x2),
            "x3" => Ok(m.gr4j.x3),
            "x4" => Ok(m.gr4j.x4),
            "tfrac" => Ok(m.tfrac),
            "taccum" => Ok(m.taccum),
            "m_rainfall" => Ok(m.m_rainfall),
            "base_rainfall" => Ok(m.base_rainfall),
            "m_nonrainfall" => Ok(m.m_nonrainfall),
            "ddfi" => Ok(m.ddfi),
            "tmelt" => Ok(m.tmelt),
            "return_flow" => Ok(m.return_flow),
            "accumulation" => Ok(m.accumulation),
            _ => Err(format!("Unknown GR4JSG parameter: {}", name)),
        }
    }

    fn list_params(&self) -> Vec<String> {
        let mut params = PARAM_NAMES
            .iter()
            .map(|s| s.to_string())
            .collect::<Vec<_>>();

        // The glacier parameters exist only on a node with ice_params
        if self.gr4jsg_model.glacier {
            params.extend(GLACIER_PARAM_NAMES.iter().map(|s| s.to_string()));
        }

        // Add rainfall parameters if using linear combination
        params.extend(RainfallWeightHandler::list_params(&self.rain_mm_input));

        params
    }
}
