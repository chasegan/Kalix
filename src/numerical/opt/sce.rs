/// SCE-UA (Shuffled Complex Evolution - University of Arizona) Algorithm
///
/// Based on Duan et al. (1992, 1994) implementation.
///
/// Key features:
/// - Population partitioned into complexes
/// - Each complex evolves independently (parallelizable)
/// - Complexes shuffled periodically to share information
/// - Simplex-based evolution within each complex
///
/// References:
/// - Duan, Q., Sorooshian, S., & Gupta, V. (1992). Effective and efficient global
///   optimization for conceptual rainfall-runoff models. Water resources research,
///   28(4), 1015-1031.
/// - Duan, Q., Sorooshian, S., & Gupta, V. K. (1994). Optimal use of the SCE-UA
///   global optimization method for calibrating watershed models. Journal of
///   hydrology, 158(3-4), 265-284.

use super::optimisable::Optimisable;
use super::optimizer_trait::{OptimizationProgress, OptimizationResult, Optimizer};
use rand::prelude::*;
use rand::seq::SliceRandom;
use rayon::prelude::*;
use std::collections::HashMap;
use std::time::Instant;

/// Experimental flag: Use parameter recombination instead of random fallback
/// when reflection and contraction both fail to improve
const USE_EXPERIMENTAL_FALLBACK: bool = false;

/// Configuration for SCE-UA algorithm
pub struct SceConfig {
    /// Number of complexes
    pub complexes: usize,

    /// Maximum number of function evaluations
    pub termination_evaluations: usize,

    /// Random seed (None for random)
    pub seed: Option<u64>,

    /// Number of threads for parallel complex evolution
    pub n_threads: usize,

    /// Progress callback (receives OptimizationProgress)
    pub progress_callback: Option<Box<dyn Fn(&OptimizationProgress) + Send + Sync>>,
}

/// Individual in the population
#[derive(Clone)]
pub struct Individual {
    /// Normalized parameters [0,1]
    pub params: Vec<f64>,

    /// Objective function value (lower is better)
    pub objective: f64,
}

impl Individual {
    pub fn new(params: Vec<f64>) -> Self {
        Self {
            params,
            objective: f64::INFINITY,
        }
    }
}

/// A complex (sub-population)
#[derive(Clone)]
struct Complex {
    /// Members of this complex
    members: Vec<Individual>,

    /// Complex number (for parallel execution)
    id: usize,
}

impl Complex {
    fn new(id: usize) -> Self {
        Self {
            members: Vec::new(),
            id,
        }
    }
}

/// SCE optimizer
pub struct Sce {
    config: SceConfig,
}

impl Sce {
    /// Create a new SCE optimizer with the given configuration
    pub fn new(config: SceConfig) -> Self {
        Self { config }
    }

