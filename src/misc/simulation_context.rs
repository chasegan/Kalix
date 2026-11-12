//! Simulation context tracking for panic error reporting
//!
//! This module provides thread-local storage for tracking the current simulation state.
//! When a panic occurs during simulation, this context is used to provide helpful
//! error messages indicating where the failure occurred.

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

// Two independent Cells rather than a RefCell<struct>: the setters run per
// node per timestep (hundreds of millions of times on a big run) and a Cell
// store is a plain write - no borrow-flag check, no branch, nothing to poison
// (per ADR-0004 §3.5: the context is written hot, read only
// on the cold panic path).
thread_local! {
    static SIM_PHASE: Cell<SimPhase> = const { Cell::new(SimPhase::Unknown) };
    static SIM_NODE: Cell<usize> = const { Cell::new(NO_NODE) };
}

/// Sentinel value indicating no node is set (usize::MAX is never a valid node index)
const NO_NODE: usize = usize::MAX;

/// A var block shares the node cell, tagged by the top bit (no model has 2^62 nodes)
const VAR_BLOCK_TAG: usize = 1 << (usize::BITS - 1);

/// A [ras.*] section shares it too, tagged by the next bit
const RAS_SECTION_TAG: usize = 1 << (usize::BITS - 2);

/// What was running when the context was last set
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

/// Set the current node index
#[inline]
pub fn set_context_node(node_idx: usize) {
    SIM_NODE.with(|c| c.set(node_idx));
}

/// Set the current var block index, so an error inside it names the block, not the last node
#[inline]
pub fn set_context_var_block(vb_idx: usize) {
    SIM_NODE.with(|c| c.set(VAR_BLOCK_TAG | vb_idx));
}

/// Set the current [ras.*] section index
#[inline]
pub fn set_context_ras_section(ras_idx: usize) {
    SIM_NODE.with(|c| c.set(RAS_SECTION_TAG | ras_idx));
}

/// Clear the current item, for work that is none of them (the step's accounting)
#[inline]
pub fn clear_context_item() {
    SIM_NODE.with(|c| c.set(NO_NODE));
}

/// Get the raw context for error formatting
pub fn get_context() -> (SimPhase, Option<ContextItem>) {
    let phase = SIM_PHASE.with(|c| c.get());
    let raw = SIM_NODE.with(|c| c.get());
    let item = if raw == NO_NODE {
        None
    } else if raw & VAR_BLOCK_TAG != 0 {
        Some(ContextItem::VarBlock(raw & !VAR_BLOCK_TAG))
    } else if raw & RAS_SECTION_TAG != 0 {
        Some(ContextItem::RasSection(raw & !RAS_SECTION_TAG))
    } else {
        Some(ContextItem::Node(raw))
    };
    (phase, item)
}

/// Clear the simulation context (called when simulation completes or before starting)
pub fn clear_context() {
    SIM_PHASE.with(|c| c.set(SimPhase::Unknown));
    SIM_NODE.with(|c| c.set(NO_NODE));
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

/// Format a simulation error with context information. `name_fn` gives the
/// name of the node, var block or [ras.*] section the context points at.
pub fn format_simulation_error<F>(
    panic_info: Box<dyn Any + Send>,
    timestamp: u64,
    name_fn: F,
) -> String
where
    F: Fn(ContextItem) -> Option<String>,
{
    let panic_msg = extract_panic_message(panic_info);
    let (phase, item) = get_context();
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
