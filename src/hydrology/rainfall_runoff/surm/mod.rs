//! Simple Urban Rainfall-Runoff Model (SURM).
//!
//! This implementation is a daily formulation. `coeff` is expressed in
//! mm/day, and the evapotranspiration equation is calibrated for daily
//! timesteps. Sub-daily use requires recalibration.
//!
//! Rainfall, potential evapotranspiration, storage, and runoff are expressed
//! in millimetres. Soil and groundwater stores are depths over the pervious
//! area.
//!
//! Runoff components generated over the impervious and pervious areas are
//! converted to equivalent whole-catchment depths using `imp_fraction`.
//! Deep seepage is tracked separately and is not included in reported runoff.
//!
//! Parameters are stored and used exactly as supplied and are never clamped.
//! Calibration bounds belong to the calibration layer; physical validity is
//! checked once, by `validate_params`, which the node calls from `initialise`
//! before every run. `run_step` does no checking and uses its inputs as
//! given, as GR4J does.
//!
//! Groundwater baseflow is calculated before seepage. Seepage uses the
//! pre-baseflow groundwater store but is limited to the storage remaining
//! after baseflow, preventing the store from becoming negative when the
//! combined coefficients exceed the available groundwater.
//!
//! The model equations and water balance are covered by the tests below.

const PARAMETER_COUNT: usize = 9;

// -----------------------------------------------------------------------------
// Model
// -----------------------------------------------------------------------------

#[derive(Clone, Debug)]
pub struct Surm {
    // Inputs and outputs
    rainfall: f64,
    pet: f64,
    runoff: f64,

    // Flow components
    imp: f64,
    infex: f64,
    satex: f64,
    bas: f64,
    seep: f64,
    evapotranspiration: f64,

    // Stores
    soil_store: f64,
    groundwater_store: f64,

    // Parameters, public for optimisation
    pub imp_fraction: f64, // Impervious fraction of catchment [-]
    pub impsc: f64,        // Impervious threshold [mm]
    pub smsc: f64,         // Soil moisture store capacity [mm]
    pub coeff: f64,        // Maximum infiltration coefficient [mm/day]
    pub sq: f64,           // Infiltration exponent [-]
    pub fc: f64,           // Field capacity [mm]
    pub rfac: f64,         // Groundwater recharge factor [-]
    pub bfac: f64,         // Baseflow factor [-]
    pub sfac: f64,         // Deep-seepage factor [-]
}

// Defaults
impl Default for Surm {
    fn default() -> Self {
        Self {
            rainfall: 0.0,
            pet: 0.0,
            runoff: 0.0,

            imp: 0.0,
            infex: 0.0,
            satex: 0.0,
            bas: 0.0,
            seep: 0.0,
            evapotranspiration: 0.0,

            soil_store: 0.0,
            groundwater_store: 0.0,

            // Default parameter set
            imp_fraction: 0.0,
            impsc: 1.0,
            smsc: 97.0,
            coeff: 360.0,
            sq: 0.5,
            fc: 79.0,
            rfac: 1.0,
            bfac: 0.5,
            sfac: 0.0,
        }
    }
}

// Model implementation
impl Surm {
    /// Creates a model using the default parameters and empty stores.
    pub fn new() -> Self {
        Self::default()
    }

    /// Restores the default parameter set.
    pub fn set_params_default(&mut self) -> &mut Self {
        *self = Self::default();
        self
    }

    /// Sets the complete SURM parameter set without clamping.
    ///
    /// Calibration bounds belong to the calibration layer. Validity is
    /// checked by `validate_params` before a run.
    #[allow(clippy::too_many_arguments)]
    pub fn set_params(
        &mut self,
        imp_fraction: f64,
        impsc: f64,
        smsc: f64,
        coeff: f64,
        sq: f64,
        fc: f64,
        rfac: f64,
        bfac: f64,
        sfac: f64,
    ) -> &mut Self {
        self.imp_fraction = imp_fraction;
        self.impsc = impsc;
        self.smsc = smsc;
        self.coeff = coeff;
        self.sq = sq;
        self.fc = fc;
        self.rfac = rfac;
        self.bfac = bfac;
        self.sfac = sfac;
        self
    }