    /// Run the SCE optimization algorithm
    pub fn optimize_detailed(
        &self,
        problem: &mut dyn Optimisable,
    ) -> OptimizationResult {
        let start_time = Instant::now();
        let n_params = problem.n_params();

        // Create thread pool ONCE for entire optimization (reused across all shuffles)
        let thread_pool = if self.config.n_threads > 1 {
            Some(rayon::ThreadPoolBuilder::new()
                .num_threads(self.config.n_threads)
                .build()
                .unwrap())
        } else {
            None
        };

        // Calculate population parameters following Duan et al. (1994)
        let m = 2 * n_params + 1;  // Points per complex
        let s = self.config.complexes * m;  // Total population size
        let p = n_params + 1;  // Number of parents in simplex
        let breeding_iterations = m;  // Number of iterations per complex per shuffle
        let elitism = 1.0;  // Duan et al. (1994) trapezoidal weighting

        // Initialize random number generator
        let mut rng = match self.config.seed {
            Some(seed) => StdRng::seed_from_u64(seed),
            None => StdRng::from_entropy(),
        };

        // Step 1: Generate initial population using Latin Hypercube Sampling
        let mut population = self.latin_hypercube_sampling(s, n_params, &mut rng);

        // Step 2: Evaluate initial population (parallel if configured)
        let mut n_evaluations = if let Some(ref pool) = thread_pool {
            self.evaluate_population_parallel(&mut population, problem, pool)
        } else {
            self.evaluate_population_sequential(&mut population, problem)
        };

        // Sort population by objective (best first)
        population.sort_by(|a, b| a.objective.partial_cmp(&b.objective).unwrap());

        // Track best solution
        let mut best_params = population[0].params.clone();
        let mut best_objective = population[0].objective;

        // Check if all initial evaluations failed
        if best_objective.is_infinite() {
            return OptimizationResult {
                best_params,
                best_objective,
                n_evaluations,
                success: false,
                message: "Optimization failed: all initial evaluations failed. \
                         Check model configuration (node names, parameter targets, input data).".to_string(),
                elapsed: start_time.elapsed(),
                algorithm_data: HashMap::new(),
            };
        }

        // Report initial population
        if let Some(ref callback) = self.config.progress_callback {
            let progress = OptimizationProgress {
                n_evaluations,
                best_objective,
                population_objectives: Some(population.iter().map(|ind| ind.objective).collect()),
                elapsed: start_time.elapsed(),
                algorithm_data: HashMap::new(),
            };
            callback(&progress);
        }

        // Step 3: Partition into complexes
        let mut complexes = self.partition_into_complexes(&population, self.config.complexes);

        // Main optimization loop
        let mut shuffle_count = 0;
        while n_evaluations < self.config.termination_evaluations {
            shuffle_count += 1;

            // Step 4: Evolve each complex (in parallel if configured)
            let evolution_result = if let Some(ref pool) = thread_pool {
                self.evolve_complexes_parallel(
                    &mut complexes,
                    problem,
                    breeding_iterations,
                    p,
                    n_params,
                    elitism,
                    &mut rng,
                    pool,
                )
            } else {
                self.evolve_complexes_sequential(
                    &mut complexes,
                    problem,
                    breeding_iterations,
                    p,
                    n_params,
                    elitism,
                    &mut rng,
                )
            };

            n_evaluations += evolution_result.evaluations;

            // Step 5: Combine and sort all individuals
            population = self.combine_complexes(&complexes);
            population.sort_by(|a, b| a.objective.partial_cmp(&b.objective).unwrap());

            // Update best solution
            if population[0].objective < best_objective {
                best_params = population[0].params.clone();
                best_objective = population[0].objective;
            }

            // Report progress
            if let Some(ref callback) = self.config.progress_callback {
                let mut algorithm_data = HashMap::new();
                algorithm_data.insert("shuffle".to_string(), shuffle_count as f64);
                algorithm_data.insert("complexes".to_string(), self.config.complexes as f64);

                let progress = OptimizationProgress {
                    n_evaluations,
                    best_objective,
                    population_objectives: Some(population.iter().map(|ind| ind.objective).collect()),
                    elapsed: start_time.elapsed(),
                    algorithm_data,
                };
                callback(&progress);
            }

            // Step 6: Re-partition (shuffle) complexes for next iteration
            complexes = self.partition_into_complexes(&population, self.config.complexes);
        }

        // Return result
        let mut algorithm_data = HashMap::new();
        algorithm_data.insert(
            "shuffles".to_string(),
            serde_json::Value::Number(serde_json::Number::from(shuffle_count)),
        );

        OptimizationResult {
            best_params,
            best_objective,
            n_evaluations,
            success: true,
            message: "Optimization completed successfully".to_string(),
            elapsed: start_time.elapsed(),
            algorithm_data,
        }
    }

    /// Latin Hypercube Sampling for initial population
    ///
    /// Generates `n_samples` individuals with `n_params` parameters each,
    /// ensuring good coverage of the parameter space.
    fn latin_hypercube_sampling(
        &self,
        n_samples: usize,
        n_params: usize,
        rng: &mut StdRng,
    ) -> Vec<Individual> {
        let mut population = Vec::with_capacity(n_samples);

        for _ in 0..n_samples {
            population.push(Individual::new(vec![0.0; n_params]));
        }

        // For each parameter dimension
        for param_idx in 0..n_params {
            // Create bins [0, 1/n, 2/n, ..., (n-1)/n, 1]
            let mut bins: Vec<usize> = (0..n_samples).collect();
            bins.shuffle(rng);

            // Assign each individual to a bin and sample within it
            for (ind_idx, &bin_idx) in bins.iter().enumerate() {
                let bin_start = bin_idx as f64 / n_samples as f64;
                let bin_width = 1.0 / n_samples as f64;
                let within_bin = rng.gen::<f64>();  // [0, 1)

                population[ind_idx].params[param_idx] = bin_start + within_bin * bin_width;
            }
        }

        population
    }

    /// Partition sorted population into complexes using round-robin
    fn partition_into_complexes(
        &self,
        population: &[Individual],
        n_complexes: usize,
    ) -> Vec<Complex> {
        let mut complexes: Vec<Complex> = (0..n_complexes)
            .map(|id| Complex::new(id))
            .collect();

        // Round-robin distribution
        for (idx, individual) in population.iter().enumerate() {
            let complex_idx = idx % n_complexes;
            complexes[complex_idx].members.push(individual.clone());
        }

        complexes
    }

