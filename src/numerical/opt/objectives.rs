/// Objective functions for model optimisation
///
/// All objective functions return values where **LOWER IS BETTER** (minimization).
/// Goodness-of-fit metrics (NSE, KGE) are negated so that optimisation minimises them.

use std::sync::{Arc, OnceLock};

/// Objective function types
#[derive(Clone, Debug)]
pub enum ObjectiveFunction {
    /// Nash-Sutcliffe Efficiency (NSE) - negated for minimization
    /// Returned range: -1 to ∞ (lower is better, -1 = perfect)
    NashSutcliffe,

    /// Nash-Sutcliffe Efficiency with log-transformed values - negated for minimization
    /// Better for low flows. Returned range: -1 to ∞ (lower is better)
    NashSutcliffeLog,

    /// Root Mean Square Error (lower is better)
    RMSE,

    /// Mean Absolute Error (lower is better)
    MAE,

    /// Kling-Gupta Efficiency - negated for minimization
    /// Returned range: -1 to ∞ (lower is better, -1 = perfect)
    KlingGupta,

    /// Percent Bias - absolute value (lower is better)
    /// PBIAS near 0 is better
    PercentBias,

    /// SDEB - Sorted Data Error with Bias
    /// Combines temporal error (SD) with distributional/ranked error (SE) and bias penalty
    /// Lower is better (0 = perfect)
    SDEB(SdebObjective),
}

/// SDEB objective with lazy-initialized cache for parallel processing
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
    /// Mask indicating which timesteps have valid data in both series
    mask: Vec<bool>,

    /// Masked observed values (QO) - only valid timesteps
    masked_observed: Vec<f64>,

    /// Sorted masked observed values (RO) - for distributional comparison
    sorted_masked_observed: Vec<f64>,

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
}

impl SdebObjective {
    /// Calculate SDEB objective
    fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        // Get or initialize cache (happens once, thread-safe)
        let cache = self.cache.get_or_init(|| {
            Self::initialize_cache(observed, simulated)
        });

        // Apply mask to simulated data (happens every evaluation)
        let masked_simulated = Self::apply_mask(simulated, &cache.mask);

        // Sort masked simulated (QM -> RM)
        let mut sorted_masked_simulated = masked_simulated.clone();
        sorted_masked_simulated.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));

        // Square root transform simulated data
        let sqrt_masked_simulated: Vec<f64> = masked_simulated.iter().map(|x| x.sqrt()).collect();
        let sqrt_sorted_masked_simulated: Vec<f64> = sorted_masked_simulated.iter().map(|x| x.sqrt()).collect();

        // Calculate SD: sum((sqrt(QO[i]) - sqrt(QM[i]))^2)
        let sd: f64 = cache.sqrt_masked_observed.iter()
            .zip(&sqrt_masked_simulated)
            .map(|(o, s)| (o - s).powi(2))
            .sum();

        // Calculate SE: sum((sqrt(RO[i]) - sqrt(RM[i]))^2)
        let se: f64 = cache.sqrt_sorted_masked_observed.iter()
            .zip(&sqrt_sorted_masked_simulated)
            .map(|(o, s)| (o - s).powi(2))
            .sum();

        // Calculate bias penalty: B = (1 + abs(sum(QO) - sum(QM)) / sum(QO))
        let sum_simulated: f64 = masked_simulated.iter().sum();
        let bias_numerator = (cache.sum_observed - sum_simulated).abs();
        let b = if cache.sum_observed == 0.0 {
            return Err("Sum of observed flows is zero, cannot calculate SDEB".to_string());
        } else {
            1.0 + (bias_numerator / cache.sum_observed)
        };

        // Final SDEB = (0.1*SD + 0.9*SE) * B
        let sdeb = (0.1 * sd + 0.9 * se) * b;

        Ok(sdeb)
    }

    /// Initialize cache on first evaluation
    fn initialize_cache(observed: &[f64], simulated: &[f64]) -> SdebCache {
        // Create mask: true where both series have valid (non-NaN) values
        let mask: Vec<bool> = observed.iter()
            .zip(simulated)
            .map(|(o, s)| o.is_finite() && s.is_finite())
            .collect();

        // Apply mask to observed data (QO)
        let masked_observed = Self::apply_mask(observed, &mask);

        // Create sorted version (RO)
        let mut sorted_masked_observed = masked_observed.clone();
        sorted_masked_observed.sort_by(|a, b| a.partial_cmp(b).unwrap_or(std::cmp::Ordering::Equal));

        // Pre-compute square roots
        let sqrt_masked_observed: Vec<f64> = masked_observed.iter().map(|x| x.sqrt()).collect();
        let sqrt_sorted_masked_observed: Vec<f64> = sorted_masked_observed.iter().map(|x| x.sqrt()).collect();

        // Pre-compute sum of observed
        let sum_observed: f64 = masked_observed.iter().sum();

        SdebCache {
            mask,
            masked_observed,
            sorted_masked_observed,
            sqrt_masked_observed,
            sqrt_sorted_masked_observed,
            sum_observed,
        }
    }

    /// Apply mask to data, keeping only valid timesteps
    fn apply_mask(data: &[f64], mask: &[bool]) -> Vec<f64> {
        data.iter()
            .zip(mask)
            .filter_map(|(val, &keep)| if keep { Some(*val) } else { None })
            .collect()
    }
}

