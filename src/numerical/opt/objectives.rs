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
    NashSutcliffe(NseObjective),

    /// Nash-Sutcliffe Efficiency with log-transformed values - negated for minimization
    /// Better for low flows. Returned range: -1 to ∞ (lower is better)
    NashSutcliffeLog(LnseObjective),

    /// Root Mean Square Error (lower is better)
    RMSE(RmseObjective),

    /// Mean Absolute Error (lower is better)
    MAE(MaeObjective),

    /// Kling-Gupta Efficiency - negated for minimization
    /// Returned range: -1 to ∞ (lower is better, -1 = perfect)
    KlingGupta(KgeObjective),

    /// Percent Bias - absolute value (lower is better)
    /// PBIAS near 0 is better
    PercentBias(PbiasObjective),

    /// SDEB - Sorted Data Error with Bias
    /// Combines temporal error (SD) with distributional/ranked error (SE) and bias penalty
    /// Lower is better (0 = perfect)
    SDEB(SdebObjective),

    /// Pearson's Correlation Coefficient (R) - negated for minimization
    /// Measures linear correlation between observed and simulated
    /// Returned range: -1 to 1 (negated: -1 to 1, where -1 = perfect positive correlation)
    PEARS_R(PearsObjective),
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
    /// Mask indicating which timesteps have valid data in both series
    mask: Vec<bool>,

    /// Masked observed values (QO)
    masked_observed: Vec<f64>,

    /// Mean of observed values
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
}

impl PearsObjective {
    /// Calculate Pearson's R objective (negated for minimization)
    fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        // Get or initialize cache (happens once, thread-safe)
        let cache = self.cache.get_or_init(|| {
            Self::initialize_cache(observed, simulated)
        });

        // Apply mask to simulated data (happens every evaluation)
        let masked_simulated = Self::apply_mask(simulated, &cache.mask);

        if masked_simulated.len() != cache.masked_observed.len() {
            return Err("Masked data length mismatch".to_string());
        }

        if masked_simulated.is_empty() {
            return Err("No valid data points after masking".to_string());
        }

        // Calculate mean of simulated
        let mean_simulated: f64 = masked_simulated.iter().sum::<f64>() / masked_simulated.len() as f64;

        // Calculate sum of squared deviations for simulated: sum((QM[i] - MEAN_QM)^2)
        let ss_simulated: f64 = masked_simulated.iter()
            .map(|&qm| (qm - mean_simulated).powi(2))
            .sum();

        // Calculate covariance: sum((QO[i] - MEAN_QO) * (QM[i] - MEAN_QM))
        let covariance: f64 = cache.masked_observed.iter()
            .zip(&masked_simulated)
            .map(|(&qo, &qm)| (qo - cache.mean_observed) * (qm - mean_simulated))
            .sum();

        // Calculate Pearson's R
        let denominator = (cache.ss_observed * ss_simulated).sqrt();

        if denominator == 0.0 {
            return Err("Cannot calculate Pearson's R: zero variance in data".to_string());
        }

        let r = covariance / denominator;

