use std::sync::{Arc, OnceLock};
use super::{Objective, seed_validity_mask, masked_observed, masked_simulated};

/// SDEB objective with lazy-initialized cache for parallel processing
///
/// SDEB — Sorted Data Error with Bias. Combines temporal error (SD), distributional
/// error (SE), and a bias penalty. Range: [0, ∞), 0 = perfect.
///
/// SDEB combines:
/// - SD: Temporal error between observed and simulated sqrt-transformed flows
/// - SE: Distributional error between ranked/sorted sqrt-transformed flows
/// - B: Bias penalty multiplier
///
/// Formula: SDEB = (0.1*SD + 0.9*SE) * B
#[derive(Clone, Debug)]
pub struct SdebObjective {
    /// Shared cache across all clones, initialized on first evaluation
    cache: Arc<OnceLock<SdebCache>>,
}

#[derive(Debug)]
struct SdebCache {
    /// Mask indicating which timesteps have valid observed data
    mask: Vec<bool>,

    /// Square root of masked observed (for SD term)
    sqrt_masked_observed: Vec<f64>,

    /// Square root of sorted masked observed (for SE term)
    sqrt_sorted_masked_observed: Vec<f64>,

    /// Sum of masked observed (for bias term)
    sum_observed: f64,
}

impl SdebObjective {
    /// Create a new SDEB objective
    pub fn new() -> Self {
        Self {
            cache: Arc::new(OnceLock::new()),
        }
    }

    /// Initialize cache on first evaluation
    fn initialize_cache(observed: &[f64], simulated: &[f64]) -> SdebCache {
        let mask = seed_validity_mask(observed, simulated);
        let masked_obs = masked_observed(observed, &mask);

        // Create sorted version (RO)
        let mut sorted_masked_obs = masked_obs.clone();
        sorted_masked_obs.sort_by(|a, b| a.total_cmp(b));

        SdebCache {
            mask,
            sqrt_masked_observed: masked_obs.iter().map(|x| x.sqrt()).collect(),
            sqrt_sorted_masked_observed: sorted_masked_obs.iter().map(|x| x.sqrt()).collect(),
            sum_observed: masked_obs.iter().sum(),
        }
    }
}

impl Objective for SdebObjective {
    /// Calculate SDEB objective
    fn evaluate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        // Get or initialize cache (happens once, thread-safe)
        let cache = self.cache.get_or_init(|| Self::initialize_cache(observed, simulated));

        // Apply mask to simulated data (happens every evaluation)
        let masked_sim = masked_simulated(simulated, &cache.mask)?;

        // Sort masked simulated (QM -> RM)
        let mut sorted_masked_sim = masked_sim.clone();
        sorted_masked_sim.sort_by(|a, b| a.total_cmp(b));

        // Square root transform simulated data
        let sqrt_masked_sim: Vec<f64> = masked_sim.iter().map(|x| x.sqrt()).collect();
        let sqrt_sorted_masked_sim: Vec<f64> = sorted_masked_sim.iter().map(|x| x.sqrt()).collect();

        // Calculate SD: sum((sqrt(QO[i]) - sqrt(QM[i]))^2)
        let sd: f64 = cache.sqrt_masked_observed.iter()
            .zip(&sqrt_masked_sim)
            .map(|(o, s)| (o - s).powi(2))
            .sum();

        // Calculate SE: sum((sqrt(RO[i]) - sqrt(RM[i]))^2)
        let se: f64 = cache.sqrt_sorted_masked_observed.iter()
            .zip(&sqrt_sorted_masked_sim)
            .map(|(o, s)| (o - s).powi(2))
            .sum();

        // Calculate bias penalty: B = (1 + abs(sum(QO) - sum(QM)) / sum(QO))
        if cache.sum_observed == 0.0 {
            return Err("Sum of observed flows is zero, cannot calculate SDEB".to_string());
        }
        let sum_simulated: f64 = masked_sim.iter().sum();
        let b = 1.0 + ((cache.sum_observed - sum_simulated).abs() / cache.sum_observed);

        // Final SDEB = (0.1*SD + 0.9*SE) * B
        Ok((0.1 * sd + 0.9 * se) * b)
    }

    fn name(&self) -> &'static str {
        "SDEB"
    }
}

impl Default for SdebObjective {
    fn default() -> Self {
        Self::new()
    }
}
