use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::numerical::table::Table;
use crate::data_cache::DataCache;
use crate::misc::location::Location;

#[derive(Default, Clone)]
pub struct LossNode {
    pub name: String,
    pub location: Location,
    pub loss_table: Table,  // By default, the columns mean Inflow Rate ML, Loss Rate ML

    // Internal state only
    usflow: f64,
    dsflow_primary: f64,
    loss_flow: f64,

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_loss: Option<usize>,
}

impl LossNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            ..Default::default()
        }
    }

    /// Base constructor with node name
    pub fn new_named(name: &str) -> Self {
        Self {
            name: name.to_string(),
            ..Default::default()
        }
    }
}

impl Node for LossNode {
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(), String> {
        // Initialize only internal state
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;
        self.loss_flow = 0.0;

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
        self.recorder_idx_loss = data_cache.get_series_idx(
            make_result_name(&self.name, "loss").as_str(), false
        );

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str {
        &self.name
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {
        // Calculate loss flow from table (inflow rate -> loss rate)
        self.loss_flow = self.loss_table.interpolate(0, 1, self.usflow);

        // Remaining flow after loss goes to ds_1
        self.dsflow_primary = self.usflow - self.loss_flow;
        if self.dsflow_primary < 0.0 {
            panic!("Negative downstream flow at '{}' when usflow={}, loss={}",
                   self.name, self.usflow, self.loss_flow);
        }

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_ds_1 {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_loss {
            data_cache.add_value_at_index(idx, self.loss_flow);
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