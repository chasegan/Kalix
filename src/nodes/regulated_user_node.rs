use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::misc::location::Location;
use crate::numerical::fifo_buffer::FifoBuffer;

const MAX_DS_LINKS: usize = 1;

#[derive(Default, Clone)]
pub struct RegulatedUserNode {

    // Properties - basic
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub order_input: DynamicInput,

    // Properties - regulated user stuff
    pub order_travel_time: usize,
    pub order_value: f64, //Captured during the ordering phase if in regulated zones
    pub order_buffer: FifoBuffer,
    pub pump_capacity: DynamicInput,

    // Internal state only
    pub dsorders: [f64; MAX_DS_LINKS],
    order_due: f64,
    usflow: f64,
    dsflow_primary: f64,
    diversion: f64,
    pump_capacity_value: f64,

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_pump_capacity: Option<usize>,
    recorder_idx_order: Option<usize>,
    recorder_idx_order_due: Option<usize>,
    recorder_idx_demand: Option<usize>,
    recorder_idx_diversion: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_ids_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
}


impl RegulatedUserNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            pump_capacity: DynamicInput::default(),
            order_input: DynamicInput::default(),
            order_buffer: FifoBuffer::default(),
            ..Default::default()
        }
    }
}

impl Node for RegulatedUserNode {
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;
        self.diversion = 0.0;
        self.pump_capacity_value = f64::INFINITY;

        // Checks
        // None

        // DynamicInput is already initialized during parsing

        // Initialize result recorders
        self.recorder_idx_usflow = data_cache.get_series_idx(
            make_result_name(&self.name, "usflow").as_str(), false
        );
        self.recorder_idx_pump_capacity = data_cache.get_series_idx(
            make_result_name(&self.name, "pump_capacity").as_str(), false
        );
        self.recorder_idx_order = data_cache.get_series_idx(
            make_result_name(&self.name, "order").as_str(), false
        );
        self.recorder_idx_order_due = data_cache.get_series_idx(
            make_result_name(&self.name, "order_due").as_str(), false
        );
        self.recorder_idx_demand = data_cache.get_series_idx(
            make_result_name(&self.name, "demand").as_str(), false
        );
        self.recorder_idx_diversion = data_cache.get_series_idx(
            make_result_name(&self.name, "diversion").as_str(), false
        );
        self.recorder_idx_dsflow = data_cache.get_series_idx(
            make_result_name(&self.name, "dsflow").as_str(), false
        );
        self.recorder_ids_ds_1 = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_1").as_str(), false
        );
        self.recorder_idx_ds_1_order = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_1_order").as_str(), false
        );

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str { &self.name }

    fn run_order_phase(&mut self, data_cache: &mut DataCache) {

        // Get demand value (this is equal to our old order, which is due to arrive today)
        self.order_due = self.order_buffer.push(self.order_value);

        // Order phase recorders
        if let Some(idx) = self.recorder_idx_order {
            data_cache.add_value_at_index(idx, self.order_value);
        }
        if let Some(idx) = self.recorder_idx_order_due {
            data_cache.add_value_at_index(idx, self.order_due);
        }
        if let Some(idx) = self.recorder_idx_demand {
            data_cache.add_value_at_index(idx, self.order_due);
        }
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {

        //let new_demand = order_due;

        // Work out availability
        let mut available = self.usflow;

        // Restrict for pump capacity
        match self.pump_capacity {
            DynamicInput::None { .. } => {}
            _ => {
                self.pump_capacity_value = self.pump_capacity.get_value(data_cache);
                available = available.min(self.pump_capacity_value) //Limited by pump rate
            }
        };

        // Determine the diversion value
        self.diversion = self.order_due.min(available);

        // Extract the water and update mbal
        self.dsflow_primary = self.usflow - self.diversion;
        self.mbal -= self.diversion;

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }
        if let Some(idx) = self.recorder_idx_diversion {
            data_cache.add_value_at_index(idx, self.diversion);
        }
        if let Some(idx) = self.recorder_idx_pump_capacity {
            data_cache.add_value_at_index(idx, self.pump_capacity_value)
        }
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_ids_ds_1 {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.dsorders[0]);
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
