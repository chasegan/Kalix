// use crate::timeseries::Timeseries;
// use tsz::{StdDecoder, StdEncoder};
// use crate::tid::utils;
// 
// 
// // This format will have 2 files. Like IQQM and Pixie, the first file will be an index file 
// // with metadata, and the second file will be binary containing the data. The binary file 
// // will be able to be read in absence of the index file, but you'll ony know the field number
// // and not the name. 
// 
// 
// 
// // The index file will be a CSV file with the following columns:
// // - address in the binary file
// // - a string for the name of the timeseries
// // - a compact JSON string for whatever additional metadata you want
// 
// 
// // use serde_derive::Deserialize;
// // extern crate csv;
// 
// // #[derive(Deserialize)]
// // struct Record {
// //     timestamp: String,
// //     value: f64,
// // }
// 
// pub struct KszReadError;
// 
// pub fn read(filename: &str) -> Result<Timeseries, KszReadError> {
// 
// 
//     let mut reader = csv::Reader::from_path(filename)?;
//     let mut new_ts = Timeseries::new();
//     for record in reader.deserialize() {
//         let record: Record = record?;
//         new_ts.push(record.timestamp, record.value);
//     }
//     Ok(new_ts)
// }
