use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::model_inputs::DynamicInput;
use crate::hydrology::rainfall_runoff::sacramento::Sacramento;
use crate::data_management::data_cache::DataCache;
use crate::misc::location::Location;
use crate::numerical::opt::optimisable_node::OptimisableNode;

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
    storage: f64,
    rain: f64,
    pet: f64,
    runoff_depth_mm: f64,
    runoff_volume_megs: f64,

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_runoff_volume_megs: Option<usize>,
    recorder_idx_runoff_depth_mm: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
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

    /// Base constructor with name
    pub fn new_named(name: &str) -> Self {
        Self {
            name: name.to_string(),
            area_km2: 1.0,
            sacramento_model: Sacramento::new(),
            ..Default::default()
        }
    }
}

impl Node for SacramentoNode {
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;
        self.storage = 0.0;
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
        self.recorder_idx_usflow = data_cache.get_series_idx(
            make_result_name(&self.name, "usflow").as_str(), false
        );
        self.recorder_idx_runoff_volume_megs = data_cache.get_series_idx(
            make_result_name(&self.name, "runoff_volume").as_str(), false
        );
        self.recorder_idx_runoff_depth_mm = data_cache.get_series_idx(
            make_result_name(&self.name, "runoff_depth").as_str(), false
        );
        self.recorder_idx_dsflow = data_cache.get_series_idx(
            make_result_name(&self.name, "dsflow").as_str(), false
        );
        self.recorder_idx_ds_1 = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_1").as_str(), false
        );

        //Return
        Ok(())
    }

    fn get_name(&self) -> &str {
        &self.name  // Return reference, not owned String
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {
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
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
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
}

// ============================================================================
// OptimisableNode Implementation
// ============================================================================

impl OptimisableNode for SacramentoNode {
    fn set_param(&mut self, name: &str, value: f64) -> Result<(), String> {
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
            "rserv" => {
                self.sacramento_model.rserv = value;
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
            "rserv" => Ok(self.sacramento_model.rserv),
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
        vec![
            "adimp", "lzfpm", "lzfsm", "lzpk", "lzsk", "lztwm",
            "pctim", "pfree", "rexp", "rserv", "sarva", "side",
            "ssout", "uzfwm", "uzk", "uztwm", "zperc", "laguh"
        ]
        .iter()
        .map(|s| s.to_string())
        .collect()
    }
}
