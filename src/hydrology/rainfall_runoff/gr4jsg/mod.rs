//! GR4JSG: GR4J with a snow store and, optionally, a glacier (ice) store.
//!
//! The DPIE formulation (Source plugin `GRSG_DPIE`), ported from the Fors
//! node `GRSGDPIENode`. Daily only: the melt equations carry daily constants.
//!
//! - Precipitation is all snow when `tfrac * tmax + (1 - tfrac) * tmin` is
//!   below `taccum`, and all rain otherwise.
//! - Snow melt joins the rain entering an unmodified GR4J.
//! - Ice melt bypasses GR4J through its own unit hydrograph. Nothing creates
//!   ice: a glacier exists only where `initial_ice` puts one.
//!
//! Unlike Fors, lapse rates and rainfall scaling are not parameters (they are
//! written in the node's input expressions), inputs are not clamped, and the
//! `tanh` argument is not capped at 13 (as Kalix's GR4J).
//!
//! Parameters are never clamped. `validate_params` checks them once, from the
//! node's `initialise`; `run_step` does no checking.

use super::gr4j::{s_curves2, Gr4j};

/// Unit-hydrograph S-curve exponent for the ice-melt hydrograph (as GR4J's).
const UH_EXPONENT: f64 = 2.5;

#[derive(Default, Clone)]
pub struct Gr4jsg {
    /// The GR4J model the snow and ice stores feed. Holds x1 to x4.
    pub gr4j: Gr4j,

    // Snow parameters
    pub tfrac: f64,         // weight on tmax in the representative temperature [0, 1]
    pub taccum: f64,        // precipitation is snow below this temperature (°C)
    pub m_rainfall: f64,    // rain-on-snow melt rate (mm/°C/day)
    pub base_rainfall: f64, // rain-on-snow base melt (mm/day)
    pub m_nonrainfall: f64, // melt rate on a day without rain (mm/°C/day)

    // Glacier parameters. Used only when `glacier` is true.
    pub glacier: bool,      // the node declared `ice_params`
    pub initial_ice: f64,   // ice store at the start of the run (mm)
    pub ddfi: f64,          // ice degree-day factor (mm/°C/day)
    pub tmelt: f64,         // ice melts above this temperature (°C)
    pub return_flow: f64,   // time base of the ice-melt unit hydrograph (days)
    pub accumulation: f64,  // constant gain to the ice store (mm/day)

    // Ice-melt unit hydrograph: kernel and storage
    uh3_ordinates: Vec<f64>,
    uh3: Vec<f64>,

    // Stores and the step's fluxes.
    // Public so that gr4jsg nodes may read them
    pub snow_store: f64,
    pub ice_store: f64,
    pub snowfall: f64,
    pub snow_melt: f64,
    pub ice_melt: f64, // melted from the ice store this step, before routing
}

impl Gr4jsg {
    pub fn new() -> Self {
        let mut ans = Self {
            gr4j: Gr4j::new(),
            tfrac: 0.5,
            taccum: 0.0,
            m_rainfall: 3.38,
            base_rainfall: 1.3,
            m_nonrainfall: 3.0,
            ddfi: 6.0,
            return_flow: 0.5,
            ..Default::default()
        };
        ans.initialize();
        ans
    }

    /// Checks that the parameters describe a model that can run. Returns the
    /// first problem found.
    pub fn validate_params(&self) -> Result<(), String> {
        let g = &self.gr4j;
        for (name, value) in [("x1", g.x1), ("x3", g.x3), ("x4", g.x4)] {
            if !(value > 0.0 && value.is_finite()) {
                return Err(format!("GR4JSG parameter {} must be positive, but was {}.", name, value));
            }
        }
        if !g.x2.is_finite() {
            return Err(format!("GR4JSG parameter x2 must be finite, but was {}.", g.x2));
        }
        if !(0.0..=1.0).contains(&self.tfrac) {
            return Err(format!("GR4JSG parameter tfrac must lie in [0, 1], but was {}.", self.tfrac));
        }
        if !self.taccum.is_finite() {
            return Err(format!("GR4JSG parameter taccum must be finite, but was {}.", self.taccum));
        }
        for (name, value) in [("m_rainfall", self.m_rainfall), ("base_rainfall", self.base_rainfall), ("m_nonrainfall", self.m_nonrainfall)] {
            if !(value >= 0.0 && value.is_finite()) {
                return Err(format!("GR4JSG parameter {} cannot be negative, but was {}.", name, value));
            }
        }
        if self.glacier {
            for (name, value) in [("initial_ice", self.initial_ice), ("ddfi", self.ddfi), ("accumulation", self.accumulation)] {
                if !(value >= 0.0 && value.is_finite()) {
                    return Err(format!("GR4JSG parameter {} cannot be negative, but was {}.", name, value));
                }
            }
            if !self.tmelt.is_finite() {
                return Err(format!("GR4JSG parameter tmelt must be finite, but was {}.", self.tmelt));
            }
            if !(self.return_flow > 0.0 && self.return_flow.is_finite()) {
                return Err(format!("GR4JSG parameter return_flow must be positive, but was {}.", self.return_flow));
            }
        }
        Ok(())
    }

