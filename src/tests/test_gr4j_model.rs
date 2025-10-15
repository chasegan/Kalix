use crate::hydrology::rainfall_runoff::gr4j::Gr4j;
use crate::hydrology::routing::unit_hydrograph::uh_dyn::UHDyn;
use crate::timeseries::Timeseries;


/// Create a GR4J model and push vectors of precipitation and evaporation
/// through it. Print the runoff.
#[test]
fn test_gr4j() {
    let pp = [20.0, 20.0, 20.0, 20.0, 0.0, 0.0, 5.0, 2.0, 0.0, 50.0, 0.0, 0.0, 0.0, 18.0, 0.0];
    let ee = [5.0, 5.0, 5.0, 5.0, 5.0, 4.0, 4.0, 4.0, 4.0, 4.0, 5.0, 5.0, 5.0, 5.0, 5.0];
    let mut g = Gr4j::new();
    for i in 0..15 {
        let p = pp[i];
        let e = ee[i];
        let q = g.run_step(p, e);
        println!("results => {p}, {e}, {q}");
    }
}


/// Create a GR4J and push timeseries of rainfall and evaporation through it.
/// Read a timeseries of Fors results and check that the answers are the same.
#[test]
fn test_gr4j_rex_creek() {
    // Read the driving rainfall and evap
    let pp = crate::io::csv_io::read_ts("./src/tests/fors_gr4j_model/rex_rain.csv").expect("Error");
    let mut pp = pp[0].clone();
    let ee = crate::io::csv_io::read_ts("./src/tests/fors_gr4j_model/rex_mpot.csv").expect("Error");
    let mut ee = ee[0].clone();
    let n = ee.len();

    // Run the model
    let mut mm = Timeseries::new_daily(); // for the modelled flows
    let mut g = Gr4j::new();
    g.x1 = 1999.99999999996;
    g.x2 = 5.99999999999991;
    g.x3 = 65.2245666006408;
    g.x4 = 0.380800595584489;
    g.initialize();
    let area = 22.8; // km2
    let rainfall_factor = 1.72036997687526;
    for i in 0..n {
        pp.next();
        ee.next();
        let p = pp.current_played_value * rainfall_factor;
        let e = ee.current_played_value;
        let q = g.run_step(p, e);
        let f = q * area;
        mm.push_value(f);
        if i < 100 {
            println!("{}: {}", i, f);
        }
    }

    // Read the answer data (from Fors)
    let aa = crate::io::csv_io::read_ts("./src/tests/fors_gr4j_model/modelled_rex_creek_flow_(fors)_with_kalix_formatting.csv").expect("Error");
    let aa = &aa[0];
    assert_eq!(aa.len(),n);

    // Now compare the mean runoff
    let maf_model = mm.mean();
    let maf_answer = aa.mean();
    assert!((maf_answer - maf_model).abs()/maf_model < 1e-15);
}


/// Create a GR4J model and drive it with some timeseries data from CSV and
/// benchmark the performance.
///
/// NOTE: The benchmarking doesn't really work from debug/test because you
/// want to be able to benchmark a release build.
#[test]
fn test_gr4j_rex_performance() {
    // Read the driving rainfall and evap

    let pp = crate::io::csv_io::read_ts("./src/tests/fors_gr4j_model/rex_rain.csv").expect("Error");
    let mut pp = pp[0].clone();
    let ee = crate::io::csv_io::read_ts("./src/tests/fors_gr4j_model/rex_mpot.csv").expect("Error");
    let mut ee = ee[0].clone();
    let n = ee.len();

    // Read the answer data (from Fors)
    let aa = crate::io::csv_io::read_ts("./src/tests/fors_gr4j_model/modelled_rex_creek_flow_(fors)_with_kalix_formatting.csv").expect("Error");
    let aa = aa[0].clone();
    assert_eq!(aa.len(),n);

    // Make and run lots of models
    let current = std::time::Instant::now();
    for _ in 0..100 {

        // Run the model
        let mut mm = Timeseries::new_daily(); // for the modelled flows
        let mut g = Gr4j::new();
        g.x1 = 1999.99999999996;
        g.x2 = 5.99999999999991;
        g.x3 = 65.2245666006408;
        g.x4 = 0.380800595584489;
        g.initialize();
        let area = 22.8; // km2
        let rainfall_factor = 1.72036997687526;
        for _ in 0..n {
            pp.next();
            ee.next();
            let p = pp.current_played_value * rainfall_factor;
            let e = ee.current_played_value;
            let q = g.run_step(p, e);
            let f = q * area;
            mm.push_value(f);
        }

        // // Now compare the mean runoff
        // let maf_model = mm.mean();
        // let maf_answer = aa.mean();
    }
    let duration = current.elapsed();
    println!("Time elapsed is: {:?}", duration);
    // ------------ RUST ---------------
    // Time elapsed is: 475.0329ms
    // This was done on using a --release build on Willow (Ryzen 7). I haven't figured out how
    // to run tests with --release, so I've been copying the test into main.rs and running the
    // whole program.
    // NOTE the Mac M1 results are very similar:
    //    Time elapsed is: 492.3725ms
    // ------------ FORS ---------------
    // 2023-10-20T15:01:57.8884585+10:00 - Starting model runs.
    // 2023-10-20T15:01:59.0068520+10:00 - Grid run finished.
    // Rumtime = 1120 ms
    // The Fors benchmark was a single threaded 100-point grid run with objective function
    // evaluation (just bias as per above test). This was done on the same machine (Willow,
    // Ryzen 7).
    // ------------ CONCLUSION ---------
    // Rust is faster, but not much faster.
}


/*
Create a unit hydrograph.
 */
#[test]
fn test_create_unit_hydrograph() {
    println!("========== STARTING TEST ==========");
    let _uh = UHDyn::new(5);
    assert_eq!(30, 30);
}


/*
Create a unit hydrograph with 8 parts and drive it with inflows.
 */
#[test]
fn test_run_unit_hydrograph() {
    println!("========== STARTING TEST ==========");

    let mut t = Timeseries::new_daily();
    t.name = "My timeseries".to_string();
    for i in 0..96 {
        let mut v = 0.0;
        if i % 10 < 5 {
            v = 100.0;
        }
        t.push_value(v);
    }
    t.print();

    let mut uh = UHDyn::new(8);
    uh.set_kernel(0, 0.1);
    uh.set_kernel(1, 0.6);
    uh.set_kernel(2, 0.15);
    uh.set_kernel(3, 0.08);
    uh.set_kernel(4, 0.03);
    uh.set_kernel(5, 0.02);
    uh.set_kernel(6, 0.015);
    uh.set_kernel(7, 0.005);

    let current = std::time::Instant::now();
    let result = uh.run(t);
    let duration = current.elapsed();
    println!("Time elapsed is: {:?}", duration);

    result.print();
    assert_eq!(30, 30);
}