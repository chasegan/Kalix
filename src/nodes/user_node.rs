use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::misc::location::Location;
use crate::numerical::fifo_buffer::FifoBuffer;
//------- IDEAS FOR ORDERING IN KALIX ----------//
// A couple of thoughts are:
//   - Having an ordering phase means you kinda have to know everything before
//     any flows occur, and it really limits parallelization.
//   - Ordering up the network means when you place your order, you dont know
//     when it will arrive. Additionally, Source mixes up the concepts of "order"
//     and "demand". This leads to all the muddiness:
//        - For a timeseries I'll order in advance, and take my water on the date.
//        - For a function I'll order on the date, and take my water in arrears.
//        - At each point there is an array of orders. This is what I want today,
//          this is what I want tomorrow, but if you ask tomorrow I might change
//          my mind.
//        - Computational overhead is insane.
//   - It is nice having the operations kinda happen automatically in Source
//     as long as your storage has an outlet. Having some robust default behaviour
//     is a must.
//----------------------------------------------//
// So in light of all that I wonder if there's a pragmatic operational view
// that's just a lot simpler. Something like...
//   - All nodes know which storage outlet they're being delivered by. And at
//     start of the run they report themselves to the outlet.
//   - There is no ordering phase.
//   - When a storage runs (flow phase), it asks all its outlets how much water
//     they want. Each outlet turns to it's registered users and asks something
//     like.
//        "Hey user A, if I release today, I can get you 1000ML in 1 day. How much do you want?"
//        "Hey user B, if I release today, I can get you 1000ML in 3 days. How much do you want?"
//        "Hey user C, if I release today, I can get you 1000ML in 3 days. How much do you want?"
//     These numbers then have to go into a table, with the inflows (recession
//     factors?), and the losses etc.
//   - The water user is therefore only ever saying how much they will want in
//     3 days time (in this example). They're not saying "well if I can get it today
//     I'll take blah". This sounds like a limitation, but I think it's exactly
//     limitation that's been
//   - Distinguish between "order" and "demand":
//        - The order is what I'm going to ask the storage to release today.
//        - The demand is what I'm going to try to extract today.
//   - The basic version of the above is for the demand to lag behind the order
//     by x days. But maybe the demand is zero (for a non-consumptive user) or
//     maybe the order was zero (for an unregulated user).
//
//----------------------------------------------//

const MAX_DS_LINKS: usize = 5;
const MAX_US_LINKS: usize = 5;

#[derive(Default, Clone)]
pub struct UserNode {

    // Properties - basic
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub demand_input: DynamicInput,

    // Properties and internal state - regulated demands and ordering
    pub is_regulated: bool,
    pub order_travel_time: usize,
    pub order_phase_demand_value: f64,
    pub order_buffer: FifoBuffer,
    pub dsorders: [f64; MAX_DS_LINKS],
    pub usorders: [f64; MAX_US_LINKS],

    // Properties - additional unreg
    pub pump_capacity: DynamicInput,
    pub flow_threshold: DynamicInput,
    pub annual_cap: Option<f64>,
    pub annual_cap_reset_month: u32,

    // Properties - additional carryover
    pub demand_carryover_allowed: bool,
    pub demand_carryover_reset_month: Option<u32>,

    // Internal state only
    usflow: f64,
    dsflow_primary: f64,
    diversion: f64,
    annual_diversion: f64,
    pump_capacity_value: f64,
    flow_threshold_value: f64,
    demand_carryover_value: f64,

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_demand: Option<usize>,
    recorder_idx_diversion: Option<usize>,
    recorder_idx_pump_capacity: Option<usize>, //New
    recorder_idx_flow_threshold: Option<usize>, //New
    recorder_idx_demand_carryover: Option<usize>, //New
    recorder_idx_dsflow: Option<usize>,
    recorder_ids_ds_1: Option<usize>,
}


impl UserNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            demand_input: DynamicInput::default(),
            is_regulated: false,
            order_buffer: FifoBuffer::default(),
            pump_capacity: DynamicInput::default(),
            flow_threshold: DynamicInput::default(),
            annual_cap: None,
            annual_cap_reset_month: 7,
            demand_carryover_allowed: false,
            demand_carryover_reset_month: None,
            ..Default::default()
        }
    }
}

impl Node for UserNode {
    fn initialise(&mut self, data_cache: &mut DataCache) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;
        self.diversion = 0.0;
        self.annual_diversion = 0.0;
        self.demand_carryover_value = 0.0;
        self.flow_threshold_value = 0.0;
        self.pump_capacity_value = f64::INFINITY;

        // Checks
        if (self.annual_cap_reset_month < 1) || (self.annual_cap_reset_month > 12) {
            return Err(format!("Invalid annual cap reset month at '{}': {}", self.name, self.annual_cap_reset_month).to_string());
        }
        if let Some(v) = self.annual_cap {
            if v < 0.0 {
                return Err(format!("Invalid annual cap at '{}': {} < 0", self.name, v).to_string());
            }
        }
        if let Some(v) = self.demand_carryover_reset_month {
            if (v < 1) || (v > 12) {
                return Err(format!("Invalid demand carryover reset month at '{}': {}", self.name, v).to_string());
            }
        }

