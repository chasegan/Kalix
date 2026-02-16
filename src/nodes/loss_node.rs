use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::numerical::table::Table;
use crate::data_management::data_cache::DataCache;
use crate::misc::location::Location;

const MAX_DS_LINKS: usize = 1;

#[derive(Default, Clone)]
pub struct LossNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub loss_table: Table,  // Columns: Inflow ML, Loss ML
    pub flow_table: Table,  // Columns: Inflow ML, Outflow ML (derived from loss_table)

    // Internal state only
    usflow: f64,
    dsflow_primary: f64,
    loss: f64,

    // Orders
    pub dsorders: [f64; MAX_DS_LINKS],

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
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
}

impl Node for LossNode {
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;
        self.loss = 0.0;

        // Build flow_table from loss_table (for reverse lookups during ordering)
        self.flow_table = Table::new(2);
        for row in 0..self.loss_table.nrows() {
            let inflow = self.loss_table.get_value(row, 0);
            let loss = self.loss_table.get_value(row, 1);
            let outflow = inflow - loss;
            self.flow_table.set_value(row, 0, inflow);
            self.flow_table.set_value(row, 1, outflow);
        }

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
        //let attempted_loss = self.loss_table.interpolate(0, 1, self.usflow).min(self.usflow);
        let attempted_loss = self.loss_table.interpolate_or_extrapolate(0, 1, self.usflow);
        self.loss = attempted_loss.max(0f64).min(self.usflow);

        // Remaining flow after loss goes to ds_1
        self.dsflow_primary = self.usflow - self.loss;
        // TODO remove below check. This can only happen if the usflow is negative. Not this node's fault.
        if self.dsflow_primary < 0.0 {
            panic!("Negative downstream flow at '{}' when usflow={}, loss={}", self.name, self.usflow, self.loss);
        }

        // Update mass balance
        self.mbal -= self.loss;

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
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.dsorders[0]);
        }
        if let Some(idx) = self.recorder_idx_loss {
            data_cache.add_value_at_index(idx, self.loss);
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