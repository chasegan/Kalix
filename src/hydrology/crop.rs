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
}

#[derive(Clone)]
pub struct Crop {
    pub name: String,
    /// mm; the crop's bucket holds `available_water × root_depth / 1000` mm
    pub root_depth: f64,
    /// Depletion fraction: stress begins beyond p of the bucket
    pub p: f64,
    pub kc: KcCurve,
    /// Days from planting to harvest. None: a perennial, never harvested.
    pub season_len: Option<u32>,
}

impl Crop {
    /// Checks a crop makes sense. Errors name the property and the crop.
    pub fn validate(&self) -> Result<(), String> {
        if !(self.root_depth > 0.0 && self.root_depth.is_finite()) {
            return Err(format!("Crop '{}': root_depth must be a positive number of mm, got {}.", self.name, self.root_depth));
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
