use super::{recorder, single_outlet_node_impls, Node};
use crate::numerical::table::Table;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::misc::location::Location;
use crate::model_inputs::DynamicInput;
use crate::numerical::table_discontinuous::TableDiscontinuous;

const MAX_DS_LINKS: usize = 1;

#[derive(Default, Clone)]
pub struct LossNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub loss_table: Table,  // Columns: Inflow ML, Loss ML
    pub order_translation_table: TableDiscontinuous,
    pub loss_rate: DynamicInput,

    // Internal state only
    usflow: f64,
    dsflow_primary: f64,
    loss: f64,
    loss_rate_value: f64,

    // Orders
    pub dsorders: [f64; MAX_DS_LINKS],
    pub usorders: f64,

    // Up to this inflow the table loses nothing, so the flow phase skips its lookup
    // beneath it. Set in initialise().
    // Keep this declared away from `usflow`, and measure if these fields move. The
    // flow phase reads both. Declared between mbal and usflow it landed beside
    // usflow, the compiler fetched the two with one paired load (ldp), and a model
    // that never skips ran 24% slower than with no skip at all; a fields-only build
    // was flat, so it was not the size. Declared here they load separately (two ldr)
    // and that model is flat. Why is not established: the builds differ only in
    // field offsets, and the same 10 ms reappeared on the skip path. Per ADR-0004 §3.4.
    zero_loss_limit: f64,

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
    recorder_idx_loss: Option<usize>,
    recorder_idx_loss_rate: Option<usize>,
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
    single_outlet_node_impls!();

    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;
        self.loss = 0.0;
        // NaN, not zero: with no loss_rate expression there is no rate value, and a
        // recorded all-NaN series says so honestly.
        self.loss_rate_value = f64::NAN;

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

        // Many loss tables lose nothing until the inflow passes some threshold, and a
        // node with no table loses nothing at all. Up to that threshold the table loss
        // is zero, so find it once here and let the flow phase skip the lookup beneath
        // it. It is the inflow of the last leading row whose loss is zero (interpolating
        // between two zero rows gives zero). A table whose loss is zero in every row
        // loses nothing at any inflow - its last segment extends at zero - so the limit
        // is infinite.
        self.zero_loss_limit = f64::INFINITY;
        let mut last_zero_inflow = 0.0;
        for row in 0..self.loss_table.nrows() {
            if self.loss_table.get_value(row, 1) > 0.0 {
                self.zero_loss_limit = last_zero_inflow;
                break;
            }
            last_zero_inflow = self.loss_table.get_value(row, 0);
        }

        // Build order_translation_table from loss_table (for lookups during ordering):
        // the smallest inflow that delivers a required outflow. Relies on the checks above.
        self.order_translation_table = TableDiscontinuous::order_translation(&self.loss_table);

        // Initialize result recorders
        self.recorder_idx_usflow = recorder(data_cache, &self.name, "usflow");
        self.recorder_idx_dsflow = recorder(data_cache, &self.name, "dsflow");
        self.recorder_idx_ds_1 = recorder(data_cache, &self.name, "ds_1");
        self.recorder_idx_ds_1_order = recorder(data_cache, &self.name, "ds_1_order");
        self.recorder_idx_loss = recorder(data_cache, &self.name, "loss");
        self.recorder_idx_loss_rate = recorder(data_cache, &self.name, "loss_rate");

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

        // Calculate usorders 
        // Note: order_translation_table is well-formed and non-negative by
        // construction, see `initialise()`, so cannot return negative orders
        self.usorders = self.order_translation_table.interpolate_or_extrapolate(self.dsorders[0]);
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

        // Calculate the attempted loss: the loss_rate expression when one is set,
        // else the loss table (inflow rate -> loss rate). Either way the loss
        // actually taken is clamped to [0, usflow] below.
        let attempted_loss = match self.loss_rate {
            // Beneath zero_loss_limit the table loss is zero, so skip the lookup (a
            // binary search, four bounds-checked reads and a division). A NaN inflow
            // fails the compare and takes the lookup, as before.
            DynamicInput::None { .. } => if self.usflow <= self.zero_loss_limit {
                0.0
            } else {
                self.loss_table.interpolate_or_extrapolate(0, 1, self.usflow)
            },
            _ => {
                self.loss_rate_value = self.loss_rate.get_value(data_cache);
                self.loss_rate_value
            }
        };
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
        if let Some(idx) = self.recorder_idx_loss {
            data_cache.add_value_at_index(idx, self.loss);
        }
        if let Some(idx) = self.recorder_idx_loss_rate {
            // The raw expression value (pre-clamp), like the user nodes'
            // pump/flow_threshold recorders; all-NaN when no loss_rate is set.
            data_cache.add_value_at_index(idx, self.loss_rate_value);
        }

        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }




}