    /// Combine all complexes back into a single population
    fn combine_complexes(&self, complexes: &[Complex]) -> Vec<Individual> {
        let mut population = Vec::new();
        for complex in complexes {
            population.extend(complex.members.clone());
        }
        population
    }

    /// Evolve all complexes sequentially (single-threaded)
    fn evolve_complexes_sequential(
        &self,
        complexes: &mut [Complex],
        problem: &mut dyn Optimisable,
        breeding_iterations: usize,
        p: usize,
        n_params: usize,
        elitism: f64,
        rng: &mut StdRng,
    ) -> EvolutionResult {
        let mut total_evaluations = 0;

        for complex in complexes.iter_mut() {
            let mut local_rng = StdRng::seed_from_u64(rng.gen());
            let evals = self.evolve_one_complex(
                complex,
                problem,
                breeding_iterations,
                p,
                n_params,
                elitism,
                &mut local_rng,
            );
            total_evaluations += evals;
        }

        EvolutionResult {
            evaluations: total_evaluations,
        }
    }

    /// Evolve all complexes in parallel using worker-based threading
    ///
    /// Creates n_threads worker problems and distributes complexes across workers.
    /// This dramatically reduces cloning overhead compared to cloning per evaluation.
    fn evolve_complexes_parallel(
        &self,
        complexes: &mut [Complex],
        problem: &dyn Optimisable,
        breeding_iterations: usize,
        p: usize,
        n_params: usize,
        elitism: f64,
        rng: &mut StdRng,
        pool: &rayon::ThreadPool,
    ) -> EvolutionResult {
        use std::sync::{Arc, Mutex, atomic::{AtomicUsize, Ordering}};

        // Create n_threads worker problems (NOT per-complex!)
        let worker_problems: Vec<Arc<Mutex<Box<dyn Optimisable>>>> =
            (0..self.config.n_threads)
                .map(|_| Arc::new(Mutex::new(problem.clone_for_parallel())))
                .collect();

        let eval_counter = AtomicUsize::new(0);

        // Generate RNG seeds for each complex upfront
        let seeds: Vec<u64> = (0..complexes.len())
            .map(|_| rng.gen())
            .collect();

        // Evolve complexes in parallel with round-robin worker assignment
        pool.install(|| {
            complexes.par_iter_mut()
                     .enumerate()
                     .for_each(|(i, complex)| {
                         // Round-robin assignment to workers
                         let worker_idx = i % self.config.n_threads;
                         let worker = &worker_problems[worker_idx];

                         // Lock worker for this complex's evolution
                         let mut prob = worker.lock().unwrap();
                         let mut local_rng = StdRng::seed_from_u64(seeds[i]);

                         let evals = self.evolve_one_complex(
                             complex,
                             &mut **prob,
                             breeding_iterations,
                             p,
                             n_params,
                             elitism,
                             &mut local_rng,
                         );

                         eval_counter.fetch_add(evals, Ordering::Relaxed);
                     });
        });

        EvolutionResult {
            evaluations: eval_counter.load(Ordering::Relaxed),
        }
    }

