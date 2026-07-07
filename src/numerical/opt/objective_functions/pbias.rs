use std::sync::{Arc, OnceLock};
use super::{Objective, seed_validity_mask, masked_observed, masked_simulated};

/// PBIAS objective with lazy-initialized cache for parallel processing
#[derive(Clone, Debug)]
pub struct PbiasObjective {
    cache: Arc<OnceLock<PbiasCache>>,
}

#[derive(Debug)]
struct PbiasCache {
    mask: Vec<bool>,
    masked_observed: Vec<f64>,
    sum_observed: f64,
}

impl PbiasObjective {
    pub fn new() -> Self {
        Self {
            cache: Arc::new(OnceLock::new()),
        }
    }

    fn initialize_cache(observed: &[f64], simulated: &[f64]) -> PbiasCache {
        let mask = seed_validity_mask(observed, simulated);
        let masked_obs = masked_observed(observed, &mask);
        let sum_observed: f64 = masked_obs.iter().sum();

        PbiasCache {
            mask,
            masked_observed: masked_obs,
            sum_observed,
        }
    }
}

impl Objective for PbiasObjective {
    /// Calculate PBIAS objective (absolute value for minimization)
    fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        let cache = self.cache.get_or_init(|| Self::initialize_cache(observed, simulated));

        let masked_sim = masked_simulated(simulated, &cache.mask)?;

        if cache.sum_observed == 0.0 {
            return Ok(0.0);
        }

        let sum_diff: f64 = masked_sim.iter()
            .zip(&cache.masked_observed)
            .map(|(s, o)| s - o)
            .sum();

        let pbias = 100.0 * sum_diff / cache.sum_observed;

        // Return absolute value for minimization
        Ok(pbias.abs())
    }

    fn name(&self) -> &'static str {
        "ABS_PBIAS"
    }
}

impl Default for PbiasObjective {
    fn default() -> Self {
        Self::new()
    }
}
