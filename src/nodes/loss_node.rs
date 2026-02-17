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
    pub order_tranlation_table: Table,  // Columns: Inflow ML, Outflow ML (derived from loss_table)

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

        // TODO: Check loss table is monotonically increasing
        //       It would be great to have this as a table method

        // Check the loss table has
        for row in 0..self.loss_table.nrows() {
            let inflow = self.loss_table.get_value(row, 0);
            let loss = self.loss_table.get_value(row, 1);
            if inflow < 0f64 || loss < 0f64 { return Err(format!("Node '{}' loss table contains negative value at row {}", self.name, row + 1)); }
            if loss > inflow  { return Err(format!("Node '{}' loss table has loss > inflow at row {}", self.name, row + 1)); }
        }

        // Build order_translation_table from loss_table (for lookups during ordering)
        // TODO: Check the validity of reverse lookup. I require that the loss function does not
        //       cause the outflow to decrease. This is reasonable and helps define the ordering
        //       calculation a bit. However multiple consecutive inflow values may still produce
        //       the same outflow, and therefore there is still ambiguity in how much to order
        //       upstream. Moreover if the PWL has multiple segments with constant outflow, then the
        //       interpolation may depend on which segment the binary search lands on (!). The
        //       proper answer is to define a new type of PWL table which has (xlo, xhi), (ylo, yhi)
        //       defined for each row but ALLOW FOR NON-CONTINUOUS y values. With a table like this
        //       we could just ditch any rows where xlo = xhi, and the use a binary search to find
        //       where xlo < x <= xhi, which will always give us the first answer (i.e. y = y(xhi[i])
        //       rather than y = y(xlo[i+1])). This will ensure our u/s order is the lowest possible
        //       that produces the required outflow.
        self.order_tranlation_table = Table::new(2);
        let mut previous_outflow = 0f64;
        for row in 0..self.loss_table.nrows() {
            // Prepare flow values
            let inflow = self.loss_table.get_value(row, 0);
            let loss = self.loss_table.get_value(row, 1);
            let outflow = (inflow - loss).max(0f64);
            if outflow < previous_outflow {
                return Err(format!("Node '{}' loss table gradient > 1 causes outflow to decrease at row {}", self.name, row + 1));
            } else {
                // Set the table values for this new point
                self.order_tranlation_table.set_value(row, 0, outflow); //downstream order (required downstream flow)
                self.order_tranlation_table.set_value(row, 1, inflow); //upstream order (required upstream flow)
                previous_outflow = outflow;
            }
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