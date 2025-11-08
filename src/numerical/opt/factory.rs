/// Factory functions for creating optimization algorithms
///
/// This module provides factory functions to create optimizers based on
/// configuration, avoiding duplication between CLI and STDIO interfaces.

use super::{
    OptimisationConfig, AlgorithmParams, Optimizer,
    DifferentialEvolution, de::DEConfig,
    SceUa, sce_ua::SceUaConfig
};

/// Error type for optimizer creation
#[derive(Debug, thiserror::Error)]
pub enum OptimizerFactoryError {
    #[error("Algorithm '{0}' is not yet implemented. Currently supported: DE")]
    NotImplemented(String),

    #[error("Invalid configuration: {0}")]
    InvalidConfig(String),
}

/// Create an optimizer that implements the Optimizer trait
///
/// This returns a trait object suitable for algorithm-agnostic code.
/// Used by STDIO API where progress is reported via OptimizationProgress.
///
/// # Arguments
/// * `config` - The optimization configuration
///
/// # Returns
/// A boxed Optimizer trait object
///
/// # Example
/// ```ignore
/// let optimizer = create_optimizer(&config)?;
/// let result = optimizer.optimize(&mut problem, Some(progress_callback));
/// ```
pub fn create_optimizer(
    config: &OptimisationConfig,
) -> Result<Box<dyn Optimizer>, OptimizerFactoryError> {
    create_optimizer_with_callback(config, None)
}

/// Create an optimizer with a progress callback
///
/// This returns a trait object suitable for algorithm-agnostic code.
/// Used by STDIO API where progress is reported via OptimizationProgress.
///
/// # Arguments
/// * `config` - The optimization configuration
/// * `progress_callback` - Optional progress callback receiving OptimizationProgress
///
/// # Returns
/// A boxed Optimizer trait object with the callback configured
///
/// # Example
/// ```ignore
/// let optimizer = create_optimizer_with_callback(&config, Some(callback))?;
/// let result = optimizer.optimize(&mut problem, None);
/// ```
pub fn create_optimizer_with_callback(
    config: &OptimisationConfig,
    progress_callback: Option<Box<dyn Fn(&super::optimizer_trait::OptimizationProgress) + Send + Sync>>,
) -> Result<Box<dyn Optimizer>, OptimizerFactoryError> {
    match &config.algorithm {
        AlgorithmParams::DE { population_size, f, cr } => {
            // DE now uses OptimizationProgress directly
            let de_config = DEConfig {
                population_size: *population_size,
                termination_evaluations: config.termination_evaluations,
                f: *f,
                cr: *cr,
                seed: config.random_seed,
                n_threads: config.n_threads,
                progress_callback,
            };
            Ok(Box::new(DifferentialEvolution::new(de_config)))
        }
        AlgorithmParams::SCEUA { complexes } => {
            let sce_ua = create_sceua_optimizer_with_callback(
                *complexes,
                config.termination_evaluations,
                config.random_seed,
                config.n_threads,
                progress_callback,
            );
            Ok(Box::new(sce_ua))
        }
        AlgorithmParams::CMAES { .. } => {
            Err(OptimizerFactoryError::NotImplemented("CMA-ES".to_string()))
        }
    }
}

/// Create a Differential Evolution optimizer directly
///
/// This returns the concrete DE type, allowing access to DE-specific features
/// like DEProgress in callbacks. Used by CLI where terminal plotting requires
/// DE-specific progress information.
///
/// # Arguments
/// * `population_size` - Size of the population
/// * `termination_evaluations` - When to stop optimization
/// * `f` - Differential weight (typically 0.5-1.0)
/// * `cr` - Crossover rate (typically 0.8-0.95)
/// * `seed` - Optional random seed
/// * `n_threads` - Number of threads for parallel evaluation
///
/// # Returns
/// A DifferentialEvolution optimizer (without progress callback)
///
/// # Note
/// The returned optimizer has no progress callback. Use
/// `create_de_optimizer_with_callback` if you need progress reporting.
pub fn create_de_optimizer(
    population_size: usize,
    termination_evaluations: usize,
    f: f64,
    cr: f64,
    seed: Option<u64>,
    n_threads: usize,
) -> DifferentialEvolution {
    create_de_optimizer_with_callback(
        population_size,
        termination_evaluations,
        f,
        cr,
        seed,
        n_threads,
        None,
    )
}

/// Create a Differential Evolution optimizer with a progress callback
///
/// This is useful for CLI applications that need progress reporting
/// for terminal plotting.
///
/// # Arguments
/// * `population_size` - Size of the population
/// * `termination_evaluations` - When to stop optimization
/// * `f` - Differential weight (typically 0.5-1.0)
/// * `cr` - Crossover rate (typically 0.8-0.95)
/// * `seed` - Optional random seed
/// * `n_threads` - Number of threads for parallel evaluation
/// * `progress_callback` - Optional progress callback receiving OptimizationProgress
///
/// # Returns
/// A DifferentialEvolution optimizer with the callback configured
pub fn create_de_optimizer_with_callback(
    population_size: usize,
    termination_evaluations: usize,
    f: f64,
    cr: f64,
    seed: Option<u64>,
    n_threads: usize,
    progress_callback: Option<Box<dyn Fn(&super::optimizer_trait::OptimizationProgress) + Send + Sync>>,
) -> DifferentialEvolution {
    let de_config = DEConfig {
        population_size,
        termination_evaluations,
        f,
        cr,
        seed,
        n_threads,
        progress_callback,
    };

    DifferentialEvolution::new(de_config)
}

