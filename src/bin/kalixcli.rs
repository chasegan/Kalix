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
    /// Run parameter optimisation
    Optimise {
        /// Path to the optimisation configuration file (.ini)
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
        Commands::Optimise { config_file } => {
            use kalix::numerical::opt::{
                OptimisationConfig, AlgorithmParams, OptimisationProblem,
                DifferentialEvolution, DEConfig, DEProgress
            };
            use kalix::io::optimisation_config_io::load_observed_timeseries;
            use kalix::terminal_plot::*;
            use std::sync::{Arc, Mutex};

            // Load optimisation configuration
            println!("Loading optimisation configuration: {}", config_file);
            let config = match OptimisationConfig::from_file(&config_file) {
                Ok(cfg) => cfg,
                Err(e) => {
                    eprintln!("Error loading optimisation config: {}", e);
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
                    eprintln!("Error: model_file must be specified in optimisation config");
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
            let problem = OptimisationProblem::new(
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

            println!("\n=== Starting Optimisation ===");
            println!("Algorithm: Differential Evolution");
            println!("Population size: {}", population_size);
            println!("Termination evaluations: {}", config.termination_evaluations);
            println!("Parameters to optimize: {}", problem.config.n_genes());
            println!("Objective: {} (minimize)\n", config.objective_function.name());

            // Create shared state for tracking optimization progress
            let best_history: Arc<Mutex<Vec<(f64, f64)>>> = Arc::new(Mutex::new(Vec::new()));
            let first_render = Arc::new(Mutex::new(true));
            let term_evals = config.termination_evaluations;

            // Create DE optimizer with progress callback
            let report_freq = config.report_frequency;  // Capture value before moving
            let n_threads = config.n_threads;  // Capture value before moving
            let history_clone = Arc::clone(&best_history);
            let first_render_clone = Arc::clone(&first_render);

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
                            // Update best history
                            let mut history = history_clone.lock().unwrap();
                            history.push((progress.n_evaluations as f64, progress.best_objective));

                            // Create plot
                            let mut plot = TerminalPlot::builder()
                                .title("KALIX//OPTIMISER")
                                .x_label("evals")
                                .y_label("Objective Function")
                                .width(50)
                                .height(12)
                                .color_scheme(ColorScheme::electric_grid())
                                .build();

                            // Set progress bar
                            plot.set_progress(progress.n_evaluations, term_evals);

                            // Add best evolution line
                            if !history.is_empty() {
                                plot.add_line(Line {
                                    points: history.clone(),
                                    style: LineStyle::Dots,
                                    color: Some(Color::BrightMagenta),
                                });
                            }

                            // Add current population scatter points
                            if let Some(ref pop_objectives) = progress.population_objectives {
                                let scatter_points: Vec<ScatterPoint> = pop_objectives
                                    .iter()
                                    .map(|&obj| ScatterPoint {
                                        x: progress.n_evaluations as f64,
                                        y: obj,
                                        color: Some(Color::BrightYellow),
                                        symbol: '∘',
                                    })
                                    .collect();
                                plot.add_scatter_points(scatter_points);
                            }

                            // Add best marker
                            plot.add_marker(Marker {
                                x: progress.n_evaluations as f64,
                                y: progress.best_objective,
                                symbol: '★',
                                color: Some(Color::BrightGreen),
                                label: Some(format!("← BEST: {:.6}", progress.best_objective)),
                            });

                            // Add footer information
                            plot.add_footer_line(format!("Best: {:.6}", progress.best_objective));
                            plot.add_footer_line(format!("Time: {:.1}s", progress.elapsed.as_secs_f64()));

                            // Render plot (clear and redraw for animation, or first render)
                            let mut is_first = first_render_clone.lock().unwrap();
                            if *is_first {
                                print!("{}", plot.render());
                                *is_first = false;
                            } else {
                                print!("{}", plot.clear_and_render());
                            }
                            io::stdout().flush().unwrap();
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

            // Render final plot if verbose mode is enabled
            if config.verbose {
                let history = best_history.lock().unwrap();

                // Create final plot
                let mut plot = TerminalPlot::builder()
                    .title("KALIX//OPTIMISER")
                    .x_label("evals")
                    .y_label("Objective Function")
                    .width(50)
                    .height(12)
                    .color_scheme(ColorScheme::electric_grid())
                    .build();

                // Set progress bar to 100%
                plot.set_progress(result.n_evaluations, term_evals);

                // Add best evolution line with final point
                let mut final_history = history.clone();
                final_history.push((result.n_evaluations as f64, result.best_objective));
                plot.add_line(Line {
                    points: final_history,
                    style: LineStyle::Dots,
                    color: Some(Color::BrightMagenta),
                });

                // Add best marker at final position
                plot.add_marker(Marker {
                    x: result.n_evaluations as f64,
                    y: result.best_objective,
                    symbol: '★',
                    color: Some(Color::BrightGreen),
                    label: Some(format!("← BEST: {:.6}", result.best_objective)),
                });

                // Add footer information
                plot.add_footer_line(format!("Best: {:.6}", result.best_objective));
                plot.add_footer_line(format!("Time: {:.1}s", result.elapsed.as_secs_f64()));

                // Render final plot
                let is_first = *first_render.lock().unwrap();
                if is_first {
                    print!("{}", plot.render());
                } else {
                    print!("{}", plot.clear_and_render());
                }
                io::stdout().flush().unwrap();
            }

            // Report results
            println!("\n\n=== Optimisation Complete ===");
            println!("Status: {}", if result.success { "SUCCESS" } else { "FAILED" });
            println!("Message: {}", result.message);
            println!("Generations: {}", result.generations);
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

            // Write results to file if specified
            if let Some(output_path) = config.output_file {
                use std::fmt::Write as FmtWrite;
                let mut output = String::new();
                writeln!(&mut output, "=== Kalix Optimisation Results ===").unwrap();
                writeln!(&mut output, "Configuration file: {}", config_file).unwrap();
                writeln!(&mut output, "Model file: {}", model_file).unwrap();
                writeln!(&mut output, "Observed data: {}", config.observed_data_series).unwrap();
                writeln!(&mut output, "Simulated series: {}\n", config.simulated_series).unwrap();
                writeln!(&mut output, "Algorithm: {}", config.algorithm.name()).unwrap();
                writeln!(&mut output, "Objective function: {}", config.objective_function.name()).unwrap();
                writeln!(&mut output, "Population size: {}", population_size).unwrap();
                writeln!(&mut output, "Generations: {}\n", result.generations).unwrap();
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

            println!("\nDone!");
        }
        Commands::GetAPI => {
            let command = Cli::command();
            let api_description = describe_cli_api(&command);
            println!("{}", serde_json::to_string_pretty(&api_description).unwrap());
        }
    }
}

