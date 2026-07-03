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
    Ordering,
    Flow,
}

impl SimPhase {
    pub fn as_str(&self) -> &'static str {
        match self {
            SimPhase::Unknown => "unknown",
            SimPhase::Ordering => "ordering",
            SimPhase::Flow => "flow",
        }
    }
}

// Two independent Cells rather than a RefCell<struct>: the setters run per
// node per timestep (hundreds of millions of times on a big run) and a Cell
// store is a plain write - no borrow-flag check, no branch, nothing to poison
// (per manifestos/performance.md §3.5: the context is written hot, read only
// on the cold panic path).
thread_local! {
    static SIM_PHASE: Cell<SimPhase> = const { Cell::new(SimPhase::Unknown) };
    static SIM_NODE: Cell<usize> = const { Cell::new(NO_NODE) };
}

/// Sentinel value indicating no node is set (usize::MAX is never a valid node index)
const NO_NODE: usize = usize::MAX;

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

/// Get the raw context for error formatting
pub fn get_context() -> (SimPhase, Option<usize>) {
    let phase = SIM_PHASE.with(|c| c.get());
    let node_idx = SIM_NODE.with(|c| c.get());
    let node = if node_idx == NO_NODE { None } else { Some(node_idx) };
    (phase, node)
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

/// Format a simulation error with context information.
pub fn format_simulation_error<F>(
    panic_info: Box<dyn Any + Send>,
    timestamp: u64,
    node_name_fn: F,
) -> String
where
    F: Fn(usize) -> Option<String>,
{
    let panic_msg = extract_panic_message(panic_info);
    let (phase, node_idx) = get_context();
    let timestamp_str = u64_to_auto_datetime_string(timestamp);

    let node_str = match node_idx {
        Some(idx) => node_name_fn(idx).unwrap_or_else(|| format!("node_idx_{}", idx)),
        None => "unknown_node".to_string(),
    };

    format!(
        "{}, Phase: {}, Node: '{}', Msg: '{}'",
        timestamp_str,
        phase.as_str(),
        node_str,
        panic_msg,
    )
}
