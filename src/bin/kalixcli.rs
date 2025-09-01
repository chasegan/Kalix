use clap::{Parser, Subcommand};
use kalix::perf::benchmarks;

#[derive(Parser)]
#[command(name = "kalixcli")]
#[command(about = "A command line interface for the Kalix hydrological modeling system")]
#[command(version = "0.1.0")]
struct Cli {
    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Run performance tests
    Test,
    /// Run a simulation
    Sim {
        /// Path to the configuration file
        #[arg(short, long)]
        config: Option<String>,
    },
    /// Run calibration
    Calibrate {
        /// Path to the configuration file
        #[arg(short, long)]
        config: Option<String>,
        /// Number of iterations
        #[arg(short, long, default_value = "1000")]
        iterations: u32,
    },
}

fn main() {
    let cli = Cli::parse();

    match cli.command {
        Commands::Test => {
            println!("Running performance tests...");
            benchmarks::bench1();
            println!("Performance tests completed!");
        }
        Commands::Sim { config } => {
            println!("Running simulation...");
            if let Some(config_path) = config {
                println!("Using configuration file: {}", config_path);
            } else {
                println!("No configuration file specified, using defaults");
            }
            // TODO: Implement simulation logic
            println!("Simulation placeholder - not yet implemented");
        }
        Commands::Calibrate { config, iterations } => {
            println!("Running calibration...");
            if let Some(config_path) = config {
                println!("Using configuration file: {}", config_path);
            } else {
                println!("No configuration file specified, using defaults");
            }
            println!("Running {} iterations", iterations);
            // TODO: Implement calibration logic
            println!("Calibration placeholder - not yet implemented");
        }
    }
}
