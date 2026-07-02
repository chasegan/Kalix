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
