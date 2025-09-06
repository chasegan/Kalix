use clap::{CommandFactory, Parser, Subcommand};
use kalix::io::ini_model_io::IniModelIO;
use kalix::perf::benchmarks;
use kalix::misc::cli_helpers::describe_cli_api;
use kalix::model::Model;

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
    /// Return API spec as JSON on STDOUT
    GetAPI,
    /// Run a simulation
    Sim {
        /// Path to the model file
        model_file: Option<String>,
        /// Raw JSON string argument
        #[arg(long)]
        raw_json: Option<String>,
        /// Path to the output file
        #[arg(long)]
        output_file: Option<String>,
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
        Commands::Sim { model_file,
            raw_json,
            output_file } => {

            let mut m = match raw_json {
                Some(raw_json) => {
                    println!("json:{}", raw_json);
                    let model = IniModelIO::new().read_model_string(raw_json.as_str()).unwrap(); //TODO: handle error
                    model
                }
                None => {
                    let model_filename = model_file.as_deref().unwrap(); //TODO: handle error
                    let model = IniModelIO::new().read_model_file(model_filename).unwrap(); //TODO: handle error
                    model
                }
            };
            println!("Running simulation...");
            m.configure();
            m.run();

            match output_file {
                Some(f) => {
                    m.write_outputs(f.as_str());  //TODO: handle error
                }
                None => {
                    println!("No output filename specified!");
                }
            }
            println!("Done!");
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
        Commands::GetAPI => {
            let command = Cli::command();
            let api_description = describe_cli_api(&command);
            println!("{}", serde_json::to_string_pretty(&api_description).unwrap());
        }
    }
}