/// Create an SCE-UA optimizer
///
/// This returns the concrete SCE-UA type.
///
/// # Arguments
/// * `complexes` - Number of complexes for parallel evolution
/// * `termination_evaluations` - When to stop optimization
/// * `seed` - Optional random seed
/// * `n_threads` - Number of threads for parallel complex evolution
///
/// # Returns
/// An SceUa optimizer (without progress callback)
///
/// # Note
/// The returned optimizer has no progress callback. Use
/// `create_sceua_optimizer_with_callback` if you need progress reporting.
pub fn create_sceua_optimizer(
    complexes: usize,
    termination_evaluations: usize,
    seed: Option<u64>,
    n_threads: usize,
) -> SceUa {
    create_sceua_optimizer_with_callback(
        complexes,
        termination_evaluations,
        seed,
        n_threads,
        None,
    )
}

/// Create an SCE-UA optimizer with a progress callback
///
/// # Arguments
/// * `complexes` - Number of complexes for parallel evolution
/// * `termination_evaluations` - When to stop optimization
/// * `seed` - Optional random seed
/// * `n_threads` - Number of threads for parallel complex evolution
/// * `progress_callback` - Optional progress callback receiving OptimizationProgress
///
/// # Returns
/// An SceUa optimizer with the callback configured
pub fn create_sceua_optimizer_with_callback(
    complexes: usize,
    termination_evaluations: usize,
    seed: Option<u64>,
    n_threads: usize,
    progress_callback: Option<Box<dyn Fn(&super::optimizer_trait::OptimizationProgress) + Send + Sync>>,
) -> SceUa {
    let config = SceUaConfig {
        complexes,
        termination_evaluations,
        seed,
        n_threads,
        progress_callback,
    };

    SceUa::new(config)
}

/// Create an optimizer from configuration, matching on algorithm type
///
/// This is a convenience wrapper that extracts algorithm parameters and
/// calls the appropriate creation function. Returns the concrete algorithm
/// type wrapped in an enum for pattern matching.
///
/// # Arguments
/// * `config` - The optimization configuration
///
/// # Returns
/// An OptimizerInstance enum that can be matched on
pub fn create_optimizer_instance(
    config: &OptimisationConfig,
) -> Result<OptimizerInstance, OptimizerFactoryError> {
    match &config.algorithm {
        AlgorithmParams::DE { population_size, f, cr } => {
            let de = create_de_optimizer(
                *population_size,
                config.termination_evaluations,
                *f,
                *cr,
                config.random_seed,
                config.n_threads,
            );
            Ok(OptimizerInstance::DE(de))
        }
        AlgorithmParams::SCEUA { complexes } => {
            let sce_ua = create_sceua_optimizer(
                *complexes,
                config.termination_evaluations,
                config.random_seed,
                config.n_threads,
            );
            Ok(OptimizerInstance::SCEUA(sce_ua))
        }
        AlgorithmParams::CMAES { .. } => {
            Err(OptimizerFactoryError::NotImplemented("CMA-ES".to_string()))
        }
    }
}

/// Concrete optimizer instances
///
/// This enum allows code to access algorithm-specific types while still
/// providing a common interface. Used when you need direct access to
/// algorithm-specific features (like DEProgress callbacks).
pub enum OptimizerInstance {
    DE(DifferentialEvolution),
    SCEUA(SceUa),
    // Future: CMAES(CmaEs),
}

impl OptimizerInstance {
    /// Get the algorithm name
    pub fn name(&self) -> &str {
        match self {
            OptimizerInstance::DE(_) => "DE",
            OptimizerInstance::SCEUA(_) => "SCE-UA",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::numerical::opt::parameter_mapping::ParameterMappingConfig;
    use crate::numerical::opt::objectives::ObjectiveFunction;

    fn create_test_config() -> OptimisationConfig {
        OptimisationConfig {
            model_file: None,
            observed_data_series: "test.csv.0".to_string(),
            simulated_series: "node.test.output".to_string(),
            objective_function: ObjectiveFunction::NashSutcliffe(crate::numerical::opt::objectives::NseObjective::new()),
            output_file: None,
            termination_evaluations: 1000,
            random_seed: Some(42),
            n_threads: 1,
            algorithm: AlgorithmParams::DE {
                population_size: 20,
                f: 0.8,
                cr: 0.9,
            },
            parameter_config: ParameterMappingConfig::new(),
            report_frequency: 10,
            verbose: false,
        }
    }

    #[test]
    fn test_create_optimizer() {
        let config = create_test_config();
        let optimizer = create_optimizer(&config).unwrap();
        assert_eq!(optimizer.name(), "DE");
    }

    #[test]
    fn test_create_de_optimizer() {
        let de = create_de_optimizer(20, 1000, 0.8, 0.9, Some(42), 1);
        // Just test that it was created successfully
        // Config is private, so we can't check internal fields
        assert_eq!(de.name(), "DE");
    }

    #[test]
    fn test_create_optimizer_instance() {
        let config = create_test_config();
        let instance = create_optimizer_instance(&config).unwrap();
        assert_eq!(instance.name(), "DE");
    }

    #[test]
    fn test_unsupported_algorithm_cmaes() {
        let mut config = create_test_config();
        config.algorithm = AlgorithmParams::CMAES {
            population_size: 20,
            sigma: 0.5,
        };

        let result = create_optimizer(&config);
        match result {
            Err(OptimizerFactoryError::NotImplemented(name)) => {
                assert_eq!(name, "CMA-ES");
            }
            _ => panic!("Expected NotImplemented error for CMA-ES"),
        }
    }
}
