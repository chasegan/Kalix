use super::Node;
use crate::hydrology::rainfall_runoff::gr4j::Gr4j;
use crate::misc::misc_functions::make_result_name;
use crate::misc::input_data_definition::InputDataDefinition;
use crate::data_cache::DataCache;
use crate::misc::location::Location;

#[derive(Default, Clone)]
pub struct Gr4jNode {
    pub name: String,
    pub location: Location,
    pub rain_mm_def: InputDataDefinition,
    pub evap_mm_def: InputDataDefinition,
    pub area_km2: f64,
    pub gr4j_model: Gr4j,

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

    /// Base constructor with name
    pub fn new_named(name: &str) -> Self {
        Self {
            name: name.to_string(),
            area_km2: 1.0,
            gr4j_model: Gr4j::new(),
            ..Default::default()
        }
    }
}

impl Node for Gr4jNode {
    fn initialise(&mut self, data_cache: &mut DataCache) {
        // Initialize only internal state
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;
        self.storage = 0.0;
        self.rain = 0.0;
        self.pet = 0.0;
        self.runoff_depth_mm = 0.0;
        self.runoff_volume_megs = 0.0;

        // Initialize input series
        self.rain_mm_def.add_series_to_data_cache_if_required_and_get_idx(data_cache, true);
        self.evap_mm_def.add_series_to_data_cache_if_required_and_get_idx(data_cache, true);

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
    }

    fn get_name(&self) -> &str {
        &self.name  // Return reference, not owned String
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {
        // Get driving data
        if let Some(idx) = self.rain_mm_def.idx {
            self.rain = data_cache.get_current_value(idx);
        }
        if let Some(idx) = self.evap_mm_def.idx {
            self.pet = data_cache.get_current_value(idx);
        }

        // Run GR4J model to get runoff
        self.runoff_depth_mm = self.gr4j_model.run_step(self.rain, self.pet);
        self.runoff_volume_megs = self.runoff_depth_mm * self.area_km2;
        self.dsflow_primary = self.usflow + self.runoff_volume_megs;

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
}
