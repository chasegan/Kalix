//! Objective functions for model optimisation — one per submodule.
//!
//! Private: everything here is re-exported from
//! [`crate::numerical::opt::objectives`], which is the public facade and where
//! the conventions (lower is better) and the intentional missing-data design
//! are documented. Read that first — the masking helpers below implement it.

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

/// Objective function trait for model optimisation
pub trait Objective {
    /// Name of the objective function (matches the INI statistic name, uppercase)
    fn name(&self) -> &'static str;

    /// Score a candidate, assuming the preconditions `calculate` has already
    /// checked. Implement this; call [`Objective::calculate`].
    fn evaluate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String>;

    /// Calculate the objective function value given observed and simulated data.
    ///
    /// The preconditions live here, not in the implementations, so that every
    /// objective enforces them however it is reached. Skipping them is not a
    /// speed win worth having: mismatched lengths silently truncate the
    /// assessment window (`seed_validity_mask` zips), scoring the candidate over
    /// whatever prefix overlaps — a wrong number with no signal, which
    /// `performance §6.2` forbids.
    fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        if observed.len() != simulated.len() {
            return Err(format!(
                "Observed and simulated must have same length ({} vs {})",
                observed.len(),
                simulated.len()
            ));
        }

        if observed.is_empty() {
            return Err("Cannot calculate objective for empty data".to_string());
        }

        self.evaluate(observed, simulated)
    }
}

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
