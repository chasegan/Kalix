use super::{recorder, single_outlet_node_impls, Node};
use super::rainfall_weights::RainfallWeightHandler;
use crate::model_inputs::DynamicInput;
use crate::hydrology::rainfall_runoff::sacramento::Sacramento;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::misc::location::Location;
use crate::numerical::opt::optimisable_component::OptimisableComponent;

const MAX_DS_LINKS: usize = 1;

#[derive(Default, Clone)]
pub struct SacramentoNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub rain_mm_input: DynamicInput,
    pub evap_mm_input: DynamicInput,
    pub area_km2: f64,
    pub sacramento_model: Sacramento,

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
    recorder_idx_rain_mm: Option<usize>,
    recorder_idx_evap_mm: Option<usize>,
    recorder_idx_roimp: Option<usize>,
    recorder_idx_flosf: Option<usize>,
    recorder_idx_flobf: Option<usize>,
    recorder_idx_floin: Option<usize>,
    recorder_idx_runoff_volume_megs: Option<usize>,
    recorder_idx_runoff_depth_mm: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
}

impl SacramentoNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            area_km2: 1.0,
            sacramento_model: Sacramento::new(),
            ..Default::default()
        }
    }
}

impl Node for SacramentoNode {
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

        // Initialize inner Sacramento model
        self.sacramento_model.initialize_state_empty();

        // DynamicInput fields are already initialized during parsing

        // Checks
        if self.area_km2 < 0.0 {
            let message = format!("Error in node '{}'. Catchment area cannot be negative, but was {}.", self.name, self.area_km2);
            return Err(message);
        }

        // Initialize result recorders
        self.recorder_idx_usflow = recorder(data_cache, &self.name, "usflow");
        self.recorder_idx_rain_mm = recorder(data_cache, &self.name, "rain");
        self.recorder_idx_evap_mm = recorder(data_cache, &self.name, "evap");
        self.recorder_idx_roimp = recorder(data_cache, &self.name, "roimp");
        self.recorder_idx_flosf = recorder(data_cache, &self.name, "flosf");
        self.recorder_idx_flobf = recorder(data_cache, &self.name, "flobf");
        self.recorder_idx_floin = recorder(data_cache, &self.name, "floin");
        self.recorder_idx_runoff_volume_megs = recorder(data_cache, &self.name, "runoff_volume");
        self.recorder_idx_runoff_depth_mm = recorder(data_cache, &self.name, "runoff_depth");
        self.recorder_idx_dsflow = recorder(data_cache, &self.name, "dsflow");
        self.recorder_idx_ds_1 = recorder(data_cache, &self.name, "ds_1");
        self.recorder_idx_ds_1_order = recorder(data_cache, &self.name, "ds_1_order");

        //Return
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
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

        // Get driving data
        self.rain = self.rain_mm_input.get_value(data_cache);
        self.pet = self.evap_mm_input.get_value(data_cache);

        // Run Sacramento model to get runoff
        self.runoff_depth_mm = self.sacramento_model.run_step(self.rain, self.pet);
        self.runoff_volume_megs = self.runoff_depth_mm * self.area_km2;
        self.dsflow_primary = self.usflow + self.runoff_volume_megs;

        // Update mass balance
        self.mbal += self.runoff_volume_megs;

        // Record results
        if let Some(idx) = self.recorder_idx_rain_mm {
            data_cache.add_value_at_index(idx, self.rain);
        }
        if let Some(idx) = self.recorder_idx_evap_mm {
            data_cache.add_value_at_index(idx, self.pet);
        }
        if let Some(idx) = self.recorder_idx_roimp {
            data_cache.add_value_at_index(idx, self.sacramento_model.roimp * self.area_km2);
        }
        if let Some(idx) = self.recorder_idx_flosf {
            data_cache.add_value_at_index(idx, self.sacramento_model.flosf * self.area_km2);
        }
        if let Some(idx) = self.recorder_idx_floin {
            data_cache.add_value_at_index(idx, self.sacramento_model.floin * self.area_km2);
        }
        if let Some(idx) = self.recorder_idx_flobf {
            data_cache.add_value_at_index(idx, self.sacramento_model.flobf * self.area_km2);
        }
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

        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }




}

// ============================================================================
// OptimisableComponent Implementation
// ============================================================================

