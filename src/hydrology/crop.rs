//! Crops: the agronomy a field grows, declared once in a `[crop.*]` section
//! and referenced by name from any number of fields.
//!
//! A crop is a declaration - numbers and one table - with nothing evaluated:
//! the farmer's decisions (when to plant, how much, when to give up) are
//! expressions on the field's crop slots, and the crop supplies what is true
//! of the plant wherever it grows.

use crate::numerical::table::Table;

/// The crop coefficient over the season: a constant, or a piecewise-linear
/// curve by days since planting (FAO-56's Kc curve as its rows), the first
/// and last values carried beyond the ends.
#[derive(Clone)]
pub enum KcCurve {
    Constant(f64),
    ByDay(Table),
}

impl KcCurve {
    /// The coefficient `days` after planting: one table lookup per crop
    /// partition per step, a few nanoseconds (ADR-0004 data point of 2026-10-04).
    pub fn at(&self, days: f64) -> f64 {
        match self {
            KcCurve::Constant(kc) => *kc,
            KcCurve::ByDay(table) => {
                let last = table.nrows() - 1;
                if days <= table.get_value(0, 0) {
                    table.get_value(0, 1)
                } else if days >= table.get_value(last, 0) {
                    table.get_value(last, 1)
                } else {
                    table.interpolate(0, 1, days)
                }
            }
        }
    }

    /// The first value and the highest value of the curve: the span the roots
    /// grow over, as pyfao56 grows them with the coefficient
    pub fn first_and_peak(&self) -> (f64, f64) {
        match self {
            KcCurve::Constant(kc) => (*kc, *kc),
            KcCurve::ByDay(table) => {
                let first = table.get_value(0, 1);
                let peak = (0..table.nrows()).map(|r| table.get_value(r, 1)).fold(first, f64::max);
                (first, peak)
            }
        }
    }
}

#[derive(Clone)]
pub struct Crop {
    pub name: String,
    /// mm; the crop's bucket holds `available_water × root_depth / 1000` mm
    /// once the roots are fully down
    pub root_depth: f64,
    /// The curve is a basal coefficient Kcb (FAO-56 Chapter 7, `kcb = …`): the
    /// crop transpires by it and the soil surface evaporates by Ke from the
    /// field's evaporation layer. False: a single coefficient Kc (`kc = …`).
    pub dual: bool,
    /// mm, the roots at planting. None: the roots are at root_depth from the
    /// day of planting (Source Irrigator's fixed root zone). Some: they grow to
    /// root_depth in step with the kc curve's rise to its peak (FAO-56 Ch. 8,
    /// as pyfao56 does it).
    pub initial_root_depth: Option<f64>,
    /// Depletion fraction: stress begins beyond p of the bucket. FAO-56 Table 22's
    /// values are for a crop using about 5 mm/day
    pub p: f64,
    /// Adjust p for the day's evapotranspiration, as FAO-56 Table 22's footnote
    /// says to and pyfao56 does: p + 0.04 (5 − ETc), within 0.1 to 0.8. False
    /// uses p as written, as Source Irrigator does.
    pub p_adjust: bool,
    pub kc: KcCurve,
    /// Days from planting to harvest. None: a perennial, never harvested.
    pub season_len: Option<u32>,
}

impl Crop {
    /// How deep the roots reach `days` after planting: the initial depth plus
    /// the share of the way to full that the kc curve has risen from its first
    /// value towards its peak. A crop with no initial depth is at full depth
    /// from planting. The caller keeps roots from shrinking when kc falls.
    /// The depletion fraction for a day whose unstressed crop evapotranspiration
    /// is `etc_mm`: the tabled p adjusted by 0.04 per mm either side of 5 mm/day,
    /// held between 0.1 and 0.8 (FAO-56 Table 22, footnote 2). A p written
    /// outside that range is its own bound, so a modeller's extreme p stands.
    /// With p_adjust false, p as written.
    #[inline]
    pub fn p_for(&self, etc_mm: f64) -> f64 {
        if !self.p_adjust {
            return self.p;
        }
        (self.p + 0.2 - 0.04 * etc_mm).clamp(self.p.min(0.1), self.p.max(0.8))
    }