    /// Evolve a single complex using the CCE (Competitive Complex Evolution) algorithm
    ///
    /// Based on Duan et al. (1994) original implementation
    fn evolve_one_complex(
        &self,
        complex: &mut Complex,
        problem: &mut dyn Optimisable,
        breeding_iterations: usize,
        p: usize,
        n_params: usize,
        elitism: f64,
        rng: &mut StdRng,
    ) -> usize {
        let mut evaluations = 0;

        for _ in 0..breeding_iterations {
            // Select p members for the simplex from complex using weighted probability
            let simplex_indices = self.create_simplex(
                complex.members.len(),
                p,
                elitism,
                rng,
            );

            // Extract simplex members WITH their complex indices
            // Store as (Individual, complex_index) to track through sorting
            let mut simplex_with_indices: Vec<(Individual, usize)> = simplex_indices
                .iter()
                .map(|&idx| (complex.members[idx].clone(), idx))
                .collect();

            // Sort simplex by objective (best first)
            simplex_with_indices.sort_by(|a, b| a.0.objective.partial_cmp(&b.0.objective).unwrap());

            // Extract just the individuals for algorithm logic
            let simplex: Vec<Individual> = simplex_with_indices.iter().map(|(ind, _)| ind.clone()).collect();

            // Identify worst member in simplex
            let worst_idx_in_simplex = simplex.len() - 1;
            let worst = &simplex[worst_idx_in_simplex];

            // Compute centroid without worst member
            let centroid = Self::compute_centroid(&simplex[..worst_idx_in_simplex]);

            // Try reflection: new = worst * (-1) + centroid * 2
            let mut proposal = self.reflect(&worst.params, &centroid.params, -1.0);

            // If reflection is out of bounds, generate random individual
            if !self.is_valid(&proposal) {
                proposal = self.random_individual(n_params, rng);
            }

            // Evaluate proposal
            let mut proposal_individual = Individual::new(proposal.clone());
            if let Ok(obj) = self.evaluate_individual(problem, &proposal) {
                proposal_individual.objective = obj;
                evaluations += 1;
            }

            // If proposal is worse than worst, try contraction
            if proposal_individual.objective > worst.objective {
                let contracted = self.reflect(&worst.params, &centroid.params, 0.5);
                if let Ok(obj) = self.evaluate_individual(problem, &contracted) {
                    evaluations += 1;

                    // Use contraction if it's better
                    if obj < proposal_individual.objective {
                        proposal_individual = Individual::new(contracted);
                        proposal_individual.objective = obj;
                    }
                }

                // If still worse than worst, apply fallback strategy
                if proposal_individual.objective > worst.objective {
                    if USE_EXPERIMENTAL_FALLBACK {
                        // EXPERIMENTAL: Generate proposal by recombining parameters from complex members
                        // This keeps the new point within the "successful" parameter space
                        let recombined_params = self.recombine_from_complex(&complex.members, n_params, rng);
                        if let Ok(obj) = self.evaluate_individual(problem, &recombined_params) {
                            proposal_individual = Individual::new(recombined_params);
                            proposal_individual.objective = obj;
                            evaluations += 1;
                        }
                    } else {
                        // ORIGINAL: Generate completely random point across entire [0,1] space
                        let random_params = self.random_individual(n_params, rng);
                        if let Ok(obj) = self.evaluate_individual(problem, &random_params) {
                            proposal_individual = Individual::new(random_params);
                            proposal_individual.objective = obj;
                            evaluations += 1;
                        }
                    }
                }
            }

            // Replace worst member in complex with proposal
            let worst_idx_in_complex = simplex_with_indices[worst_idx_in_simplex].1;
            complex.members[worst_idx_in_complex] = proposal_individual;

            // Re-sort complex after replacement to maintain sorted order for next iteration
            // This is critical because weighted selection assumes sorted order
            complex.members.sort_by(|a, b| a.objective.partial_cmp(&b.objective).unwrap());
        }

        evaluations
    }

    /// Select simplex members
    ///
    /// Implements the trapezoidal weighting scheme from Duan et al. (1994)
    /// where probability weight[i] = i^elitism (with i from n down to 1)
    fn create_simplex(
        &self,
        n_members: usize,
        n_parents: usize,
        elitism: f64,
        rng: &mut StdRng,
    ) -> Vec<usize> {
        let mut simplex = Vec::with_capacity(n_parents);
        let mut available: Vec<usize> = (0..n_members).collect();
        let mut weights: Vec<f64> = (1..=n_members)
            .rev()
            .map(|i| (i as f64).powf(elitism))
            .collect();

        for _ in 0..n_parents {
            // Compute total weight
            let total_weight: f64 = weights.iter().sum();

            // Random selection
            let mut r = rng.gen::<f64>() * total_weight;
            let mut chosen_idx = 0;

            while r > weights[chosen_idx] && chosen_idx < weights.len() - 1 {
                r -= weights[chosen_idx];
                chosen_idx += 1;
            }

            // Add selected member to simplex
            simplex.push(available[chosen_idx]);

            // Remove from available pool
            available.remove(chosen_idx);
            weights.remove(chosen_idx);
        }
        simplex
    }

    /// Compute centroid of a set of individuals
    pub fn compute_centroid(individuals: &[Individual]) -> Individual {
        let n_params = individuals[0].params.len();
        let mut centroid_params = vec![0.0; n_params];

        for individual in individuals {
            for (i, &param) in individual.params.iter().enumerate() {
                centroid_params[i] += param;
            }
        }

        let n = individuals.len() as f64;
        for param in &mut centroid_params {
            *param /= n;
        }

        Individual::new(centroid_params)
    }

    /// Reflect a point through a mirror point
    ///
    /// Formula: new = original * factor + mirror * (1 - factor)
    /// - factor = -1.0 for standard reflection
    /// - factor = 0.5 for contraction
    fn reflect(&self, original: &[f64], mirror: &[f64], factor: f64) -> Vec<f64> {
        original
            .iter()
            .zip(mirror.iter())
            .map(|(&orig, &mirr)| orig * factor + mirr * (1.0 - factor))
            .collect()
    }

