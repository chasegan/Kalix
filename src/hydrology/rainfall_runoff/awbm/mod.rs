//! Australian Water Balance Model (AWBM).
//!
//! This is the daily AWBM formulation described by Boughton (2004). It uses
//! three partial-area surface stores, a baseflow store, and a surface-runoff
//! routing store. Recession constants must be calibrated for other timesteps.
//!
//! Rainfall, potential evapotranspiration, storage, and runoff are expressed
//! as depths in millimetres per timestep.

const PARAMETER_COUNT: usize = 8;

/// Default AWBM parameters.
pub const DEFAULT_A1: f64 = 0.134;
pub const DEFAULT_A2: f64 = 0.433;
pub const DEFAULT_C1: f64 = 7.0;
pub const DEFAULT_C2: f64 = 70.0;
pub const DEFAULT_C3: f64 = 150.0;
pub const DEFAULT_BFI: f64 = 0.35;
pub const DEFAULT_K_BASE: f64 = 0.95;
pub const DEFAULT_K_SURF: f64 = 0.35;

#[derive(Clone, Debug)]
pub struct Awbm {
    rainfall: f64,
    pet: f64,

    runoff: f64,
    baseflow: f64,
    surface_runoff: f64,
    routed_surface_runoff: f64,
    baseflow_recharge: f64,
    evapotranspiration: f64,

    /// Rainfall minus actual evapotranspiration.
    ///
    /// This is a water-balance diagnostic and is not necessarily runoff-
    /// producing rainfall.
    effective_rainfall: f64,

    excess: f64,

    /// Catchment-weighted surface-store excess depth.
    partial_excess: f64,

    s1: f64,
    s2: f64,
    s3: f64,
    baseflow_store: f64,
    surface_store: f64,

    /// Partial area of surface store 1.
    pub a1: f64,
    /// Partial area of surface store 2. A3 is calculated internally.
    pub a2: f64,
    /// Capacity of surface store 1, in mm.
    pub c1: f64,
    /// Capacity of surface store 2, in mm.
    pub c2: f64,
    /// Capacity of surface store 3, in mm.
    pub c3: f64,
    /// Fraction of excess runoff recharging the baseflow store.
    pub bfi: f64,
    /// Fraction of baseflow-store water remaining after each timestep.
    pub k_base: f64,
    /// Fraction of surface-runoff-store water remaining after each timestep.
    pub k_surf: f64,
}

impl Default for Awbm {
    fn default() -> Self {
        Self {
            rainfall: 0.0,
            pet: 0.0,

            runoff: 0.0,
            baseflow: 0.0,
            surface_runoff: 0.0,
            routed_surface_runoff: 0.0,
            baseflow_recharge: 0.0,
            evapotranspiration: 0.0,
            effective_rainfall: 0.0,
            excess: 0.0,
            partial_excess: 0.0,

            s1: 0.0,
            s2: 0.0,
            s3: 0.0,
            baseflow_store: 0.0,
            surface_store: 0.0,

            a1: DEFAULT_A1,
            a2: DEFAULT_A2,
            c1: DEFAULT_C1,
            c2: DEFAULT_C2,
            c3: DEFAULT_C3,
            bfi: DEFAULT_BFI,
            k_base: DEFAULT_K_BASE,
            k_surf: DEFAULT_K_SURF,
        }
    }
}

impl Awbm {
    /// Creates an AWBM with the published default parameters.
    pub fn new() -> Self {
        Self::default()
    }

    /// Restores the published default parameter set.
    pub fn set_params_default(&mut self) -> &mut Self {
        self.set_params(
            DEFAULT_A1,
            DEFAULT_A2,
            DEFAULT_C1,
            DEFAULT_C2,
            DEFAULT_C3,
            DEFAULT_BFI,
            DEFAULT_K_BASE,
            DEFAULT_K_SURF,
        )
    }

    /// Sets the complete parameter set without clamping.
    ///
    /// Calibration bounds belong in the calibration layer. Values are
    /// validated when the model is run.
    #[allow(clippy::too_many_arguments)]
    pub fn set_params(
        &mut self,
        a1: f64,
        a2: f64,
        c1: f64,
        c2: f64,
        c3: f64,
        bfi: f64,
        k_base: f64,
        k_surf: f64,
    ) -> &mut Self {
        self.a1 = a1;
        self.a2 = a2;
        self.c1 = c1;
        self.c2 = c2;
        self.c3 = c3;
        self.bfi = bfi;
        self.k_base = k_base;
        self.k_surf = k_surf;
        self
    }

    /// Parameter order: A1, A2, C1, C2, C3, BFI, KBase, KSurf.
    pub fn set_params_by_vec(&mut self, params: &[f64]) {
        assert_eq!(
            params.len(),
            PARAMETER_COUNT,
            "AWBM requires exactly {PARAMETER_COUNT} parameters"
        );

        self.set_params(
            params[0], params[1], params[2], params[3], params[4], params[5], params[6], params[7],
        );
    }

