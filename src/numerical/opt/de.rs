/// Differential Evolution (DE) global optimization algorithm
///
/// Classic DE/rand/1/bin strategy with tournament selection.
///
/// Reference: Storn, R. and Price, K. (1997). Differential evolutiona simple
/// and efficient heuristic for global optimization over continuous spaces.
/// Journal of global optimization, 11(4), 341-359.

use super::optimisable::Optimisable;
use rand::{Rng, RngCore, SeedableRng};
use rand::rngs::StdRng;
use rand::distributions::Uniform;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::{Duration, Instant};

/// Progress information for callback reporting
#[derive(Debug, Clone)]
pub struct DEProgress {
    /// Current generation number
    pub generation: usize,

    /// Best objective value so far (lower is better)
    pub best_objective: f64,

    /// Total number of function evaluations performed
    pub n_evaluations: usize,

    /// Elapsed time since optimisation started
    pub elapsed: Duration,
}

/// Result of a Differential Evolution optimisation run
#[derive(Debug, Clone)]
pub struct DEResult {
    /// Best parameter values found (normalised [0,1])
    pub best_params: Vec<f64>,

    /// Best objective function value (lower is better)
    pub best_objective: f64,

    /// Number of generations completed
    pub generations: usize,

    /// Number of function evaluations performed
    pub n_evaluations: usize,

    /// History of best objective per generation
    pub objective_history: Vec<f64>,

    /// Whether optimization terminated successfully
    pub success: bool,

    /// Termination message
    pub message: String,
}

/// Differential Evolution optimizer configuration
pub struct DEConfig {
    /// Population size (NP)
    pub population_size: usize,

    /// Termination criterion: stop after approximately this many function evaluations
    pub termination_evaluations: usize,

    /// Differential weight F  [0, 2], typically 0.8
    pub f: f64,

    /// Crossover probability CR  [0, 1], typically 0.9
    pub cr: f64,

    /// Random number generator seed (None = random seed)
    pub seed: Option<u64>,

    /// Number of threads for parallel evaluation (1 = single-threaded)
    pub n_threads: usize,

    /// Optional callback for progress reporting
    pub progress_callback: Option<Box<dyn Fn(&DEProgress)>>,
}

impl Default for DEConfig {
    fn default() -> Self {
        Self {
            population_size: 50,
            termination_evaluations: 5000,  // 50 pop × 100 generations
            f: 0.8,
            cr: 0.9,
            seed: None,
            n_threads: 1,
            progress_callback: None,
        }
    }
}

/// Differential Evolution optimizer
pub struct DifferentialEvolution {
    config: DEConfig,
}

impl DifferentialEvolution {
    /// Create a new DE optimizer with given configuration
    pub fn new(config: DEConfig) -> Self {
        Self { config }
    }

    /// Create a new DE optimizer with default configuration
    pub fn with_defaults() -> Self {
        Self::new(DEConfig::default())
    }

