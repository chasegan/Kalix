use clap::{Parser, Subcommand};
use kalix::io::ini_model_io::IniModelIO;
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
        /// Path to the model file
        model_file: Option<String>,
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
        Commands::Sim { model_file } => {
            let model_filename = model_file.as_deref().unwrap_or(""); //use the positional parameter or empty string as fallback
            let output_filename = "output.csv"; //placeholder to be overwritten by optional named CLI input parameter.

            println!("Running simulation...");
            fn run_model(model_filename: &str, output_filename: &str) -> Result<(), String> {
                let ini_reader = IniModelIO::new();
                let mut m = ini_reader.read_model(model_filename)?;
                m.configure();
                m.run();
                m.write_outputs(output_filename)?;
                Ok(())
            }
            match run_model(model_filename, output_filename) {
                Ok(_) => println!("Done!"),
                Err(s) => {
                    println!("Error: {}", s);
                    // End program with nonzero code.
                }
            }
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
