use super::{Node, recorder};
use crate::numerical::table::Table;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::misc::location::Location;
use crate::numerical::fifo_buffer::FifoBuffer;
use crate::numerical::table_discontinuous::TableDiscontinuous;

const MAX_DS_LINKS: usize = 5;

/// Outlet indices are zero-based: ds_1 (the main channel) is 0, ds_2 (the effluent) is 1.
pub const DS_1_OUTLET: u8 = 0;
pub const DS_2_OUTLET: u8 = 1;

#[derive(Default, Clone)]
pub struct SplitterNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub splitter_table: Table,  // As the modeller wrote it, and optional. By default, the columns mean Inflow Rate ML, Effluent Rate ML (maybe ways to override this later)
    flow_table: Table,          // The table the node runs on: splitter_table, or zero effluent at every inflow if none was given. Built in initialise().
    pub order_translation_table: TableDiscontinuous, // Required ds_1 flow -> smallest inflow that delivers it past the table. Built in initialise().

    // Internal state only
    usflow: f64,
    ds_1_flow: f64,
    ds_2_flow: f64,

    // Orders
    pub dsorders: [f64; MAX_DS_LINKS],
    pub usorders: f64,                 //The order sent upstream, set in the ordering phase
    max_ds_1_flow: f64,                //The most ds_1 can ever receive: finite only when the table's last segment sends all additional flow down the effluent. Set in initialise().
    order_identity_limit: f64,         //Up to this ds_1 order the table diverts nothing, so the order passes through unchanged. Set in initialise().
    pub ds_2_order_buffer: FifoBuffer, //Delays effluent orders by the supply travel time, so they are diverted on the step the ordered water arrives. Sized by the ordering system from the ds_2 link's lag.
    pub ds_2_order_due: f64,           //Populated from the fifo buffer in the ordering phase

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
    //recorder_idx_ds_1_order_due: Option<usize>, // currently not tracking when ds_1 orders are due
    recorder_idx_ds_2: Option<usize>,
    recorder_idx_ds_2_order: Option<usize>,
    recorder_idx_ds_2_order_due: Option<usize>,
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
    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.ds_1_flow = 0.0;
        self.ds_2_flow = 0.0;

        // Reset order state. A zero-length buffer passes orders straight through;
        // the ordering system (which initialises after the nodes) replaces it when ds_2 is
        // a regulated link.
        self.ds_2_order_buffer = FifoBuffer::default();
        self.ds_2_order_due = 0.0;
        self.usorders = 0.0;

        // The table is optional. Without one the splitter diverts nothing of its own
        // accord, and the effluent receives only what is ordered down it: a regulated
        // offtake. The node runs on flow_table, a working copy, so that splitter_table
        // stays exactly as the modeller wrote it and a model saved after a run does not
        // gain a table nobody wrote.
        if self.splitter_table.nrows() == 0 {
            self.flow_table = Table::new(2);
            //(0, 0)
            self.flow_table.set_value(0, 0, 0.0);
            self.flow_table.set_value(0, 1, 0.0);
            //(100, 0)
            self.flow_table.set_value(1, 0, 100.0);
            self.flow_table.set_value(1, 1, 0.0);
        } else {
            self.flow_table = self.splitter_table.clone();
        }

        // Check the splitter table is well-behaved (mirrors the loss node, see the
        // matching Table assertions):
        //  - it must be monotonically increasing (inflow ascending, effluent non-decreasing)
        //  - it must start at zero inflow
        //  - it must not have negative values
        //  - it must not specify effluent greater than the inflow
        //  - its slope must not exceed 1:1, i.e. the ds_1 continuation flow must
        //    not decrease as inflow rises
        if let Err(e) = self.flow_table.assert_monotonically_increasing(0, 1) {
            return Err(format!("Node '{}' splitter table. {}", self.name, e));
        }
        if let Err(e) = self.flow_table.assert_starts_at_zero(0) {
            return Err(format!("Node '{}' splitter table. {}", self.name, e));
        }
        if let Err(e) = self.flow_table.assert_non_negative() {
            return Err(format!("Node '{}' splitter table. {}", self.name, e));
        }
        if let Err(e) = self.flow_table.assert_col_not_exceeding(1, 0) {
            return Err(format!("Node '{}' splitter table has effluent exceeding inflow. {}", self.name, e));
        }
        if let Err(e) = self.flow_table.assert_slope_not_exceeding_one(0, 1) {
            return Err(format!("Node '{}' splitter table slope exceeds 1:1 (ds_1 flow would decrease). {}", self.name, e));
        }

        // Build order_translation_table from the table (for lookups during
        // ordering): the smallest inflow that leaves a required flow on ds_1 after
        // the table has sent its share down the effluent. The same function a loss
        // node uses for its losses, and it relies on the checks above.
        self.order_translation_table = TableDiscontinuous::order_translation(&self.flow_table);

        // If the table's last segment has a 1:1 slope, everything above it goes down
        // the effluent and ds_1 can never receive more than it does at that point. An
        // order beyond that cannot be met by any inflow, so the ordering phase holds
        // the ds_1 order to it (the order translation alone caps its own term, as at
        // a loss node, but the sum of the two orders would not be). Otherwise the last
        // segment extends and ds_1 is unbounded.
        self.max_ds_1_flow = f64::INFINITY;
        let n = self.flow_table.nrows();
        if n >= 2 {
            let passing = |row: usize| self.flow_table.get_value(row, 0) - self.flow_table.get_value(row, 1);
            if passing(n - 1) <= passing(n - 2) {
                self.max_ds_1_flow = passing(n - 1);
            }
        }

        // Most regulated splitters are high-flow breakouts: their table diverts
        // nothing until the inflow passes some threshold well above regulated flows.
        // Up to that threshold the order translation is the identity, so find it
        // once here and let the ordering phase skip the lookup beneath it. It is the
        // inflow of the last leading row whose effluent is zero (interpolating
        // between two zero rows gives zero). A table whose effluent is zero in every
        // row, including the one that stands in for no table, diverts nothing at any
        // inflow - its last segment extends at zero - so the limit is infinite.
        self.order_identity_limit = f64::INFINITY;
        let mut last_zero_inflow = 0.0;
        for row in 0..self.flow_table.nrows() {
            if self.flow_table.get_value(row, 1) > 0.0 {
                self.order_identity_limit = last_zero_inflow;
                break;
            }
            last_zero_inflow = self.flow_table.get_value(row, 0);
        }

        // Initialize result recorders
        self.recorder_idx_usflow = recorder(data_cache, &self.name, "usflow");
        self.recorder_idx_dsflow = recorder(data_cache, &self.name, "dsflow");
        self.recorder_idx_ds_1 = recorder(data_cache, &self.name, "ds_1");
        self.recorder_idx_ds_1_order = recorder(data_cache, &self.name, "ds_1_order");
        self.recorder_idx_ds_2 = recorder(data_cache, &self.name, "ds_2");
        self.recorder_idx_ds_2_order = recorder(data_cache, &self.name, "ds_2_order");
        self.recorder_idx_ds_2_order_due = recorder(data_cache, &self.name, "ds_2_order_due");

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str {
        &self.name
    }

    fn run_order_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Update the effluent order buffer
        self.ds_2_order_due = self.ds_2_order_buffer.push(self.dsorders[DS_2_OUTLET as usize]);

        // Calculate usorders: the smallest inflow that meets both orders when it
        // arrives. The flow phase gives ds_2 the larger of the table flow and its
        // order, and ds_1 the rest, so ds_1 receives min(q - table(q), q - ds_2_order)
        // from an inflow q. Both terms rise with q, so the order on ds_1 is met once
        // q reaches the larger of the two inflows that satisfy them: the inflow that
        // passes the ds_1 order through the table (as at a loss node), and the plain
        // sum of the two orders.
        // Note: order_translation_table is well-formed and non-negative by
        // construction, see `initialise()`, so cannot return negative orders
        let ds_1_order = self.dsorders[DS_1_OUTLET as usize].min(self.max_ds_1_flow);
        let ds_2_order = self.dsorders[DS_2_OUTLET as usize];
        // Beneath order_identity_limit the table diverts nothing and the lookup would
        // return the order unchanged (or zero for a negative order, hence the max), so
        // skip it. Measured on 60 regulated breakout splitters: the lookup cost +10%
        // simulation time, this path +2%, and where the table does divert at regulated
        // flows the extra compare measured as no change (per ADR-0004 §6). A NaN order
        // fails the compare and takes the lookup, as before.
        let through_table = if ds_1_order <= self.order_identity_limit {
            ds_1_order.max(0.0)
        } else {
            self.order_translation_table.interpolate_or_extrapolate(ds_1_order)
        };
        self.usorders = through_table.max(ds_1_order + ds_2_order);

        // Record downstream orders
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.dsorders[DS_1_OUTLET as usize]);
        }
        if let Some(idx) = self.recorder_idx_ds_2_order {
            data_cache.add_value_at_index(idx, self.dsorders[DS_2_OUTLET as usize]);
        }
        if let Some(idx) = self.recorder_idx_ds_2_order_due {
            data_cache.add_value_at_index(idx, self.ds_2_order_due);
        }
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

        // Determine effluent flow. Use interpolate_or_extrapolate so that inflows
        // beyond the table domain extend the last segment rather than returning NaN
        // (NaN would slip through .min, sending the entire flow down ds_2). The
        // .min(usflow) guards over-extraction, and max(self.ds_2_order_due) serves a
        // secondary purpose ensuring that ds_2_flow >= 0.
        // We made a deliberate decision that effluent orders (ds_2 orders) get
        // priority over main channel orders (ds_1 orders); this is in line with the
        // first-in-best-dressed principle followed elsewhere in the ordering system.
        self.ds_2_flow = self.flow_table.interpolate_or_extrapolate(0, 1, self.usflow).max(self.ds_2_order_due).min(self.usflow);
        self.ds_1_flow = self.usflow - self.ds_2_flow;
        if self.ds_1_flow < 0f64 {
            panic!("Negative ds_1 flow at '{}' when usflow={}, ds_1={}", self.name, self.usflow, self.ds_1_flow);
        }

        // Update mass balance
        // self.mbal = 0.0; // This is always zero for Splitter nodes. The water on ds_2 is not lost in this node.

        // Record results
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.usflow); //Total dsflow is same as usflow
        }
        if let Some(idx) = self.recorder_idx_ds_1 {
            data_cache.add_value_at_index(idx, self.ds_1_flow);
        }
        if let Some(idx) = self.recorder_idx_ds_2 {
            data_cache.add_value_at_index(idx, self.ds_2_flow);
        }

        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }

    fn add_usflow(&mut self, flow: f64, _inlet: u8) {
        self.usflow += flow;
    }

    fn remove_dsflow(&mut self, outlet: u8) -> f64 {
        match outlet {
            DS_1_OUTLET => {
                let outflow = self.ds_1_flow;
                self.ds_1_flow = 0.0;
                outflow
            }
            DS_2_OUTLET => {
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