    /// Run optimization on the given problem
    pub fn optimize(&self, problem: &mut dyn Optimisable) -> DEResult {
        let start_time = Instant::now();
        let n_params = problem.n_params();

        // Initialize RNG
        let mut rng: Box<dyn RngCore> = match self.config.seed {
            Some(seed) => Box::new(StdRng::seed_from_u64(seed)),
            None => Box::new(StdRng::from_entropy()),
        };

        let uniform = Uniform::new(0.0, 1.0);

        // Initialize population randomly in [0, 1]^n
        let mut population: Vec<Vec<f64>> = (0..self.config.population_size)
            .map(|_| {
                (0..n_params)
                    .map(|_| rng.sample(uniform))
                    .collect()
            })
            .collect();

        // Evaluate initial population
        let mut objective: Vec<f64> = vec![f64::INFINITY; self.config.population_size];
        let mut n_evaluations = 0;

        for i in 0..self.config.population_size {
            match problem.set_params(&population[i]) {
                Ok(_) => {
                    match problem.evaluate() {
                        Ok(f) => {
                            objective[i] = f;
                            n_evaluations += 1;
                        },
                        Err(e) => {
                            // If evaluation fails, leave objective as infinity (invalid solution)
                            eprintln!("Warning: Evaluation failed for individual {}: {}", i, e);
                        }
                    }
                },
                Err(e) => {
                    eprintln!("Warning: Failed to set params for individual {}: {}", i, e);
                }
            }
        }

        // Find initial best
        let mut best_idx = 0;
        let mut best_objective = objective[0];
        for i in 1..self.config.population_size {
            if objective[i] < best_objective {
                best_objective = objective[i];
                best_idx = i;
            }
        }

        let mut best_params = population[best_idx].clone();
        let mut objective_history = vec![best_objective];

        // Main DE loop - terminate based on evaluations
        let mut generation = 0;
        while n_evaluations < self.config.termination_evaluations {

            // Progress callback
            if let Some(ref callback) = self.config.progress_callback {
                let progress = DEProgress {
                    generation,
                    best_objective,
                    n_evaluations,
                    elapsed: start_time.elapsed(),
                };
                callback(&progress);
            }

            // Generate all trial individuals for this generation
            let mut trials: Vec<Vec<f64>> = Vec::with_capacity(self.config.population_size);
            for i in 0..self.config.population_size {
                // Select three random distinct individuals (different from i)
                let (r1, r2, r3) = self.select_random_indices(i, self.config.population_size, &mut *rng);

                // Mutation: trial = x_r1 + F * (x_r2 - x_r3)
                let mut trial = vec![0.0; n_params];
                for j in 0..n_params {
                    trial[j] = population[r1][j] +
                               self.config.f * (population[r2][j] - population[r3][j]);
                }

                // Crossover: binomial crossover
                let j_rand = rng.gen_range(0..n_params);  // Ensure at least one parameter is from trial
                for j in 0..n_params {
                    if j != j_rand && rng.sample(uniform) >= self.config.cr {
                        trial[j] = population[i][j];  // Keep original parameter
                    }
                }

                // Enforce bounds [0, 1] by clipping
                for j in 0..n_params {
                    trial[j] = trial[j].clamp(0.0, 1.0);
                }

                trials.push(trial);
            }

            // Evaluate trials (parallel or sequential based on n_threads)
            let trial_objectives = if self.config.n_threads > 1 {
                self.evaluate_parallel(problem, &trials, &mut n_evaluations)
            } else {
                self.evaluate_sequential(problem, &trials, &mut n_evaluations)
            };

            // Selection: greedy replacement
            for i in 0..self.config.population_size {
                if trial_objectives[i] < objective[i] {
                    population[i] = trials[i].clone();
                    objective[i] = trial_objectives[i];

                    // Update global best
                    if trial_objectives[i] < best_objective {
                        best_objective = trial_objectives[i];
                        best_params = population[i].clone();
                    }
                }
            }

            objective_history.push(best_objective);
            generation += 1;
        }

        // Final callback
        if let Some(ref callback) = self.config.progress_callback {
            let progress = DEProgress {
                generation,
                best_objective,
                n_evaluations,
                elapsed: start_time.elapsed(),
            };
            callback(&progress);
        }

        DEResult {
            best_params,
            best_objective,
            generations: generation,
            n_evaluations,
            objective_history,
            success: true,
            message: "Optimization completed successfully".to_string(),
        }
    }

    /// Select three random distinct indices different from target_idx
    fn select_random_indices(&self, target_idx: usize, pop_size: usize, rng: &mut dyn RngCore) -> (usize, usize, usize) {
        let mut r1 = rng.gen_range(0..pop_size);
        while r1 == target_idx {
            r1 = rng.gen_range(0..pop_size);
        }

        let mut r2 = rng.gen_range(0..pop_size);
        while r2 == target_idx || r2 == r1 {
            r2 = rng.gen_range(0..pop_size);
        }

        let mut r3 = rng.gen_range(0..pop_size);
        while r3 == target_idx || r3 == r1 || r3 == r2 {
            r3 = rng.gen_range(0..pop_size);
        }

        (r1, r2, r3)
    }