        // Negate for minimization (perfect correlation R=1 becomes -1 for minimizer)
        Ok(-r)
    }

    /// Initialize cache on first evaluation
    fn initialize_cache(observed: &[f64], simulated: &[f64]) -> PearsCache {
        // Create mask: true where both series have valid (non-NaN) values
        let mask: Vec<bool> = observed.iter()
            .zip(simulated)
            .map(|(o, s)| o.is_finite() && s.is_finite())
            .collect();

        // Apply mask to observed data (QO)
        let masked_observed = Self::apply_mask(observed, &mask);

        // Calculate mean of observed
        let mean_observed: f64 = if masked_observed.is_empty() {
            0.0
        } else {
            masked_observed.iter().sum::<f64>() / masked_observed.len() as f64
        };

        // Calculate sum of squared deviations: sum((QO[i] - MEAN_QO)^2)
        let ss_observed: f64 = masked_observed.iter()
            .map(|&qo| (qo - mean_observed).powi(2))
            .sum();

        PearsCache {
            mask,
            masked_observed,
            mean_observed,
            ss_observed,
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

/// NSE objective with lazy-initialized cache for parallel processing
#[derive(Clone, Debug)]
pub struct NseObjective {
    cache: Arc<OnceLock<NseCache>>,
}

#[derive(Debug)]
struct NseCache {
    mask: Vec<bool>,
    masked_observed: Vec<f64>,
    mean_observed: f64,
    ss_tot: f64,  // sum((obs[i] - mean_obs)^2)
}

impl NseObjective {
    pub fn new() -> Self {
        Self {
            cache: Arc::new(OnceLock::new()),
        }
    }

    fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        let cache = self.cache.get_or_init(|| {
            Self::initialize_cache(observed, simulated)
        });

        let masked_simulated = Self::apply_mask(simulated, &cache.mask);

        if masked_simulated.len() != cache.masked_observed.len() {
            return Err("Masked data length mismatch".to_string());
        }

        if masked_simulated.is_empty() {
            return Err("No valid data points after masking".to_string());
        }

        // Calculate sum of squared residuals
        let ss_res: f64 = cache.masked_observed.iter()
            .zip(&masked_simulated)
            .map(|(o, s)| (o - s).powi(2))
            .sum();

        let nse = 1.0 - (ss_res / cache.ss_tot);

        // Negate for minimization
        Ok(-nse)
    }

    fn initialize_cache(observed: &[f64], simulated: &[f64]) -> NseCache {
        let mask: Vec<bool> = observed.iter()
            .zip(simulated)
            .map(|(o, s)| o.is_finite() && s.is_finite())
            .collect();

        let masked_observed = Self::apply_mask(observed, &mask);

        let mean_observed: f64 = if masked_observed.is_empty() {
            0.0
        } else {
            masked_observed.iter().sum::<f64>() / masked_observed.len() as f64
        };

        let ss_tot: f64 = masked_observed.iter()
            .map(|o| (o - mean_observed).powi(2))
            .sum();

        NseCache {
            mask,
            masked_observed,
            mean_observed,
            ss_tot,
        }
    }

    fn apply_mask(data: &[f64], mask: &[bool]) -> Vec<f64> {
        data.iter()
            .zip(mask)
            .filter_map(|(val, &keep)| if keep { Some(*val) } else { None })
            .collect()
    }
}

/// LNSE objective with lazy-initialized cache for parallel processing
#[derive(Clone, Debug)]
pub struct LnseObjective {
    cache: Arc<OnceLock<LnseCache>>,
}

#[derive(Debug)]
struct LnseCache {
    mask: Vec<bool>,
    log_masked_observed: Vec<f64>,
    mean_log_observed: f64,
    ss_tot_log: f64,
}

impl LnseObjective {
    pub fn new() -> Self {
        Self {
            cache: Arc::new(OnceLock::new()),
        }
    }

    fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        const EPSILON: f64 = 0.01;

        let cache = self.cache.get_or_init(|| {
            Self::initialize_cache(observed, simulated)
        });

        let masked_simulated = Self::apply_mask(simulated, &cache.mask);

        if masked_simulated.len() != cache.log_masked_observed.len() {
            return Err("Masked data length mismatch".to_string());
        }

        if masked_simulated.is_empty() {
            return Err("No valid data points after masking".to_string());
        }

        // Log transform simulated
        let log_masked_simulated: Vec<f64> = masked_simulated.iter()
            .map(|x| (x + EPSILON).ln())
            .collect();

        // Calculate sum of squared residuals
        let ss_res: f64 = cache.log_masked_observed.iter()
            .zip(&log_masked_simulated)
            .map(|(o, s)| (o - s).powi(2))
            .sum();

        let lnse = 1.0 - (ss_res / cache.ss_tot_log);

        // Negate for minimization
        Ok(-lnse)
    }

    fn initialize_cache(observed: &[f64], simulated: &[f64]) -> LnseCache {
        const EPSILON: f64 = 0.01;

        let mask: Vec<bool> = observed.iter()
            .zip(simulated)
            .map(|(o, s)| o.is_finite() && s.is_finite())
            .collect();

        let masked_observed = Self::apply_mask(observed, &mask);

        // Log transform observed
        let log_masked_observed: Vec<f64> = masked_observed.iter()
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
            mean_log_observed,
            ss_tot_log,
        }
    }

    fn apply_mask(data: &[f64], mask: &[bool]) -> Vec<f64> {
        data.iter()
            .zip(mask)
            .filter_map(|(val, &keep)| if keep { Some(*val) } else { None })
            .collect()
    }
}

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

    fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        let cache = self.cache.get_or_init(|| {
            Self::initialize_cache(observed, simulated)
        });

        let masked_simulated = Self::apply_mask(simulated, &cache.mask);

        if masked_simulated.len() != cache.masked_observed.len() {
            return Err("Masked data length mismatch".to_string());
        }

        if masked_simulated.is_empty() {
            return Err("No valid data points after masking".to_string());
        }

        let mse: f64 = cache.masked_observed.iter()
            .zip(&masked_simulated)
            .map(|(o, s)| (o - s).powi(2))
            .sum::<f64>()
            / cache.masked_observed.len() as f64;

        Ok(mse.sqrt())
    }

    fn initialize_cache(observed: &[f64], simulated: &[f64]) -> RmseCache {
        let mask: Vec<bool> = observed.iter()
            .zip(simulated)
            .map(|(o, s)| o.is_finite() && s.is_finite())
            .collect();

        let masked_observed = Self::apply_mask(observed, &mask);

        RmseCache {
            mask,
            masked_observed,
        }
    }

    fn apply_mask(data: &[f64], mask: &[bool]) -> Vec<f64> {
        data.iter()
            .zip(mask)
            .filter_map(|(val, &keep)| if keep { Some(*val) } else { None })
            .collect()
    }
}

