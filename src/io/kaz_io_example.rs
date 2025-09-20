/// Example demonstrating how to use the Kalix time series I/O functionality
/// This shows the basic usage patterns that match the Java implementation

use crate::timeseries::Timeseries;
use crate::io::kaz_io::{write_series, write_series_with_precision, read_series, read_all_series, get_series_info};
use crate::tid::utils::wrap_to_u64;

pub fn demonstrate_kaz_io() -> Result<(), Box<dyn std::error::Error>> {
    println!("=== Kalix Time Series I/O Demonstration ===");

    // Create some sample time series data
    let mut flow_rate = Timeseries::new_daily();
    flow_rate.name = "river_flow_rate".to_string();
    flow_rate.step_size = 86400; // Daily data (24 hours in seconds)

    // Add some data points (timestamps in wrapped format)
    let base_time = 1640995200u64; // 2022-01-01 00:00:00 UTC
    for i in 0..10 {
        let timestamp = wrap_to_u64((base_time + (i * 86400)) as i64); // Daily intervals
        let value = 100.0 + (i as f64) * 5.0 + (i as f64 * 0.5).sin() * 20.0; // Synthetic flow data
        flow_rate.push(timestamp, value);
    }

    let mut temperature = Timeseries::new_daily();
    temperature.name = "water_temperature".to_string();
    temperature.step_size = 86400;

    for i in 0..10 {
        let timestamp = wrap_to_u64((base_time + (i * 86400)) as i64);
        let value = 15.0 + (i as f64 * 0.3).sin() * 8.0; // Synthetic temperature data
        temperature.push(timestamp, value);
    }

    let base_path = "/tmp/demo_hydro_data";

    // Example 1: Write multiple series with default precision (32-bit float)
    println!("\n1. Writing time series data (32-bit float precision)...");
    write_series(base_path, &[&flow_rate, &temperature])?;
    println!("   Written to: {}.kaz and {}.kai", base_path, base_path);

    // Example 2: Read series information without loading data
    println!("\n2. Reading series metadata...");
    let series_info = get_series_info(base_path)?;
    for info in &series_info {
        println!("   {}", info);
    }

    // Example 3: Read a specific series by name
    println!("\n3. Reading specific series: '{}'", flow_rate.name);
    let loaded_flow = read_series(base_path, &flow_rate.name)?;
    println!("   Loaded {} points, first value: {:.2}", loaded_flow.values.len(), loaded_flow.values[0]);

    // Example 4: Read all series
    println!("\n4. Reading all series...");
    let all_series = read_all_series(base_path)?;
    println!("   Loaded {} series:", all_series.len());
    for series in &all_series {
        println!("     - '{}': {} points", series.name, series.values.len());
    }

    // Example 5: High precision writing (64-bit double)
    let high_precision_path = "/tmp/demo_hydro_data_hq";
    println!("\n5. Writing with high precision (64-bit double)...");
    write_series_with_precision(high_precision_path, &[&flow_rate], true)?;

    // Compare file sizes
    let float_size = std::fs::metadata(format!("{}.kaz", base_path))?.len();
    let double_size = std::fs::metadata(format!("{}.kaz", high_precision_path))?.len();
    println!("   Float file size: {} bytes", float_size);
    println!("   Double file size: {} bytes", double_size);
    println!("   Size difference: {:.1}%", ((double_size as f64 / float_size as f64) - 1.0) * 100.0);

    // Example 6: Verify data integrity
    println!("\n6. Verifying data integrity...");
    let reloaded_flow = read_series(base_path, &flow_rate.name)?;
    let mut max_error = 0.0f64;
    for (original, reloaded) in flow_rate.values.iter().zip(reloaded_flow.values.iter()) {
        let error = (original - reloaded).abs();
        if error > max_error {
            max_error = error;
        }
    }
    println!("   Maximum round-trip error: {:.2e}", max_error);

    // Clean up
    let _ = std::fs::remove_file(format!("{}.kaz", base_path));
    let _ = std::fs::remove_file(format!("{}.kai", base_path));
    let _ = std::fs::remove_file(format!("{}.kaz", high_precision_path));
    let _ = std::fs::remove_file(format!("{}.kai", high_precision_path));

    println!("\n=== Demonstration complete ===");
    Ok(())
}

/// Example of error handling with the Kalix time series I/O
pub fn demonstrate_error_handling() {
    println!("\n=== Error Handling Demonstration ===");

    // Try to read a non-existent file
    match read_series("/nonexistent/path", "some_series") {
        Ok(_) => println!("Unexpected success!"),
        Err(e) => println!("Expected error reading non-existent file: {}", e),
    }

    // Try to read a non-existent series from a real file
    let mut temp_series = Timeseries::new_daily();
    temp_series.name = "temp_data".to_string();
    temp_series.push(wrap_to_u64(1640995200), 1.0);

    let temp_path = "/tmp/temp_test";
    if write_series(temp_path, &[&temp_series]).is_ok() {
        match read_series(temp_path, "nonexistent_series") {
            Ok(_) => println!("Unexpected success!"),
            Err(e) => println!("Expected error reading non-existent series: {}", e),
        }

        // Clean up
        let _ = std::fs::remove_file(format!("{}.kaz", temp_path));
        let _ = std::fs::remove_file(format!("{}.kai", temp_path));
    }

    println!("=== Error handling complete ===");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_demonstration() {
        // Run the demonstration - this also serves as an integration test
        demonstrate_kaz_io().expect("Demonstration should complete successfully");
    }

    #[test]
    fn test_error_cases() {
        // This shouldn't panic
        demonstrate_error_handling();
    }
}