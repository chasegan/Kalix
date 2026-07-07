//! Objective functions for model optimisation
//!
//! All objective functions return values in `[0, ∞)` where **LOWER IS BETTER** (0 = perfect).
//! Goodness-of-fit metrics whose natural form is "higher better" (NSE, KGE, Pearson r) are
//! re-expressed as `1 - x` so that every statistic obeys the same convention with no sign flips.
//!
//! Each objective lives in its own submodule; the `ObjectiveFunction` enum that
//! dispatches over them lives in [`crate::numerical::opt::objectives`].
//!
//! # Missing-data handling (intentional design)
//!
//! Each objective caches observed-side statistics on first evaluation (thread-safe via
//! `Arc<OnceLock>`, shared across parallel clones). The validity mask — the fixed
//! assessment window — is seeded once from the FIRST evaluation: a timestep is in the
//! window when both the observed value and the first candidate's simulated value are
//! finite. This lets structurally-missing simulated values (e.g. from gaps in
//! non-critical input data, identical for every candidate) define the window alongside
//! observed gaps, and avoids re-deriving the window on every evaluation.
//!
//! Every subsequent candidate is scored over that same window and is VALIDATED against
//! it: a candidate that produces a non-finite value inside the window is rejected with
//! an error, which the optimisers treat as an infeasible candidate (objective = ∞).
//! All feasible candidates are therefore always compared over identical data.

mod nse;
mod lnse;
mod rmse;
mod mae;
mod kge;
mod pbias;
mod sdeb;
mod pears;

pub use nse::NseObjective;
pub use lnse::LnseObjective;
pub use rmse::RmseObjective;
pub use mae::MaeObjective;
pub use kge::KgeObjective;
pub use pbias::PbiasObjective;
pub use sdeb::SdebObjective;
pub use pears::PearsObjective;

/// Build the fixed assessment window from the observed series and the FIRST
/// candidate's simulated series: true where both are finite. Called once per
/// optimisation run (cache seeding); see the module docs for the rationale.
fn seed_validity_mask(observed: &[f64], simulated: &[f64]) -> Vec<bool> {
    observed.iter()
        .zip(simulated)
        .map(|(o, s)| o.is_finite() && s.is_finite())
        .collect()
}

/// Extract observed values at masked-in positions (cache initialisation only).
fn masked_observed(observed: &[f64], mask: &[bool]) -> Vec<f64> {
    observed.iter()
        .zip(mask)
        .filter_map(|(val, &keep)| if keep { Some(*val) } else { None })
        .collect()
}

/// Extract simulated values at masked-in positions, rejecting infeasible candidates.
///
/// Called on every evaluation. Errs if the simulated series length does not match the
/// cached mask, or if any kept value is non-finite (see module docs).
fn masked_simulated(simulated: &[f64], mask: &[bool]) -> Result<Vec<f64>, String> {
    if simulated.len() != mask.len() {
        return Err(format!(
            "Simulated series length ({}) does not match the cached mask length ({})",
            simulated.len(), mask.len()
        ));
    }

    let mut out = Vec::with_capacity(mask.len());
    let mut n_bad = 0usize;
    for (val, &keep) in simulated.iter().zip(mask) {
        if keep {
            if !val.is_finite() { n_bad += 1; }
            out.push(*val);
        }
    }

    if n_bad > 0 {
        return Err(format!(
            "Simulated series contains {} non-finite value(s) inside the assessment \
             window; candidate treated as infeasible",
            n_bad
        ));
    }
    if out.is_empty() {
        return Err("No valid data points after masking".to_string());
    }
    Ok(out)
}
