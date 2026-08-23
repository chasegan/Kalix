use super::{Node, recorder};
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::misc::location::Location;
use crate::model_inputs::DynamicInput;
use crate::numerical::fifo_buffer::FifoBuffer;

const MAX_US_LINKS: usize = 2; //TODO: not sure how to police this
const MAX_DS_LINKS: usize = 1;

/// How downstream orders split across the two upstream branches, resolved at
/// ordering initialise (the `regulated =` property):
/// - `Harmony`: `harmony_fraction` × orders up us_1, the rest up us_2 —
///   the legacy mode (unnamed branches, first regulated link = us_1) and the
///   two-name mode (first named = us_1, so the fraction's direction is
///   unambiguous).
/// - `AllToUs1`: one `regulated` pathway named — every order goes up it,
///   immediately (no lag-differential buffering: there is no second pathway
///   to synchronise with).
#[derive(Default, Clone, Copy, PartialEq, Eq, Debug)]
pub enum OrderSplit {
    #[default]
    Harmony,
    AllToUs1,
}

#[derive(Default, Clone)]
pub struct ConfluenceNode {
    pub name: String,
    pub location: Location,
    pub mbal: f64,

    // Harmony fraction
    pub harmony_fraction: DynamicInput,
    pub harmony_fraction_value: f64,
    pub remaining_order: f64,

    // Internal state for order delays
    pub us_1_link_idx: Option<usize>,
    pub us_1_lag: usize,
    pub us_2_lag: usize,
    pub us_1_order_buffer: FifoBuffer, //The order buffers are used to lag orders directed up the short
    pub us_2_order_buffer: FifoBuffer, //pathway. At least one of these buffers will have zero length.
    pub order_prepared_for_us_1_buffer: Option<f64>,
    pub order_prepared_for_us_2_buffer: Option<f64>,

    // Internal state only
    usflow: f64,
    dsflow_primary: f64,

    // Orders
    pub expected_inflow_input: DynamicInput,
    pub dsorders: [f64; MAX_DS_LINKS],
    pub total_outgoing_order: f64, // Memoise for simple_nodewise_ordering.rs

    // Recorders
    recorder_idx_usflow: Option<usize>,
    recorder_idx_dsflow: Option<usize>,
    recorder_idx_ds_1: Option<usize>,
    recorder_idx_ds_1_order: Option<usize>,
    recorder_idx_harmony_fraction: Option<usize>,
    recorder_idx_expected_inflow: Option<usize>,

    // --- Cold configuration, grouped last for readability. Note this does
    // NOT control memory layout: rustc reorders repr(Rust) fields, and
    // regulated_upstream in fact lands mid-struct. A ~3% Proserpine
    // regression was once attributed to declaration order here; that
    // explanation did not survive re-measurement (per performance §3.4,
    // retracted 2026-08). Group these here because they read better
    // together, not because it buys anything at run time.
    /// Named regulated ordering pathway(s) — the `regulated =` property, as
    /// written (upstream node names, order preserved). Resolved to links and
    /// an OrderSplit by the ordering system's initialise; empty = legacy
    /// harmony behaviour.
    pub regulated_upstream: Vec<String>,
    /// Resolved split mode (see OrderSplit). Set by ordering initialise.
    pub order_split: OrderSplit,
    /// Pinned by `regulated =` (second name listed); None in legacy mode,
    /// where any non-us_1 regulated link is us_2 by elimination.
    pub us_2_link_idx: Option<usize>,
}

impl ConfluenceNode {

    /// Base constructor
    pub fn new() -> Self {
        Self {
            name: "".to_string(),
            ..Default::default()
        }
    }
}

impl Node for ConfluenceNode {
    fn initialise(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) -> Result<(), String> {
        // Initialize only internal state
        self.mbal = 0.0;
        self.usflow = 0.0;
        self.dsflow_primary = 0.0;

        // Harmony
        self.harmony_fraction_value = 1.0; //100% for link 1. This will be overwritten anyway.
        self.remaining_order = 0.0;

        // State. The ordering system's initialise runs after node initialise
        // and re-resolves `regulated =` into these fields.
        self.order_split = OrderSplit::Harmony;
        self.us_1_link_idx = None;
        self.us_2_link_idx = None;
        self.us_1_lag = 0;
        self.us_2_lag = 0;
        self.us_1_order_buffer = FifoBuffer::default();
        self.us_2_order_buffer = FifoBuffer::default();
        self.total_outgoing_order = 0.0;

        // Initialize result recorders
        self.recorder_idx_usflow = recorder(data_cache, &self.name, "usflow");
        self.recorder_idx_dsflow = recorder(data_cache, &self.name, "dsflow");
        self.recorder_idx_ds_1 = recorder(data_cache, &self.name, "ds_1");
        self.recorder_idx_ds_1_order = recorder(data_cache, &self.name, "ds_1_order");
        self.recorder_idx_harmony_fraction = recorder(data_cache, &self.name, "harmony_fraction");
        self.recorder_idx_expected_inflow = recorder(data_cache, &self.name, "expected_inflow");

        // Return
        Ok(())
    }

    fn get_name(&self) -> &str { &self.name }

    fn run_order_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {
        // Evaluate expected inflow
        let expected_inflow_value_on_delivery_timestep = self.expected_inflow_input.get_value(data_cache);
        if let Some(idx) = self.recorder_idx_expected_inflow {
            data_cache.add_value_at_index(idx, expected_inflow_value_on_delivery_timestep);
        }

        // Record downstream orders
        if let Some(idx) = self.recorder_idx_ds_1_order {
            data_cache.add_value_at_index(idx, self.dsorders[0]);
        }
        if let Some(idx) = self.recorder_idx_harmony_fraction {
            data_cache.add_value_at_index(idx, self.harmony_fraction_value);
        }

        self.total_outgoing_order = (
            self.dsorders.iter().sum::<f64>()
            - expected_inflow_value_on_delivery_timestep
        ).max(0f64);

        // Refer to simple_nodewise_ordering.rs for order propagation logic.
    }

    fn run_flow_phase(&mut self, data_cache: &mut DataCache, _account_manager: &mut AccountManager) {

        // Record results
        if let Some(idx) = self.recorder_idx_usflow {
            data_cache.add_value_at_index(idx, self.usflow);
        }

        // For confluence nodes, outflow equals upstream inflow
        self.dsflow_primary = self.usflow;

        // Update mass balance
        // self.mbal = 0.0; // This is always zero for Confluence nodes

        // Record results
        if let Some(idx) = self.recorder_idx_dsflow {
            data_cache.add_value_at_index(idx, self.dsflow_primary);
        }
        if let Some(idx) = self.recorder_idx_ds_1 {
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
}