/// MAE objective with lazy-initialized cache for parallel processing
#[derive(Clone, Debug)]
pub struct MaeObjective {
    cache: Arc<OnceLock<MaeCache>>,
}

#[derive(Debug)]
struct MaeCache {
    mask: Vec<bool>,
    masked_observed: Vec<f64>,
}

impl MaeObjective {
    pub fn new() -> Self {
        Self {
            cache: Arc::new(OnceLock::new()),
        }
    }

    fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        let cache = self.cache.get_or_init(|| {
            Self::initialize_cache(observed, simulated)
        });

        let masked_simulated = Self::apply_mask(simulated, &cache.mask);

        if masked_simulated.len() != cache.masked_observed.len() {
            return Err("Masked data length mismatch".to_string());
        }

        if masked_simulated.is_empty() {
            return Err("No valid data points after masking".to_string());
        }

        let mae: f64 = cache.masked_observed.iter()
            .zip(&masked_simulated)
            .map(|(o, s)| (o - s).abs())
            .sum::<f64>()
            / cache.masked_observed.len() as f64;

        Ok(mae)
    }

    fn initialize_cache(observed: &[f64], simulated: &[f64]) -> MaeCache {
        let mask: Vec<bool> = observed.iter()
            .zip(simulated)
            .map(|(o, s)| o.is_finite() && s.is_finite())
            .collect();

        let masked_observed = Self::apply_mask(observed, &mask);

        MaeCache {
            mask,
            masked_observed,
        }
    }

    fn apply_mask(data: &[f64], mask: &[bool]) -> Vec<f64> {
        data.iter()
            .zip(mask)
            .filter_map(|(val, &keep)| if keep { Some(*val) } else { None })
            .collect()
    }
}

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

    fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        let cache = self.cache.get_or_init(|| {
            Self::initialize_cache(observed, simulated)
        });

        let masked_simulated = Self::apply_mask(simulated, &cache.mask);

        if masked_simulated.len() != cache.masked_observed.len() {
            return Err("Masked data length mismatch".to_string());
        }

        if masked_simulated.is_empty() {
            return Err("No valid data points after masking".to_string());
        }

        // Calculate simulated statistics
        let mean_simulated: f64 = masked_simulated.iter().sum::<f64>() / masked_simulated.len() as f64;
        let std_simulated: f64 = {
            let variance: f64 = masked_simulated.iter()
                .map(|x| (x - mean_simulated).powi(2))
                .sum::<f64>() / masked_simulated.len() as f64;
            variance.sqrt()
        };

        if cache.std_observed == 0.0 {
            return Err("Observed data has zero variance".to_string());
        }

        // Calculate correlation
        let r = if std_simulated == 0.0 {
            0.0
        } else {
            let cov: f64 = cache.masked_observed.iter()
                .zip(&masked_simulated)
                .map(|(o, s)| (o - cache.mean_observed) * (s - mean_simulated))
                .sum::<f64>()
                / cache.masked_observed.len() as f64;
            cov / (cache.std_observed * std_simulated)
        };

        let alpha = std_simulated / cache.std_observed;
        let beta = mean_simulated / cache.mean_observed;

        let kge = 1.0 - ((r - 1.0).powi(2) + (alpha - 1.0).powi(2) + (beta - 1.0).powi(2)).sqrt();

        // Negate for minimization
        Ok(-kge)
    }

    fn initialize_cache(observed: &[f64], simulated: &[f64]) -> KgeCache {
        let mask: Vec<bool> = observed.iter()
            .zip(simulated)
            .map(|(o, s)| o.is_finite() && s.is_finite())
            .collect();

        let masked_observed = Self::apply_mask(observed, &mask);

        let mean_observed: f64 = if masked_observed.is_empty() {
            0.0
        } else {
            masked_observed.iter().sum::<f64>() / masked_observed.len() as f64
        };

        let std_observed: f64 = if masked_observed.is_empty() {
            0.0
        } else {
            let variance: f64 = masked_observed.iter()
                .map(|x| (x - mean_observed).powi(2))
                .sum::<f64>() / masked_observed.len() as f64;
            variance.sqrt()
        };

        KgeCache {
            mask,
            masked_observed,
            mean_observed,
            std_observed,
        }
    }

    fn apply_mask(data: &[f64], mask: &[bool]) -> Vec<f64> {
        data.iter()
            .zip(mask)
            .filter_map(|(val, &keep)| if keep { Some(*val) } else { None })
            .collect()
    }
}

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

    fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        let cache = self.cache.get_or_init(|| {
            Self::initialize_cache(observed, simulated)
        });

        let masked_simulated = Self::apply_mask(simulated, &cache.mask);

        if masked_simulated.len() != cache.masked_observed.len() {
            return Err("Masked data length mismatch".to_string());
        }

        if masked_simulated.is_empty() {
            return Err("No valid data points after masking".to_string());
        }

        if cache.sum_observed == 0.0 {
            return Ok(0.0);
        }

        let sum_diff: f64 = masked_simulated.iter()
            .zip(&cache.masked_observed)
            .map(|(s, o)| s - o)
            .sum();

        let pbias = 100.0 * sum_diff / cache.sum_observed;

        // Return absolute value for minimization
        Ok(pbias.abs())
    }

    fn initialize_cache(observed: &[f64], simulated: &[f64]) -> PbiasCache {
        let mask: Vec<bool> = observed.iter()
            .zip(simulated)
            .map(|(o, s)| o.is_finite() && s.is_finite())
            .collect();

        let masked_observed = Self::apply_mask(observed, &mask);
        let sum_observed: f64 = masked_observed.iter().sum();

        PbiasCache {
            mask,
            masked_observed,
            sum_observed,
        }
    }

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
            ObjectiveFunction::NashSutcliffe(obj) => obj.calculate(observed, simulated),
            ObjectiveFunction::NashSutcliffeLog(obj) => obj.calculate(observed, simulated),
            ObjectiveFunction::RMSE(obj) => obj.calculate(observed, simulated),
            ObjectiveFunction::MAE(obj) => obj.calculate(observed, simulated),
            ObjectiveFunction::KlingGupta(obj) => obj.calculate(observed, simulated),
            ObjectiveFunction::PercentBias(obj) => obj.calculate(observed, simulated),
            ObjectiveFunction::SDEB(obj) => obj.calculate(observed, simulated),
            ObjectiveFunction::PEARS_R(obj) => obj.calculate(observed, simulated),
        }
    }

    /// Get name of objective function
    pub fn name(&self) -> &str {
        match self {
            ObjectiveFunction::NashSutcliffe(_) => "NSE",
            ObjectiveFunction::NashSutcliffeLog(_) => "NSE-Log",
            ObjectiveFunction::RMSE(_) => "RMSE",
            ObjectiveFunction::MAE(_) => "MAE",
            ObjectiveFunction::KlingGupta(_) => "KGE",
            ObjectiveFunction::PercentBias(_) => "PBIAS",
            ObjectiveFunction::SDEB(_) => "SDEB",
            ObjectiveFunction::PEARS_R(_) => "PEARS_R",
        }
    }
}

