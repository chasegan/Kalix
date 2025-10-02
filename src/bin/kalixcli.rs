use clap::{CommandFactory, Parser, Subcommand};
use kalix::io::ini_model_io::IniModelIO;
use kalix::perf::benchmarks;
use kalix::misc::cli_helpers::describe_cli_api;
use kalix::apis::stdio::handlers::run_stdio_session;
use std::io::{self, Read, Write};
use std::thread;
use std::time::Duration;





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
    NewSession {

    },
    /// Run performance tests
    Test {
        //#[clap(subcommand)]
        #[arg(long)]
        sim_duration_seconds: Option<i32>,
        //#[clap(subcommand)]
        #[arg(long)]
        new_session: Option<i32>,
    },
    /// Return API spec as JSON on STDOUT
    GetAPI,
    /// Run a simulation
    Sim {
        /// Path to the model file. If this argument is not used, Kalix will
        /// ask for the model via STDIO.
        model_file: Option<String>,
        /// Path to the output file
        #[arg(short, long)]
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
        Commands::NewSession { } => {
            if let Err(e) = run_stdio_session() {
                eprintln!("Session error: {}", e);
                std::process::exit(1);
            }
        }
        Commands::Test { sim_duration_seconds, new_session } => {
            if let Some(_) = new_session {
                println!("KALIX_SESSION_READY");

                let mut buffer=String::new();
                while buffer.trim().is_empty() {
                    io::stdin().read_to_string(&mut buffer).expect("Something went wrong reading stdin");
                    println!("Input was: {}", &buffer);
                }
                println!("KALIX_SESSION_ENDING");
            }
            match sim_duration_seconds {
                Some(sim_duration) => {
                    let total_steps = 100; // 100 steps for 0% to 100%
                    let step_duration = Duration::from_millis((sim_duration * 1000 / total_steps) as u64);
                    
                    println!("Running simulation for {} seconds...", sim_duration);
                    
                    for i in 0..=total_steps {
                        print!("\rProgress: {}%", i);
                        io::stdout().flush().unwrap(); // Force output to appear immediately
                        
                        if i < total_steps {
                            thread::sleep(step_duration);
                        }
                    }
                    
                    println!(); // New line after completion
                    println!("Simulation completed!");
                    thread::sleep(step_duration);
                },
                None => {
                    println!("Running performance tests...");
                    benchmarks::bench1();
                    println!("Performance tests completed!");
                }
            }
        }
        Commands::Sim { model_file, output_file } => {
            // Read model either from a file (if provided) or from STDIN
            let mut m = match model_file {
                Some(filename) => {
                    println!("Loading model file: {}", filename);
                    match IniModelIO::new().read_model_file(filename.as_str()) {
                        Ok(model) => model,
                        Err(s) => {
                            panic!("Error: {}", s); // TODO: handle error properly
                        }
                    }
                }
                None => {
                    println!("Waiting for model...");
                    use std::io::{self, Read};
                    let mut buffer = String::new();
                    io::stdin().read_to_string(&mut buffer).expect("Failed to read from STDIN");
                    println!("Reading model from STDIN ({} bytes)...", buffer.len());
                    match IniModelIO::new().read_model_string(buffer.as_str()) {
                        Ok(model) => model,
                        Err(e) => {
                            panic!("Error: {}", e); // TODO: handle error properly
                        }
                    }
                }
            };

            println!("Running simulation...");
            if let Err(e) =  m.configure() {
                panic!("Error: {}", e); // TODO: handle error properly
            }
            if let Err(e) = m.run() {
                panic!("Error: {}", e); // TODO: handle error properly
            }

            match output_file {
                Some(f) => {
                    match m.write_outputs(f.as_str()) {
                        Ok(_) => { }
                        Err(s) => eprintln!("{}", s)
                    }
                }
                None => eprintln!("No output filename specified!")
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

