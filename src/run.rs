//! High-level run-style entry points used by the CLI and the Python bindings.
//!
//! These wrap the `Model` lifecycle (load → configure → run → optional writes)
//! so the same code path is exercised regardless of caller. CLI-only concerns
//! (profiling, mass-balance verification) stay in `bin/kalix.rs`.

use crate::io::ini_model_io::IniModelIO;

/// Outcome of a non-interactive optimisation run.
///
/// Mirrors the information the CLI prints to the terminal, packaged for
/// programmatic callers (e.g. the Python bindings). Holds no model state —
/// `optimised_model_ini` is the tuned model serialised back to an INI string,
/// so callers can re-load it without a stateful handle.
pub struct OptimisationOutcome {
    /// Best objective value found (lower is better).
    pub best_objective: f64,
    /// Total number of function evaluations performed.
    pub n_evaluations: usize,
    /// Whether the optimiser terminated successfully.
    pub success: bool,
    /// Termination message from the optimiser.
    pub message: String,
    /// Optimised parameters as (target, physical value) pairs, in config order.
    /// Targets look like `node.<name>.<param>` or `c.<constant>`.
    pub parameters: Vec<(String, f64)>,
    /// The optimised model serialised to an INI string (lossless round-trip).
    pub optimised_model_ini: String,
}

/// Load a model from an INI file, run it, and write optional outputs.
///
/// Both output paths are optional — callers may pass `None` to skip a given
/// output (useful e.g. for benchmarking the run on its own). Returns `Err`
/// on any failure in load / configure / run / write.
pub fn simulate_from_file(
    model_path: &str,
    output_path: Option<&str>,
    mass_balance_path: Option<&str>,
) -> Result<(), String> {
    let mut m = IniModelIO::new().read_model_file(model_path)?;
    m.configure()?;
    m.run()?;
    if let Some(p) = output_path {
        m.write_outputs(p)?;
    }
    if let Some(p) = mass_balance_path {
        let report = m.generate_mass_balance_report();
        std::fs::write(p, report).map_err(|e| e.to_string())?;
    }
    Ok(())
}

/// Load an optimisation config, run the calibration, and return the outcome.
///
/// The non-interactive core of the `kalix optimise` CLI subcommand: the same
/// load → build problem → optimise → apply-best path, minus the terminal plot
/// and profiling. Used by the Python bindings (and available to any in-process
/// caller).
///
/// Paths inside the config (`model_file`, each term's `observed_file`) are
/// resolved relative to the current working directory, exactly as the CLI does.
///
/// # Arguments
/// * `config_path` - Path to the optimisation config `.ini`.
/// * `model_path` - Optional model `.ini` path; overrides `model_file` in the
///   config when given (mirrors the CLI's positional `[model_file]`).
/// * `save_model_path` - Optional path to write the optimised model `.ini`
///   (mirrors the CLI's `-s/--save-model`).
/// * `progress_callback` - Optional callback invoked once per generation with
///   the current [`OptimizationProgress`]. Pass `None` for a silent run.
///
/// If the config specifies an `output_file`, a results summary is written there
/// too — same as the CLI.
pub fn optimise_from_file(
    config_path: &str,
    model_path: Option<&str>,
    save_model_path: Option<&str>,
    progress_callback: Option<Box<dyn Fn(&crate::numerical::opt::optimizer_trait::OptimizationProgress) + Send + Sync>>,
) -> Result<OptimisationOutcome, String> {
    use crate::numerical::opt::{OptimisationConfig, OptimisationProblem, Optimisable, create_optimizer_with_callback};
    use crate::numerical::opt::optimisation::ComparisonPair;
    use crate::io::optimisation_config_io::load_observed_for_term;
    use crate::functions::parse_function;

    // Load optimisation configuration.
    let config = OptimisationConfig::from_file(config_path)?;

    // Resolve the model file: explicit override wins, else the config's value.
    let model_file_path = match model_path {
        Some(p) => p,
        None => config.model_file.as_deref().ok_or_else(|| {
            "model_file must be specified either as an argument or in the optimisation config"
                .to_string()
        })?,
    };

    let model = IniModelIO::new().read_model_file(model_file_path)?;

    // Build comparison pairs from terms (load each observed series).
    let mut comparisons: Vec<ComparisonPair> = Vec::with_capacity(config.terms.len());
    for term in &config.terms {
        let observed = load_observed_for_term(&term.observed_file, &term.observed_series)
            .map_err(|e| format!("Failed to load observed data for term '{}': {}", term.name, e))?;
        comparisons.push(ComparisonPair {
            name: term.name.clone(),
            observed: observed.timeseries,
            simulated_series_name: term.simulated_series.clone(),
            statistic: term.statistic.clone(),
        });
    }

    // Parse the composite objective expression.
    let expression = parse_function(&config.objective_expression).map_err(|e| {
        format!("Failed to parse objective_expression '{}': {}", config.objective_expression, e)
    })?;

    let mut problem = OptimisationProblem::new(
        model,
        config.parameter_config.clone(),
        comparisons,
        expression,
    );

    // Run the optimisation, wiring up the caller's progress callback (if any).
    let optimiser = create_optimizer_with_callback(&config, progress_callback)
        .map_err(|e| e.to_string())?;
    let result = optimiser.optimize(&mut problem, None);

    // Physical parameter values for the best genes.
    let parameters = problem.config.evaluate(&result.best_params);

    // Apply best parameters so the model is in its optimal state before saving.
    problem.set_params(&result.best_params)
        .map_err(|e| format!("Failed to apply best parameters: {}", e))?;

    let optimised_model_ini = IniModelIO::new().model_to_string(&problem.model);

    // Optionally write the optimised model to disk.
    if let Some(path) = save_model_path {
        std::fs::write(path, &optimised_model_ini)
            .map_err(|e| format!("Failed to write optimised model to '{}': {}", path, e))?;
    }

    // Optionally write the results-summary file requested by the config.
    if let Some(output_path) = &config.output_file {
        use std::fmt::Write as _;
        let mut output = String::new();
        writeln!(&mut output, "=== Kalix Optimisation Results ===").unwrap();
        writeln!(&mut output, "Configuration file: {}", config_path).unwrap();
        writeln!(&mut output, "Model file: {}", model_file_path).unwrap();
        writeln!(&mut output, "Terms:").unwrap();
        for term in &config.terms {
            writeln!(&mut output, "  {}: {} over (sim '{}', obs '{}')",
                term.name, term.statistic.name(),
                term.simulated_series, term.observed_file).unwrap();
        }
        writeln!(&mut output, "Objective expression: {}", config.objective_expression).unwrap();
        writeln!(&mut output, "Algorithm: {}", config.algorithm.name()).unwrap();
        writeln!(&mut output, "Population size: {}", config.algorithm.population_size()).unwrap();
        writeln!(&mut output, "Best objective value: {:.6}", result.best_objective).unwrap();
        writeln!(&mut output, "Function evaluations: {}\n", result.n_evaluations).unwrap();
        writeln!(&mut output, "Optimized Parameters:").unwrap();
        for (target, value) in &parameters {
            writeln!(&mut output, "  {} = {:.6}", target, value).unwrap();
        }
        std::fs::write(output_path, output)
            .map_err(|e| format!("Failed to write results to '{}': {}", output_path, e))?;
    }

    Ok(OptimisationOutcome {
        best_objective: result.best_objective,
        n_evaluations: result.n_evaluations,
        success: result.success,
        message: result.message,
        parameters,
        optimised_model_ini,
    })
}
