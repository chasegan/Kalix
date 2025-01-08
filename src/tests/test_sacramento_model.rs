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