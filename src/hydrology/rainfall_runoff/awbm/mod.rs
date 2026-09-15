//! Australian Water Balance Model (AWBM).
//!
//! This is the daily AWBM formulation described by Boughton (2004). It uses
//! three partial-area surface stores, a baseflow store, and a surface-runoff
//! routing store. Recession constants must be calibrated for other timesteps.
//!
//! Rainfall, potential evapotranspiration, storage, and runoff are expressed
//! as depths in millimetres per timestep.
//!
//! Parameters are stored exactly as supplied and are never clamped. They are
//! checked once, by `validate_params`, which the node calls from `initialise`
//! before every run so that an invalid set is reported with the node name
//! rather than discovered mid-simulation. `run_step` does no checking.
//!
//! # Two-tap variant
//!
//! `AwbmVariant::TwoTap` is the "AWBM Two Tap" of Hydro Tasmania (Parkyn and
//! Wilson, 1997), as used in Hydstra and TascatchSIM. The surface stores are
//! unchanged. The excess is split by a recharge fraction `inf` that equals
//! `inf_base` while the groundwater store is below `gw_sat` and falls
//! linearly to zero at `gw_max`. Recharge goes to the groundwater store; the
//! rest is direct runoff, unrouted. The groundwater store drains through two
//! taps applied simultaneously to the start-of-step contents: a lower tap
//! releasing `(1 - k_base)` of the store, and an upper tap at depth `h_gw`
//! releasing `(1 - k2)` of the depth above it. The taps are computed before
//! the step's recharge is added and `inf` is evaluated on the drained store.
//! There is no surface routing store in this variant.
//!
//! Both variants accept a per-step capacity scale (`set_capacity_scale`),
//! which the node drives from its optional `cap_ave` expression so that the
//! three capacities can follow a seasonal profile: the effective capacity of
//! store i is `c_i * cap_ave`.

const PARAMETER_COUNT: usize = 8;
const TWO_TAP_PARAMETER_COUNT: usize = 11;

const STANDARD_PARAM_NAMES: [&str; PARAMETER_COUNT] =
    ["a1", "a2", "c1", "c2", "c3", "bfi", "k_base", "k_surf"];
const TWO_TAP_PARAM_NAMES: [&str; TWO_TAP_PARAMETER_COUNT] =
    ["a1", "a2", "c1", "c2", "c3", "inf_base", "gw_sat", "gw_max", "k_base", "k2", "h_gw"];

/// Selects the model formulation.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum AwbmVariant {
    /// Boughton's daily AWBM: BFI split, one baseflow tap, surface routing store.
    #[default]
    Standard,
    /// Hydro Tasmania's two-tap groundwater store with saturation-limited recharge.
    TwoTap,
}

impl AwbmVariant {
    /// The name written to and read from the model file.
    pub fn as_name(self) -> &'static str {
        match self {
            AwbmVariant::Standard => "awbm",
            AwbmVariant::TwoTap => "two_tap",
        }
    }

    /// Parses a model-file name; `None` for anything unrecognised.
    pub fn from_name(name: &str) -> Option<Self> {
        match name.to_lowercase().as_str() {
            "awbm" | "standard" => Some(AwbmVariant::Standard),
            "two_tap" | "twotap" | "two-tap" => Some(AwbmVariant::TwoTap),
            _ => None,
        }
    }

    /// Number of values on the `params` line.
    pub fn parameter_count(self) -> usize {
        self.parameter_names().len()
    }

    /// Parameter names in `params` order; also the optimisable names.
    pub fn parameter_names(self) -> &'static [&'static str] {
        match self {
            AwbmVariant::Standard => &STANDARD_PARAM_NAMES,
            AwbmVariant::TwoTap => &TWO_TAP_PARAM_NAMES,
        }
    }
}

/// Default AWBM parameters.
pub const DEFAULT_A1: f64 = 0.134;
pub const DEFAULT_A2: f64 = 0.433;
pub const DEFAULT_C1: f64 = 7.0;
pub const DEFAULT_C2: f64 = 70.0;
pub const DEFAULT_C3: f64 = 150.0;
pub const DEFAULT_BFI: f64 = 0.35;
pub const DEFAULT_K_BASE: f64 = 0.95;
pub const DEFAULT_K_SURF: f64 = 0.35;

