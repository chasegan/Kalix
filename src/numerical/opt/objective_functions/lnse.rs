use std::sync::{Arc, OnceLock};
use super::{seed_validity_mask, masked_observed, masked_simulated};

/// LNSE objective with lazy-initialized cache for parallel processing
#[derive(Clone, Debug)]
pub struct LnseObjective {
    cache: Arc<OnceLock<LnseCache>>,
}

#[derive(Debug)]
struct LnseCache {
    mask: Vec<bool>,
    log_masked_observed: Vec<f64>,
    ss_tot_log: f64,
}

impl LnseObjective {
    pub fn new() -> Self {
        Self {
            cache: Arc::new(OnceLock::new()),
        }
    }

    pub(crate) fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        const EPSILON: f64 = 0.01;

        let cache = self.cache.get_or_init(|| Self::initialize_cache(observed, simulated));

        let masked_sim = masked_simulated(simulated, &cache.mask)?;

        // Log transform simulated
        let log_masked_sim: Vec<f64> = masked_sim.iter()
            .map(|x| (x + EPSILON).ln())
            .collect();

        // Calculate sum of squared residuals
        let ss_res: f64 = cache.log_masked_observed.iter()
            .zip(&log_masked_sim)
            .map(|(o, s)| (o - s).powi(2))
            .sum();

        let lnse = 1.0 - (ss_res / cache.ss_tot_log);

        // Convert to loss form: 0 = perfect, increases as fit worsens
        Ok(1.0 - lnse)
    }

    fn initialize_cache(observed: &[f64], simulated: &[f64]) -> LnseCache {
        const EPSILON: f64 = 0.01;

        let mask = seed_validity_mask(observed, simulated);
        let masked_obs = masked_observed(observed, &mask);

        // Log transform observed
        let log_masked_observed: Vec<f64> = masked_obs.iter()
            .map(|x| (x + EPSILON).ln())
            .collect();

        let mean_log_observed: f64 = if log_masked_observed.is_empty() {
            0.0
        } else {
            log_masked_observed.iter().sum::<f64>() / log_masked_observed.len() as f64
        };

        let ss_tot_log: f64 = log_masked_observed.iter()
            .map(|o| (o - mean_log_observed).powi(2))
            .sum();

        LnseCache {
            mask,
            log_masked_observed,
            ss_tot_log,
        }
    }
}
