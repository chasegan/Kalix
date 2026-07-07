use std::sync::{Arc, OnceLock};
use super::{seed_validity_mask, masked_observed, masked_simulated};

/// KGE objective with lazy-initialized cache for parallel processing
#[derive(Clone, Debug)]
pub struct KgeObjective {
    cache: Arc<OnceLock<KgeCache>>,
}

#[derive(Debug)]
struct KgeCache {
    mask: Vec<bool>,
    masked_observed: Vec<f64>,
    mean_observed: f64,
    std_observed: f64,
}

impl KgeObjective {
    pub fn new() -> Self {
        Self {
            cache: Arc::new(OnceLock::new()),
        }
    }

    pub(crate) fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        let cache = self.cache.get_or_init(|| Self::initialize_cache(observed, simulated));

        // KGE's alpha and beta terms are undefined for degenerate observed data;
        // check up front so the error names the real problem.
        if cache.std_observed == 0.0 {
            return Err("Observed data has zero variance; KGE alpha term is undefined".to_string());
        }
        if cache.mean_observed == 0.0 {
            return Err("Observed data has zero mean; KGE beta term is undefined".to_string());
        }

        let masked_sim = masked_simulated(simulated, &cache.mask)?;

        // Calculate simulated statistics
        let mean_simulated: f64 = masked_sim.iter().sum::<f64>() / masked_sim.len() as f64;
        let std_simulated: f64 = {
            let variance: f64 = masked_sim.iter()
                .map(|x| (x - mean_simulated).powi(2))
                .sum::<f64>() / masked_sim.len() as f64;
            variance.sqrt()
        };

        // Calculate correlation
        let r = if std_simulated == 0.0 {
            0.0
        } else {
            let cov: f64 = cache.masked_observed.iter()
                .zip(&masked_sim)
                .map(|(o, s)| (o - cache.mean_observed) * (s - mean_simulated))
                .sum::<f64>()
                / cache.masked_observed.len() as f64;
            cov / (cache.std_observed * std_simulated)
        };

        let alpha = std_simulated / cache.std_observed;
        let beta = mean_simulated / cache.mean_observed;

        let kge = 1.0 - ((r - 1.0).powi(2) + (alpha - 1.0).powi(2) + (beta - 1.0).powi(2)).sqrt();

        // Convert to loss form: 0 = perfect, increases as fit worsens
        Ok(1.0 - kge)
    }

    fn initialize_cache(observed: &[f64], simulated: &[f64]) -> KgeCache {
        let mask = seed_validity_mask(observed, simulated);
        let masked_obs = masked_observed(observed, &mask);

        let mean_observed: f64 = if masked_obs.is_empty() {
            0.0
        } else {
            masked_obs.iter().sum::<f64>() / masked_obs.len() as f64
        };

        let std_observed: f64 = if masked_obs.is_empty() {
            0.0
        } else {
            let variance: f64 = masked_obs.iter()
                .map(|x| (x - mean_observed).powi(2))
                .sum::<f64>() / masked_obs.len() as f64;
            variance.sqrt()
        };

        KgeCache {
            mask,
            masked_observed: masked_obs,
            mean_observed,
            std_observed,
        }
    }
}
