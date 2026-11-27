//! Simulation context tracking for panic error reporting
//!
//! The phase is held in a thread-local, which the CLI's panic hook reads; the
//! item that is running is held in `DataCache::context_item`. When a run
//! panics, both name where it failed.

use std::any::Any;
use std::cell::Cell;
use std::panic;
use crate::tid::utils::u64_to_auto_datetime_string;

/// Simulation phase
#[derive(Clone, Copy, Default, PartialEq)]
pub enum SimPhase {
    #[default]
    Unknown,
    /// The ras slot at the top of the step: assessments and [ras.*] sections
    Ras,
    Ordering,
    Flow,
}

impl SimPhase {
    pub fn as_str(&self) -> &'static str {
        match self {
            SimPhase::Unknown => "unknown",
            SimPhase::Ras => "ras",
            SimPhase::Ordering => "ordering",
            SimPhase::Flow => "flow",
        }
    }
}

// The phase is set three times a step. The item is written per item into
// `DataCache::context_item`, a plain field, not a thread-local.
thread_local! {
    static SIM_PHASE: Cell<SimPhase> = const { Cell::new(SimPhase::Unknown) };
}

/// No item is running (usize::MAX is never a valid item, tagged or not)
pub const NO_ITEM: usize = usize::MAX;

/// A var block's item is its index tagged by the top bit (no model has 2^62 nodes)
const VAR_BLOCK_TAG: usize = 1 << (usize::BITS - 1);

/// A [ras.*] section's is tagged by the next bit
const RAS_SECTION_TAG: usize = 1 << (usize::BITS - 2);

/// What was running when the item was last written
#[derive(Clone, Copy, Debug, PartialEq)]
pub enum ContextItem {
    Node(usize),
    VarBlock(usize),
    RasSection(usize),
}

/// Set the current phase
#[inline]
pub fn set_context_phase(phase: SimPhase) {
    SIM_PHASE.with(|c| c.set(phase));
}

/// The raw item for a node
#[inline(always)]
pub const fn node_item(node_idx: usize) -> usize {
    node_idx
}

/// The raw item for a var block, so an error inside it names the block, not the last node
#[inline(always)]
pub const fn var_block_item(vb_idx: usize) -> usize {
    VAR_BLOCK_TAG | vb_idx
}

/// The raw item for a [ras.*] section
#[inline(always)]
pub const fn ras_section_item(ras_idx: usize) -> usize {
    RAS_SECTION_TAG | ras_idx
}

/// Decode a raw item
fn decode_item(raw: usize) -> Option<ContextItem> {
    if raw == NO_ITEM {
        None
    } else if raw & VAR_BLOCK_TAG != 0 {
        Some(ContextItem::VarBlock(raw & !VAR_BLOCK_TAG))
    } else if raw & RAS_SECTION_TAG != 0 {
        Some(ContextItem::RasSection(raw & !RAS_SECTION_TAG))
    } else {
        Some(ContextItem::Node(raw))
    }
}

/// Clear the simulation phase (called before a run, and when it completes or fails)
pub fn clear_context() {
    SIM_PHASE.with(|c| c.set(SimPhase::Unknown));
}

/// Check if we're currently inside a simulation (phase is not Unknown)
pub fn is_in_simulation() -> bool {
    SIM_PHASE.with(|c| c.get() != SimPhase::Unknown)
}

/// Install a custom panic hook that suppresses output when inside a simulation.
/// Panics outside simulation context still print normally.
pub fn install_simulation_panic_hook() {
    let default_hook = panic::take_hook();
    panic::set_hook(Box::new(move |info| {
        if !is_in_simulation() {
            default_hook(info);
        }
        // Silent when in simulation - we'll format our own error message
    }));
}

/// Extract a message string from panic info
fn extract_panic_message(panic_info: Box<dyn Any + Send>) -> String {
    if let Some(s) = panic_info.downcast_ref::<&str>() {
        s.to_string()
    } else if let Some(s) = panic_info.downcast_ref::<String>() {
        s.clone()
    } else {
        "no_panic_message".to_string()
    }
}

/// Format a simulation error with context information. `raw_item` is the
/// last item written; `name_fn` gives the name of the node, var block or
/// [ras.*] section it points at.
pub fn format_simulation_error<F>(
    panic_info: Box<dyn Any + Send>,
    timestamp: u64,
    raw_item: usize,
    name_fn: F,
) -> String
where
    F: Fn(ContextItem) -> Option<String>,
{
    let panic_msg = extract_panic_message(panic_info);
    let phase = SIM_PHASE.with(|c| c.get());
    let item = decode_item(raw_item);
    let timestamp_str = u64_to_auto_datetime_string(timestamp);

    let item_str = match item {
        Some(it @ ContextItem::Node(idx)) => format!(
            "Node: '{}'", name_fn(it).unwrap_or_else(|| format!("node_idx_{}", idx))),
        Some(it @ ContextItem::VarBlock(idx)) => format!(
            "Var block: '[var.{}]'", name_fn(it).unwrap_or_else(|| format!("var_block_idx_{}", idx))),
        Some(it @ ContextItem::RasSection(idx)) => format!(
            "Ras section: '[ras.{}]'", name_fn(it).unwrap_or_else(|| format!("ras_idx_{}", idx))),
        None => "Node: 'unknown_node'".to_string(),
    };

    format!(
        "{}, Phase: {}, {}, Msg: '{}'",
        timestamp_str,
        phase.as_str(),
        item_str,
        panic_msg,
    )
}
