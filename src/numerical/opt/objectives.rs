/// Objective functions for model optimisation
///
/// All objective functions return values where **LOWER IS BETTER** (minimization).
/// Goodness-of-fit metrics (NSE, KGE) are negated so that optimization minimizes them.

/// Objective function types
#[derive(Clone, Copy, Debug, PartialEq)]
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
}