impl OptimisableComponent for SacramentoNode {
    fn set_param(&mut self, name: &str, value: f64) -> Result<(), String> {
        // Try to handle as rainfall weight parameter first
        match RainfallWeightHandler::try_set_param(&mut self.rain_mm_input, name, value, &self.name)? {
            true => return Ok(()), // Parameter was handled
            false => {} // Not a rainfall parameter, continue to standard parameters
        }

        // Standard Sacramento parameters
        match name {
            "adimp" => {
                self.sacramento_model.adimp = value;
                Ok(())
            },
            "lzfpm" => {
                self.sacramento_model.lzfpm = value;
                Ok(())
            },
            "lzfsm" => {
                self.sacramento_model.lzfsm = value;
                Ok(())
            },
            "lzpk" => {
                self.sacramento_model.lzpk = value;
                Ok(())
            },
            "lzsk" => {
                self.sacramento_model.lzsk = value;
                Ok(())
            },
            "lztwm" => {
                self.sacramento_model.lztwm = value;
                Ok(())
            },
            "pctim" => {
                self.sacramento_model.pctim = value;
                Ok(())
            },
            "pfree" => {
                self.sacramento_model.pfree = value;
                Ok(())
            },
            "rexp" => {
                self.sacramento_model.rexp = value;
                Ok(())
            },
            "sarva" => {
                self.sacramento_model.sarva = value;
                Ok(())
            },
            "side" => {
                self.sacramento_model.side = value;
                Ok(())
            },
            "ssout" => {
                self.sacramento_model.ssout = value;
                Ok(())
            },
            "uzfwm" => {
                self.sacramento_model.uzfwm = value;
                Ok(())
            },
            "uzk" => {
                self.sacramento_model.uzk = value;
                Ok(())
            },
            "uztwm" => {
                self.sacramento_model.uztwm = value;
                Ok(())
            },
            "zperc" => {
                self.sacramento_model.zperc = value;
                Ok(())
            },
            "laguh" => {
                self.sacramento_model.set_laguh(value);
                Ok(())
            },
            _ => Err(format!("Unknown Sacramento parameter: {}", name)),
        }
    }

    fn get_param(&self, name: &str) -> Result<f64, String> {
        // Try to handle as rainfall weight parameter first
        if let Some(value) = RainfallWeightHandler::try_get_param(&self.rain_mm_input, name, &self.name)? {
            return Ok(value);
        }

        // Standard Sacramento parameters
        match name {
            "adimp" => Ok(self.sacramento_model.adimp),
            "lzfpm" => Ok(self.sacramento_model.lzfpm),
            "lzfsm" => Ok(self.sacramento_model.lzfsm),
            "lzpk" => Ok(self.sacramento_model.lzpk),
            "lzsk" => Ok(self.sacramento_model.lzsk),
            "lztwm" => Ok(self.sacramento_model.lztwm),
            "pctim" => Ok(self.sacramento_model.pctim),
            "pfree" => Ok(self.sacramento_model.pfree),
            "rexp" => Ok(self.sacramento_model.rexp),
            "sarva" => Ok(self.sacramento_model.sarva),
            "side" => Ok(self.sacramento_model.side),
            "ssout" => Ok(self.sacramento_model.ssout),
            "uzfwm" => Ok(self.sacramento_model.uzfwm),
            "uzk" => Ok(self.sacramento_model.uzk),
            "uztwm" => Ok(self.sacramento_model.uztwm),
            "zperc" => Ok(self.sacramento_model.zperc),
            "laguh" => Ok(self.sacramento_model.get_laguh()),
            _ => Err(format!("Unknown Sacramento parameter: {}", name)),
        }
    }

    fn list_params(&self) -> Vec<String> {
        let mut params = vec![
            "adimp", "lzfpm", "lzfsm", "lzpk", "lzsk", "lztwm",
            "pctim", "pfree", "rexp", "sarva", "side",
            "ssout", "uzfwm", "uzk", "uztwm", "zperc", "laguh"
        ]
        .iter()
        .map(|s| s.to_string())
        .collect::<Vec<_>>();

        // Add rainfall parameters if using linear combination
        params.extend(RainfallWeightHandler::list_params(&self.rain_mm_input));

        params
    }
}
