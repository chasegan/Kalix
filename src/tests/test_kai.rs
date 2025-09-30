#[test]
pub fn demonstrate_kaz_io_2() -> Result<(), Box<dyn std::error::Error>> {
    use crate::io::kaz_io::{read_series, read_all_series, get_series_info};

    //let model_filename = "./src/tests/example_data/output_31d6f6b4.kai";
    let base_path = "./src/tests/example_data/output_31d6f6b4";

    // Example 2: Read series information without loading data
    println!("\n2. Reading series metadata...");
    let series_info = get_series_info(base_path)?;
    for info in &series_info {
        println!("   {}", info);
    }

    // Example 3: Read a specific series by name
    let series_name = "output_31d6f6b4.csv: Network>Node_249";
    println!("\n3. Reading specific series: '{series_name}'", );
    let ts = read_series(base_path, series_name)?;
    println!("   Loaded {} points, first value: {:.2}", ts.values.len(), ts.values[0]);

    // Example 4: Read all series
    println!("\n4. Reading all series...");
    let all_series = read_all_series(base_path)?;
    println!("   Loaded {} series:", all_series.len());
    for temp_ts in &all_series {
        println!("     - '{}': {} points", temp_ts.name, temp_ts.values.len());
    }

    Ok(())
}
