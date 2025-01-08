use crate::io::csv_io::{read_ts, write_ts};


#[test]
fn test_csv_reader_1() {
    let filename = "./src/tests/example_data/output_31d6f6b4.csv";
    let r = read_ts(filename).unwrap();
    r[0].print();
}


#[test]
fn test_csv_reader() {
    let filename = "./src/tests/example_data/output_31d6f6b4.csv";
    let r = read_ts(filename);
    match r {
        Ok(vec_of_ts) => {
            let n_series = vec_of_ts.len();
            assert_eq!(n_series, 249); //check that we're reading all the series
            assert_eq!(vec_of_ts[0].name, "Network>Node_001");
            assert_eq!(vec_of_ts[1].name, "Network>Node_002");
            assert_eq!(vec_of_ts[248].name, "Network>Node_249");
            assert_eq!(vec_of_ts[0].len(), 16985);
            assert_eq!(vec_of_ts[248].len(), 16985);
            let mut sum_all = 0.0;
            for ts in vec_of_ts {
                let sum_values: f64 = ts.values.iter().sum();
                sum_all += sum_values;
            }
            assert!((sum_all - 3512804675.4945393).abs() < 0.001);
        },
        Err(e) => panic!("Something went wrong: {:?}", e)
    }
}


#[test]
fn test_csv_writer() {
    let filename = "./src/tests/example_data/output_31d6f6b4.csv";
    let r = read_ts(filename);
    match r {
        Ok(vec_of_ts) => {
            write_ts("./src/tests/example_data/output_31d6f6b4_EXPORT.csv", vec_of_ts).expect("Something went wrong.");
        },
        Err(e) => panic!("Something went wrong: {:?}", e)
    }
    let r = read_ts("./src/tests/example_data/output_31d6f6b4_EXPORT.csv");
    match r {
        Ok(vec_of_ts) => {
            let n_series = vec_of_ts.len();
            assert_eq!(n_series, 249);
            assert_eq!(vec_of_ts[248].len(), 16985);
            assert_eq!(vec_of_ts[0].name, "Network>Node_001");
            assert_eq!(vec_of_ts[1].len(), 16985);
        },
        Err(e) => panic!("Something went wrong: {:?}", e)
    }
}