    pub fn root_depth_at(&self, days: f64) -> f64 {
        match self.initial_root_depth {
            None => self.root_depth,
            Some(initial) => {
                let (first, peak) = self.kc.first_and_peak();
                let risen = ((self.kc.at(days) - first) / (peak - first)).clamp(0.0, 1.0);
                initial + (self.root_depth - initial) * risen
            }
        }
    }

    /// Checks a crop makes sense. Errors name the property and the crop.
    pub fn validate(&self) -> Result<(), String> {
        if !(self.root_depth > 0.0 && self.root_depth.is_finite()) {
            return Err(format!("Crop '{}': root_depth must be a positive number of mm, got {}.", self.name, self.root_depth));
        }
        if let Some(initial) = self.initial_root_depth {
            if !(initial > 0.0 && initial < self.root_depth) {
                return Err(format!("Crop '{}': initial_root_depth must be a positive number of mm less than root_depth ({}), got {}.", self.name, self.root_depth, initial));
            }
            let (first, peak) = self.kc.first_and_peak();
            if !(peak > first) {
                return Err(format!("Crop '{}': initial_root_depth needs a kc table that rises from its first value, so the roots have a development stage to grow over.", self.name));
            }
        }
        if !(self.p >= 0.0 && self.p < 1.0) {
            return Err(format!("Crop '{}': p must be at least 0 and less than 1, got {}.", self.name, self.p));
        }
        match &self.kc {
            KcCurve::Constant(kc) => {
                if !(*kc >= 0.0 && kc.is_finite()) {
                    return Err(format!("Crop '{}': kc must be a non-negative number, got {}.", self.name, kc));
                }
            }
            KcCurve::ByDay(table) => {
                if table.ncols() != 2 || table.nrows() < 1 {
                    return Err(format!("Crop '{}': kc must be a number, or a table of two columns (days since planting, kc) with at least one row.", self.name));
                }
                for row in 0..table.nrows() {
                    let (day, kc) = (table.get_value(row, 0), table.get_value(row, 1));
                    if !(day >= 0.0 && day.is_finite()) {
                        return Err(format!("Crop '{}': kc table days must be non-negative, got {} in row {}.", self.name, day, row + 1));
                    }
                    if !(kc >= 0.0 && kc.is_finite()) {
                        return Err(format!("Crop '{}': kc table values must be non-negative, got {} in row {}.", self.name, kc, row + 1));
                    }
                    if row > 0 && day <= table.get_value(row - 1, 0) {
                        return Err(format!("Crop '{}': kc table days must increase down the table, row {} does not.", self.name, row + 1));
                    }
                }
            }
        }
        if let Some(0) = self.season_len {
            return Err(format!("Crop '{}': season_len must be at least 1 day, or omitted for a perennial.", self.name));
        }
        Ok(())
    }
}

/// The crops a model declares, looked up by name at load time.
#[derive(Clone, Default)]
pub struct CropRegistry {
    crops: Vec<Crop>,
}

impl CropRegistry {
    pub fn insert(&mut self, crop: Crop) -> Result<usize, String> {
        if self.crops.iter().any(|c| c.name == crop.name) {
            return Err(format!("Crop '{}' is declared twice.", crop.name));
        }
        self.crops.push(crop);
        Ok(self.crops.len() - 1)
    }

    pub fn get_idx(&self, name: &str) -> Option<usize> {
        self.crops.iter().position(|c| c.name.eq_ignore_ascii_case(name))
    }

    pub fn get(&self, idx: usize) -> &Crop {
        &self.crops[idx]
    }

    pub fn is_empty(&self) -> bool {
        self.crops.is_empty()
    }

    /// Crops by name, for a deterministic canonical render.
    pub fn iter_sorted(&self) -> Vec<&Crop> {
        let mut out: Vec<&Crop> = self.crops.iter().collect();
        out.sort_by(|a, b| a.name.cmp(&b.name));
        out
    }
}
