use super::{recorder, Node};
use crate::model_inputs::DynamicInput;
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::misc::location::Location;
use crate::numerical::fifo_buffer::FifoBuffer;

const MAX_DS_LINKS: usize = 4;

/// An outlet that the user supplies water down: ds_2, ds_3 or ds_4. A node on it (a field,
/// say) places its orders with the user. The user adds them to its own order, and when the
/// ordered water arrives it diverts it and sends it down the outlet.
#[derive(Default, Clone)]
pub struct SupplyOutlet {
    /// Zero-based: ds_2 is 1
    pub outlet: u8,
    /// Holds each accepted order for the user's travel time, so that it is delivered on the
    /// step the ordered water arrives at the user. Sized by the ordering system.
    pub order_buffer: FifoBuffer,
    order: f64,     // The order arriving on this outlet, this step
    order_due: f64, // The accepted order that falls due today
    flow: f64,      // What is sent down the outlet this step
    recorder_idx_flow: Option<usize>,
    recorder_idx_order: Option<usize>,
    recorder_idx_order_due: Option<usize>,
}

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
    /// Order-authorisation accounts (debit-on-order). They extend the order
    /// cap, and the portion of the approved order beyond the regular balance
    /// is debited from them immediately at order time, walked in list order.
    /// They are invisible to the flow phase: never part of the delivery cap,
    /// never debited by takes, never refunded for undelivered orders.
    pub order_account_idxs: Vec<usize>,

    // Internal state only
    order_due: f64,
    /// Factor applied to this node's own order as it is sent upstream:
    /// the network sees order_factor * order, while `order`, `order_due` and
    /// the delivery use the order as placed. 1 (the default) is a no-op:
    /// 1.0 * x is x bit for bit, so there is no branch to skip the multiply.
    pub order_factor: f64,
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

    // Supply outlets (ds_2 to ds_4): one entry per outlet that has a link, added as the model
    // is read (add_supply_outlet) and reset in initialise(). Empty for most users.
    //
    // These two are declared last, with nothing above them moved, on measurement: see the
    // speed note in the commit that added supply outlets (ADR-0004 §3.4).
    pub supply_outlets: Vec<SupplyOutlet>,
    pub dsorders: [f64; MAX_DS_LINKS], // Orders arriving on ds_1 to ds_4, written by the ordering system
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
            order_factor: 1.0,
            ..Default::default()
        }
    }

    /// Give the user a supply outlet. `outlet` is zero-based: ds_2 is 1.
    pub fn add_supply_outlet(&mut self, outlet: u8) {
        self.supply_outlets.push(SupplyOutlet { outlet, ..Default::default() });
    }

    /// The order this node places: its own order plus the orders accepted from its supply
    /// outlets. The network sees order_factor times this.
    #[inline]
    pub fn order_placed(&self) -> f64 {
        let mut order_placed = self.order_value;
        for supply_outlet in &self.supply_outlets {
            order_placed += supply_outlet.order;
        }
        order_placed
    }

    /// Register the ordered list of accounts this node draws on.
    pub fn register_accounts(&mut self, account_idxs: Vec<usize>) {
        self.account_idxs = account_idxs;
    }

    /// Register the ordered list of order-authorisation accounts.
    pub fn register_order_accounts(&mut self, account_idxs: Vec<usize>) {
        self.order_account_idxs = account_idxs;
    }

    fn total_account_balance(&self, account_manager: &AccountManager) -> f64 {
        self.account_idxs.iter()
            .map(|&idx| account_manager.get_account_balance(idx).max(0.0))
            .sum()
    }

    fn total_order_account_balance(&self, account_manager: &AccountManager) -> f64 {
        self.order_account_idxs.iter()
            .map(|&idx| account_manager.get_account_balance(idx).max(0.0))
            .sum()
    }
}

impl Node for RegulatedUserNode {
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
            _ => {
                for supply_outlet in &mut self.supply_outlets {
                    if supply_outlet.outlet == outlet {
                        let outflow = supply_outlet.flow;
                        supply_outlet.flow = 0.0;
                        return outflow;
                    }
                }
                0.0
            }
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
        self.pump_capacity_value = f64::INFINITY;

        // Reset order state, so a rerun of the same model object starts clean.
        // The ordering system (which initialises after the nodes) only replaces
        // the buffer when it finds a longer travel time than the one stored, so
        // without this a second run keeps the first run's buffer, and the first
        // run's final orders fall due at the start of the second.
        self.order_travel_time = 0;
        self.order_buffer = FifoBuffer::default();
        self.order_value = 0.0;
        self.order_due = 0.0;

        // Checks
        if !(self.order_factor >= 0.0 && self.order_factor.is_finite()) {
            return Err(format!("Error in node '{}'. order_factor must be a finite, non-negative factor, got {}.", self.name, self.order_factor));
        }

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

        // Supply outlets: reset every run, with zero-length order buffers that the ordering
        // system (which initialises after the nodes) sizes from the travel time. Kept in
        // outlet order whatever order the model file gives them in, because that is the
        // order they are served in when water or account balance runs short.
        self.supply_outlets.sort_by_key(|supply_outlet| supply_outlet.outlet);
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
                recorder_idx_order_due: recorder(data_cache, &self.name, &format!("ds_{n}_order_due")),
                ..Default::default()
            };
        }

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str { &self.name }


    // Whether the user has supply outlets is fixed for the run, so it is decided once per
    // phase here and not at each step inside it. The `false` instantiations are the node as
    // it was before supply outlets existed, so a user without them pays for this one test
    // and nothing else (ADR-0004 §3.1, and its 2026-09-14 amendment). Kept out of line so
    // that two copies of each phase are not inlined into the step loop.
    fn run_order_phase(&mut self, data_cache: &mut DataCache, account_manager: &mut AccountManager) {
        if self.supply_outlets.is_empty() {
            self.order_phase::<false>(data_cache, account_manager)
        } else {
            self.order_phase::<true>(data_cache, account_manager)
        }
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache, account_manager: &mut AccountManager) {
        if self.supply_outlets.is_empty() {
            self.flow_phase::<false>(data_cache, account_manager)
        } else {
            self.flow_phase::<true>(data_cache, account_manager)
        }
    }
}

