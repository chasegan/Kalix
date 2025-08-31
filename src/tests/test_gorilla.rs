use crate::io::csv_io::{read_ts};
use std::time::SystemTime;
use tsz::{DataPoint, Encode, StdEncoder};
use tsz::stream::BufferedWriter;


#[test]
fn test_gorilla() {

    //Read a big csv file full of data
    let start = SystemTime::now();
    let filename = "./src/tests/example_data/output_31d6f6b4.csv";
    let r = read_ts(filename);
    let vec_of_ts = match r {
        Ok(v) => v,
        Err(_) => panic!("Error reading the csv file. Try test_csv_io")
    };
    let end = SystemTime::now();
    let duration = end.duration_since(start).unwrap().as_millis();
    println!("File read in {duration} ms: {filename}");

    //Now compress each timeseries using gorilla
    let start = SystemTime::now();
    let mut total_len = 0;
    let mut min_compressed_size = 99999999999999usize;
    let mut max_compressed_size = 0usize;
    for t in vec_of_ts {
        let first_datetime = t.timestamps[0];
        let writer = BufferedWriter::new();
        let mut encoder = StdEncoder::new(first_datetime, writer);
        for i in 0..t.len() {
            let p = DataPoint::new(t.timestamps[i], t.values[i]);
            encoder.encode(p)
        }
        let bytes = encoder.close();
        let bytes_len = bytes.len();

        //println!("Compressed size = {bytes_len} bytes");
        min_compressed_size = min_compressed_size.min(bytes_len);
        max_compressed_size = max_compressed_size.max(bytes_len);

        total_len += bytes_len;
    }
    println!("Timeseries compressed sizes ranging {min_compressed_size} to {max_compressed_size} bytes");
    println!("Total compressed size = {total_len} bytes");
    let end = SystemTime::now();
    let duration = end.duration_since(start).unwrap().as_millis();
    println!("Data compressed in {duration} ms.");
}



#[test]
fn test_gorilla_drop_repeat_vals() {

    //Read a big csv file full of data
    let start = SystemTime::now();
    let filename = "./src/tests/example_data/output_31d6f6b4.csv";
    let r = read_ts(filename);
    let vec_of_ts = match r {
        Ok(v) => v,
        Err(_) => panic!("Error reading the csv file. Try test_csv_io")
    };
    let end = SystemTime::now();
    let duration = end.duration_since(start).unwrap().as_millis();
    println!("File read in {duration} ms: {filename}");

    //Now compress each timeseries using gorilla
    let start = SystemTime::now();
    let mut total_len = 0;
    for t in vec_of_ts {
        let first_datetime = t.timestamps[0];
        let writer = BufferedWriter::new();
        let mut encoder = StdEncoder::new(first_datetime, writer);
        let mut previous_value = f64::NAN;
        for i in 0..t.len() {
            if i == t.len() - 1 {
                //invalidate the previous value to force writing the last one
                previous_value = f64::NAN;
            }
            let v = t.values[i];
            if v == previous_value {
                //pass
            } else {
                let p = DataPoint::new(t.timestamps[i],  v);
                encoder.encode(p);
                previous_value = v;
            }
        }
        let bytes = encoder.close();
        let bytes_len = bytes.len();
        //println!("Compressed size = {bytes_len} bytes");

        total_len += bytes_len;
    }
    println!("Total compressed size = {total_len} bytes");
    let end = SystemTime::now();
    let duration = end.duration_since(start).unwrap().as_millis();
    println!("Data compressed in {duration} ms.");
}



#[test]
fn test_gorilla_with_dummy_dates() {

    //Read a big csv file full of data
    let start = SystemTime::now();
    let filename = "./src/tests/example_data/output_31d6f6b4.csv";
    let r = read_ts(filename);
    let vec_of_ts = match r {
        Ok(v) => v,
        Err(_) => panic!("Error reading the csv file. Try test_csv_io")
    };
    let end = SystemTime::now();
    let duration = end.duration_since(start).unwrap().as_millis();
    println!("File read in {duration} ms: {filename}");

    //Now compress each timeseries using gorilla
    let start = SystemTime::now();
    let mut total_len = 0;
    for t in vec_of_ts {
        let first_datetime = t.timestamps[0];
        let writer = BufferedWriter::new();
        let mut encoder = StdEncoder::new(first_datetime, writer);
        for i in 0..t.len() {
            let p = DataPoint::new(first_datetime, t.values[i]);
            encoder.encode(p)
        }
        let bytes = encoder.close();
        let bytes_len = bytes.len();
        //println!("Compressed size = {bytes_len} bytes");
        total_len += bytes_len;
    }
    println!("Total compressed size = {total_len} bytes");
    let end = SystemTime::now();
    let duration = end.duration_since(start).unwrap().as_millis();
    println!("Data compressed in {duration} ms.");
}


//TODO: need test for StdDecoder