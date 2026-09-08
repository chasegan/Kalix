/// Simple Urban Rainfall-Runoff Model (SURM).
///
/// Rainfall, potential evapotranspiration, storage and runoff are expressed
/// in millimetres per daily model timestep.
///
/// Soil and groundwater stores are expressed as depths over the pervious area.
///
/// Reported runoff components are converted to equivalent depths over the
/// whole catchment using:
///
/// - impervious contribution = impervious runoff depth * imp_fraction
/// - pervious contribution = pervious runoff depth * (1 - imp_fraction)

// -----------------------------------------------------------------------------
// Parameter limits
// -----------------------------------------------------------------------------

const IMP_FRACTION_MIN: f64 = 0.0;
const IMP_FRACTION_MAX: f64 = 1.0;

const IMPSC_MIN: f64 = 0.0;
const IMPSC_MAX: f64 = 5.0;

const SMSC_MIN: f64 = 1.0;
const SMSC_MAX: f64 = 500.0;

const COEFF_MIN: f64 = 0.0;
const COEFF_MAX: f64 = 400.0;

const SQ_MIN: f64 = 0.0;
const SQ_MAX: f64 = 10.0;

const FC_MIN: f64 = 0.0;
const FC_MAX: f64 = 500.0;

const RFAC_MIN: f64 = 0.0;
const RFAC_MAX: f64 = 1.0;

const BFAC_MIN: f64 = 0.0;
const BFAC_MAX: f64 = 1.0;

const SFAC_MIN: f64 = 0.0;
const SFAC_MAX: f64 = 1.0;

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
        self.set_params(
            0.0,   // IMP_FRACTION
            1.0,   // IMPSC
            97.0,  // SMSC
            360.0, // COEFF
            0.5,   // SQ
            79.0,  // FC
            1.0,   // RFAC
            0.5,   // BFAC
            0.0,   // SFAC
        )
    }

    /// Sets the complete SURM parameter set (and clamps)
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
        self.imp_fraction = imp_fraction.clamp(IMP_FRACTION_MIN, IMP_FRACTION_MAX);
        self.impsc = impsc.clamp(IMPSC_MIN, IMPSC_MAX);
        self.smsc = smsc.clamp(SMSC_MIN, SMSC_MAX);
        self.coeff = coeff.clamp(COEFF_MIN, COEFF_MAX);
        self.sq = sq.clamp(SQ_MIN, SQ_MAX);
        self.fc = fc.clamp(FC_MIN, FC_MAX);
        self.rfac = rfac.clamp(RFAC_MIN, RFAC_MAX);
        self.bfac = bfac.clamp(BFAC_MIN, BFAC_MAX);
        self.sfac = sfac.clamp(SFAC_MIN, SFAC_MAX);

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

        // Use set_params so parameters supplied by an optimiser are clamped
        // to the documented ranges.
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
    pub fn run_step(&mut self, rainfall: f64, pet: f64) -> f64 {
        self.rainfall = rainfall.max(0.0);
        self.pet = pet.max(0.0);

        // Defensive clamping because parameters remain public.
        let imp_fraction = self.imp_fraction.clamp(IMP_FRACTION_MIN, IMP_FRACTION_MAX);

        let pervious_fraction = 1.0 - imp_fraction;

        let impsc = self.impsc.clamp(IMPSC_MIN, IMPSC_MAX);
        let smsc = self.smsc.clamp(SMSC_MIN, SMSC_MAX);
        let coeff = self.coeff.clamp(COEFF_MIN, COEFF_MAX);
        let sq = self.sq.clamp(SQ_MIN, SQ_MAX);
        let fc = self.fc.clamp(FC_MIN, FC_MAX);
        let rfac = self.rfac.clamp(RFAC_MIN, RFAC_MAX);
        let bfac = self.bfac.clamp(BFAC_MIN, BFAC_MAX);
        let sfac = self.sfac.clamp(SFAC_MIN, SFAC_MAX);

        // Impervious runoff
        let impervious_runoff_depth = (self.rainfall - impsc).max(0.0);

        // Convert runoff depths from contributing-area depths
        // to equivalent depths over the whole catchment.
        self.imp = imp_fraction * impervious_runoff_depth;

        // Pervious-area infiltration
        let infiltration_capacity = coeff * (-sq * self.soil_store / smsc).exp();

        let infiltration = infiltration_capacity.max(0.0).min(self.rainfall);

        // Infiltration-excess runoff
        let infiltration_excess_depth = (self.rainfall - infiltration).max(0.0);

        self.infex = pervious_fraction * infiltration_excess_depth;

        // Add infiltration to the pervious-area soil store.
        self.soil_store += infiltration;

        // Evapotranspiration
        let evaporation_capacity = (10.0 * self.soil_store / smsc).max(0.0);

        let evaporation = evaporation_capacity.min(self.pet).min(self.soil_store);

        self.soil_store -= evaporation;

        // Saturation-excess runoff
        let saturation_excess_depth = (self.soil_store - smsc).max(0.0);

        self.soil_store -= saturation_excess_depth;

        self.satex = pervious_fraction * saturation_excess_depth;

        // Groundwater recharge
        let recharge = (rfac * (self.soil_store - fc))
            .max(0.0)
            .min(self.soil_store);

        self.soil_store -= recharge;
        self.groundwater_store += recharge;

        // Baseflow
        let baseflow_depth = (bfac * self.groundwater_store)
            .max(0.0)
            .min(self.groundwater_store);

        self.bas = pervious_fraction * baseflow_depth;

        // Deep seepage
        let groundwater_after_baseflow = (self.groundwater_store - baseflow_depth).max(0.0);

        let seepage_depth = (sfac * self.groundwater_store)
            .max(0.0)
            .min(groundwater_after_baseflow);

        self.seep = pervious_fraction * seepage_depth;

        self.groundwater_store = (self.groundwater_store - baseflow_depth - seepage_depth).max(0.0);

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
}
