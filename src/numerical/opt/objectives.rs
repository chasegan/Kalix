/// Objective functions for model optimisation
///
/// All objective functions return values in `[0, ∞)` where **LOWER IS BETTER** (0 = perfect).
/// Goodness-of-fit metrics whose natural form is "higher better" (NSE, KGE, Pearson r) are
/// re-expressed as `1 - x` so that every statistic obeys the same convention with no sign flips.
///
/// # Missing-data handling (intentional design)
///
/// Each objective caches observed-side statistics on first evaluation (thread-safe via
/// `Arc<OnceLock>`, shared across parallel clones). The validity mask — the fixed
/// assessment window — is seeded once from the FIRST evaluation: a timestep is in the
/// window when both the observed value and the first candidate's simulated value are
/// finite. This lets structurally-missing simulated values (e.g. from gaps in
/// non-critical input data, identical for every candidate) define the window alongside
/// observed gaps, and avoids re-deriving the window on every evaluation.
///
/// Every subsequent candidate is scored over that same window and is VALIDATED against
/// it: a candidate that produces a non-finite value inside the window is rejected with
/// an error, which the optimisers treat as an infeasible candidate (objective = ∞).
/// All feasible candidates are therefore always compared over identical data.

use std::sync::{Arc, OnceLock};

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

/// Objective function types — all return values in `[0, ∞)`, lower is better
#[derive(Clone, Debug)]
pub enum ObjectiveFunction {
    /// 1 - Nash-Sutcliffe Efficiency. Range: [0, ∞), 0 = perfect.
    OneMinusNse(NseObjective),

    /// 1 - log-transformed Nash-Sutcliffe Efficiency. Range: [0, ∞), 0 = perfect.
    /// Better at penalising errors in low flows.
    OneMinusLnse(LnseObjective),

    /// Root Mean Square Error. Range: [0, ∞), 0 = perfect.
    RMSE(RmseObjective),

    /// Mean Absolute Error. Range: [0, ∞), 0 = perfect.
    MAE(MaeObjective),

    /// 1 - Kling-Gupta Efficiency. Range: [0, ∞), 0 = perfect.
    OneMinusKge(KgeObjective),

    /// Absolute percent bias |PBIAS|. Range: [0, ∞), 0 = perfect.
    AbsPbias(PbiasObjective),

    /// SDEB — Sorted Data Error with Bias. Combines temporal error (SD), distributional
    /// error (SE), and a bias penalty. Range: [0, ∞), 0 = perfect.
    SDEB(SdebObjective),

    /// 1 - Pearson's correlation coefficient. Range: [0, 2], 0 = perfect positive correlation.
    OneMinusPearsR(PearsObjective),
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

    /// Calculate SDEB objective
    fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
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

    /// Calculate Pearson's R objective (loss form 1 - r for minimization)
    fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
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

    fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
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

    fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
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
        let cache = self.cache.get_or_init(|| Self::initialize_cache(observed, simulated));

        let masked_sim = masked_simulated(simulated, &cache.mask)?;

        let mae: f64 = cache.masked_observed.iter()
            .zip(&masked_sim)
            .map(|(o, s)| (o - s).abs())
            .sum::<f64>()
            / cache.masked_observed.len() as f64;

        Ok(mae)
    }

    fn initialize_cache(observed: &[f64], simulated: &[f64]) -> MaeCache {
        let mask = seed_validity_mask(observed, simulated);
        let masked_obs = masked_observed(observed, &mask);

        MaeCache {
            mask,
            masked_observed: masked_obs,
        }
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
            ObjectiveFunction::OneMinusNse(obj) => obj.calculate(observed, simulated),
            ObjectiveFunction::OneMinusLnse(obj) => obj.calculate(observed, simulated),
            ObjectiveFunction::RMSE(obj) => obj.calculate(observed, simulated),
            ObjectiveFunction::MAE(obj) => obj.calculate(observed, simulated),
            ObjectiveFunction::OneMinusKge(obj) => obj.calculate(observed, simulated),
            ObjectiveFunction::AbsPbias(obj) => obj.calculate(observed, simulated),
            ObjectiveFunction::SDEB(obj) => obj.calculate(observed, simulated),
            ObjectiveFunction::OneMinusPearsR(obj) => obj.calculate(observed, simulated),
        }
    }

    /// Get name of objective function (matches the INI statistic name, uppercase)
    pub fn name(&self) -> &str {
        match self {
            ObjectiveFunction::OneMinusNse(_) => "ONE_MINUS_NSE",
            ObjectiveFunction::OneMinusLnse(_) => "ONE_MINUS_LNSE",
            ObjectiveFunction::RMSE(_) => "RMSE",
            ObjectiveFunction::MAE(_) => "MAE",
            ObjectiveFunction::OneMinusKge(_) => "ONE_MINUS_KGE",
            ObjectiveFunction::AbsPbias(_) => "ABS_PBIAS",
            ObjectiveFunction::SDEB(_) => "SDEB",
            ObjectiveFunction::OneMinusPearsR(_) => "ONE_MINUS_PEARS_R",
        }
    }
}

