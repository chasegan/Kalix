use super::{recorder, single_outlet_node_impls, Node};
use super::rainfall_weights::RainfallWeightHandler;
use crate::hydrology::rainfall_runoff::gr4j::Gr4j;
use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::misc::location::Location;
use crate::numerical::opt::optimisable_component::OptimisableComponent;

const MAX_DS_LINKS: usize = 1;

#[derive(Default, Clone)]
pub struct Gr4jNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub rain_mm_input: DynamicInput,
    pub evap_mm_input: DynamicInput,
    pub area_km2: f64,
    pub gr4j_model: Gr4j,

    // Internal state only
    usflow: f64,
    dsflow_primary: f64,
    rain: f64,
    pet: f64,
    runoff_depth_mm: f64,
    runoff_volume_megs: f64,

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
}

impl Gr4jNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            area_km2: 1.0,
            gr4j_model: Gr4j::new(),
            ..Default::default()
        }
    }
}

impl Node for Gr4jNode {
    single_outlet_node_impls!();

    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;
        self.rain = 0.0;
        self.pet = 0.0;
        self.runoff_depth_mm = 0.0;
        self.runoff_volume_megs = 0.0;

        // Initialize the GR4J model
        self.gr4j_model.initialize();
        
        // DynamicInput fields are already initialized during parsing

        // Checks
        if self.area_km2 < 0.0 {
            let message = format!("Error in node '{}'. Catchment area cannot be negative, but was {}.", self.name, self.area_km2);
            return Err(message);
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
        self.recorder_idx_production_store_mm = recorder(data_cache, &self.name, "production_store");
        self.recorder_idx_routing_store_mm = recorder(data_cache, &self.name, "routing_store");

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str {
        &self.name  // Return reference, not owned String
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
        self.rain = self.rain_mm_input.get_value(data_cache);
        self.pet = self.evap_mm_input.get_value(data_cache);

        // Run GR4J model to get runoff
        self.runoff_depth_mm = self.gr4j_model.run_step(self.rain, self.pet);
        self.runoff_volume_megs = self.runoff_depth_mm * self.area_km2;
        self.dsflow_primary = self.usflow + self.runoff_volume_megs;

        let production_store_mm = self.gr4j_model.production_store;
        let routing_store_mm = self.gr4j_model.routing_store;

        // Update mass balance
        self.mbal += self.runoff_volume_megs;

        // Record results
        if let Some(idx) = self.recorder_idx_runoff_volume_megs {
            data_cache.add_value_at_index(idx, self.runoff_volume_megs);
        }
        if let Some(idx) = self.recorder_idx_runoff_depth_mm {
            data_cache.add_value_at_index(idx, self.runoff_depth_mm);
        }
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_ds_1 {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_rain_mm {
            data_cache.add_value_at_index(idx, self.rain);
        }
        if let Some(idx) = self.recorder_idx_evap_mm {
            data_cache.add_value_at_index(idx, self.pet);
        }
        if let Some(idx) = self.recorder_idx_production_store_mm {
            data_cache.add_value_at_index(idx, production_store_mm);
        }
        if let Some(idx) = self.recorder_idx_routing_store_mm {
            data_cache.add_value_at_index(idx, routing_store_mm);
        }

        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }




}

// ============================================================================
// OptimisableComponent Implementation
// ============================================================================

impl OptimisableComponent for Gr4jNode {
    fn set_param(&mut self, name: &str, value: f64) -> Result<(), String> {
        // Try to handle as rainfall weight parameter first
        match RainfallWeightHandler::try_set_param(&mut self.rain_mm_input, name, value, &self.name)? {
            true => return Ok(()), // Parameter was handled
            false => {} // Not a rainfall parameter, continue to standard parameters
        }

        // Standard GR4J parameters
        match name {
            "x1" => {
                self.gr4j_model.x1 = value;
                self.gr4j_model.initialize();
                Ok(())
            },
            "x2" => {
                self.gr4j_model.x2 = value;
                self.gr4j_model.initialize();
                Ok(())
            },
            "x3" => {
                self.gr4j_model.x3 = value;
                self.gr4j_model.initialize();
                Ok(())
            },
            "x4" => {
                self.gr4j_model.x4 = value;
                self.gr4j_model.initialize();  // Must reinitialize UH when x4 changes
                Ok(())
            },
            _ => Err(format!("Unknown GR4J parameter: {}", name)),
        }
    }

    fn get_param(&self, name: &str) -> Result<f64, String> {
        // Try to handle as rainfall weight parameter first
        if let Some(value) = RainfallWeightHandler::try_get_param(&self.rain_mm_input, name, &self.name)? {
            return Ok(value);
        }

        // Standard GR4J parameters
        match name {
            "x1" => Ok(self.gr4j_model.x1),
            "x2" => Ok(self.gr4j_model.x2),
            "x3" => Ok(self.gr4j_model.x3),
            "x4" => Ok(self.gr4j_model.x4),
            _ => Err(format!("Unknown GR4J parameter: {}", name)),
        }
    }

    fn list_params(&self) -> Vec<String> {
        let mut params = vec!["x1", "x2", "x3", "x4"]
            .iter()
            .map(|s| s.to_string())
            .collect::<Vec<_>>();

        // Add rainfall parameters if using linear combination
        params.extend(RainfallWeightHandler::list_params(&self.rain_mm_input));

        params
    }
}
