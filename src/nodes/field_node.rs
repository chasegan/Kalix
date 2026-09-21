use super::{recorder, single_outlet_node_impls, Node};
use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::misc::location::Location;
use crate::numerical::fifo_buffer::FifoBuffer;

const MAX_DS_LINKS: usize = 1;

/// SKELETON. A field is a water demand: it places orders upstream, as a
/// regulated user does, and takes its order from what arrives. There is no
/// soil store yet, so the water it takes leaves the model (mbal), and the
/// order is an authored expression standing in for whatever the field will
/// later work out for itself. What arrives above the order due passes to ds_1.
///
/// A field sends only its own order upstream. Its ds_1 carries surplus and
/// returns back to the river, so an order arriving on it is not one the field
/// can serve, and passing it on would order the same water twice wherever the
/// return rejoins a regulated reach. The arriving order is still recorded
/// (`ds_1_order`), so the modeller can see what was dropped.
#[derive(Default, Clone)]
pub struct FieldNode {

    // Properties - basic
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub order_input: DynamicInput,

    // Ordering
    pub order_travel_time: usize,
    pub order_value: f64, //Captured during the ordering phase if in regulated zones
    pub order_buffer: FifoBuffer,

    // Internal state only
    pub dsorders: [f64; MAX_DS_LINKS],
    order_due: f64,
    usflow: f64,
    dsflow_primary: f64,

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_order: Option<usize>,
    recorder_idx_order_due: Option<usize>,
    recorder_idx_supply: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
}


impl FieldNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            order_input: DynamicInput::default(),
            order_buffer: FifoBuffer::default(),
            ..Default::default()
        }
    }
}

impl Node for FieldNode {
    single_outlet_node_impls!();

    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;

        // Reset order state, so a rerun of the same model object starts clean.
        // The ordering system (which initialises after the nodes) sizes the
        // buffer from the travel time it finds.
        self.order_travel_time = 0;
        self.order_buffer = FifoBuffer::default();
        self.order_value = 0.0;
        self.order_due = 0.0;

        // DynamicInput is already initialized during parsing

        // Initialize result recorders
        self.recorder_idx_usflow = recorder(data_cache, &self.name, "usflow");
        self.recorder_idx_order = recorder(data_cache, &self.name, "order");
        self.recorder_idx_order_due = recorder(data_cache, &self.name, "order_due");
        self.recorder_idx_supply = recorder(data_cache, &self.name, "supply");
        self.recorder_idx_dsflow = recorder(data_cache, &self.name, "dsflow");
        self.recorder_idx_ds_1 = recorder(data_cache, &self.name, "ds_1");
        self.recorder_idx_ds_1_order = recorder(data_cache, &self.name, "ds_1_order");

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str { &self.name }

    fn run_order_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record downstream orders
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.dsorders[0]);
        }

        // Ensure non-negativity of orders
        self.order_value = self.order_input.get_value(data_cache).max(0.0);

        // The order placed order_travel_time steps ago is due to arrive today
        self.order_due = self.order_buffer.push(self.order_value);

        // Order phase recorders
        if let Some(idx) = self.recorder_idx_order {
            data_cache.add_value_at_index(idx, self.order_value);
        }
        if let Some(idx) = self.recorder_idx_order_due {
            data_cache.add_value_at_index(idx, self.order_due);
        }
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

        // Take the order due from what arrives; the rest passes downstream
        let supply = self.order_due.min(self.usflow);
        self.dsflow_primary = self.usflow - supply;
        self.mbal -= supply;

        // Record results
        if let Some(idx) = self.recorder_idx_supply {
            data_cache.add_value_at_index(idx, supply);
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
}