impl PartialEq for ObjectiveFunction {
    fn eq(&self, other: &Self) -> bool {
        // All stateful objectives - we can't compare cache contents, so just check type
        match (self, other) {
            (Self::OneMinusNse(_), Self::OneMinusNse(_)) => true,
            (Self::OneMinusLnse(_), Self::OneMinusLnse(_)) => true,
            (Self::RMSE(_), Self::RMSE(_)) => true,
            (Self::MAE(_), Self::MAE(_)) => true,
            (Self::OneMinusKge(_), Self::OneMinusKge(_)) => true,
            (Self::AbsPbias(_), Self::AbsPbias(_)) => true,
            (Self::SDEB(_), Self::SDEB(_)) => true,
            (Self::OneMinusPearsR(_), Self::OneMinusPearsR(_)) => true,
            _ => false,
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_nse_perfect() {
        let obs = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let sim = vec![1.0, 2.0, 3.0, 4.0, 5.0];

        // Loss form 1-NSE: perfect fit (NSE=1) gives 0
        let obj = ObjectiveFunction::OneMinusNse(NseObjective::new()).calculate(&obs, &sim).unwrap();
        assert!(obj.abs() < 1e-10, "Perfect fit should give 1-NSE=0, got {}", obj);
    }

    #[test]
    fn test_nse_mean_baseline() {
        let obs = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let sim = vec![3.0, 3.0, 3.0, 3.0, 3.0]; // Mean of obs

        // Loss form 1-NSE: predicting the mean (NSE=0) gives 1
        let obj = ObjectiveFunction::OneMinusNse(NseObjective::new()).calculate(&obs, &sim).unwrap();
        assert!((obj - 1.0).abs() < 1e-10, "Predicting mean should give 1-NSE=1, got {}", obj);
    }

    #[test]
    fn test_rmse() {
        let obs = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let sim = vec![1.1, 2.1, 3.1, 4.1, 5.1];

        let result = ObjectiveFunction::RMSE(RmseObjective::new()).calculate(&obs, &sim).unwrap();
        assert!((result - 0.1).abs() < 1e-10);
    }

    #[test]
    fn test_mae() {
        let obs = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let sim = vec![1.5, 2.5, 3.5, 4.5, 5.5];

        let result = ObjectiveFunction::MAE(MaeObjective::new()).calculate(&obs, &sim).unwrap();
        assert!((result - 0.5).abs() < 1e-10);
    }

    #[test]
    fn test_percent_bias() {
        let obs = vec![10.0, 20.0, 30.0];
        let sim = vec![11.0, 22.0, 33.0]; // 10% overestimation

        let pbias = ObjectiveFunction::AbsPbias(PbiasObjective::new()).calculate(&obs, &sim).unwrap();
        assert!((pbias - 10.0).abs() < 1e-10);
    }

    #[test]
    fn test_kge_perfect() {
        let obs = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let sim = vec![1.0, 2.0, 3.0, 4.0, 5.0];

        // Loss form 1-KGE: perfect fit (KGE=1) gives 0
        let obj = ObjectiveFunction::OneMinusKge(KgeObjective::new()).calculate(&obs, &sim).unwrap();
        assert!(obj.abs() < 1e-10, "Perfect fit should give 1-KGE=0, got {}", obj);
    }

    /// KGE's beta term divides by the observed mean; zero-mean observed data
    /// must produce a clear error rather than an infinite/NaN objective.
    #[test]
    fn test_kge_zero_mean_observed_errors() {
        let obs = vec![-1.0, 0.0, 1.0]; // nonzero variance, zero mean
        let sim = vec![-0.9, 0.1, 1.1];

        let result = ObjectiveFunction::OneMinusKge(KgeObjective::new()).calculate(&obs, &sim);
        assert!(result.is_err(), "zero-mean observed should error, got {:?}", result);
        assert!(result.unwrap_err().contains("zero mean"));
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

    /// The assessment window is frozen from the first evaluation (intentional
    /// design). Before the 2026-07 fix, later candidates were never validated
    /// against it: a candidate producing NaN at an in-window position passed
    /// that NaN straight into the sums, yielding a NaN objective (which then
    /// panicked the optimiser's sort). Such a candidate must instead be
    /// rejected as infeasible.
    #[test]
    fn test_later_candidate_with_nan_is_rejected_not_poisoned() {
        let obs = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let sim_clean = vec![1.1, 2.1, 3.1, 4.1, 5.1];
        let sim_blown_up = vec![1.0, 2.0, f64::NAN, 4.0, 5.0];

        for objective in [
            ObjectiveFunction::OneMinusNse(NseObjective::new()),
            ObjectiveFunction::OneMinusLnse(LnseObjective::new()),
            ObjectiveFunction::RMSE(RmseObjective::new()),
            ObjectiveFunction::MAE(MaeObjective::new()),
            ObjectiveFunction::OneMinusKge(KgeObjective::new()),
            ObjectiveFunction::AbsPbias(PbiasObjective::new()),
            ObjectiveFunction::SDEB(SdebObjective::new()),
            ObjectiveFunction::OneMinusPearsR(PearsObjective::new()),
        ] {
            // First candidate is clean: initializes the cache.
            let first = objective.calculate(&obs, &sim_clean);
            assert!(first.is_ok(), "{}: clean candidate should evaluate", objective.name());
            assert!(first.unwrap().is_finite(),
                "{}: clean candidate must give a finite objective", objective.name());

            // Second candidate blows up mid-series: must be rejected, and must
            // never return NaN.
            let second = objective.calculate(&obs, &sim_blown_up);
            match second {
                Err(e) => assert!(e.contains("infeasible"),
                    "{}: expected infeasible-candidate error, got '{}'", objective.name(), e),
                Ok(v) => panic!("{}: NaN candidate returned Ok({}) instead of an error", objective.name(), v),
            }
        }
    }

    /// Infinity inside the window is just as infeasible as NaN. (In the first
    /// candidate it would instead seed the window, so seed with a clean
    /// candidate first.)
    #[test]
    fn test_infinite_simulated_is_rejected() {
        let obs = vec![1.0, 2.0, 3.0];
        let objective = ObjectiveFunction::OneMinusNse(NseObjective::new());
        objective.calculate(&obs, &[1.0, 2.0, 3.0]).unwrap(); // seed full window

        let result = objective.calculate(&obs, &[1.0, f64::INFINITY, 3.0]);
        assert!(result.is_err());
    }

    /// Simulated NaN at observed-gap positions is outside the window and fine
    /// (the common case: sim covers the whole period, observed has holes).
    #[test]
    fn test_simulated_nan_at_observed_gap_is_fine() {
        let obs = vec![1.0, f64::NAN, 3.0, 4.0];
        let sim = vec![1.0, f64::NAN, 3.0, 4.0]; // NaN only where observed is NaN

        let result = ObjectiveFunction::OneMinusNse(NseObjective::new()).calculate(&obs, &sim);
        assert!(result.is_ok(), "NaN at observed-gap positions must be ignored: {:?}", result);
        assert!(result.unwrap().abs() < 1e-10, "fit is perfect on the valid window");
    }

    /// Structurally-missing simulated values (same positions for every
    /// candidate, e.g. from gaps in non-critical input data) legitimately
    /// shape the window via the first evaluation; later candidates with NaN at
    /// those SAME positions are fine, while NaN at any NEW in-window position
    /// is infeasible.
    #[test]
    fn test_first_candidate_seeds_window_and_later_candidates_validate_against_it() {
        let obs = vec![1.0, 2.0, 3.0, 4.0, 5.0];
        let sim_seed = vec![1.0, f64::NAN, 3.0, 4.0, 5.0]; // index 1 excluded from window

        let objective = ObjectiveFunction::OneMinusNse(NseObjective::new());
        assert!(objective.calculate(&obs, &sim_seed).is_ok());

        // Same structural gap: in-window values all finite -> feasible.
        let sim_same_gap = vec![1.1, f64::NAN, 3.1, 4.1, 5.1];
        assert!(objective.calculate(&obs, &sim_same_gap).is_ok());

        // New NaN inside the window -> infeasible.
        let sim_new_gap = vec![1.1, f64::NAN, f64::NAN, 4.1, 5.1];
        let result = objective.calculate(&obs, &sim_new_gap);
        assert!(result.is_err());
        assert!(result.unwrap_err().contains("infeasible"));
    }
}