impl ObjectiveFunction {
    /// Calculate objective (LOWER IS BETTER - minimization)
    ///
    /// # Arguments
    /// * `observed` - Observed values
    /// * `simulated` - Simulated/modeled values
    ///
    /// # Returns
    /// Objective function value to be minimized (lower is better)
    pub fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
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

        match self {
            ObjectiveFunction::NashSutcliffe => Ok(-nash_sutcliffe(observed, simulated)?),      // Negate for minimization
            ObjectiveFunction::NashSutcliffeLog => Ok(-nash_sutcliffe_log(observed, simulated)?),  // Negate for minimization
            ObjectiveFunction::RMSE => Ok(rmse(observed, simulated)),                            // Already minimization
            ObjectiveFunction::MAE => Ok(mae(observed, simulated)),                              // Already minimization
            ObjectiveFunction::KlingGupta => Ok(-kling_gupta(observed, simulated)?),             // Negate for minimization
            ObjectiveFunction::PercentBias => Ok(percent_bias(observed, simulated).abs()),       // Absolute value, minimize
            ObjectiveFunction::SDEB(obj) => obj.calculate(observed, simulated),                  // Already minimization
        }
    }

    /// Get name of objective function
    pub fn name(&self) -> &str {
        match self {
            ObjectiveFunction::NashSutcliffe => "NSE",
            ObjectiveFunction::NashSutcliffeLog => "NSE-Log",
            ObjectiveFunction::RMSE => "RMSE",
            ObjectiveFunction::MAE => "MAE",
            ObjectiveFunction::KlingGupta => "KGE",
            ObjectiveFunction::PercentBias => "PBIAS",
            ObjectiveFunction::SDEB(_) => "SDEB",
        }
    }
}

impl PartialEq for ObjectiveFunction {
    fn eq(&self, other: &Self) -> bool {
        // For simple variants, use discriminant comparison
        // For SDEB, we can't really compare cache contents, so just check type
        match (self, other) {
            (Self::NashSutcliffe, Self::NashSutcliffe) => true,
            (Self::NashSutcliffeLog, Self::NashSutcliffeLog) => true,
            (Self::RMSE, Self::RMSE) => true,
            (Self::MAE, Self::MAE) => true,
            (Self::KlingGupta, Self::KlingGupta) => true,
            (Self::PercentBias, Self::PercentBias) => true,
            (Self::SDEB(_), Self::SDEB(_)) => true,  // Consider all SDEB instances equal
            _ => false,
        }
    }
}

