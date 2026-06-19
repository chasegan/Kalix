//! Phase 3: validate Kalix's GR4 core against airGR.
//!
//! Reference outputs are produced by `hydrogr` (a Python-callable airGR port
//! whose CI asserts agreement with airGR), driven by airGR's canonical sample
//! catchments with the production/routing stores pinned EMPTY to match Kalix's
//! zero-init. See `example_data/gr4h/generate_reference.py`.
//!
//! Two tests:
//!   * GR4H on the hourly catchment L0123003 — the new sub-daily formulation.
//!   * GR4J on the daily catchment L0123001 — the CONTROL: it confirms the base
//!     implementation already agrees with airGR, so any GR4H-only divergence
//!     would be attributable to the variant change, not a pre-existing offset.

use crate::hydrology::rainfall_runoff::gr4j::{Gr4j, Gr4Variant};
use crate::io::csv_io;

/// Agreement tolerance (mm of runoff depth per step). Both variants actually
/// agree with airGR far more tightly than this — observed maxima are ~5e-12 mm
/// (GR4H) and ~3e-7 mm (GR4J), i.e. floating-point operation-ordering noise
/// between two independent implementations, not algorithmic divergence. The
/// threshold is set well above that for cross-platform robustness while still
/// being ~6 orders of magnitude below typical step flows (~1-14 mm).
const TOL_MM: f64 = 1e-5;

/// Push the fixture's precip/PET through a freshly-initialised Gr4j core and
/// report the maximum absolute deviation from the reference runoff.
fn max_deviation_against_reference(fixture: &str, variant: Gr4Variant,
                                   x1: f64, x2: f64, x3: f64, x4: f64) -> (f64, usize) {
    let data = csv_io::read_ts(fixture).unwrap_or_else(|e| panic!("could not read {fixture}: {e}"));
    let precip = &data[0];
    let pet = &data[1];
    let expected = &data[2];

    let mut g = Gr4j::new();
    g.x1 = x1;
    g.x2 = x2;
    g.x3 = x3;
    g.x4 = x4;
    g.set_variant(variant); // sets the variant AND re-initialises (UH, divisor, empty stores)

    let n = precip.len();
    let mut max_abs = 0.0_f64;
    let mut worst_step = 0usize;
    for i in 0..n {
        let q = g.run_step(precip.values[i], pet.values[i]);
        let abs = (q - expected.values[i]).abs();
        if abs > max_abs {
            max_abs = abs;
            worst_step = i;
        }
    }
    (max_abs, worst_step)
}

#[test]
fn gr4h_matches_airgr_reference() {
    // airGR's published parameters for hourly catchment L0123003.
    let (max_abs, worst_step) = max_deviation_against_reference(
        "./src/tests/example_data/gr4h/gr4h_airgr_reference.csv",
        Gr4Variant::Gr4h,
        521.113, -2.918, 218.009, 4.124,
    );
    println!("GR4H vs airGR: max_abs_dev = {max_abs:.3e} mm (at step {worst_step})");
    assert!(max_abs < TOL_MM, "GR4H diverges from airGR reference: max_abs={max_abs:e} mm at step {worst_step}");
}

#[test]
fn gr4j_matches_airgr_reference_control() {
    // airGR's published parameters for daily catchment L0123001.
    let (max_abs, worst_step) = max_deviation_against_reference(
        "./src/tests/example_data/gr4h/gr4j_airgr_reference.csv",
        Gr4Variant::Gr4j,
        257.238, 1.012, 88.235, 2.208,
    );
    println!("GR4J vs airGR (control): max_abs_dev = {max_abs:.3e} mm (at step {worst_step})");
    assert!(max_abs < TOL_MM, "GR4J control diverges from airGR reference: max_abs={max_abs:e} mm at step {worst_step}");
}
