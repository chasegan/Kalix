use crate::hydrology::rainfall_runoff::sacramento::Sacramento;
use crate::io::csv_io;



/// Create a Sac model and push vectors of precipitation and evaporation
/// through it. Print the runoff. Check results against Fors results.
#[test]
fn test_sacramento_model_1() {
    let pp = &csv_io::read_ts("./src/tests/example_data/fors/rain_infilled.csv").unwrap()[0];
    let ee = &csv_io::read_ts("./src/tests/example_data/fors/mpot_rolled.csv").unwrap()[0];
    let correct_answer = &csv_io::read_ts("./src/tests/example_data/fors/modelled_flow.csv").unwrap()[0];

    let area = 228.0;
    let mut s = Sacramento::new();
    s.set_params(0.0,45.0,60.0,0.01,
                 0.01,150.0,0.0,0.11,
                 1.5,0.0,0.2,0.01,
                 25.0,0.2,47.0,15.0,0.1);
    s.initialize_state_empty();

    let mut sum_abs_error = 0f64;
    let n = pp.len();
    // let mut my_answer = Timeseries::new();
    for i in 0..n {
        let p = pp.values[i];
        let e = ee.values[i];
        let flow = s.run_step(p, e) * area;
        // if i > n-10 {
        //     println!("{}", flow);
        // }
        // my_answer.push_value(flow);
        sum_abs_error += (flow - correct_answer.values[i]).abs();
    }
    //println!("Correct mean flow: {}", correct_answer.mean());
    // println!("Sum abs error: {}", sum_abs_error);
    // println!("Sum flow: {}", my_answer.sum());
    assert!(sum_abs_error < 0.0000001)
}

/// Builds a Sacramento model whose parameters neutralise everything downstream
/// of the evaporation phase: lzpk = lzsk = 0 gives pbase = 0 (no percolation),
/// uzk = 0 gives no interflow, and pctim = adimp = sarva = side = ssout = 0
/// remove the area adjustments. With no rain, the only state changes in a step
/// come from the evaporation logic and the deterministic UZ ratio transfer,
/// so expected values can be derived by hand.
fn make_evap_test_model() -> Sacramento {
    let mut s = Sacramento::new();
    s.set_params(
        0.0,   // adimp
        45.0,  // lzfpm
        60.0,  // lzfsm
        0.0,   // lzpk
        0.0,   // lzsk
        150.0, // lztwm
        0.0,   // pctim
        0.11,  // pfree
        1.5,   // rexp
        0.0,   // sarva
        0.0,   // side
        0.0,   // ssout
        40.0,  // uzfwm
        0.0,   // uzk
        5.0,   // uztwm
        15.0,  // zperc
        0.1,   // laguh
    );
    s.initialize_state_empty();
    s
}

/// The upper-zone free-water evaporation branch (fires when evaporative demand
/// exhausts tension water, i.e. evapt > uztwm) must take the *residual* demand
/// from free water: E2 = min(evapt - E1, uzfwc), where E1 is the tension-water
/// evaporation just computed this step (Fors Sacramento.cs line 458). Before the
/// 2026-07 fix this line subtracted the *previous step's* free-water evaporation
/// instead of E1.
///
/// Hand-derived trace for uztwm=5, uzfwm=40, state (uztwc=1, uzfwc=10), step
/// (p=0, e=8):
///   E1 = min(8 * 1/5, uztwc) = 1.0, uztwc -> 0
///   E2 = min(8 - 1, 10) = 7.0,      uzfwc -> 3
///   UZ ratio transfer (free fuller than tension): common ratio = 3/45,
///     uztwc -> 5 * 3/45 = 1/3, uzfwc -> 40 * 3/45 = 8/3
#[test]
fn test_uz_free_water_evap_takes_residual_demand() {
    let mut s = make_evap_test_model();
    // Poison the stale field: the result must not depend on it.
    s.set_uz_state_for_test(1.0, 10.0, 2.0);

    let runoff = s.run_step(0.0, 8.0);

    let (uztwc, uzfwc, evapuzfw) = s.get_uz_state_for_test();
    assert!((evapuzfw - 7.0).abs() < 1e-12, "E2 should be 7.0, got {}", evapuzfw);
    assert!((uztwc - 1.0 / 3.0).abs() < 1e-12, "uztwc should be 1/3, got {}", uztwc);
    assert!((uzfwc - 8.0 / 3.0).abs() < 1e-12, "uzfwc should be 8/3, got {}", uzfwc);
    assert!(runoff.abs() < 1e-12, "no runoff expected, got {}", runoff);
}

/// Two models in identical states must produce identical results for the same
/// step, regardless of what the previous step's free-water evaporation was.
/// This is the invariant the pre-fix code violated: it read the stale
/// `evapuzfw` field as if it were this step's tension-water evaporation.
#[test]
fn test_uz_free_water_evap_independent_of_history() {
    let mut a = make_evap_test_model();
    let mut b = make_evap_test_model();
    a.set_uz_state_for_test(1.0, 10.0, 0.0); // no prior free-water evap
    b.set_uz_state_for_test(1.0, 10.0, 5.0); // large prior free-water evap

    let runoff_a = a.run_step(0.0, 8.0);
    let runoff_b = b.run_step(0.0, 8.0);

    assert_eq!(a.get_uz_state_for_test(), b.get_uz_state_for_test(),
        "post-step state must not depend on the previous step's evapuzfw");
    assert_eq!(runoff_a, runoff_b);
}

