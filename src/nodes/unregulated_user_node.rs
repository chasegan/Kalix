use super::{recorder, single_outlet_node_impls, Node};
use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
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
    /// Ordered account references (deemed order-of-use): the take draws the
    /// first account down before touching the second, so available volume is
    /// the sum and the debit cascades (kalix-allocation-components.md §3.6).
    pub account_idxs: Vec<usize>,
    pub annual_cap_reset_month: u8,
    pub demand_carryover_allowed: bool,
    pub demand_carryover_reset_month: Option<u8>,

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

    /// Register the ordered list of accounts this node draws on.
    pub fn register_accounts(&mut self, account_idxs: Vec<usize>) {
        self.account_idxs = account_idxs;
    }
}

impl Node for UnregulatedUserNode {
    single_outlet_node_impls!();

    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String> {
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
        self.recorder_idx_usflow = recorder(data_cache, &self.name, "usflow");
        self.recorder_idx_pump_capacity = recorder(data_cache, &self.name, "pump");
        self.recorder_idx_flow_threshold = recorder(data_cache, &self.name, "flow_threshold");
        self.recorder_idx_demand_carryover = recorder(data_cache, &self.name, "demand_carryover");
        self.recorder_idx_order = recorder(data_cache, &self.name, "order");
        self.recorder_idx_order_due = recorder(data_cache, &self.name, "order_due");
        self.recorder_idx_demand = recorder(data_cache, &self.name, "demand");
        self.recorder_idx_diversion = recorder(data_cache, &self.name, "diversion");
        self.recorder_idx_dsflow = recorder(data_cache, &self.name, "dsflow");
        self.recorder_ids_ds_1 = recorder(data_cache, &self.name, "ds_1");
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
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

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
                    let m = data_cache.get_timestamp_month() as u8;
                    let s = data_cache.get_timestamp_seconds();
                    if (m == self.annual_cap_reset_month) && (s == 0) {
                        self.annual_diversion = 0.0;
                    }
                }
                available = available.min(annual_cap - self.annual_diversion);
            }
        }

        // Restrict take based on accounts if applicable: available volume is
        // the sum across the ordered account list
        if !self.account_idxs.is_empty() {
            let total_balance: f64 = self.account_idxs.iter()
                .map(|&idx| _account_manager.get_account_balance(idx).max(0.0))
                .sum();
            available = available.min(total_balance);
        }

        // Carryover
        if self.demand_carryover_allowed {
            // Allowing demand carryover
            // Check if we need to reset the demand carryover today
            if let Some(m_reset) = self.demand_carryover_reset_month {
                let d = data_cache.get_timestamp_day();
                if d == 1 {
                    let m = data_cache.get_timestamp_month() as u8;
                    let s = data_cache.get_timestamp_seconds();
                    if (m == m_reset) && (s == 0) {
                        self.demand_carryover_value = 0.0;
                    }
                }
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

        // Debit the diversion across accounts in order of use: drain the first
        // account before touching the second
        let mut remaining = self.diversion;
        for &account_idx in &self.account_idxs {
            if remaining <= 0.0 { break; }
            let take = remaining.min(_account_manager.get_account_balance(account_idx).max(0.0));
            if take > 0.0 {
                _account_manager.debit_account(account_idx, take);
                remaining -= take;
            }
        }

        // Update the annual diversion
        if let Some(_) = self.annual_cap { self.annual_diversion += self.diversion; }

        // Extract the water and update mbal
        self.dsflow_primary = self.usflow - self.diversion;
        self.mbal -= self.diversion;

        // Record results
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

        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }




}