    /// Evaluate trials sequentially (single-threaded)
    fn evaluate_sequential(&self, problem: &mut dyn Optimisable, trials: &[Vec<f64>], n_evaluations: &mut usize) -> Vec<f64> {
        trials.iter().map(|trial| {
            match problem.set_params(trial) {
                Ok(_) => {
                    match problem.evaluate() {
                        Ok(f) => {
                            *n_evaluations += 1;
                            f
                        },
                        Err(_) => f64::INFINITY,
                    }
                },
                Err(_) => f64::INFINITY,
            }
        }).collect()
    }

    /// Evaluate trials in parallel using rayon
    fn evaluate_parallel(&self, problem: &dyn Optimisable, trials: &[Vec<f64>], n_evaluations: &mut usize) -> Vec<f64> {
        use rayon::prelude::*;

        // Configure thread pool
        let pool = rayon::ThreadPoolBuilder::new()
            .num_threads(self.config.n_threads)
            .build()
            .unwrap();

        // Clone problems upfront (one per trial)
        let mut problems: Vec<Box<dyn Optimisable>> = (0..trials.len())
            .map(|_| problem.clone_for_parallel())
            .collect();

        // Atomic counter for evaluations
        let eval_counter = AtomicUsize::new(0);

        // Evaluate in parallel (zip trials with their corresponding problems)
        let objectives = pool.install(|| {
            problems.par_iter_mut().zip(trials.par_iter()).map(|(thread_problem, trial)| {
                let objective = match thread_problem.set_params(trial) {
                    Ok(_) => {
                        match thread_problem.evaluate() {
                            Ok(f) => {
                                eval_counter.fetch_add(1, Ordering::Relaxed);
                                f
                            },
                            Err(_) => f64::INFINITY,
                        }
                    },
                    Err(_) => f64::INFINITY,
                };

                objective
            }).collect()
        });

        // Update total evaluation count
        *n_evaluations += eval_counter.load(Ordering::Relaxed);

        objectives
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::numerical::opt::optimisable::Optimisable;

    /// Simple test problem: minimize sum of squared deviations from 0.5
    struct SimpleProblem {
        n_params: usize,
    }

    impl Optimisable for SimpleProblem {
        fn n_params(&self) -> usize {
            self.n_params
        }

        fn set_params(&mut self, _params: &[f64]) -> Result<(), String> {
            Ok(())
        }

        fn get_params(&self) -> Vec<f64> {
            vec![0.5; self.n_params]
        }

        fn evaluate(&mut self) -> Result<f64, String> {
            // This is a hack - in real use set_params would store the params
            // For testing, we'll just return a dummy value
            Ok(0.0)
        }

        fn param_names(&self) -> Vec<String> {
            (0..self.n_params).map(|i| format!("p{}", i)).collect()
        }

        fn clone_for_parallel(&self) -> Box<dyn Optimisable> {
            Box::new(Self { n_params: self.n_params })
        }
    }

    #[test]
    fn test_de_initialization() {
        let config = DEConfig {
            population_size: 20,
            termination_evaluations: 200,
            f: 0.8,
            cr: 0.9,
            seed: Some(42),
            n_threads: 1,
            progress_callback: None,
        };

        let de = DifferentialEvolution::new(config);
        assert_eq!(de.config.population_size, 20);
        assert_eq!(de.config.termination_evaluations, 200);
    }

    #[test]
    fn test_select_random_indices() {
        let config = DEConfig {
            seed: Some(42),
            ..Default::default()
        };
        let de = DifferentialEvolution::new(config);
        let mut rng = StdRng::seed_from_u64(42);

        let (r1, r2, r3) = de.select_random_indices(0, 10, &mut rng);

        // All should be different from each other and from target (0)
        assert_ne!(r1, 0);
        assert_ne!(r2, 0);
        assert_ne!(r3, 0);
        assert_ne!(r1, r2);
        assert_ne!(r1, r3);
        assert_ne!(r2, r3);

        // All should be in range [0, 10)
        assert!(r1 < 10);
        assert!(r2 < 10);
        assert!(r3 < 10);
    }
}