impl RegulatedUserNode {
    #[inline(never)]
    fn order_phase<const SUPPLY_OUTLETS: bool>(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record downstream orders
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.dsorders[0]);
        }

        // The orders arriving on the supply outlets. They become this user's order: they are
        // capped by its accounts and scaled by its order_factor along with its own.
        if SUPPLY_OUTLETS {
            for supply_outlet in &mut self.supply_outlets {
                supply_outlet.order = self.dsorders[supply_outlet.outlet as usize].max(0.0);
                if let Some(idx) = supply_outlet.recorder_idx_order {
                    data_cache.add_value_at_index(idx, supply_outlet.order);
                }
            }
        }

        self.order_value = self.order_input.get_value(data_cache);

        // Cap the order by what the user owns: don't order water you can't take
        // (§3.6 — enforced where orders originate, not inside the storage).
        // order_accounts extend the cap, and the portion of the approved order
        // beyond the regular balance is debited from them NOW (debit-on-order,
        // excess-only, walked in list order). Regular accounts keep
        // debit-on-use at flow time; the two never pay for the same water.
        //
        // Where the cap bites, the supply outlets are served first, in outlet order, and the
        // user's own order takes what is left - the same order of service as at flow time.
        if !self.account_idxs.is_empty() || !self.order_account_idxs.is_empty() {
            let regular_balance = self.total_account_balance(_account_manager);
            let order_balance = self.total_order_account_balance(_account_manager);
            let mut remaining = regular_balance + order_balance;
            let mut outlet_orders_accepted = 0.0;
            if SUPPLY_OUTLETS {
                for supply_outlet in &mut self.supply_outlets {
                    supply_outlet.order = supply_outlet.order.min(remaining);
                    remaining -= supply_outlet.order;
                    outlet_orders_accepted += supply_outlet.order;
                }
            }
            // Ensure non-negativity of orders, as below: a negative order is no order
            self.order_value = self.order_value.max(0.0).min(remaining);
            let order_placed = if SUPPLY_OUTLETS { outlet_orders_accepted + self.order_value } else { self.order_value };
            let mut excess = (order_placed - regular_balance).max(0.0);
            for &account_idx in &self.order_account_idxs {
                if excess <= 0.0 { break; }
                let debit = excess.min(_account_manager.get_account_balance(account_idx).max(0.0));
                if debit > 0.0 {
                    _account_manager.debit_account(account_idx, debit);
                    excess -= debit;
                }
            }
        } else {
            // Ensure non-negativity of orders
            self.order_value = self.order_value.max(0.0);
        }

        // TODO: is this where things are supposed to happen?

        // Get demand value (this is equal to our old order, which is due to arrive today)
        self.order_due = self.order_buffer.push(self.order_value);

        // Each supply outlet's accepted order is held for the same travel time
        if SUPPLY_OUTLETS {
            for supply_outlet in &mut self.supply_outlets {
                supply_outlet.order_due = supply_outlet.order_buffer.push(supply_outlet.order);
                if let Some(idx) = supply_outlet.recorder_idx_order_due {
                    data_cache.add_value_at_index(idx, supply_outlet.order_due);
                }
            }
        }

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

    #[inline(never)]
    fn flow_phase<const SUPPLY_OUTLETS: bool>(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

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
        // The orders due on the supply outlets are served first, in outlet order, and the
        // user's own order from what is left.
        let mut outlet_take = 0.0;
        if SUPPLY_OUTLETS {
            for supply_outlet in &mut self.supply_outlets {
                supply_outlet.flow = supply_outlet.order_due.min(available - outlet_take);
                outlet_take += supply_outlet.flow;
            }
        }
        let mut own_regulated = if SUPPLY_OUTLETS { self.order_due.min(available - outlet_take) } else { self.order_due.min(available) };
        let mut diversion_regulated = if SUPPLY_OUTLETS { outlet_take + own_regulated } else { own_regulated };

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
        // balance - the supply outlets first, then the user's own order; the
        // opportunistic take gets what remains. The whole take is debited, the
        // supply outlets' share included: it is the user's water.
        if !self.account_idxs.is_empty() {
            let balance = self.total_account_balance(_account_manager);
            if SUPPLY_OUTLETS {
                let mut remaining_balance = balance;
                outlet_take = 0.0;
                for supply_outlet in &mut self.supply_outlets {
                    supply_outlet.flow = supply_outlet.flow.min(remaining_balance);
                    remaining_balance -= supply_outlet.flow;
                    outlet_take += supply_outlet.flow;
                }
                own_regulated = own_regulated.min(remaining_balance);
                diversion_regulated = outlet_take + own_regulated;
            } else {
                diversion_regulated = diversion_regulated.min(balance);
            }
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

        // Extract the water and update mbal. What goes down the supply outlets stays in the
        // model; what the user keeps for its own use leaves it here.
        self.dsflow_primary = self.usflow - self.diversion;
        self.mbal -= if SUPPLY_OUTLETS { self.diversion - outlet_take } else { self.diversion };

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