    /// Builds the unit hydrographs and resets every store to its starting
    /// value. Call after the parameters change and before a run.
    pub fn initialize(&mut self) {
        self.gr4j.initialize();

        //Ice-melt unit hydrograph (depends on return_flow). It has the shape of
        //GR4J's UH2 with return_flow in place of x4.
        let uh3_len = if self.glacier { (2.0 * self.return_flow).ceil() as usize } else { 0 };
        self.uh3_ordinates = (0..uh3_len)
            .map(|t| s_curves2(t + 1, self.return_flow, UH_EXPONENT) - s_curves2(t, self.return_flow, UH_EXPONENT))
            .collect();
        self.uh3 = vec![0.0; uh3_len];

        self.snow_store = 0.0;
        self.ice_store = if self.glacier { self.initial_ice } else { 0.0 };
        self.snowfall = 0.0;
        self.snow_melt = 0.0;
        self.ice_melt = 0.0;
    }

    /// Runs one day and returns the runoff depth (mm). `tmax` and `tmin` are
    /// the catchment's temperatures (°C), already adjusted for elevation.
    #[inline]
    pub fn run_step(&mut self, p: f64, e: f64, tmax: f64, tmin: f64) -> f64 {
        // Fixed by the model file, so a snow-only catchment runs no glacier code (ADR-0004 §3.5).
        if self.glacier {
            self.step::<true>(p, e, tmax, tmin)
        } else {
            self.step::<false>(p, e, tmax, tmin)
        }
    }

