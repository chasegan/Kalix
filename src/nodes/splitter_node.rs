use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::numerical::table::Table;
use crate::data_management::data_cache::DataCache;
use crate::misc::location::Location;

const MAX_DS_LINKS: usize = 5;

#[derive(Default, Clone)]
pub struct SplitterNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub splitter_table: Table,  // By default, the columns mean Inflow Rate ML, Effluent Rate ML (maybe ways to override this later)

    // Internal state only
    usflow: f64,
    ds_1_flow: f64,
    ds_2_flow: f64,

    // Orders
    pub dsorders: [f64; MAX_DS_LINKS],

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
    recorder_idx_ds_2: Option<usize>,
    recorder_idx_ds_2_order: Option<usize>,
}

impl SplitterNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            ..Default::default()
        }
    }
}

impl Node for SplitterNode {
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.ds_1_flow = 0.0;
        self.ds_2_flow = 0.0;

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
        self.recorder_idx_ds_2 = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_2").as_str(), false
        );
        self.recorder_idx_ds_2_order = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_2_order").as_str(), false
        );

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str {
        &self.name
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {

        // Determine effluent flow
        self.ds_2_flow = self.splitter_table.interpolate(0, 1, self.usflow);
        self.ds_1_flow = self.usflow - self.ds_2_flow;
        if self.ds_1_flow < 0f64 {
            panic!("Negative ds_1 flow at '{}' when usflow={}", self.name, self.usflow);
        }

        // Update mass balance
        // self.mbal = 0.0; // This is always zero for Splitter nodes. The water on ds_2 is not lost in this node.

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.usflow); //Total dsflow is same as usflow
        }
        if let Some(idx) = self.recorder_idx_ds_1 {
            data_cache.add_value_at_index(idx, self.ds_1_flow);
        }
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.dsorders[0]);
        }
        if let Some(idx) = self.recorder_idx_ds_2 {
            data_cache.add_value_at_index(idx, self.ds_2_flow);
        }
        if let Some(idx) = self.recorder_idx_ds_2_order {
            data_cache.add_value_at_index(idx, self.dsorders[1]);
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
                let outflow = self.ds_1_flow;
                self.ds_1_flow = 0.0;
                outflow
            }
            1 => {
                let outflow = self.ds_2_flow;
                self.ds_2_flow = 0.0;
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