        // DynamicInput is already initialized during parsing

        // Initialize result recorders
        self.recorder_idx_usflow = data_cache.get_series_idx(
            make_result_name(&self.name, "usflow").as_str(), false
        );
        self.recorder_idx_demand = data_cache.get_series_idx(
            make_result_name(&self.name, "demand").as_str(), false
        );
        self.recorder_idx_diversion = data_cache.get_series_idx(
            make_result_name(&self.name, "diversion").as_str(), false
        );
        self.recorder_idx_pump_capacity = data_cache.get_series_idx(
            make_result_name(&self.name, "pump_capacity").as_str(), false
        );
        self.recorder_idx_flow_threshold = data_cache.get_series_idx(
            make_result_name(&self.name, "flow_threshold").as_str(), false
        );
        self.recorder_idx_demand_carryover = data_cache.get_series_idx(
            make_result_name(&self.name, "demand_carryover").as_str(), false
        );
        self.recorder_idx_dsflow = data_cache.get_series_idx(
            make_result_name(&self.name, "dsflow").as_str(), false
        );
        self.recorder_ids_ds_1 = data_cache.get_series_idx(
            make_result_name(&self.name, "ds_1").as_str(), false
        );

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str { &self.name }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {

        // Get demand value
        let new_demand = if self.is_regulated {
            let order_expected = self.order_buffer.push(self.order_phase_demand_value);
            order_expected
        } else {
            self.demand_input.get_value(data_cache)
        };

        // Work out availability considering flow threshold
        let mut available = match self.flow_threshold {
            DynamicInput::None { .. } => { self.usflow }
            _ => {
                self.flow_threshold_value = self.flow_threshold.get_value(data_cache);
                (self.usflow - self.flow_threshold_value).max(0.0)
            }
        };

        // Restrict for pump capacity
        match self.pump_capacity {
            DynamicInput::None { .. } => {}
            _ => {
                self.pump_capacity_value = self.pump_capacity.get_value(data_cache);
                available = available.min(self.pump_capacity_value) //Limited by pump rate
            }
        };

        // Restrict for annual cap if applicable
        match self.annual_cap {
            None => {}
            Some(annual_cap) => {
                let d = data_cache.get_timestamp_day();
                if d == 1 {
                    let m_reset = self.annual_cap_reset_month;
                    let m = data_cache.get_timestamp_month();
                    let s = data_cache.get_timestamp_seconds();
                    if (m == m_reset) && (s == 0) {
                        self.annual_diversion = 0.0;
                    }
                }
                available = available.min(annual_cap - self.annual_diversion);
            }
        }

        // Carryover
        if self.demand_carryover_allowed {
            // Allowing demand carryover
            // Check if we need to reset the demand carryover today
            match self.demand_carryover_reset_month {
                Some(m_reset) => {
                    let d = data_cache.get_timestamp_day();
                    if d == 1 {
                        let m = data_cache.get_timestamp_month();
                        let s = data_cache.get_timestamp_seconds();
                        if (m == m_reset) && (s == 0) {
                            self.demand_carryover_value = 0.0;
                        }
                    }
                }
                None => {}
            }
            // Now calculate the diversion
            self.demand_carryover_value += new_demand;
            if self.demand_carryover_value > available {
                // we will not meet demand
                self.diversion = available;
                self.demand_carryover_value -= self.diversion;
            } else {
                // we will meet demand (incl carryover)
                self.diversion = self.demand_carryover_value;
                self.demand_carryover_value = 0.0;
            }
        } else {
            // Not simulating carryover
            self.diversion = new_demand.min(available);
        }

        // Update the annual diversion
        if let Some(_) = self.annual_cap { self.annual_diversion += self.diversion; }

        // Extract the water and update mbal
        self.dsflow_primary = self.usflow - self.diversion;
        self.mbal -= self.diversion;

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }
        if let Some(idx) = self.recorder_idx_demand {
            data_cache.add_value_at_index(idx, new_demand);
        }
        if let Some(idx) = self.recorder_idx_diversion {
            data_cache.add_value_at_index(idx, self.diversion);
        }
        if let Some(idx) = self.recorder_idx_pump_capacity {
            data_cache.add_value_at_index(idx, self.pump_capacity_value)
        }
        if let Some(idx) = self.recorder_idx_flow_threshold {
            data_cache.add_value_at_index(idx, self.flow_threshold_value)
        }
        if let Some(idx) = self.recorder_idx_demand_carryover {
            data_cache.add_value_at_index(idx, self.demand_carryover_value)
        }
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_ids_ds_1 {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
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

    fn usorders_mut(&mut self) -> &mut [f64] {
        &mut self.usorders
    }
}