    /// Check if parameters are valid (within [0, 1] bounds)
    fn is_valid(&self, params: &[f64]) -> bool {
        params.iter().all(|&p| p >= 0.0 && p <= 1.0)
    }

    /// Generate a random individual within [0, 1] bounds
    fn random_individual(&self, n_params: usize, rng: &mut StdRng) -> Vec<f64> {
        (0..n_params).map(|_| rng.gen::<f64>()).collect()
    }

    /// Generate a new individual by recombining parameters from complex members
    ///
    /// For each parameter dimension, randomly select the value from one of the
    /// complex members. This creates a new point that stays within the "successful"
    /// parameter space defined by the complex, similar to crossover in genetic algorithms.
    fn recombine_from_complex(&self, complex_members: &[Individual], n_params: usize, rng: &mut StdRng) -> Vec<f64> {
        let n_members = complex_members.len();
        (0..n_params)
            .map(|param_idx| {
                // Randomly select a member to donate this parameter
                let donor_idx = rng.gen_range(0..n_members);
                complex_members[donor_idx].params[param_idx]
            })
            .collect()
    }

    /// Evaluate an individual's objective function
    ///
    /// Now uses mutable reference directly - no cloning!
    fn evaluate_individual(&self, problem: &mut dyn Optimisable, params: &[f64]) -> Result<f64, String> {
        problem.set_params(params)?;
        problem.evaluate()
    }

    /// Evaluate a population of individuals sequentially
    fn evaluate_population_sequential(
        &self,
        individuals: &mut [Individual],
        problem: &mut dyn Optimisable,
    ) -> usize {
        let mut evals = 0;
        for individual in individuals.iter_mut() {
            match problem.set_params(&individual.params) {
                Ok(_) => {
                    match problem.evaluate() {
                        Ok(obj) => {
                            individual.objective = obj;
                            evals += 1;
                        },
                        Err(_) => {
                            individual.objective = f64::INFINITY;
                        }
                    }
                },
                Err(_) => {
                    individual.objective = f64::INFINITY;
                }
            }
        }
        evals
    }

    /// Evaluate a population of individuals in parallel using worker-based threading
    ///
    /// This uses the same worker pattern as complex evolution to minimize cloning.
    fn evaluate_population_parallel(
        &self,
        individuals: &mut [Individual],
        problem: &dyn Optimisable,
        pool: &rayon::ThreadPool,
    ) -> usize {
        use rayon::prelude::*;
        use std::sync::{Arc, Mutex};
        use std::sync::atomic::{AtomicUsize, Ordering};

        // Create n_threads worker problems (NOT population_size!)
        let worker_problems: Vec<Arc<Mutex<Box<dyn Optimisable>>>> =
            (0..self.config.n_threads)
                .map(|_| Arc::new(Mutex::new(problem.clone_for_parallel())))
                .collect();

        let eval_counter = AtomicUsize::new(0);

        // Collect parameters to evaluate
        let params: Vec<Vec<f64>> = individuals.iter().map(|ind| ind.params.clone()).collect();

        // Evaluate in parallel with round-robin worker assignment
        let objectives: Vec<f64> = pool.install(|| {
            params.par_iter()
                  .enumerate()
                  .map(|(i, param_vec)| {
                      // Round-robin assignment to workers
                      let worker_idx = i % self.config.n_threads;
                      let worker = &worker_problems[worker_idx];

                      // Lock worker, evaluate, unlock
                      let mut prob = worker.lock().unwrap();
                      match prob.set_params(param_vec) {
                          Ok(_) => match prob.evaluate() {
                              Ok(obj) => {
                                  eval_counter.fetch_add(1, Ordering::Relaxed);
                                  obj
                              },
                              Err(_) => f64::INFINITY,
                          },
                          Err(_) => f64::INFINITY,
                      }
                  })
                  .collect()
        });

        // Write objectives back to individuals
        for (individual, objective) in individuals.iter_mut().zip(objectives.iter()) {
            individual.objective = *objective;
        }

        eval_counter.load(Ordering::Relaxed)
    }
}

/// Result of complex evolution
struct EvolutionResult {
    evaluations: usize,
}

impl Optimizer for Sce {
    fn optimize(
        &self,
        problem: &mut dyn Optimisable,
        _progress_callback: Option<Box<dyn Fn(&OptimizationProgress) + Send + Sync>>,
    ) -> OptimizationResult {
        // Note: progress_callback is ignored because it's already in self.config
        self.optimize_detailed(problem)
    }

    fn name(&self) -> &str {
        "SCE"
    }
}
