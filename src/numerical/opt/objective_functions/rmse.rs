use std::sync::{Arc, OnceLock};
use super::{seed_validity_mask, masked_observed, masked_simulated};

/// RMSE objective with lazy-initialized cache for parallel processing
#[derive(Clone, Debug)]
pub struct RmseObjective {
    cache: Arc<OnceLock<RmseCache>>,
}

#[derive(Debug)]
struct RmseCache {
    mask: Vec<bool>,
    masked_observed: Vec<f64>,
}

impl RmseObjective {
    pub fn new() -> Self {
        Self {
            cache: Arc::new(OnceLock::new()),
        }
    }

    pub(crate) fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        let cache = self.cache.get_or_init(|| Self::initialize_cache(observed, simulated));

        let masked_sim = masked_simulated(simulated, &cache.mask)?;

        let mse: f64 = cache.masked_observed.iter()
            .zip(&masked_sim)
            .map(|(o, s)| (o - s).powi(2))
            .sum::<f64>()
            / cache.masked_observed.len() as f64;

        Ok(mse.sqrt())
    }

    fn initialize_cache(observed: &[f64], simulated: &[f64]) -> RmseCache {
        let mask = seed_validity_mask(observed, simulated);
        let masked_obs = masked_observed(observed, &mask);

        RmseCache {
            mask,
            masked_observed: masked_obs,
        }
    }
}
