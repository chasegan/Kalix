//! The `ObjectiveFunction` enum: dispatch over the objective functions for model
//! optimisation.
//!
//! The individual objectives (and the shared missing-data/masking machinery,
//! whose intentional design is documented there) live in
//! [`crate::numerical::opt::objective_functions`]; they are re-exported here so
//! existing `objectives::*` paths keep working.
//!
//! All objective functions return values in `[0, ∞)` where **LOWER IS BETTER**
//! (0 = perfect). Goodness-of-fit metrics whose natural form is "higher better"
//! (NSE, KGE, Pearson r) are re-expressed as `1 - x` so that every statistic
//! obeys the same convention with no sign flips.

pub use crate::numerical::opt::objective_functions::*;

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