/// Default two-tap groundwater parameters: the Musselroe calibration
/// (Hydro Tasmania, 2007), the only complete published set.
pub const DEFAULT_GW_SAT: f64 = 90.0;
pub const DEFAULT_GW_MAX: f64 = 100.0;
pub const DEFAULT_K2: f64 = 0.80;
pub const DEFAULT_H_GW: f64 = 22.0;

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
    /// Unused by the two-tap variant.
    pub k_surf: f64,

    /// Model formulation.
    pub variant: AwbmVariant,
    /// Two-tap: groundwater depth above which the recharge fraction starts to fall, in mm.
    pub gw_sat: f64,
    /// Two-tap: groundwater depth at which the recharge fraction reaches zero, in mm.
    pub gw_max: f64,
    /// Two-tap: fraction of the depth above `h_gw` retained by the upper tap each timestep.
    pub k2: f64,
    /// Two-tap: depth of the upper tap, in mm.
    pub h_gw: f64,

    /// Multiplier applied to the three capacities this step (1 unless the node
    /// supplies a `cap_ave` expression).
    cap_scale: f64,
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

            variant: AwbmVariant::Standard,
            gw_sat: DEFAULT_GW_SAT,
            gw_max: DEFAULT_GW_MAX,
            k2: DEFAULT_K2,
            h_gw: DEFAULT_H_GW,

            cap_scale: 1.0,
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
    /// Calibration bounds belong in the calibration layer. Validity is
    /// checked by `validate_params` before a run.
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

    /// Sets parameters from a vector in the order given by
    /// `AwbmVariant::parameter_names` for the current variant:
    /// standard `a1, a2, c1, c2, c3, bfi, k_base, k_surf`;
    /// two-tap `a1, a2, c1, c2, c3, inf_base, gw_sat, gw_max, k_base, k2, h_gw`.
    pub fn set_params_by_vec(&mut self, params: &[f64]) {
        assert_eq!(
            params.len(),
            self.variant.parameter_count(),
            "AWBM ({}) requires exactly {} parameters",
            self.variant.as_name(),
            self.variant.parameter_count()
        );

        match self.variant {
            AwbmVariant::Standard => {
                self.set_params(
                    params[0], params[1], params[2], params[3], params[4], params[5], params[6], params[7],
                );
            }
            AwbmVariant::TwoTap => {
                self.a1 = params[0];
                self.a2 = params[1];
                self.c1 = params[2];
                self.c2 = params[3];
                self.c3 = params[4];
                self.bfi = params[5]; // inf_base
                self.gw_sat = params[6];
                self.gw_max = params[7];
                self.k_base = params[8];
                self.k2 = params[9];
                self.h_gw = params[10];
            }
        }
    }

    /// Returns parameters in the order used by `set_params_by_vec`.
    pub fn get_params_as_vec(&self) -> Vec<f64> {
        match self.variant {
            AwbmVariant::Standard => vec![
                self.a1, self.a2, self.c1, self.c2, self.c3, self.bfi, self.k_base, self.k_surf,
            ],
            AwbmVariant::TwoTap => vec![
                self.a1, self.a2, self.c1, self.c2, self.c3, self.bfi, self.gw_sat, self.gw_max,
                self.k_base, self.k2, self.h_gw,
            ],
        }
    }

    /// Sets the multiplier applied to the three store capacities on the next
    /// step. The node calls this each step from its `cap_ave` expression.
    pub fn set_capacity_scale(&mut self, scale: f64) {
        self.cap_scale = scale;
    }

    /// Checks that the parameter set is physically valid.
    ///
    /// Returns a message naming the offending parameter(s) and their values.
    /// Call once before a run; the node does this from `initialise`.
    pub fn validate_params(&self) -> Result<(), String> {
        let unit = |v: f64| v.is_finite() && (0.0..=1.0).contains(&v);
        let non_negative = |v: f64| v.is_finite() && v >= 0.0;

        if !(non_negative(self.a1) && non_negative(self.a2) && self.a1 + self.a2 <= 1.0) {
            return Err(format!(
                "AWBM partial areas must be finite and non-negative with a1 + a2 <= 1, but a1 = {} and a2 = {}.",
                self.a1, self.a2
            ));
        }
        if !(non_negative(self.c1) && non_negative(self.c2) && non_negative(self.c3)) {
            return Err(format!(
                "AWBM store capacities must be finite and non-negative, but c1 = {}, c2 = {} and c3 = {}.",
                self.c1, self.c2, self.c3
            ));
        }
        let bfi_name = match self.variant {
            AwbmVariant::Standard => "bfi",
            AwbmVariant::TwoTap => "inf_base",
        };
        if !unit(self.bfi) {
            return Err(format!("AWBM {} must be between 0 and 1, but was {}.", bfi_name, self.bfi));
        }
        if !unit(self.k_base) {
            return Err(format!("AWBM k_base must be between 0 and 1, but was {}.", self.k_base));
        }
        match self.variant {
            AwbmVariant::Standard => {
                if !unit(self.k_surf) {
                    return Err(format!("AWBM k_surf must be between 0 and 1, but was {}.", self.k_surf));
                }
            }
            AwbmVariant::TwoTap => {
                if !(non_negative(self.gw_sat) && self.gw_max.is_finite() && self.gw_max > self.gw_sat) {
                    return Err(format!(
                        "AWBM two-tap requires 0 <= gw_sat < gw_max, but gw_sat = {} and gw_max = {}.",
                        self.gw_sat, self.gw_max
                    ));
                }
                if !unit(self.k2) {
                    return Err(format!("AWBM k2 must be between 0 and 1, but was {}.", self.k2));
                }
                if !non_negative(self.h_gw) {
                    return Err(format!("AWBM h_gw must be finite and non-negative, but was {}.", self.h_gw));
                }
            }
        }
        Ok(())
    }

    fn areas(&self) -> (f64, f64, f64) {
        (self.a1, self.a2, 1.0 - self.a1 - self.a2)
    }

    /// Runs one timestep and returns total runoff.
    ///
    /// Parameters are assumed valid (see `validate_params`). Inputs are used
    /// as given, as in GR4J: a non-finite input produces non-finite output.
    pub fn run_step(&mut self, rainfall: f64, pet: f64) -> f64 {
        self.rainfall = rainfall;
        self.pet = pet;

        let (a1, a2, a3) = self.areas();
        let cap_scale = self.cap_scale;

        let update_store = |store: &mut f64, capacity: f64| -> (f64, f64) {
            *store += rainfall;

            let actual_et = pet.min(*store);
            *store -= actual_et;

            let excess = (*store - capacity).max(0.0);
            *store -= excess;

            (excess, actual_et)
        };

        let (e1, et1) = update_store(&mut self.s1, self.c1 * cap_scale);
        let (e2, et2) = update_store(&mut self.s2, self.c2 * cap_scale);
        let (e3, et3) = update_store(&mut self.s3, self.c3 * cap_scale);

        self.excess = a1 * e1 + a2 * e2 + a3 * e3;
        self.partial_excess = self.excess;
        self.evapotranspiration = a1 * et1 + a2 * et2 + a3 * et3;
        self.effective_rainfall = rainfall - self.evapotranspiration;

        match self.variant {
            AwbmVariant::Standard => {
                self.baseflow_recharge = self.bfi * self.excess;
                self.surface_runoff = (1.0 - self.bfi) * self.excess;

                self.baseflow_store += self.baseflow_recharge;
                self.baseflow = (1.0 - self.k_base) * self.baseflow_store;
                self.baseflow_store -= self.baseflow;

                self.surface_store += self.surface_runoff;
                self.routed_surface_runoff = (1.0 - self.k_surf) * self.surface_store;
                self.surface_store -= self.routed_surface_runoff;
            }
            AwbmVariant::TwoTap => {
                // Both taps drain the start-of-step store.
                let lower_tap = (1.0 - self.k_base) * self.baseflow_store;
                let upper_tap = (1.0 - self.k2) * (self.baseflow_store - self.h_gw).max(0.0);
                self.baseflow_store -= lower_tap + upper_tap;
                self.baseflow = lower_tap + upper_tap;

                // Recharge fraction tapers from inf_base at gw_sat to zero at gw_max.
                let taper = ((self.gw_max - self.baseflow_store) / (self.gw_max - self.gw_sat)).clamp(0.0, 1.0);
                self.baseflow_recharge = self.bfi * taper * self.excess;
                self.baseflow_store += self.baseflow_recharge;

                // Direct runoff leaves unrouted.
                self.surface_runoff = self.excess - self.baseflow_recharge;
                self.routed_surface_runoff = self.surface_runoff;
            }
        }

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
    ) -> Result<&mut Self, String> {
        let stores = [s1, s2, s3, baseflow_store, surface_store];
        if !stores.iter().all(|v| v.is_finite() && *v >= 0.0) {
            return Err(format!(
                "AWBM initial stores must be finite and non-negative, but were {:?}.",
                stores
            ));
        }
        if s1 > self.c1 || s2 > self.c2 || s3 > self.c3 {
            return Err(format!(
                "AWBM initial surface stores ({}, {}, {}) cannot exceed their capacities ({}, {}, {}).",
                s1, s2, s3, self.c1, self.c2, self.c3
            ));
        }

        self.s1 = s1;
        self.s2 = s2;
        self.s3 = s3;
        self.baseflow_store = baseflow_store;
        self.surface_store = surface_store;
        Ok(self)
    }

    /// Partial area of surface store 3, derived from `a1` and `a2`.
    pub fn area3(&self) -> f64 {
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
    fn variant_names_round_trip() {
        assert_eq!(AwbmVariant::from_name("two_tap"), Some(AwbmVariant::TwoTap));
        assert_eq!(AwbmVariant::from_name("AWBM"), Some(AwbmVariant::Standard));
        assert_eq!(AwbmVariant::from_name("gr4j"), None);
        assert_eq!(AwbmVariant::TwoTap.as_name(), "two_tap");
    }

    #[test]
    fn default_parameters_are_valid() {
        assert_eq!(Awbm::new().validate_params(), Ok(()));
    }

    #[test]
    fn rejects_invalid_area_fractions() {
        let mut model = Awbm::new();
        model.a1 = 0.7;
        model.a2 = 0.4;

        let message = model.validate_params().unwrap_err();
        assert!(message.contains("a1 + a2 <= 1"), "{message}");
        assert!(message.contains("0.7") && message.contains("0.4"), "{message}");
    }

    #[test]
    fn rejects_out_of_range_parameters() {
        let cases: [(fn(&mut Awbm), &str); 5] = [
            (|m| m.a1 = -0.1, "partial areas"),
            (|m| m.c2 = -1.0, "capacities"),
            (|m| m.bfi = 1.5, "bfi"),
            (|m| m.k_base = f64::NAN, "k_base"),
            (|m| m.k_surf = -0.2, "k_surf"),
        ];

        for (mutate, expected) in cases {
            let mut model = Awbm::new();
            mutate(&mut model);
            let message = model.validate_params().unwrap_err();
            assert!(message.contains(expected), "expected '{expected}' in '{message}'");
        }
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

        assert!(model.set_initial_stores(8.0, 0.0, 0.0, 0.0, 0.0).is_err());
        assert!(model.set_initial_stores(7.0, 70.0, 150.0, 5.0, 5.0).is_ok());
        assert_eq!(model.s1(), 7.0);
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

    fn two_tap() -> Awbm {
        let mut model = Awbm::new();
        model.variant = AwbmVariant::TwoTap;
        // Musselroe 2007: inf_base 0.76, gw_sat 90, gw_max 100, K1 0.98, K2 0.80, H_GW 22
        model.set_params_by_vec(&[0.134, 0.433, 7.0, 70.0, 150.0, 0.76, 90.0, 100.0, 0.98, 0.80, 22.0]);
        model
    }

    #[test]
    fn two_tap_parameter_vector_round_trips() {
        let model = two_tap();
        assert_eq!(model.get_params_as_vec(), vec![0.134, 0.433, 7.0, 70.0, 150.0, 0.76, 90.0, 100.0, 0.98, 0.80, 22.0]);
        assert_eq!(AwbmVariant::TwoTap.parameter_names().len(), 11);
        assert_eq!(model.validate_params(), Ok(()));
    }

    #[test]
    fn two_tap_recession_is_0_78_above_the_upper_tap_and_0_98_below() {
        // Flow ratio between dry days: 1 - (1 - k_base) - (1 - k2) = 0.78 while the
        // store is above h_gw, then k_base = 0.98 once it has drained below.
        let mut model = two_tap();
        model.set_initial_stores(0.0, 0.0, 0.0, 60.0, 0.0).unwrap();
        let mut previous = model.run_step(0.0, 0.0);
        for _ in 0..3 {
            let flow = model.run_step(0.0, 0.0);
            assert!((flow / previous - 0.78).abs() < 1e-12, "{}", flow / previous);
            previous = flow;
        }
        model.set_initial_stores(0.0, 0.0, 0.0, 10.0, 0.0).unwrap();
        let first = model.run_step(0.0, 0.0);
        let second = model.run_step(0.0, 0.0);
        assert!((second / first - 0.98).abs() < 1e-12);
        assert!((first - 0.02 * 10.0).abs() < 1e-12);
    }

    #[test]
    fn two_tap_upper_tap_is_continuous_at_its_depth() {
        let mut just_below = two_tap();
        just_below.set_initial_stores(0.0, 0.0, 0.0, 22.0, 0.0).unwrap();
        let mut just_above = two_tap();
        just_above.set_initial_stores(0.0, 0.0, 0.0, 22.0 + 1e-9, 0.0).unwrap();
        assert!((just_below.run_step(0.0, 0.0) - just_above.run_step(0.0, 0.0)).abs() < 1e-9);
    }

    #[test]
    fn two_tap_recharge_tapers_to_zero_at_gw_max() {
        // Zero-capacity stores turn all rain into excess; the store starts at
        // gw_max, so after the taps it is drained to a known depth and the
        // taper can be checked exactly.
        let mut model = two_tap();
        model.set_params_by_vec(&[0.134, 0.433, 0.0, 0.0, 0.0, 0.76, 90.0, 100.0, 1.0, 1.0, 1000.0]);
        model.set_initial_stores(0.0, 0.0, 0.0, 100.0, 0.0).unwrap();
        let runoff = model.run_step(10.0, 0.0);
        assert_eq!(model.baseflow_recharge(), 0.0);
        assert!((runoff - 10.0).abs() < 1e-12);

        model.set_initial_stores(0.0, 0.0, 0.0, 95.0, 0.0).unwrap();
        model.run_step(10.0, 0.0);
        assert!((model.baseflow_recharge() - 0.76 * 0.5 * 10.0).abs() < 1e-12);

        model.set_initial_stores(0.0, 0.0, 0.0, 50.0, 0.0).unwrap();
        model.run_step(10.0, 0.0);
        assert!((model.baseflow_recharge() - 7.6).abs() < 1e-12);
    }

    #[test]
    fn two_tap_conserves_water() {
        let mut model = two_tap();
        let rain = [12.0, 0.0, 30.0, 45.0, 0.0, 0.0, 3.0, 80.0, 0.0, 0.0, 0.0, 20.0];
        let pet = [2.0, 3.0, 1.0, 1.0, 4.0, 4.0, 3.0, 1.0, 2.0, 5.0, 5.0, 2.0];
        let stored = |m: &Awbm| 0.134 * m.s1() + 0.433 * m.s2() + 0.433 * m.s3() + m.baseflow_store();
        let mut balance = 0.0;
        for (p, e) in rain.iter().zip(pet.iter()) {
            let before = stored(&model);
            let runoff = model.run_step(*p, *e);
            balance += p - model.evapotranspiration() - runoff - (stored(&model) - before);
        }
        assert!(balance.abs() < 1e-12, "{balance}");
    }

    #[test]
    fn two_tap_rejects_inverted_thresholds() {
        let mut model = two_tap();
        model.gw_max = 90.0; // equal to gw_sat
        assert!(model.validate_params().unwrap_err().contains("gw_sat < gw_max"));
        let mut model = two_tap();
        model.k2 = 1.2;
        assert!(model.validate_params().unwrap_err().contains("k2"));
    }

    #[test]
    fn capacity_scale_multiplies_the_three_capacities() {
        let mut model = Awbm::new();
        model.set_params(1.0, 0.0, 10.0, 100.0, 100.0, 0.0, 1.0, 0.0);
        model.set_capacity_scale(0.5);
        // c1 is effectively 5 mm: 15 mm of rain overflows 10 mm.
        assert!((model.run_step(15.0, 0.0) - 10.0).abs() < 1e-12);
        model.set_capacity_scale(1.0);
        model.set_initial_stores(0.0, 0.0, 0.0, 0.0, 0.0).unwrap();
        assert!((model.run_step(15.0, 0.0) - 5.0).abs() < 1e-12);
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
