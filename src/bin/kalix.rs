use clap::{CommandFactory, Parser, Subcommand};
use kalix::io::ini_model_io::IniModelIO;
use kalix::perf::benchmarks;
use kalix::misc::cli_helpers::describe_cli_api;
use kalix::misc::simulation_context::install_simulation_panic_hook;
use kalix::apis::stdio::handlers::run_stdio_session;
use std::fs;
use std::io::{self, Read, Write};
use std::thread;
use std::time::{Duration, Instant};


#[derive(Parser)]
#[command(name = "kalix")]
#[command(about = "A command line interface for the Kalix hydrological modeling system")]
#[command(version = env!("KALIX_VERSION"))]
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
    #[command(visible_alias = "sim")]
    Simulate {
        /// Path to the model file
        model_file: String,
        /// Path to the output file
        #[arg(short, long)]
        output_file: Option<String>,
        /// Mass balance
        #[arg(short, long)]
        mass_balance: Option<String>,
        /// Verify mass balance
        #[arg(short, long)]
        verify_mass_balance: Option<String>,
        /// Report execution time profile
        #[arg(short = 'p', long)]
        profile: bool,
    },
    /// Run parameter optimisation
    #[command(visible_alias = "opt", alias = "optimize")]
    Optimise {
        /// Path to the optimisation configuration file (.ini)
        config_file: String,
        /// Path to the model file (.ini). Overrides model_file in config if specified
        model_file: Option<String>,
        /// Path to save the optimised model file (.ini)
        #[arg(short = 's', long = "save-model")]
        save_model: Option<String>,
        /// Suppress terminal output and plotting
        #[arg(short = 'q', long = "quiet")]
        quiet: bool,
        /// Report frequency (plot updates every N evaluations)
        #[arg(short = 'r', long = "report-frequency", default_value = "20")]
        report_frequency: usize,
        /// Report execution time profile
        #[arg(short = 'p', long)]
        profile: bool,
    },
}

