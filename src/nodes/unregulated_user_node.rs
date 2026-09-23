use super::{recorder, Node};
use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::misc::location::Location;

const MAX_DS_LINKS: usize = 4;

/// An outlet that the user supplies water down: ds_2, ds_3 or ds_4. A node on it (a field,
/// say) places its orders with the user, which is the supply at the top of that node's
/// regulated zone. The user adds the orders to its demand as they arrive, takes what the river
/// and its own limits allow, and sends the outlet's share down the outlet the same step.
#[derive(Default, Clone)]
pub struct SupplyOutlet {
    /// Zero-based: ds_2 is 1
    pub outlet: u8,
    flow: f64, // What is sent down the outlet this step
    recorder_idx_flow: Option<usize>,
    recorder_idx_order: Option<usize>,
}

#[derive(Default, Clone)]
pub struct UnregulatedUserNode {

    // Supply outlets (ds_2 to ds_4): one entry per outlet that has a link, added as the model
    // is read (add_supply_outlet) and reset in initialise(). Empty for most users.
    //
    // These two are declared first, on measurement, which is the opposite of where the same
    // two fields sit in RegulatedUserNode: see the speed note in the commit that added
    // supply outlets (ADR-0004 §3.4).
    pub supply_outlets: Vec<SupplyOutlet>,
    has_supply_outlets: bool, // Set in initialise(): fixed for the run
    pub dsorders: [f64; MAX_DS_LINKS], // Orders arriving on ds_1 to ds_4, written by the ordering system

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

    /// Give the user a supply outlet. `outlet` is zero-based: ds_2 is 1.
    pub fn add_supply_outlet(&mut self, outlet: u8) {
        self.supply_outlets.push(SupplyOutlet { outlet, ..Default::default() });
    }

    /// Register the ordered list of accounts this node draws on.
    pub fn register_accounts(&mut self, account_idxs: Vec<usize>) {
        self.account_idxs = account_idxs;
    }
}

impl Node for UnregulatedUserNode {
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
            _ => self.remove_supply_outlet_flow(outlet),
        }
    }

    fn get_mass_balance(&self) -> f64 {
        self.mbal
    }

    fn dsorders_mut(&mut self) -> &mut [f64] {
        &mut self.dsorders
    }

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

        // Supply outlets: reset every run. Kept in outlet order whatever order the model file
        // gives them in, because that is the order they are served in when water runs short.
        self.supply_outlets.sort_by_key(|supply_outlet| supply_outlet.outlet);
        self.has_supply_outlets = !self.supply_outlets.is_empty();
        for i in 0..self.supply_outlets.len() {
            let outlet = self.supply_outlets[i].outlet;
            if outlet == 0 || outlet as usize >= MAX_DS_LINKS {
                return Err(format!("Error in node '{}'. A supply outlet must be one of ds_2 to ds_{}.", self.name, MAX_DS_LINKS));
            }
            let n = outlet + 1;
            self.supply_outlets[i] = SupplyOutlet {
                outlet,
                recorder_idx_flow: recorder(data_cache, &self.name, &format!("ds_{n}")),
                recorder_idx_order: recorder(data_cache, &self.name, &format!("ds_{n}_order")),
                ..Default::default()
            };
        }

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str { &self.name }

    fn run_order_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record downstream orders
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.dsorders[0]);
        }
        for supply_outlet in &self.supply_outlets {
            if let Some(idx) = supply_outlet.recorder_idx_order {
                data_cache.add_value_at_index(idx, self.dsorders[supply_outlet.outlet as usize]);
            }
        }
    }

    // Whether the user has supply outlets is fixed for the run, so it is decided once per step
    // here and not inside the phase. The `false` instantiation is the node as it was before
    // supply outlets existed, and it is inlined here as that code always was, so a user
    // without them pays for this one test and nothing else (ADR-0004 §3.1, and its 2026-09-14
    // amendment). The `true` instantiation is kept out of line and marked cold, so that a
    // second copy of the phase is not inlined into the step loop beside it.
    fn run_flow_phase(&mut self, data_cache: &mut DataCache, account_manager: &mut AccountManager) {
        if !self.has_supply_outlets {
            self.flow_phase::<false>(data_cache, account_manager)
        } else {
            self.flow_phase_with_supply_outlets(data_cache, account_manager)
        }
    }
}

impl UnregulatedUserNode {
    /// remove_dsflow for ds_2 to ds_4. Out of line, so that remove_dsflow stays the few
    /// instructions it was for the ds_1 every user has, and inlines into the step loop as before.
    #[cold]
    #[inline(never)]
    fn remove_supply_outlet_flow(&mut self, outlet: u8) -> f64 {
        for supply_outlet in &mut self.supply_outlets {
            if supply_outlet.outlet == outlet {
                let outflow = supply_outlet.flow;
                supply_outlet.flow = 0.0;
                return outflow;
            }
        }
        0.0
    }

    #[cold]
    #[inline(never)]
    fn flow_phase_with_supply_outlets(&mut self, data_cache: &mut DataCache, account_manager: &mut AccountManager) {
        self.flow_phase::<true>(data_cache, account_manager)
    }

    #[inline(always)]
    fn flow_phase<const SUPPLY_OUTLETS: bool>(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

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

        // The orders arriving on the supply outlets join the demand. They are served first, in
        // outlet order, within everything that limits the take above; the user's own demand
        // is served from what is left. They take no part in carryover: a node that orders to
        // meet a deficit orders that deficit again tomorrow, and carrying it over as well
        // would count it twice.
        let mut outlet_take = 0.0;
        if SUPPLY_OUTLETS {
            for supply_outlet in &mut self.supply_outlets {
                let order = self.dsorders[supply_outlet.outlet as usize].max(0.0);
                supply_outlet.flow = order.min(available - outlet_take).max(0.0);
                outlet_take += supply_outlet.flow;
            }
            available -= outlet_take;
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

        // The diversion is the whole metered take: the user's own, worked out above, and what
        // goes down the supply outlets. It is the user's water, so all of it is debited to the
        // accounts and counted against the annual cap.
        if SUPPLY_OUTLETS {
            self.diversion += outlet_take;
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

        // Extract the water and update mbal. What goes down the supply outlets stays in the
        // model; what the user keeps for its own use leaves it here.
        self.dsflow_primary = self.usflow - self.diversion;
        self.mbal -= if SUPPLY_OUTLETS { self.diversion - outlet_take } else { self.diversion };

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
        if SUPPLY_OUTLETS {
            for supply_outlet in &self.supply_outlets {
                if let Some(idx) = supply_outlet.recorder_idx_flow {
                    data_cache.add_value_at_index(idx, supply_outlet.flow);
                }
            }
        }
        if let Some(idx) = self.recorder_idx_dsflow {
            // Total dsflow, all outlets
            data_cache.add_value_at_index(idx, if SUPPLY_OUTLETS { self.dsflow_primary + outlet_take } else { self.dsflow_primary });
        }
        if let Some(idx) = self.recorder_ids_ds_1 {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }

        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }




}
