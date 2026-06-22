use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::numerical::table::Table;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::misc::location::Location;
use crate::numerical::table_discontinuous::TableDiscontinuous;

const MAX_DS_LINKS: usize = 1;

#[derive(Default, Clone)]
pub struct LossNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub loss_table: Table,  // Columns: Inflow ML, Loss ML
    pub order_translation_table: TableDiscontinuous,

    // Internal state only
    usflow: f64,
    dsflow_primary: f64,
    loss: f64,

    // Orders
    pub dsorders: [f64; MAX_DS_LINKS],
    pub usorders: f64,

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
    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;
        self.loss = 0.0;

        // If the loss table is incomplete, fix it.
        match self.loss_table.nrows() {
            0 => {
                //Make a table with 0 loss.
                self.loss_table = Table::new(2);
                //(0, 0)
                self.loss_table.set_value(0, 0, 0.0);
                self.loss_table.set_value(0, 1, 0.0);
                //(100, 0)
                self.loss_table.set_value(1, 0, 100.0);
                self.loss_table.set_value(1, 1, 0.0);
            }
            1 => {
                //A table was defined but only includes one row.
                //Add another row to make explicit that we are assuming constant loss
                let flow0 = self.loss_table.get_value(0, 0);
                let loss0 = self.loss_table.get_value(0, 1);
                self.loss_table.set_value(1, 0, flow0 + 100.0);
                self.loss_table.set_value(1, 1, loss0 + 100.0);
            }
            _ => { }
        }

        // Check the loss table is well-behaved (see the matching Table assertions):
        //  - it must be monotonically increasing
        //  - it must start at zero inflow
        //  - it must not have negative values
        //  - it must not have loss > inflow
        //  - its slope must not exceed 1:1, i.e. outflow must not decrease
        if let Err(e) = self.loss_table.assert_monotonically_increasing(0, 1) {
            return Err(format!("Node '{}' loss table. {}", self.name, e));
        }
        if let Err(e) = self.loss_table.assert_starts_at_zero(0) {
            return Err(format!("Node '{}' loss table. {}", self.name, e));
        }
        if let Err(e) = self.loss_table.assert_non_negative() {
            return Err(format!("Node '{}' loss table. {}", self.name, e));
        }
        if let Err(e) = self.loss_table.assert_col_not_exceeding(1, 0) {
            return Err(format!("Node '{}' loss table has loss exceeding inflow. {}", self.name, e));
        }
        if let Err(e) = self.loss_table.assert_slope_not_exceeding_one(0, 1) {
            return Err(format!("Node '{}' loss table slope exceeds 1:1 (outflow would decrease). {}", self.name, e));
        }

        // The maximum outflow is the last row's (inflow - loss); the slope check
        // guarantees (inflow - loss) is non-decreasing, so the last row is the max.
        let last_row = self.loss_table.nrows() - 1;
        let max_outflow = self.loss_table.get_value(last_row, 0) - self.loss_table.get_value(last_row, 1);

        // Build order_translation_table from loss_table (for lookups during ordering)
        // I require that the loss function does not cause the outflow to decrease. However,
        // multiple consecutive inflow values may still produce the same outflow, and therefore
        // ambiguity still exists in how much to order upstream. Moreover, if the PWL has multiple
        // segments with constant outflow, then the interpolation may depend on which segment the
        // binary search lands on!
        // The answer is to define a new type of PWL table which ALLOWS FOR NON-CONTINUOUS y values,
        // and to use a binary search to find where xlo < x <= xhi, which will always give us the
        // lowest possible order that produces the required outflow.
        self.order_translation_table = TableDiscontinuous::new();
        self.order_translation_table.add_point(-1.0, 0.0);
        self.order_translation_table.add_point(0.0, 0.0);
        if max_outflow > 0.0 {
            for row in 0..self.loss_table.nrows() {
                let inflow = self.loss_table.get_value(row, 0);
                let loss = self.loss_table.get_value(row, 1);
                let outflow = inflow - loss;
                self.order_translation_table.add_point(outflow, inflow);
            }
        } else {
            // Loss table has 100% loss and cant satisfy any orders.
            // The only reasonable thing to do is to make a new table with no orders.
            //self.order_translation_table = TableDiscontinuous::new();
            self.order_translation_table.add_point(0.0, 0.0);
            self.order_translation_table.add_point(1.0, 0.0);
        }
        self.order_translation_table.cap_if_unfinished();

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

    fn run_order_phase(&mut self, data_cache: &mut DataCache) {

        // Record downstream orders
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.dsorders[0]);
        }

        // Calculate usorders
        self.usorders = self.order_translation_table.interpolate_or_extrapolate(self.dsorders[0]);
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

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
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_ds_1 {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        // if let Some(idx) = self.recorder_idx_ds_1_order {
        //     data_cache.add_value_at_index(idx, self.dsorders[0]);
        // }
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