impl PartialEq for ObjectiveFunction {
    fn eq(&self, other: &Self) -> bool {
        // All stateful objectives - we can't compare cache contents, so just check type
        match (self, other) {
            (Self::NashSutcliffe(_), Self::NashSutcliffe(_)) => true,
            (Self::NashSutcliffeLog(_), Self::NashSutcliffeLog(_)) => true,
            (Self::RMSE(_), Self::RMSE(_)) => true,
            (Self::MAE(_), Self::MAE(_)) => true,
            (Self::KlingGupta(_), Self::KlingGupta(_)) => true,
            (Self::PercentBias(_), Self::PercentBias(_)) => true,
            (Self::SDEB(_), Self::SDEB(_)) => true,
            (Self::PEARS_R(_), Self::PEARS_R(_)) => true,
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
        let obj = ObjectiveFunction::NashSutcliffe(NseObjective::new()).calculate(&obs, &sim).unwrap();
        assert!((obj + 1.0).abs() < 1e-10, "Perfect fit should give objective=-1");
    }

    #[test]
    fn test_nse_mean_baseline() {
        let obs = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let sim = vec![3.0, 3.0, 3.0, 3.0, 3.0]; // Mean of obs

        let nse = nash_sutcliffe(&obs, &sim).unwrap();
        assert!((nse - 0.0).abs() < 1e-10, "Predicting mean should give NSE=0");

        // Through ObjectiveFunction (negated for minimization)
        let obj = ObjectiveFunction::NashSutcliffe(NseObjective::new()).calculate(&obs, &sim).unwrap();
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
        let obj = ObjectiveFunction::KlingGupta(KgeObjective::new()).calculate(&obs, &sim).unwrap();
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