    /// Sets parameters from a vector (defined or calibration)
    /// [IMP_FRACTION, IMPSC, SMSC, COEFF, SQ, FC, RFAC, BFAC, SFAC]
    pub fn set_params_by_vec(&mut self, vec_params: &[f64]) {
        assert_eq!(
            vec_params.len(),
            PARAMETER_COUNT,
            "SURM requires exactly {PARAMETER_COUNT} parameters"
        );

        // Parameters are stored exactly as supplied.
        self.set_params(
            vec_params[0],
            vec_params[1],
            vec_params[2],
            vec_params[3],
            vec_params[4],
            vec_params[5],
            vec_params[6],
            vec_params[7],
            vec_params[8],
        );
    }

    /// Returns the active parameter set as a vector.
    /// [IMP_FRACTION, IMPSC, SMSC, COEFF, SQ, FC, RFAC, BFAC, SFAC]
    pub fn get_params_as_vec(&self) -> Vec<f64> {
        vec![
            self.imp_fraction,
            self.impsc,
            self.smsc,
            self.coeff,
            self.sq,
            self.fc,
            self.rfac,
            self.bfac,
            self.sfac,
        ]
    }

    /// Checks that the parameter set is physically valid.
    ///
    /// Fractions and factors must lie in [0, 1]; depths and the infiltration
    /// coefficient and exponent must be non-negative; `smsc` must be positive
    /// because the infiltration and evaporation equations divide by it.
    /// Returns a message naming the offending parameter and its value. Call
    /// once before a run; the node does this from `initialise`.
    pub fn validate_params(&self) -> Result<(), String> {
        let unit = |v: f64| v.is_finite() && (0.0..=1.0).contains(&v);
        let non_negative = |v: f64| v.is_finite() && v >= 0.0;

        let unit_params = [
            ("imp_fraction", self.imp_fraction),
            ("rfac", self.rfac),
            ("bfac", self.bfac),
            ("sfac", self.sfac),
        ];
        for (name, value) in unit_params {
            if !unit(value) {
                return Err(format!("SURM {} must be between 0 and 1, but was {}.", name, value));
            }
        }

        let non_negative_params = [
            ("impsc", self.impsc),
            ("coeff", self.coeff),
            ("sq", self.sq),
            ("fc", self.fc),
        ];
        for (name, value) in non_negative_params {
            if !non_negative(value) {
                return Err(format!("SURM {} must be finite and non-negative, but was {}.", name, value));
            }
        }

        if !(self.smsc.is_finite() && self.smsc > 0.0) {
            return Err(format!("SURM smsc must be finite and positive, but was {}.", self.smsc));
        }
        Ok(())
    }

    /// Resets the model inputs, outputs, flow components and stores.
    pub fn initialize_state_empty(&mut self) -> &mut Self {
        self.rainfall = 0.0;
        self.pet = 0.0;
        self.runoff = 0.0;

        self.imp = 0.0;
        self.infex = 0.0;
        self.satex = 0.0;
        self.bas = 0.0;
        self.seep = 0.0;
        self.evapotranspiration = 0.0;
        self.soil_store = 0.0;
        self.groundwater_store = 0.0;

        self
    }

    pub fn set_initial_stores(&mut self, soil_store: f64, groundwater_store: f64) -> &mut Self {
        self.soil_store = soil_store.max(0.0);
        self.groundwater_store = groundwater_store.max(0.0);

        self
    }

    /// Alias for `initialize_state_empty()`.
    pub fn reset(&mut self) {
        self.initialize_state_empty();
    }