/// Builds a Sacramento model with every area fraction switched on and the
/// given side loss, for testing the store outputs.
fn make_store_test_model(side: f64) -> Sacramento {
    let mut s = Sacramento::new();
    s.set_params(
        0.1,   // adimp
        45.0,  // lzfpm
        60.0,  // lzfsm
        0.01,  // lzpk
        0.05,  // lzsk
        150.0, // lztwm
        0.02,  // pctim
        0.11,  // pfree
        1.5,   // rexp
        0.0,   // sarva
        side,
        0.0,   // ssout
        25.0,  // uzfwm
        0.2,   // uzk
        47.0,  // uztwm
        15.0,  // zperc
        0.1,   // laguh
    );
    s.initialize_state_empty();
    s
}

/// Rain that fits within upper zone tension water is held there in full, and
/// reaches no other store.
#[test]
fn test_stores_after_rain_held_in_tension_water() {
    let mut s = make_store_test_model(0.2);
    assert_eq!(s.get_uztw() + s.get_uzfw() + s.get_lztw() + s.get_lzfp() + s.get_lzfs(), 0.0,
        "stores start empty");

    s.run_step(10.0, 0.0);

    assert!((s.get_uztw() - 10.0).abs() < 1e-12, "uztw should be 10, got {}", s.get_uztw());
    assert_eq!(s.get_uzfw(), 0.0);
    assert_eq!(s.get_lztw(), 0.0);
    assert_eq!(s.get_lzfp(), 0.0);
    assert_eq!(s.get_lzfs(), 0.0);
}

/// Deterministic rain: dry spells, light falls, and storms big enough to
/// fill the upper zone and generate surface runoff.
fn store_test_rain_at(i: usize) -> f64 {
    match i % 23 {
        0 => 85.0,
        1 => 40.0,
        5 | 6 => 12.0,
        11 => 3.0,
        17 => 150.0,
        _ => 0.0,
    }
}

/// The five stores are depths over the pervious area. With no evaporation and
/// no side loss, the only ways water leaves them are surface flow, interflow
/// and baseflow, which the model reports scaled by the pervious fraction, so
/// every step must satisfy
///   pervious fraction * (rain - change in the five stores) = flosf + floin + flobf.
#[test]
fn test_stores_close_pervious_water_balance_without_evap() {
    let mut s = make_store_test_model(0.0);
    let pervious_fraction = 1.0 - s.pctim - s.adimp;
    let total = |s: &Sacramento| s.get_uztw() + s.get_uzfw() + s.get_lztw() + s.get_lzfp() + s.get_lzfs();

    let mut previous = total(&s);
    let mut saw_surface_runoff = false;
    let mut saw_baseflow = false;
    for i in 0..2000 {
        let rain = store_test_rain_at(i);
        s.run_step(rain, 0.0);
        let current = total(&s);
        let generated = s.flosf + s.floin + s.flobf;
        let error = pervious_fraction * (rain - (current - previous)) - generated;
        assert!(error.abs() < 1e-9, "step {}: water balance error {}", i, error);
        saw_surface_runoff |= s.flosf > 0.0;
        saw_baseflow |= s.flobf > 0.0;
        previous = current;
    }
    assert!(saw_surface_runoff && saw_baseflow, "rain pattern should exercise surface runoff and baseflow");
}

/// With a side loss, lzfp and lzfs are the stores with the side component
/// divided out, as Fors records them. They fill to lzfpm and lzfsm, not to
/// (1 + side) times those. In this form the water lost to groundwater never
/// sits in a store: it leaves the balance when it percolates, so what leaves
/// the stores is at least what reaches the channel. The gap is the side loss,
/// which is zero on a step with no percolation into free water.
#[test]
fn test_stores_with_side_loss_stay_within_capacity() {
    let mut s = make_store_test_model(0.2);
    let pervious_fraction = 1.0 - s.pctim - s.adimp;
    let total = |s: &Sacramento| s.get_uztw() + s.get_uzfw() + s.get_lztw() + s.get_lzfp() + s.get_lzfs();

    let mut previous = total(&s);
    let mut max_lzfp = 0.0f64;
    let mut max_lzfs = 0.0f64;
    for i in 0..2000 {
        let rain = store_test_rain_at(i);
        s.run_step(rain, 0.0);
        let current = total(&s);
        let channel = s.flosf + s.floin + s.flobf / (1.0 + s.side);
        let side_loss = pervious_fraction * (rain - (current - previous)) - channel;
        assert!(side_loss > -1e-9, "step {}: more water reached the channel than left the stores, by {}", i, -side_loss);
        max_lzfp = max_lzfp.max(s.get_lzfp());
        max_lzfs = max_lzfs.max(s.get_lzfs());
        previous = current;
    }
    // The primary store fills under this rain, so its bound is tested where it
    // binds. The supplemental store drains too fast to fill.
    assert!(max_lzfp > 0.9 * s.lzfpm && max_lzfp <= s.lzfpm + 1e-9, "lzfp peaked at {} against lzfpm {}", max_lzfp, s.lzfpm);
    assert!(max_lzfs > 0.0 && max_lzfs <= s.lzfsm + 1e-9, "lzfs peaked at {} against lzfsm {}", max_lzfs, s.lzfsm);
}
