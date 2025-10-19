use clap::{CommandFactory, Parser, Subcommand};
use kalix::io::ini_model_io::IniModelIO;
use kalix::perf::benchmarks;
use kalix::misc::cli_helpers::describe_cli_api;
use kalix::apis::stdio::handlers::run_stdio_session;
use std::fs;
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
        /// Mass balance
        #[arg(short, long)]
        mass_balance: Option<String>,
        /// Verify mass balance
        #[arg(short, long)]
        verify_mass_balance: Option<String>,
    },
    /// Run calibration
    Calibrate {
        /// Path to the calibration configuration file (.ini)
        config_file: String,
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
        Commands::Sim { model_file, output_file,
            mass_balance, verify_mass_balance} => {

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

            // Output file
            match output_file {
                Some(f) => {
                    match m.write_outputs(f.as_str()) {
                        Ok(_) => { }
                        Err(s) => eprintln!("{}", s)
                    }
                }
                None => {} // TODO: do we want to look at defaulting to some output here?
            }

            // Mass balance reporting and verification
            let mut mb_report = String::new();
            match mass_balance {
                Some(f) => {
                    mb_report = m.generate_mass_balance_report();
                    match fs::write(f, &mb_report) {
                        Ok(_) => {}
                        Err(s) => eprintln!("Error: {}", s)
                    }
                }
                None => {}
            }
            match verify_mass_balance {
                Some(f) => {
                    match fs::read_to_string(f) {
                        Ok(mb_verification) => {

                            // Generate the mass balance report for the current model if we haven't already.
                            if mb_report.is_empty() {
                                mb_report = m.generate_mass_balance_report();
                            }

                            // Check that they are identical (nothing fancy for now)
                            let red = "\x1b[31m";
                            let green = "\x1b[32m";
                            let reset = "\x1b[0m";
                            if mb_report.trim() == mb_verification.trim() {
                                println!("Mass balance verification: {green}VERIFIED!{reset}");
                            } else {
                                eprintln!("Mass balance verification: {red}FAILED!{reset}")
                            }
                        }
                        Err(s) => eprintln!("Error: {}", s)
                    }
                }
                None => {}
            }

            println!("Done!");
        }
        Commands::Calibrate { config_file } => {
            use kalix::numerical::opt::{
                CalibrationConfig, AlgorithmParams, CalibrationProblem,
                DifferentialEvolution, DEConfig, DEProgress
            };
            use kalix::io::calibration_config_io::load_observed_timeseries;

            // Load calibration configuration
            println!("Loading calibration configuration: {}", config_file);
            let config = match CalibrationConfig::from_file(&config_file) {
                Ok(cfg) => cfg,
                Err(e) => {
                    eprintln!("Error loading calibration config: {}", e);
                    std::process::exit(1);
                }
            };

            if config.verbose {
                println!("Objective function: {:?}", config.objective_function);
                println!("Algorithm: {}", config.algorithm.name());
                println!("Population size: {}", config.algorithm.population_size());
                println!("Termination evaluations: {}", config.termination_evaluations);
                println!("Number of parameters: {}", config.parameter_config.n_genes());
            }

            // Load model
            let model_file = match &config.model_file {
                Some(path) => path,
                None => {
                    eprintln!("Error: model_file must be specified in calibration config");
                    std::process::exit(1);
                }
            };

            println!("Loading model: {}", model_file);
            let model = match IniModelIO::new().read_model_file(model_file) {
                Ok(m) => m,
                Err(e) => {
                    eprintln!("Error loading model: {}", e);
                    std::process::exit(1);
                }
            };

            // Load observed data
            println!("Loading observed data: {}", config.observed_data_series);
            let observed_timeseries = match load_observed_timeseries(&config.observed_data_series) {
                Ok(ts) => ts,
                Err(e) => {
                    eprintln!("Error loading observed data: {}", e);
                    std::process::exit(1);
                }
            };

            let observed_data = observed_timeseries.timeseries.values.clone();
            if config.verbose {
                println!("Observed data points: {}", observed_data.len());
            }

            // Create calibration problem
            let problem = CalibrationProblem::new(
                model,
                config.parameter_config.clone(),
                observed_data,
                config.simulated_series.clone(),
            ).with_objective(config.objective_function);

            // Setup optimizer based on algorithm type
            let (population_size, de_f, de_cr) = match &config.algorithm {
                AlgorithmParams::DE { population_size, f, cr } => (*population_size, *f, *cr),
                _ => {
                    eprintln!("Error: Only 'DE' algorithm is currently supported");
                    eprintln!("Requested algorithm: {}", config.algorithm.name());
                    std::process::exit(1);
                }
            };

            println!("\n=== Starting Calibration ===");
            println!("Algorithm: Differential Evolution");
            println!("Population size: {}", population_size);
            println!("Termination evaluations: {}", config.termination_evaluations);
            println!("Parameters to optimize: {}", problem.config.n_genes());
            println!("Objective: {} (minimize)\n", config.objective_function.name());

            // Create DE optimizer with progress callback
            let report_freq = config.report_frequency;  // Capture value before moving
            let n_threads = config.n_threads;  // Capture value before moving
            let de_config = DEConfig {
                population_size,
                termination_evaluations: config.termination_evaluations,
                f: de_f,
                cr: de_cr,
                seed: config.random_seed,
                n_threads,
                progress_callback: if config.verbose {
                    Some(Box::new(move |progress: &DEProgress| {
                        if progress.generation % report_freq == 0 {
                            let elapsed_secs = progress.elapsed.as_secs_f64();
                            println!(
                                "Generation {:4}: fitness = {:.6} | evaluations = {:6} | time = {:.1}s",
                                progress.generation,
                                progress.best_fitness,
                                progress.n_evaluations,
                                elapsed_secs
                            );
                        }
                    }))
                } else {
                    None
                },
            };

            let optimizer = DifferentialEvolution::new(de_config);

            // Run optimization
            let mut problem_mut = problem;  // Make mutable for optimization
            let result = optimizer.optimize(&mut problem_mut);

            // Report results
            println!("\n=== Calibration Complete ===");
            println!("Status: {}", if result.success { "SUCCESS" } else { "FAILED" });
            println!("Message: {}", result.message);
            println!("Generations: {}", result.generations);
            println!("Function evaluations: {}", result.n_evaluations);
            println!("Best objective value: {:.6}", result.best_fitness);
            println!("\nOptimized Parameters (normalized [0,1]):");
            for (i, val) in result.best_params.iter().enumerate() {
                println!("  g({}) = {:.6}", i + 1, val);
            }

            // Evaluate parameters to get physical values (compute once, use twice)
            let param_values = problem_mut.config.evaluate(&result.best_params);
            println!("\nOptimized Parameters (physical values):");
            for (target, value) in &param_values {
                println!("  {} = {:.6}", target, value);
            }

            // Write results to file if specified
            if let Some(output_path) = config.output_file {
                use std::fmt::Write as FmtWrite;
                let mut output = String::new();
                writeln!(&mut output, "=== Kalix Calibration Results ===").unwrap();
                writeln!(&mut output, "Configuration file: {}", config_file).unwrap();
                writeln!(&mut output, "Model file: {}", model_file).unwrap();
                writeln!(&mut output, "Observed data: {}", config.observed_data_series).unwrap();
                writeln!(&mut output, "Simulated series: {}\n", config.simulated_series).unwrap();
                writeln!(&mut output, "Algorithm: {}", config.algorithm.name()).unwrap();
                writeln!(&mut output, "Objective function: {}", config.objective_function.name()).unwrap();
                writeln!(&mut output, "Population size: {}", population_size).unwrap();
                writeln!(&mut output, "Generations: {}\n", result.generations).unwrap();
                writeln!(&mut output, "Best objective value: {:.6}", result.best_fitness).unwrap();
                writeln!(&mut output, "Function evaluations: {}\n", result.n_evaluations).unwrap();
                writeln!(&mut output, "Optimized Parameters:").unwrap();
                for (target, value) in &param_values {
                    writeln!(&mut output, "  {} = {:.6}", target, value).unwrap();
                }

                match fs::write(&output_path, output) {
                    Ok(_) => println!("\nResults written to: {}", output_path),
                    Err(e) => eprintln!("Error writing results: {}", e),
                }
            }

            println!("\nDone!");
        }
        Commands::GetAPI => {
            let command = Cli::command();
            let api_description = describe_cli_api(&command);
            println!("{}", serde_json::to_string_pretty(&api_description).unwrap());
        }
    }
}