    /// Runs one daily timestep and returns total catchment runoff in mm.
    ///
    /// Rainfall depth is applied to both surface types. Runoff generated from
    /// each surface is then weighted by its corresponding area fraction.
    ///
    /// Parameters are assumed valid (see `validate_params`).
    pub fn run_step(&mut self, rainfall: f64, pet: f64) -> f64 {
        self.rainfall = rainfall;
        self.pet = pet;

        let imp_fraction = self.imp_fraction;
        let pervious_fraction = 1.0 - imp_fraction;

        // Impervious runoff
        let impervious_runoff_depth = (self.rainfall - self.impsc).max(0.0);

        // Convert runoff depths from contributing-area depths
        // to equivalent depths over the whole catchment.
        self.imp = imp_fraction * impervious_runoff_depth;

        // Pervious-area infiltration
        let infiltration_capacity = self.coeff * (-self.sq * self.soil_store / self.smsc).exp();

        let infiltration = infiltration_capacity.min(self.rainfall);

        // Infiltration-excess runoff
        let infiltration_excess_depth = (self.rainfall - infiltration).max(0.0);

        self.infex = pervious_fraction * infiltration_excess_depth;

        // Add infiltration to the pervious-area soil store.
        self.soil_store += infiltration;

        // Evapotranspiration
        let evaporation_capacity = 10.0 * self.soil_store / self.smsc;

        let evaporation = evaporation_capacity.min(self.pet).min(self.soil_store);

        self.evapotranspiration = pervious_fraction * evaporation;
        self.soil_store -= evaporation;

        // Saturation-excess runoff
        let saturation_excess_depth = (self.soil_store - self.smsc).max(0.0);

        self.soil_store -= saturation_excess_depth;

        self.satex = pervious_fraction * saturation_excess_depth;

        // Groundwater recharge
        let recharge = (self.rfac * (self.soil_store - self.fc))
            .max(0.0)
            .min(self.soil_store);

        self.soil_store -= recharge;
        self.groundwater_store += recharge;

        // Baseflow
        let baseflow_depth = (self.bfac * self.groundwater_store).min(self.groundwater_store);

        self.bas = pervious_fraction * baseflow_depth;

        // Deep seepage
        let groundwater_after_baseflow = self.groundwater_store - baseflow_depth;

        let seepage_depth = (self.sfac * self.groundwater_store).min(groundwater_after_baseflow);

        self.seep = pervious_fraction * seepage_depth;

        self.groundwater_store -= baseflow_depth + seepage_depth;

        // Total catchment runoff
        self.runoff = self.imp + self.infex + self.satex + self.bas;

        self.runoff
    }

    // Getters

    pub fn runoff(&self) -> f64 {
        self.runoff
    }

    pub fn rainfall(&self) -> f64 {
        self.rainfall
    }

    pub fn pet(&self) -> f64 {
        self.pet
    }

    pub fn evapotranspiration(&self) -> f64 {
        self.evapotranspiration
    }

    /// Soil moisture depth over the pervious area.
    pub fn soil_store(&self) -> f64 {
        self.soil_store
    }

    /// Groundwater storage depth over the pervious area.
    pub fn groundwater_store(&self) -> f64 {
        self.groundwater_store
    }

    /// Impervious runoff as an equivalent depth over the whole catchment.
    pub fn impervious_runoff(&self) -> f64 {
        self.imp
    }

    /// Infiltration-excess runoff as an equivalent whole-catchment depth.
    pub fn infiltration_excess(&self) -> f64 {
        self.infex
    }

    /// Saturation-excess runoff as an equivalent whole-catchment depth.
    pub fn saturation_excess(&self) -> f64 {
        self.satex
    }

    /// Baseflow as an equivalent depth over the whole catchment.
    pub fn baseflow(&self) -> f64 {
        self.bas
    }

    /// Deep seepage as an equivalent depth over the whole catchment.
    pub fn deep_seepage(&self) -> f64 {
        self.seep
    }
}