/// Nash-Sutcliffe Efficiency
///
/// NSE = 1 - sum((obs - sim)^2) / sum((obs - mean(obs))^2)
fn nash_sutcliffe(observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
    let obs_mean = mean(observed);

    let ss_res: f64 = observed
        .iter()
        .zip(simulated)
        .map(|(o, s)| (o - s).powi(2))
        .sum();

    let ss_tot: f64 = observed
        .iter()
        .map(|o| (o - obs_mean).powi(2))
        .sum();

    if ss_tot == 0.0 {
        return Err("Observed data has zero variance (constant values)".to_string());
    }

    Ok(1.0 - (ss_res / ss_tot))
}

/// Nash-Sutcliffe Efficiency with log-transformed values
///
/// Better for evaluating low flows. Adds small constant to avoid log(0).
fn nash_sutcliffe_log(observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
    const EPSILON: f64 = 0.01;

    let log_obs: Vec<f64> = observed.iter().map(|x| (x + EPSILON).ln()).collect();
    let log_sim: Vec<f64> = simulated.iter().map(|x| (x + EPSILON).ln()).collect();

    nash_sutcliffe(&log_obs, &log_sim)
}

/// Root Mean Square Error
fn rmse(observed: &[f64], simulated: &[f64]) -> f64 {
    let mse: f64 = observed
        .iter()
        .zip(simulated)
        .map(|(o, s)| (o - s).powi(2))
        .sum::<f64>()
        / observed.len() as f64;

    mse.sqrt()
}

/// Mean Absolute Error
fn mae(observed: &[f64], simulated: &[f64]) -> f64 {
    observed
        .iter()
        .zip(simulated)
        .map(|(o, s)| (o - s).abs())
        .sum::<f64>()
        / observed.len() as f64
}

/// Kling-Gupta Efficiency
///
/// KGE = 1 - sqrt((r-1)^2 + (alpha-1)^2 + (beta-1)^2)
/// where:
/// - r = correlation coefficient
/// - alpha = ratio of standard deviations (sim/obs)
/// - beta = ratio of means (sim/obs)
fn kling_gupta(observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
    let obs_mean = mean(observed);
    let sim_mean = mean(simulated);
    let obs_std = std_dev(observed, obs_mean);
    let sim_std = std_dev(simulated, sim_mean);

    if obs_std == 0.0 {
        return Err("Observed data has zero variance".to_string());
    }

    let r = correlation(observed, simulated, obs_mean, sim_mean, obs_std, sim_std);
    let alpha = sim_std / obs_std;
    let beta = sim_mean / obs_mean;

    let kge = 1.0 - ((r - 1.0).powi(2) + (alpha - 1.0).powi(2) + (beta - 1.0).powi(2)).sqrt();

    Ok(kge)
}

/// Percent Bias
///
/// PBIAS = 100 * sum(sim - obs) / sum(obs)
/// Negative values indicate model overestimation
fn percent_bias(observed: &[f64], simulated: &[f64]) -> f64 {
    let sum_obs: f64 = observed.iter().sum();
    let sum_diff: f64 = simulated.iter().zip(observed).map(|(s, o)| s - o).sum();

    if sum_obs == 0.0 {
        return 0.0;
    }

    100.0 * sum_diff / sum_obs
}

// Helper functions

fn mean(data: &[f64]) -> f64 {
    data.iter().sum::<f64>() / data.len() as f64
}

fn std_dev(data: &[f64], mean: f64) -> f64 {
    let variance: f64 = data.iter().map(|x| (x - mean).powi(2)).sum::<f64>() / data.len() as f64;
    variance.sqrt()
}