    /// Returns parameters in the order used by `set_params_by_vec`.
    pub fn get_params_as_vec(&self) -> Vec<f64> {
        vec![
            self.a1,
            self.a2,
            self.c1,
            self.c2,
            self.c3,
            self.bfi,
            self.k_base,
            self.k_surf,
        ]
    }

    fn validate(&self, rainfall: f64, pet: f64) {
        assert!(
            rainfall.is_finite() && rainfall >= 0.0,
            "AWBM rainfall must be finite and non-negative"
        );
        assert!(
            pet.is_finite() && pet >= 0.0,
            "AWBM PET must be finite and non-negative"
        );
        assert!(
            self.a1.is_finite()
                && self.a2.is_finite()
                && self.a1 >= 0.0
                && self.a2 >= 0.0
                && self.a1 + self.a2 <= 1.0,
            "AWBM requires finite, non-negative areas with A1 + A2 <= 1"
        );
        assert!(
            self.c1.is_finite()
                && self.c2.is_finite()
                && self.c3.is_finite()
                && self.c1 >= 0.0
                && self.c2 >= 0.0
                && self.c3 >= 0.0,
            "AWBM capacities must be finite and non-negative"
        );
        assert!(
            self.bfi.is_finite() && (0.0..=1.0).contains(&self.bfi),
            "AWBM BFI must be finite and between 0 and 1"
        );
        assert!(
            self.k_base.is_finite()
                && self.k_surf.is_finite()
                && (0.0..=1.0).contains(&self.k_base)
                && (0.0..=1.0).contains(&self.k_surf),
            "AWBM recession constants must be finite and between 0 and 1"
        );
    }

    fn areas(&self) -> (f64, f64, f64) {
        (self.a1, self.a2, 1.0 - self.a1 - self.a2)
    }

    /// Runs one timestep and returns total runoff.
    pub fn run_step(&mut self, rainfall: f64, pet: f64) -> f64 {
        self.validate(rainfall, pet);

        self.rainfall = rainfall;
        self.pet = pet;

        let (a1, a2, a3) = self.areas();

        let update_store = |store: &mut f64, capacity: f64| -> (f64, f64) {
            *store += rainfall;

            let actual_et = pet.min(*store);
            *store -= actual_et;

            let excess = (*store - capacity).max(0.0);
            *store -= excess;

            (excess, actual_et)
        };

        let (e1, et1) = update_store(&mut self.s1, self.c1);
        let (e2, et2) = update_store(&mut self.s2, self.c2);
        let (e3, et3) = update_store(&mut self.s3, self.c3);

        self.excess = a1 * e1 + a2 * e2 + a3 * e3;
        self.partial_excess = self.excess;
        self.evapotranspiration = a1 * et1 + a2 * et2 + a3 * et3;
        self.effective_rainfall = rainfall - self.evapotranspiration;

        self.baseflow_recharge = self.bfi * self.excess;
        self.surface_runoff = (1.0 - self.bfi) * self.excess;

        self.baseflow_store += self.baseflow_recharge;
        self.baseflow = (1.0 - self.k_base) * self.baseflow_store;
        self.baseflow_store -= self.baseflow;

        self.surface_store += self.surface_runoff;
        self.routed_surface_runoff = (1.0 - self.k_surf) * self.surface_store;
        self.surface_store -= self.routed_surface_runoff;

        self.runoff = self.baseflow + self.routed_surface_runoff;
        self.runoff
    }

    pub fn reset(&mut self) {
        self.initialize_state_empty();
    }

    pub fn initialize_state_empty(&mut self) -> &mut Self {
        self.rainfall = 0.0;
        self.pet = 0.0;

        self.runoff = 0.0;
        self.baseflow = 0.0;
        self.surface_runoff = 0.0;
        self.routed_surface_runoff = 0.0;
        self.baseflow_recharge = 0.0;
        self.evapotranspiration = 0.0;
        self.effective_rainfall = 0.0;
        self.excess = 0.0;
        self.partial_excess = 0.0;

        self.s1 = 0.0;
        self.s2 = 0.0;
        self.s3 = 0.0;
        self.baseflow_store = 0.0;
        self.surface_store = 0.0;

        self
    }

    pub fn set_initial_stores(
        &mut self,
        s1: f64,
        s2: f64,
        s3: f64,
        baseflow_store: f64,
        surface_store: f64,
    ) -> &mut Self {
        assert!(
            s1.is_finite()
                && s2.is_finite()
                && s3.is_finite()
                && baseflow_store.is_finite()
                && surface_store.is_finite()
                && s1 >= 0.0
                && s2 >= 0.0
                && s3 >= 0.0
                && baseflow_store >= 0.0
                && surface_store >= 0.0
                && s1 <= self.c1
                && s2 <= self.c2
                && s3 <= self.c3,
            "AWBM initial stores must be finite, non-negative, and within capacity"
        );

        self.s1 = s1;
        self.s2 = s2;
        self.s3 = s3;
        self.baseflow_store = baseflow_store;
        self.surface_store = surface_store;
        self
    }