fn main() {
    install_simulation_panic_hook();
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
        Commands::Simulate { model_file, output_file,
            mass_balance, verify_mass_balance, profile } => {

            let total_start = Instant::now();

            // Load model from file
            let load_start = Instant::now();
            println!("Loading model file: {}", model_file);
            let mut m = match IniModelIO::new().read_model_file(model_file.as_str()) {
                Ok(model) => model,
                Err(s) => {
                    panic!("Error: {}", s); // TODO: handle error properly
                }
            };

            println!("Running simulation...");
            if let Err(e) =  m.configure() {
                panic!("Error: {}", e); // TODO: handle error properly
            }
            let load_time = load_start.elapsed();

            let sim_start = Instant::now();
            if let Err(e) = m.run() {
                eprintln!("Error: {}", e);
                std::process::exit(1);
            }
            let sim_time = sim_start.elapsed();

            // Output file
            let output_start = Instant::now();
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
            let output_time = output_start.elapsed();

            let total_time = total_start.elapsed();

            println!("Done!");

            if profile {
                let misc_time = total_time.saturating_sub(load_time + sim_time + output_time);
                println!("\n=== Execution Profile ===");
                println!("  Loading time:    {:>10.3} ms", load_time.as_secs_f64() * 1000.0);
                println!("  Simulation time: {:>10.3} ms", sim_time.as_secs_f64() * 1000.0);
                println!("  Output time:     {:>10.3} ms", output_time.as_secs_f64() * 1000.0);
                println!("  Misc:            {:>10.3} ms", misc_time.as_secs_f64() * 1000.0);
                println!("  ─────────────────────────────");
                println!("  Total time:      {:>10.3} ms", total_time.as_secs_f64() * 1000.0);
            }
        }
        Commands::Optimise { config_file, model_file, save_model, quiet, report_frequency, profile } => {
            use kalix::numerical::opt::{
                OptimisationConfig, OptimisationProblem,
                create_optimizer_with_callback, OptimizationProgress, Optimisable
            };
            use kalix::io::optimisation_config_io::load_observed_timeseries;
            use kalix::terminal_plot::optimisation_plot::OptimisationPlot;
            use std::sync::{Arc, Mutex};

            let total_start = Instant::now();
            let setup_start = Instant::now();

            // Load optimisation configuration
            println!("Loading optimisation configuration: {}", config_file);
            let config = match OptimisationConfig::from_file(&config_file) {
                Ok(cfg) => cfg,
                Err(e) => {
                    eprintln!("Error loading optimisation config: {}", e);
                    std::process::exit(1);
                }
            };

            if !quiet {
                println!("Objective function: {:?}", config.objective_function);
                println!("Algorithm: {}", config.algorithm.name());
                println!("Population size: {}", config.algorithm.population_size());
                println!("Termination evaluations: {}", config.termination_evaluations);
                println!("Number of parameters: {}", config.parameter_config.n_genes());
            }

            // Determine model file: CLI argument takes precedence over config file
            let model_file_path = match model_file {
                Some(ref path) => path,
                None => match &config.model_file {
                    Some(path) => path,
                    None => {
                        eprintln!("Error: model_file must be specified either as a CLI argument or in optimisation config");
                        std::process::exit(1);
                    }
                }
            };

            println!("Loading model: {}", model_file_path);
            let model = match IniModelIO::new().read_model_file(model_file_path) {
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

            if !quiet {
                println!("Observed data points: {}", observed_timeseries.timeseries.len());
            }

            // Create calibration problem with temporal alignment
            let problem = OptimisationProblem::single_comparison(
                model,
                config.parameter_config.clone(),
                observed_timeseries.timeseries,
                config.simulated_series.clone(),
            ).with_objective(config.objective_function.clone());

            println!("\n=== Starting Optimisation ===");
            println!("Algorithm: {}", config.algorithm.name());
            println!("Population size: {}", config.algorithm.population_size());
            println!("Termination evaluations: {}", config.termination_evaluations);
            println!("Parameters to optimise: {}", problem.config.n_genes());
            println!("Objective: {} (minimize)\n", config.objective_function.name());

            // Create optimisation plot
            let opt_plot = Arc::new(Mutex::new(
                OptimisationPlot::new("KALIX//OPTIMISER", config.termination_evaluations, 50, 12)
            ));

            // Create progress callback for terminal plot
            use std::sync::atomic::{AtomicUsize, Ordering};
            let report_freq = report_frequency;
            let plot_clone = Arc::clone(&opt_plot);
            let last_report_eval = Arc::new(AtomicUsize::new(0));
            let progress_callback = if !quiet {
                Some(Box::new(move |progress: &OptimizationProgress| {
                    // Report every N evaluations (since OptimizationProgress doesn't have generation)
                    let last = last_report_eval.load(Ordering::Relaxed);
                    let should_report = (progress.n_evaluations / report_freq) > (last / report_freq);
                    if should_report {
                        last_report_eval.store(progress.n_evaluations, Ordering::Relaxed);
                        let mut plot = plot_clone.lock().unwrap();
                        plot.update_from_progress(progress);
                        print!("{}", plot.render());
                        io::stdout().flush().unwrap();
                    }
                }) as Box<dyn Fn(&OptimizationProgress) + Send + Sync>)
            } else {
                None
            };

            // Create optimizer with progress callback configured
            let optimizer = match create_optimizer_with_callback(&config, progress_callback) {
                Ok(opt) => opt,
                Err(e) => {
                    eprintln!("Error creating optimizer: {}", e);
                    std::process::exit(1);
                }
            };
            let setup_time = setup_start.elapsed();

            // Run optimization (callback already configured in optimizer)
            let mut problem_mut = problem;  // Make mutable for optimisation
            let result = optimizer.optimize(&mut problem_mut, None);
            let opt_time = result.elapsed;

            // Render final plot
            if !quiet {
                let mut plot = opt_plot.lock().unwrap();
                plot.render_final(result.best_objective, result.n_evaluations, result.elapsed);
                print!("{}", plot.render());
                io::stdout().flush().unwrap();
            }

            // Report results
            println!("\n\n=== Optimisation Complete ===");
            println!("Status: {}", if result.success { "SUCCESS" } else { "FAILED" });
            println!("Message: {}", result.message);
            println!("Function evaluations: {}", result.n_evaluations);
            println!("Best objective value: {:.6}", result.best_objective);
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

            // Apply best parameters to model one final time to ensure it's in the optimal state
            if let Err(e) = problem_mut.set_params(&result.best_params) {
                eprintln!("Warning: Failed to apply final parameters: {}", e);
            }

            // Save optimised model to file if specified
            let output_start = Instant::now();
            if let Some(model_path) = save_model {
                let ini_io = IniModelIO::new();
                let model_string = ini_io.model_to_string(&problem_mut.model);
                match fs::write(&model_path, model_string) {
                    Ok(_) => println!("\nOptimized model written to: {}", model_path),
                    Err(e) => eprintln!("Error writing model: {}", e),
                }
            }

            // Write results to file if specified
            if let Some(output_path) = config.output_file {
                use std::fmt::Write as FmtWrite;
                let mut output = String::new();
                writeln!(&mut output, "=== Kalix Optimisation Results ===").unwrap();
                writeln!(&mut output, "Configuration file: {}", config_file).unwrap();
                writeln!(&mut output, "Model file: {}", model_file_path).unwrap();
                writeln!(&mut output, "Observed data: {}", config.observed_data_series).unwrap();
                writeln!(&mut output, "Simulated series: {}\n", config.simulated_series).unwrap();
                writeln!(&mut output, "Algorithm: {}", config.algorithm.name()).unwrap();
                writeln!(&mut output, "Objective function: {}", config.objective_function.name()).unwrap();
                writeln!(&mut output, "Population size: {}", config.algorithm.population_size()).unwrap();
                writeln!(&mut output, "Best objective value: {:.6}", result.best_objective).unwrap();
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
            let output_time = output_start.elapsed();

            let total_time = total_start.elapsed();

            println!("\nDone!");

            if profile {
                let misc_time = total_time.saturating_sub(setup_time + opt_time + output_time);
                println!("\n=== Execution Profile ===");
                println!("  Setup time:        {:>10.3} ms", setup_time.as_secs_f64() * 1000.0);
                println!("  Optimisation time: {:>10.3} ms", opt_time.as_secs_f64() * 1000.0);
                println!("  Output time:       {:>10.3} ms", output_time.as_secs_f64() * 1000.0);
                println!("  Misc:              {:>10.3} ms", misc_time.as_secs_f64() * 1000.0);
                println!("  ─────────────────────────────────");
                println!("  Total time:        {:>10.3} ms", total_time.as_secs_f64() * 1000.0);
            }
        }
        Commands::GetAPI => {
            let command = Cli::command();
            let api_description = describe_cli_api(&command);
            println!("{}", serde_json::to_string_pretty(&api_description).unwrap());
        }
    }
}

