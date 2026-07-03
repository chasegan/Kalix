use super::{recorder, single_outlet_node_impls, Node};
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::model_inputs::DynamicInput;
use crate::misc::location::Location;
use crate::numerical::fifo_buffer::FifoBuffer;

const MAX_DS_LINKS: usize = 1;

#[derive(Default, Clone)]
pub struct OrderControlNode {
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
    pub sent_order_buffer: FifoBuffer,
    pub usorders: f64,
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

impl OrderControlNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            ..Default::default()
        }
    }
}

impl Node for OrderControlNode {
    single_outlet_node_impls!();

    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String> {
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
        self.recorder_idx_usflow = recorder(data_cache, &self.name, "usflow");
        self.recorder_idx_dsflow = recorder(data_cache, &self.name, "dsflow");
        self.recorder_idx_ds_1 = recorder(data_cache, &self.name, "ds_1");
        self.recorder_idx_ds_1_order = recorder(data_cache, &self.name, "ds_1_order");
        self.recorder_idx_min_order = recorder(data_cache, &self.name, "min_order");
        self.recorder_idx_max_order = recorder(data_cache, &self.name, "max_order");
        self.recorder_idx_set_order = recorder(data_cache, &self.name, "set_order");
        self.recorder_idx_order = recorder(data_cache, &self.name, "order");
        self.recorder_idx_order_due = recorder(data_cache, &self.name, "order_due");

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

        // Calculate orders
        let mut order = self.delay_order_buffer.push(self.dsorders[0]);
        if self.set_order_defined {
            self.set_order_value = self.set_order_input.get_value(data_cache);
            order = self.set_order_value;
        } else {
            if self.min_order_defined {
                self.min_order_value = self.min_order_input.get_value(data_cache);
                order = order.max(self.min_order_value);
            }
            if self.max_order_defined {
                self.max_order_value = self.max_order_input.get_value(data_cache);
                order = order.min(self.max_order_value);
            }
        }
        self.usorders = order;
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

        // Recall the order that is due today (and push the current order into the buffer)
        // TODO: can I just move this into the recorder if block? Is it okay to only do this if we are recording?
        let order_due = self.sent_order_buffer.push(self.usorders);

        // Force flows if required, otherwise pass upstream value
        self.dsflow_primary = self.usflow;

        // Record results
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_ds_1 {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
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
            data_cache.add_value_at_index(idx, self.usorders);
        }
        if let Some(idx) = self.recorder_idx_order_due {
            data_cache.add_value_at_index(idx, order_due);
        }

        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }




}