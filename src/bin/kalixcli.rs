//use statements from the binary need to reference the package name "gr4j_rust" rather than "crate".
//#[macro_use]
extern crate ini;


use kalix::hydrology::rainfall_runoff::gr4j::Gr4j;
use kalix::timeseries::Timeseries;
use kalix::io::csv_io::read_ts;

fn main() {
    // println!("something big is coming");
    // let mut model = gr4j
    test_gr4j_rex_performance();
    println!("Done!")
}

fn test_gr4j_rex_performance() {
    // Read the driving rainfall and evap
    let pp = read_ts("./src/tests/fors_gr4j_model/rex_rain.csv").expect("Error");
    let mut pp = pp[0].clone();
    let ee = read_ts("./src/tests/fors_gr4j_model/rex_mpot.csv").expect("Error");
    let mut ee = ee[0].clone();
    let n = ee.len();

    // Read the answer data (from Fors)
    let aa = read_ts("./src/tests/fors_gr4j_model/modelled_rex_creek_flow_(fors)_with_kalix_formatting.csv").expect("Error");
    let aa = aa[0].clone();
    assert_eq!(aa.len(),n);

    // Make and run lots of models
    let current = std::time::Instant::now();
    for i in 0..100 {

        print!("{i} ");

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
        for _i in 0..n {
            pp.next();
            ee.next();
            let p = pp.current_played_value * rainfall_factor;
            let e = ee.current_played_value;
            let q = g.run_step(p, e);
            let f = q * area;
            mm.push_value(f);
        }

        // Now compare the mean runoff
        let maf_model = mm.mean();
        let maf_answer = aa.mean();
        println!("{}, {}", maf_model, maf_answer);
    }
    let duration = current.elapsed();
    println!("Time elapsed is: {:?}", duration);
    // ------------ RUST ---------------
    // Time elapsed is: 475.0329ms
    // This was done on using a --release build on Willow (Ryzen 7)
    // NOTE: on my mac m1 it's a
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