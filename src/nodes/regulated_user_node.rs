use super::{recorder, single_outlet_node_impls, Node};
use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
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
    /// Optional flow-phase demand for water above the arriving order (e.g.
    /// off-allocation access announced on flow conditions). Evaluated at flow
    /// time, supplied from what the regulated delivery leaves behind, and
    /// debited to the same accounts.
    pub opportunistic_demand: DynamicInput,
    /// Ordered account references (deemed order-of-use). Orders are capped by
    /// the summed balance at order time, deliveries are capped and debited at
    /// flow time — debit-on-use semantics (kalix-allocation-components.md §3.6).
    pub account_idxs: Vec<usize>,

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
    recorder_idx_diversion_regulated: Option<usize>,
    recorder_idx_diversion_opportunistic: Option<usize>,
    recorder_idx_opportunistic_demand: Option<usize>,
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
            opportunistic_demand: DynamicInput::default(),
            order_buffer: FifoBuffer::default(),
            ..Default::default()
        }
    }

    /// Register the ordered list of accounts this node draws on.
    pub fn register_accounts(&mut self, account_idxs: Vec<usize>) {
        self.account_idxs = account_idxs;
    }

    fn total_account_balance(&self, account_manager: &AccountManager) -> f64 {
        self.account_idxs.iter()
            .map(|&idx| account_manager.get_account_balance(idx).max(0.0))
            .sum()
    }
}

impl Node for RegulatedUserNode {
    single_outlet_node_impls!();

    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String> {
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
        self.recorder_idx_usflow = recorder(data_cache, &self.name, "usflow");
        self.recorder_idx_pump_capacity = recorder(data_cache, &self.name, "pump");
        self.recorder_idx_order = recorder(data_cache, &self.name, "order");
        self.recorder_idx_order_due = recorder(data_cache, &self.name, "order_due");
        self.recorder_idx_demand = recorder(data_cache, &self.name, "demand");
        self.recorder_idx_diversion = recorder(data_cache, &self.name, "diversion");
        self.recorder_idx_diversion_regulated = recorder(data_cache, &self.name, "diversion_regulated");
        self.recorder_idx_diversion_opportunistic = recorder(data_cache, &self.name, "diversion_opportunistic");
        self.recorder_idx_opportunistic_demand = recorder(data_cache, &self.name, "opportunistic_demand");
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

        self.order_value = self.order_input.get_value(data_cache);

        // Cap the order by what the user owns: don't order water you can't take
        // (§3.6 — enforced where orders originate, not inside the storage)
        if !self.account_idxs.is_empty() {
            self.order_value = self.order_value.min(self.total_account_balance(_account_manager));
        }

        // TODO: is this where things are supposed to happen?

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

    fn run_flow_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

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

        // Determine the regulated diversion value
        // assume demand = order_due
        let mut diversion_regulated = self.order_due.min(available);

        // Opportunistic take: demand for water above the arriving order (e.g.
        // off-allocation access), supplied from whatever availability the
        // regulated delivery leaves behind
        let mut opportunistic_demand_value = 0.0;
        let mut diversion_opportunistic = 0.0;
        match self.opportunistic_demand {
            DynamicInput::None { .. } => {}
            _ => {
                opportunistic_demand_value = self.opportunistic_demand.get_value(data_cache).max(0.0);
                diversion_opportunistic = opportunistic_demand_value.min(available - diversion_regulated);
            }
        };

        // Cap delivery by current holdings and debit the metered take across
        // accounts in order of use (debit-on-use; balances may have moved since
        // the order was placed). The regulated delivery has first claim on the
        // balance; the opportunistic take gets what remains.
        if !self.account_idxs.is_empty() {
            let balance = self.total_account_balance(_account_manager);
            diversion_regulated = diversion_regulated.min(balance);
            diversion_opportunistic = diversion_opportunistic.min(balance - diversion_regulated);
            let mut remaining = diversion_regulated + diversion_opportunistic;
            for &account_idx in &self.account_idxs {
                if remaining <= 0.0 { break; }
                let take = remaining.min(_account_manager.get_account_balance(account_idx).max(0.0));
                if take > 0.0 {
                    _account_manager.debit_account(account_idx, take);
                    remaining -= take;
                }
            }
        }
        self.diversion = diversion_regulated + diversion_opportunistic;

        // Extract the water and update mbal
        self.dsflow_primary = self.usflow - self.diversion;
        self.mbal -= self.diversion;

        // Record results
        if let Some(idx) = self.recorder_idx_diversion {
            data_cache.add_value_at_index(idx, self.diversion);
        }
        if let Some(idx) = self.recorder_idx_diversion_regulated {
            data_cache.add_value_at_index(idx, diversion_regulated);
        }
        if let Some(idx) = self.recorder_idx_diversion_opportunistic {
            data_cache.add_value_at_index(idx, diversion_opportunistic);
        }
        if let Some(idx) = self.recorder_idx_opportunistic_demand {
            data_cache.add_value_at_index(idx, opportunistic_demand_value);
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

        // Reset upstream inflow for next timestep
        self.usflow = 0.0;
    }




}