    #[inline(always)]
    fn step<const GLACIER: bool>(&mut self, p: f64, e: f64, tmax: f64, tmin: f64) -> f64 {
        let rep_temp = self.tfrac * tmax + (1.0 - self.tfrac) * tmin;

        //Snow gained from above the catchment. It feeds the glacier while there is one.
        if GLACIER {
            if self.ice_store > 0.0 {
                self.ice_store += self.accumulation;
            } else {
                self.snow_store += self.accumulation;
            }
        }

        //Precipitation is all snow or all rain
        let mut rain = p;
        if rep_temp < self.taccum {
            self.snowfall = p;
            self.snow_store += p;
            rain = 0.0;
        } else {
            self.snowfall = 0.0;
        }

        //Snow melts first. `snow_fraction` is the part of the day it took.
        let mut snow_fraction = 0.0;
        self.snow_melt = 0.0;
        if self.snow_store > 0.0 {
            let potential = if rain > 0.0 {
                rep_temp.max(0.0) * (self.m_rainfall + 0.0126 * rain) + self.base_rainfall // USACE 1960
            } else {
                let beta = (tmin.max(0.0) / 4.4).min(1.5);
                self.m_nonrainfall * (rep_temp.max(0.0) + beta * ((tmax - tmin) / 8.0 + tmin)) // Quick and Pipes 1976
            };
            self.snow_melt = potential.min(self.snow_store);
            self.snow_store -= self.snow_melt;
            if potential > 0.0 {
                snow_fraction = self.snow_melt / potential;
            }
        }

        //Ice melts for the rest of the day, and is routed through its own unit hydrograph
        let mut ice_runoff = 0.0;
        if GLACIER {
            self.ice_melt = 0.0;
            if self.ice_store > 0.0 && rep_temp > self.tmelt {
                let potential = (1.0 - snow_fraction) * self.ddfi * (rep_temp - self.tmelt);
                self.ice_melt = potential.max(0.0).min(self.ice_store);
                self.ice_store -= self.ice_melt;
            }
            let n = self.uh3.len();
            for i in 0..n - 1 {
                self.uh3[i] = self.uh3[i + 1] + self.uh3_ordinates[i] * self.ice_melt;
            }
            self.uh3[n - 1] = self.uh3_ordinates[n - 1] * self.ice_melt;
            ice_runoff = self.uh3[0];
        }

        //Rain and snow melt go through GR4J
        self.gr4j.run_step(rain + self.snow_melt, e) + ice_runoff
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn glacier_model() -> Gr4jsg {
        let mut m = Gr4jsg::new();
        m.glacier = true;
        m.initial_ice = 1000.0;
        m.initialize();
        m
    }

    #[test]
    fn warm_catchment_is_gr4j() {
        // Never cold enough to snow: the model must reproduce GR4J bit for bit.
        let mut snow = Gr4jsg::new();
        let mut plain = Gr4j::new();
        for day in 0..2000 {
            let p = if day % 7 == 0 { 25.0 } else if day % 3 == 0 { 4.0 } else { 0.0 };
            let e = 3.0 + (day % 11) as f64 * 0.3;
            assert_eq!(snow.run_step(p, e, 22.0, 9.0), plain.run_step(p, e));
        }
        assert_eq!(snow.snow_store, 0.0);
    }

    #[test]
    fn cold_precipitation_is_stored_as_snow() {
        let mut m = Gr4jsg::new();
        m.run_step(12.0, 1.0, -2.0, -8.0);
        assert_eq!(m.snowfall, 12.0);
        assert_eq!(m.snow_store, 12.0);
        assert_eq!(m.snow_melt, 0.0);
    }

    #[test]
    fn dry_day_melt_follows_quick_and_pipes() {
        let mut m = Gr4jsg::new();
        m.snow_store = 500.0;
        m.run_step(0.0, 1.0, 10.0, 2.0);
        // rep = 6; beta = 2/4.4; potential = 3 * (6 + beta * (8/8 + 2))
        let expected = 3.0 * (6.0 + (2.0 / 4.4) * 3.0);
        assert!((m.snow_melt - expected).abs() < 1e-12);
        assert!((m.snow_store - (500.0 - expected)).abs() < 1e-12);
    }

    #[test]
    fn rain_day_melt_follows_usace() {
        let mut m = Gr4jsg::new();
        m.snow_store = 500.0;
        m.run_step(10.0, 1.0, 10.0, 2.0);
        let expected = 6.0 * (3.38 + 0.0126 * 10.0) + 1.3;
        assert!((m.snow_melt - expected).abs() < 1e-12);
    }

    #[test]
    fn ice_melts_for_the_part_of_the_day_left_after_snow() {
        let mut m = glacier_model();
        m.snow_store = 9.0;
        m.run_step(0.0, 1.0, 10.0, 2.0);
        let potential_snow = 3.0 * (6.0 + (2.0 / 4.4) * 3.0);
        assert_eq!(m.snow_melt, 9.0);
        let expected_ice = (1.0 - 9.0 / potential_snow) * 6.0 * 6.0;
        assert!((m.ice_melt - expected_ice).abs() < 1e-12);
        assert!((m.ice_store - (1000.0 - expected_ice)).abs() < 1e-12);
    }

    #[test]
    fn default_return_flow_passes_ice_melt_straight_through() {
        let mut m = glacier_model();
        let mut plain = Gr4j::new();
        let runoff = m.run_step(0.0, 1.0, 10.0, 2.0);
        assert_eq!(m.ice_melt, 36.0);
        assert_eq!(runoff, plain.run_step(0.0, 1.0) + 36.0);
    }

    #[test]
    fn ice_melt_hydrograph_conserves_water() {
        let mut m = glacier_model();
        m.return_flow = 7.3;
        m.initialize();
        assert!((m.uh3_ordinates.iter().sum::<f64>() - 1.0).abs() < 1e-12);
        // One warm dry day, then cold: everything that melted must come out.
        let mut total = m.run_step(0.0, 0.0, 10.0, 2.0);
        let melted = m.ice_melt;
        for _ in 0..30 {
            total += m.run_step(0.0, 0.0, -5.0, -10.0);
        }
        assert!((total - melted).abs() < 1e-9);
    }

    #[test]
    fn accumulation_feeds_ice_while_there_is_a_glacier_and_snow_after() {
        let mut m = glacier_model();
        m.accumulation = 2.0;
        m.run_step(0.0, 1.0, -5.0, -10.0);
        assert_eq!(m.ice_store, 1002.0);
        assert_eq!(m.snow_store, 0.0);
        m.ice_store = 0.0;
        m.run_step(0.0, 1.0, -5.0, -10.0);
        assert_eq!(m.ice_store, 0.0);
        assert_eq!(m.snow_store, 2.0);
    }

    #[test]
    fn water_balance_closes() {
        let mut m = glacier_model();
        m.accumulation = 0.5;
        m.return_flow = 3.0;
        m.gr4j.x2 = 0.0; // no groundwater exchange, so the balance is closed
        m.initialize();
        let (mut p_total, mut q_total) = (0.0, 0.0);
        let days = 3000;
        for day in 0..days {
            let season = (day as f64 * std::f64::consts::TAU / 365.0).sin();
            let p = if day % 5 == 0 { 18.0 } else { 0.0 };
            p_total += p;
            // PET of zero: everything that goes in is stored or runs off.
            q_total += m.run_step(p, 0.0, 6.0 + 12.0 * season, -4.0 + 10.0 * season);
        }
        let stored = m.snow_store + m.ice_store + m.gr4j.production_store + m.gr4j.routing_store
            + m.uh3.iter().skip(1).sum::<f64>() + m.gr4j.uh_storage();
        let supplied = p_total + 1000.0 + 0.5 * days as f64;
        assert!((supplied - q_total - stored).abs() < 1e-6, "imbalance {}", supplied - q_total - stored);
    }

    #[test]
    fn validation_names_the_bad_parameter() {
        let mut m = Gr4jsg::new();
        assert!(m.validate_params().is_ok());
        m.tfrac = 1.2;
        assert!(m.validate_params().unwrap_err().contains("tfrac"));
        m.tfrac = 0.5;
        m.m_nonrainfall = -1.0;
        assert!(m.validate_params().unwrap_err().contains("m_nonrainfall"));
        m.m_nonrainfall = 3.0;
        // Glacier parameters are only checked when there is a glacier.
        m.return_flow = 0.0;
        assert!(m.validate_params().is_ok());
        m.glacier = true;
        assert!(m.validate_params().unwrap_err().contains("return_flow"));
    }
}
