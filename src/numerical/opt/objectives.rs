//! Objective functions for model optimisation, and the `ObjectiveFunction` enum
//! that dispatches over them.
//!
//! Each objective is implemented in its own submodule of the private
//! `objective_functions` module, alongside the shared missing-data masking
//! machinery; all of them are re-exported here, so this is the one path to each
//! type.
//!
//! All objective functions return values in `[0, ∞)` where **LOWER IS BETTER**
//! (0 = perfect). Goodness-of-fit metrics whose natural form is "higher better"
//! (NSE, KGE, Pearson r) are re-expressed as `1 - x` so that every statistic
//! obeys the same convention with no sign flips.
//!
//! # Missing-data handling (intentional design)
//!
//! Each objective caches observed-side statistics on first evaluation (thread-safe via
//! `Arc<OnceLock>`, shared across parallel clones). The validity mask — the fixed
//! assessment window — is seeded once from the FIRST evaluation: a timestep is in the
//! window when both the observed value and the first candidate's simulated value are
//! finite. This lets structurally-missing simulated values (e.g. from gaps in
//! non-critical input data, identical for every candidate) define the window alongside
//! observed gaps, and avoids re-deriving the window on every evaluation.
//!
//! Every subsequent candidate is scored over that same window and is VALIDATED against
//! it: a candidate that produces a non-finite value inside the window is rejected with
//! an error, which the optimisers treat as an infeasible candidate (objective = ∞).
//! All feasible candidates are therefore always compared over identical data.

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
    /// The underlying objective, viewed through the shared trait. Dispatches
    /// dynamically, so it is for the cold path only — scoring goes through the
    /// `match` in [`Objective::evaluate`] instead (`performance §6`).
    fn inner(&self) -> &dyn Objective {
        match self {
            Self::OneMinusNse(o) => o,
            Self::OneMinusLnse(o) => o,
            Self::RMSE(o) => o,
            Self::MAE(o) => o,
            Self::OneMinusKge(o) => o,
            Self::AbsPbias(o) => o,
            Self::SDEB(o) => o,
            Self::OneMinusPearsR(o) => o,
        }
    }

    /// Calculate objective (LOWER IS BETTER - minimization)
    ///
    /// # Arguments
    /// * `observed` - Observed values
    /// * `simulated` - Simulated/modeled values
    ///
    /// # Returns
    /// Objective function value to be minimized (lower is better)
    pub fn calculate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        <Self as Objective>::calculate(self, observed, simulated)
    }

    /// Get name of objective function (matches the INI statistic name, uppercase)
    pub fn name(&self) -> &'static str {
        <Self as Objective>::name(self)
    }
}

/// The composite satisfies the same contract as the individual objectives, so
/// code taking an `impl Objective` accepts either. The preconditions come free
/// with the trait's provided `calculate`.
impl Objective for ObjectiveFunction {
    /// Dispatched with a `match` rather than through `Self::inner`: each arm
    /// is a static call the compiler can inline, where a `&dyn Objective` would
    /// cost a vtable indirection per candidate for nothing.
    fn evaluate(&self, observed: &[f64], simulated: &[f64]) -> Result<f64, String> {
        match self {
            Self::OneMinusNse(o) => o.evaluate(observed, simulated),
            Self::OneMinusLnse(o) => o.evaluate(observed, simulated),
            Self::RMSE(o) => o.evaluate(observed, simulated),
            Self::MAE(o) => o.evaluate(observed, simulated),
            Self::OneMinusKge(o) => o.evaluate(observed, simulated),
            Self::AbsPbias(o) => o.evaluate(observed, simulated),
            Self::SDEB(o) => o.evaluate(observed, simulated),
            Self::OneMinusPearsR(o) => o.evaluate(observed, simulated),
        }
    }

    fn name(&self) -> &'static str {
        self.inner().name()
    }
}

impl PartialEq for ObjectiveFunction {
    fn eq(&self, other: &Self) -> bool {
        // All stateful objectives - we can't compare cache contents, so just check type
        std::mem::discriminant(self) == std::mem::discriminant(other)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The length/empty preconditions must hold on EVERY route to an objective,
    /// not just through `ObjectiveFunction`. Reaching a bare objective through
    /// the `Objective` trait once skipped them: a shorter simulated series
    /// silently truncated the assessment window (`seed_validity_mask` zips) and
    /// the candidate scored a perfect 0.0 on the overlapping prefix — a wrong
    /// number with no signal (`performance §6.2`).
    #[test]
    fn test_bare_objective_enforces_length_precondition() {
        let obs = [1.0, 2.0, 3.0, 4.0];
        let sim = [1.0, 2.0]; // shorter: would perfectly fit the truncated window

        let result = Objective::calculate(&NseObjective::new(), &obs, &sim);
        match result {
            Err(e) => assert!(e.contains("same length"),
                "expected a length error, got '{}'", e),
            Ok(v) => panic!("mismatched lengths scored Ok({}) instead of erroring", v),
        }
    }

    #[test]
    fn test_bare_objective_rejects_empty_data() {
        let result = Objective::calculate(&NseObjective::new(), &[], &[]);
        assert!(result.is_err(), "empty data must error, got {:?}", result);
    }

    /// `ObjectiveFunction`'s inherent methods and its `Objective` impl must
    /// agree, and neither may recurse into the other.
    #[test]
    fn test_enum_trait_impl_matches_inherent_and_terminates() {
        let obj = ObjectiveFunction::RMSE(RmseObjective::new());
        let obs = [1.0, 2.0, 3.0];
        let sim = [1.1, 2.1, 3.1];

        let via_trait = <ObjectiveFunction as Objective>::calculate(&obj, &obs, &sim).unwrap();
        assert!((via_trait - 0.1).abs() < 1e-10, "got {}", via_trait);
        assert_eq!(<ObjectiveFunction as Objective>::name(&obj), "RMSE");

        // Reached through the trait, the composite still enforces preconditions.
        assert!(<ObjectiveFunction as Objective>::calculate(&obj, &obs, &[1.0]).is_err());
    }

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
