use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::data_management::data_cache::DataCache;
use crate::model_inputs::DynamicInput;
use crate::misc::location::Location;
use crate::numerical::fifo_buffer::FifoBuffer;

const MAX_DS_LINKS: usize = 1;

#[derive(Default, Clone)]
pub struct OrderConstraintNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,

    // Properties
    pub min_order_input: DynamicInput,
    pub max_order_input: DynamicInput,
    pub set_order_input: DynamicInput,

    // Properties and state for delaying downstream orders
    pub delay_order_steps: usize,
    pub delay_order_buffer: FifoBuffer,

    // Internal state only
    pub min_order_defined: bool,
    pub max_order_defined: bool,
    pub set_order_defined: bool,
    pub min_order_value: f64,
    pub max_order_value: f64,
    pub set_order_value: f64,
    pub sent_order_value: f64,
    pub sent_order_buffer: FifoBuffer,
    usflow: f64,
    dsflow_primary: f64,

    // Orders
    pub dsorders: [f64; MAX_DS_LINKS],

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
    recorder_idx_min_order: Option<usize>,
    recorder_idx_max_order: Option<usize>,
    recorder_idx_set_order: Option<usize>,
    recorder_idx_order: Option<usize>,
    recorder_idx_order_due: Option<usize>,
}

impl OrderConstraintNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            ..Default::default()
        }
    }
}

impl Node for OrderConstraintNode {
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;
        self.delay_order_buffer = FifoBuffer::new(self.delay_order_steps);
        self.min_order_defined = !matches!(self.min_order_input, DynamicInput::None { .. });
        self.max_order_defined = !matches!(self.max_order_input, DynamicInput::None { .. });
        self.set_order_defined = !matches!(self.set_order_input, DynamicInput::None { .. });
        self.set_order_value = 0.0;
        self.min_order_value = 0.0;
        self.max_order_value = f64::INFINITY;
        //self.orders_sent = FifoBuffer::new(0); //Will be initialized in ordering system init.

        //DynamicInput is already initialized during parsing

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
        self.recorder_idx_min_order = data_cache.get_series_idx(
            make_result_name(&self.name, "min_order").as_str(), false
        );
        self.recorder_idx_max_order = data_cache.get_series_idx(
            make_result_name(&self.name, "max_order").as_str(), false
        );
        self.recorder_idx_set_order = data_cache.get_series_idx(
            make_result_name(&self.name, "set_order").as_str(), false
        );
        self.recorder_idx_order = data_cache.get_series_idx(
            make_result_name(&self.name, "order").as_str(), false
        );
        self.recorder_idx_order_due = data_cache.get_series_idx(
            make_result_name(&self.name, "order_due").as_str(), false
        );

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str {
        &self.name
    }

    fn run_pre_order_phase(&mut self, data_cache: &mut DataCache) {

        // Record downstream orders
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.dsorders[0]);
        }
    }

    fn run_post_order_phase(&mut self, data_cache: &mut DataCache) {
        // Nothing
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

        // Recall the order that is due today (and push the current order into the buffer)
        // TODO: can I just move this into the recorder if block? Is it okay to only do this if we are recording?
        let order_due = self.sent_order_buffer.push(self.sent_order_value);

        // Force flows if required, otherwise pass upstream value
        self.dsflow_primary = self.usflow;

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
        if let Some(idx) = self.recorder_idx_min_order {
            data_cache.add_value_at_index(idx, self.min_order_value);
        }
        if let Some(idx) = self.recorder_idx_max_order {
            data_cache.add_value_at_index(idx, self.max_order_value);
        }
        if let Some(idx) = self.recorder_idx_set_order {
            data_cache.add_value_at_index(idx, self.set_order_value);
        }
        if let Some(idx) = self.recorder_idx_order {
            data_cache.add_value_at_index(idx, self.sent_order_value);
        }
        if let Some(idx) = self.recorder_idx_order_due {
            data_cache.add_value_at_index(idx, order_due);
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