fn correlation(
    x: &[f64],
    y: &[f64],
    x_mean: f64,
    y_mean: f64,
    x_std: f64,
    y_std: f64,
) -> f64 {
    if x_std == 0.0 || y_std == 0.0 {
        return 0.0;
    }

    let cov: f64 = x
        .iter()
        .zip(y)
        .map(|(xi, yi)| (xi - x_mean) * (yi - y_mean))
        .sum::<f64>()
        / x.len() as f64;

    cov / (x_std * y_std)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_nse_perfect() {
        let obs = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let sim = vec![1.0, 2.0, 3.0, 4.0, 5.0];

        let nse = nash_sutcliffe(&obs, &sim).unwrap();
        assert!((nse - 1.0).abs() < 1e-10, "Perfect fit should give NSE=1");

        // Through ObjectiveFunction (negated for minimization)
        let obj = ObjectiveFunction::NashSutcliffe.calculate(&obs, &sim).unwrap();
        assert!((obj + 1.0).abs() < 1e-10, "Perfect fit should give objective=-1");
    }

    #[test]
    fn test_nse_mean_baseline() {
        let obs = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let sim = vec![3.0, 3.0, 3.0, 3.0, 3.0]; // Mean of obs

        let nse = nash_sutcliffe(&obs, &sim).unwrap();
        assert!((nse - 0.0).abs() < 1e-10, "Predicting mean should give NSE=0");

        // Through ObjectiveFunction (negated for minimization)
        let obj = ObjectiveFunction::NashSutcliffe.calculate(&obs, &sim).unwrap();
        assert!((obj - 0.0).abs() < 1e-10, "Predicting mean should give objective=0");
    }

    #[test]
    fn test_rmse() {
        let obs = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let sim = vec![1.1, 2.1, 3.1, 4.1, 5.1];

        let result = rmse(&obs, &sim);
        assert!((result - 0.1).abs() < 1e-10);
    }

    #[test]
    fn test_mae() {
        let obs = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let sim = vec![1.5, 2.5, 3.5, 4.5, 5.5];

        let result = mae(&obs, &sim);
        assert!((result - 0.5).abs() < 1e-10);
    }

    #[test]
    fn test_percent_bias() {
        let obs = vec![10.0, 20.0, 30.0];
        let sim = vec![11.0, 22.0, 33.0]; // 10% overestimation

        let pbias = percent_bias(&obs, &sim);
        assert!((pbias - 10.0).abs() < 1e-10);
    }

    #[test]
    fn test_kge_perfect() {
        let obs = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let sim = vec![1.0, 2.0, 3.0, 4.0, 5.0];

        let kge = kling_gupta(&obs, &sim).unwrap();
        assert!((kge - 1.0).abs() < 1e-10, "Perfect fit should give KGE=1");

        // Through ObjectiveFunction (negated for minimization)
        let obj = ObjectiveFunction::KlingGupta.calculate(&obs, &sim).unwrap();
        assert!((obj + 1.0).abs() < 1e-10, "Perfect fit should give objective=-1");
    }

    #[test]
    fn test_sdeb_perfect() {
        let obs = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let sim = vec![1.0, 2.0, 3.0, 4.0, 5.0];

        let sdeb_obj = ObjectiveFunction::SDEB(SdebObjective::new());
        let result = sdeb_obj.calculate(&obs, &sim).unwrap();

        // Perfect fit: SD=0, SE=0, B=1, so SDEB = 0
        assert!((result - 0.0).abs() < 1e-10, "Perfect fit should give SDEB=0, got {}", result);
    }

    #[test]
    fn test_sdeb_with_missing_data() {
        let obs = vec![1.0, 2.0, f64::NAN, 4.0, 5.0];
        let sim = vec![1.1, 2.1, 3.0, 4.1, 5.1];

        let sdeb_obj = ObjectiveFunction::SDEB(SdebObjective::new());
        let result = sdeb_obj.calculate(&obs, &sim);

        // Should succeed, masking out the NaN
        assert!(result.is_ok());
        assert!(result.unwrap() > 0.0, "Non-perfect fit should have SDEB > 0");
    }

    #[test]
    fn test_sdeb_cache_reuse() {
        let obs = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let sim1 = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let sim2 = vec![1.5, 2.5, 3.5, 4.5, 5.5];

        let sdeb_obj = ObjectiveFunction::SDEB(SdebObjective::new());

        // First evaluation initializes cache
        let result1 = sdeb_obj.calculate(&obs, &sim1).unwrap();

        // Second evaluation reuses cache (observed data cached)
        let result2 = sdeb_obj.calculate(&obs, &sim2).unwrap();

        assert!((result1 - 0.0).abs() < 1e-10, "Perfect fit should give SDEB=0");
        assert!(result2 > 0.0, "Imperfect fit should give SDEB > 0");
    }
}
