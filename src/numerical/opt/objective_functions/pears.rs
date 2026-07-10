use std::sync::{Arc, OnceLock};
use super::{Objective, seed_validity_mask, masked_observed, masked_simulated};

/// Pearson's R objective with lazy-initialized cache for parallel processing
///
/// Pearson's correlation coefficient measures linear correlation between
/// observed and simulated values.
///
/// Formula: R = sum((QO[i] - MEAN_QO) * (QM[i] - MEAN_QM)) / sqrt(sum((QO[i] - MEAN_QO)^2) * sum((QM[i] - MEAN_QM)^2))
#[derive(Clone, Debug)]
pub struct PearsObjective {
    /// Shared cache across all clones, initialized on first evaluation
    cache: Arc<OnceLock<PearsCache>>,
}

#[derive(Debug)]
struct PearsCache {
    mask: Vec<bool>,
    masked_observed: Vec<f64>,
    mean_observed: f64,
    /// Sum of squared deviations from mean: sum((QO[i] - MEAN_QO)^2)
    ss_observed: f64,
}

impl PearsObjective {
    /// Create a new Pearson's R objective
    pub fn new() -> Self {
        Self {
            cache: Arc::new(OnceLock::new()),
        }
    }

    /// Initialize cache on first evaluation
    fn initialize_cache(observed: &[f64], simulated: &[f64]) -> PearsCache {
        let mask = seed_validity_mask(observed, simulated);
        let masked_obs = masked_observed(observed, &mask);

        let mean_observed: f64 = if masked_obs.is_empty() {
            0.0
        } else {
            masked_obs.iter().sum::<f64>() / masked_obs.len() as f64
        };

        let ss_observed: f64 = masked_obs.iter()
            .map(|&qo| (qo - mean_observed).powi(2))
            .sum();

        PearsCache {
            mask,
            masked_observed: masked_obs,
            mean_observed,
            ss_observed,
        }
    }
}

impl Objective for PearsObjective {
    /// Calculate Pearson's R objective (loss form 1 - r for minimization)
    fn evaluate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        let cache = self.cache.get_or_init(|| Self::initialize_cache(observed, simulated));

        let masked_sim = masked_simulated(simulated, &cache.mask)?;

        // Calculate mean of simulated
        let mean_simulated: f64 = masked_sim.iter().sum::<f64>() / masked_sim.len() as f64;

        // Calculate sum of squared deviations for simulated: sum((QM[i] - MEAN_QM)^2)
        let ss_simulated: f64 = masked_sim.iter()
            .map(|&qm| (qm - mean_simulated).powi(2))
            .sum();

        // Calculate covariance: sum((QO[i] - MEAN_QO) * (QM[i] - MEAN_QM))
        let covariance: f64 = cache.masked_observed.iter()
            .zip(&masked_sim)
            .map(|(&qo, &qm)| (qo - cache.mean_observed) * (qm - mean_simulated))
            .sum();

        // Calculate Pearson's R
        let denominator = (cache.ss_observed * ss_simulated).sqrt();

        if denominator == 0.0 {
            return Err("Cannot calculate Pearson's R: zero variance in data".to_string());
        }

        let r = covariance / denominator;

        // Convert to loss form: 0 = perfect (r=1), 2 = worst (r=-1)
        Ok(1.0 - r)
    }

    fn name(&self) -> &'static str {
        "ONE_MINUS_PEARS_R"
    }
}

impl Default for PearsObjective {
    fn default() -> Self {
        Self::new()
    }
}
