use super::Node;
use crate::misc::misc_functions::make_result_name;
use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::misc::location::Location;

const MAX_DS_LINKS: usize = 1;

#[derive(Default, Clone)]
pub struct UnregulatedUserNode {

    // Properties - basic
    pub name: String,
    pub location: Location,
    pub mbal: f64,
    pub demand_input: DynamicInput,

    // Properties - unreg user stuff
    pub pump_capacity: DynamicInput,
    pub flow_threshold: DynamicInput,
    pub annual_cap: Option<f64>,
    pub annual_cap_reset_month: u32,
    pub demand_carryover_allowed: bool,
    pub demand_carryover_reset_month: Option<u32>,

    // Internal state only
    pub dsorders: [f64; MAX_DS_LINKS],
    usflow: f64,
    dsflow_primary: f64,
    diversion: f64,
    annual_diversion: f64,
    pump_capacity_value: f64,
    flow_threshold_value: f64,
    demand_carryover_value: f64,

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_pump_capacity: Option<usize>,
    recorder_idx_flow_threshold: Option<usize>,
    recorder_idx_demand_carryover: Option<usize>,
    recorder_idx_order: Option<usize>,
    recorder_idx_order_due: Option<usize>,
    recorder_idx_demand: Option<usize>,
    recorder_idx_diversion: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_ids_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
}


impl UnregulatedUserNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            demand_input: DynamicInput::default(),
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

impl Node for UnregulatedUserNode {
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
        self.recorder_idx_pump_capacity = data_cache.get_series_idx(
            make_result_name(&self.name, "pump_capacity").as_str(), false
        );
        self.recorder_idx_flow_threshold = data_cache.get_series_idx(
            make_result_name(&self.name, "flow_threshold").as_str(), false
        );
        self.recorder_idx_demand_carryover = data_cache.get_series_idx(
            make_result_name(&self.name, "demand_carryover").as_str(), false
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

    fn run_flow_phase(&mut self, data_cache: &mut DataCache) {

        // Get demand value
        let new_demand = self.demand_input.get_value(data_cache);

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
        if let Some(idx) = self.recorder_idx_order {
            data_cache.add_value_at_index(idx, 0.0);
        }
        if let Some(idx) = self.recorder_idx_order_due {
            data_cache.add_value_at_index(idx, 0.0);
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