    pub fn area3(&self) -> f64 {
        assert!(
            self.a1.is_finite()
                && self.a2.is_finite()
                && self.a1 >= 0.0
                && self.a2 >= 0.0
                && self.a1 + self.a2 <= 1.0,
            "AWBM requires finite, non-negative areas with A1 + A2 <= 1"
        );

        1.0 - self.a1 - self.a2
    }

    pub fn rainfall(&self) -> f64 {
        self.rainfall
    }

    pub fn pet(&self) -> f64 {
        self.pet
    }

    pub fn runoff(&self) -> f64 {
        self.runoff
    }

    pub fn baseflow(&self) -> f64 {
        self.baseflow
    }

    pub fn surface_runoff(&self) -> f64 {
        self.surface_runoff
    }

    pub fn routed_surface_runoff(&self) -> f64 {
        self.routed_surface_runoff
    }

    pub fn baseflow_recharge(&self) -> f64 {
        self.baseflow_recharge
    }

    pub fn evapotranspiration(&self) -> f64 {
        self.evapotranspiration
    }

    pub fn effective_rainfall(&self) -> f64 {
        self.effective_rainfall
    }

    pub fn excess(&self) -> f64 {
        self.excess
    }

    pub fn partial_excess(&self) -> f64 {
        self.partial_excess
    }

    pub fn s1(&self) -> f64 {
        self.s1
    }

    pub fn s2(&self) -> f64 {
        self.s2
    }

    pub fn s3(&self) -> f64 {
        self.s3
    }

    pub fn baseflow_store(&self) -> f64 {
        self.baseflow_store
    }

    pub fn surface_store(&self) -> f64 {
        self.surface_store
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn defaults_match_boughton_awbm_defaults() {
        let model = Awbm::new();

        assert_eq!(model.a1, 0.134);
        assert_eq!(model.a2, 0.433);
        assert_eq!(model.area3(), 0.433);
        assert_eq!(model.c1, 7.0);
        assert_eq!(model.c2, 70.0);
        assert_eq!(model.c3, 150.0);
        assert_eq!(model.bfi, 0.35);
        assert_eq!(model.k_base, 0.95);
        assert_eq!(model.k_surf, 0.35);
    }

    #[test]
    fn rejects_invalid_area_fractions() {
        let mut model = Awbm::new();
        model.a1 = 0.7;
        model.a2 = 0.4;

        assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            model.run_step(1.0, 0.0);
        }))
        .is_err());
    }

    #[test]
    fn zero_capacity_stores_produce_excess() {
        let mut model = Awbm::new();
        model.set_params(0.134, 0.433, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0);

        assert!((model.run_step(10.0, 0.0) - 10.0).abs() < 1e-12);
        assert!((model.excess() - 10.0).abs() < 1e-12);
    }

    #[test]
    fn evapotranspiration_is_limited_by_available_water() {
        let mut model = Awbm::new();

        model.run_step(5.0, 20.0);

        assert!((model.evapotranspiration() - 5.0).abs() < 1e-12);
        assert_eq!(model.s1(), 0.0);
        assert_eq!(model.s2(), 0.0);
        assert_eq!(model.s3(), 0.0);
        assert_eq!(model.excess(), 0.0);
    }

    #[test]
    fn initial_stores_cannot_exceed_capacity() {
        let mut model = Awbm::new();

        assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            model.set_initial_stores(71.0, 0.0, 0.0, 0.0, 0.0);
        }))
        .is_err());
    }

    #[test]
    fn routing_matches_recession_constants() {
        let mut model = Awbm::new();
        model.set_params(1.0, 0.0, 10.0, 100.0, 100.0, 0.5, 0.5, 0.5);

        assert!((model.run_step(15.0, 0.0) - 2.5).abs() < 1e-12);
        assert!((model.baseflow_store() - 1.25).abs() < 1e-12);
        assert!((model.surface_store() - 1.25).abs() < 1e-12);

        assert!((model.run_step(0.0, 0.0) - 1.25).abs() < 1e-12);
        assert!((model.baseflow_store() - 0.625).abs() < 1e-12);
        assert!((model.surface_store() - 0.625).abs() < 1e-12);
    }

    #[test]
    fn stores_remain_non_negative() {
        let mut model = Awbm::new();

        for _ in 0..100 {
            model.run_step(0.0, 100.0);
        }

        assert!(model.s1() >= 0.0);
        assert!(model.s2() >= 0.0);
        assert!(model.s3() >= 0.0);
        assert!(model.baseflow_store() >= 0.0);
        assert!(model.surface_store() >= 0.0);
    }
}
