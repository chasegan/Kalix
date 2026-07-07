use std::sync::{Arc, OnceLock};
use super::{seed_validity_mask, masked_observed, masked_simulated};

/// NSE objective with lazy-initialized cache for parallel processing
#[derive(Clone, Debug)]
pub struct NseObjective {
    cache: Arc<OnceLock<NseCache>>,
}

#[derive(Debug)]
struct NseCache {
    mask: Vec<bool>,
    masked_observed: Vec<f64>,
    ss_tot: f64,  // sum((obs[i] - mean_obs)^2)
}

impl NseObjective {
    pub fn new() -> Self {
        Self {
            cache: Arc::new(OnceLock::new()),
        }
    }

    pub(crate) fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        let cache = self.cache.get_or_init(|| Self::initialize_cache(observed, simulated));

        let masked_sim = masked_simulated(simulated, &cache.mask)?;

        // Calculate sum of squared residuals
        let ss_res: f64 = cache.masked_observed.iter()
            .zip(&masked_sim)
            .map(|(o, s)| (o - s).powi(2))
            .sum();

        let nse = 1.0 - (ss_res / cache.ss_tot);

        // Convert to loss form: 0 = perfect, increases as fit worsens
        Ok(1.0 - nse)
    }

    fn initialize_cache(observed: &[f64], simulated: &[f64]) -> NseCache {
        let mask = seed_validity_mask(observed, simulated);
        let masked_obs = masked_observed(observed, &mask);

        let mean_observed: f64 = if masked_obs.is_empty() {
            0.0
        } else {
            masked_obs.iter().sum::<f64>() / masked_obs.len() as f64
        };

        let ss_tot: f64 = masked_obs.iter()
            .map(|o| (o - mean_observed).powi(2))
            .sum();

        NseCache {
            mask,
            masked_observed: masked_obs,
            ss_tot,
        }
    }
}