// Tests
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn new_model_uses_expected_defaults() {
        let model = Surm::new();

        assert_eq!(model.imp_fraction, 0.0);
        assert_eq!(model.impsc, 1.0);
        assert_eq!(model.smsc, 97.0);
        assert_eq!(model.coeff, 360.0);
        assert_eq!(model.sq, 0.5);
        assert_eq!(model.fc, 79.0);
        assert_eq!(model.rfac, 1.0);
        assert_eq!(model.bfac, 0.5);
        assert_eq!(model.sfac, 0.0);
    }

    #[test]
    fn default_model_has_no_stored_water() {
        let model = Surm::new();

        assert_eq!(model.soil_store(), 0.0);
        assert_eq!(model.groundwater_store(), 0.0);
    }

    #[test]
    fn runoff_is_non_negative() {
        let mut model = Surm::new();

        assert!(model.run_step(100.0, 0.0) >= 0.0);
    }

    #[test]
    fn parameter_vector_has_expected_length() {
        let model = Surm::new();

        assert_eq!(model.get_params_as_vec().len(), PARAMETER_COUNT);
    }

    #[test]
    fn impervious_runoff_is_scaled_by_impervious_fraction() {
        let mut model = Surm::new();

        model.set_params(0.3, 1.0, 97.0, 360.0, 0.5, 79.0, 1.0, 0.5, 0.0);

        model.run_step(10.0, 0.0);

        // Impervious runoff depth:
        // 10 - 1 = 9 mm
        //
        // Whole-catchment contribution:
        // 9 * 0.3 = 2.7 mm
        assert!((model.impervious_runoff() - 2.7).abs() < 1e-12);
    }

    #[test]
    fn groundwater_store_cannot_become_negative() {
        let mut model = Surm::new();

        model.set_params(0.0, 1.0, 97.0, 360.0, 0.5, 79.0, 1.0, 1.0, 1.0);

        model.run_step(100.0, 0.0);

        for _ in 0..100 {
            model.run_step(0.0, 0.0);
        }

        assert!(model.groundwater_store() >= 0.0);
    }

    #[test]
    fn initial_stores_can_be_set() {
        let mut model = Surm::new();

        model.set_initial_stores(30.0, 10.0);

        assert_eq!(model.soil_store(), 30.0);
        assert_eq!(model.groundwater_store(), 10.0);
    }

    #[test]
    fn default_parameters_are_valid() {
        assert_eq!(Surm::new().validate_params(), Ok(()));
    }

    #[test]
    fn rejects_out_of_range_parameters() {
        let cases: [(fn(&mut Surm), &str); 5] = [
            (|m| m.imp_fraction = 1.2, "imp_fraction"),
            (|m| m.coeff = -50.0, "coeff"),
            (|m| m.bfac = -0.5, "bfac"),
            (|m| m.smsc = 0.0, "smsc"),
            (|m| m.fc = f64::NAN, "fc"),
        ];

        for (mutate, expected) in cases {
            let mut model = Surm::new();
            mutate(&mut model);
            let message = model.validate_params().unwrap_err();
            assert!(message.contains(expected), "expected '{expected}' in '{message}'");
        }
    }

    #[test]
    fn parameters_are_not_silently_clamped() {
        let mut model = Surm::new();

        model.set_params(0.25, 2.0, 120.0, 200.0, 0.75, 80.0, 0.8, 0.4, 0.1);

        assert_eq!(model.imp_fraction, 0.25);
        assert_eq!(model.impsc, 2.0);
        assert_eq!(model.sq, 0.75);
    }

    #[test]
    fn water_balance_closes_for_pervious_catchment() {
        let mut model = Surm::new();
        model.set_params(0.0, 0.0, 10.0, 100.0, 0.0, 5.0, 1.0, 0.25, 0.1);

        let mut previous_soil = model.soil_store();
        let mut previous_groundwater = model.groundwater_store();
        let mut balance_error = 0.0;

        for (rainfall, pet) in [(12.0, 0.0), (0.0, 2.0), (8.0, 1.0)] {
            let runoff = model.run_step(rainfall, pet);
            let storage_change = (model.soil_store() - previous_soil)
                + (model.groundwater_store() - previous_groundwater);

            balance_error += rainfall
                - runoff
                - model.deep_seepage()
                - model.evapotranspiration()
                - storage_change;

            previous_soil = model.soil_store();
            previous_groundwater = model.groundwater_store();
        }

        assert!(balance_error.abs() < 1e-10);
    }

    #[test]
    fn daily_reference_sequence_is_stable() {
        let mut model = Surm::new();
        model.set_params(0.0, 0.0, 10.0, 100.0, 0.0, 5.0, 1.0, 0.0, 0.0);

        let runoff = [
            model.run_step(12.0, 0.0),
            model.run_step(0.0, 0.0),
            model.run_step(0.0, 2.0),
        ];

        assert_eq!(runoff, [2.0, 0.0, 0.0]);
    }
}
