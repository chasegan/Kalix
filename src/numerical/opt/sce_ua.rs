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
use std::time::{Duration, Instant};

/// Configuration for SCE-UA algorithm
pub struct SceUaConfig {
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
struct Individual {
    /// Normalized parameters [0,1]
    params: Vec<f64>,

    /// Objective function value (lower is better)
    objective: f64,
}

impl Individual {
    fn new(params: Vec<f64>) -> Self {
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

/// SCE-UA optimizer
pub struct SceUa {
    config: SceUaConfig,
}

impl SceUa {
    /// Create a new SCE-UA optimizer with the given configuration
    pub fn new(config: SceUaConfig) -> Self {
        Self { config }
    }

    /// Run the SCE-UA optimization algorithm
    pub fn optimize_detailed(
        &self,
        problem: &mut dyn Optimisable,
    ) -> OptimizationResult {
        let start_time = Instant::now();
        let n_params = problem.n_params();

        // Calculate population parameters following Duan et al. (1994)
        let m = 2 * n_params + 1;  // Points per complex
        let s = self.config.complexes * m;  // Total population size
        let p = n_params + 1;  // Number of parents in simplex
        let breeding_iterations = m;  // Number of iterations per complex per shuffle

        // Verify population is divisible by complexes
        if s % self.config.complexes != 0 {
            return OptimizationResult::new(
                vec![0.5; n_params],
                f64::INFINITY,
                0,
                false,
                "Population size must be divisible by number of complexes".to_string(),
                Duration::default(),
            );
        }

        // Initialize random number generator
        let mut rng = match self.config.seed {
            Some(seed) => StdRng::seed_from_u64(seed),
            None => StdRng::from_entropy(),
        };

        // Step 1: Generate initial population using Latin Hypercube Sampling
        let mut population = self.latin_hypercube_sampling(s, n_params, &mut rng);

        // Step 2: Evaluate initial population
        let mut n_evaluations = 0;
        for individual in &mut population {
            if let Err(e) = problem.set_params(&individual.params) {
                return OptimizationResult::new(
                    vec![0.5; n_params],
                    f64::INFINITY,
                    n_evaluations,
                    false,
                    format!("Error setting parameters: {}", e),
                    start_time.elapsed(),
                );
            }

            match problem.evaluate() {
                Ok(obj) => individual.objective = obj,
                Err(e) => {
                    return OptimizationResult::new(
                        vec![0.5; n_params],
                        f64::INFINITY,
                        n_evaluations,
                        false,
                        format!("Error evaluating objective: {}", e),
                        start_time.elapsed(),
                    );
                }
            }
            n_evaluations += 1;
        }

        // Sort population by objective (best first)
        population.sort_by(|a, b| a.objective.partial_cmp(&b.objective).unwrap());

        // Track best solution
        let mut best_params = population[0].params.clone();
        let mut best_objective = population[0].objective;

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
            let evolution_result = self.evolve_complexes_parallel(
                &mut complexes,
                problem,
                breeding_iterations,
                p,
                &mut rng,
            );

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

    /// Evolve all complexes in parallel
    fn evolve_complexes_parallel(
        &self,
        complexes: &mut [Complex],
        problem: &mut dyn Optimisable,
        breeding_iterations: usize,
        p: usize,
        rng: &mut StdRng,
    ) -> EvolutionResult {
        let n_params = problem.n_params();
        let elitism = 1.0;  // Duan et al. (1994) trapezoidal weighting

        // For now, use single-threaded evolution to avoid Sync issues with trait objects
        // TODO: Implement parallel evolution by restructuring to avoid trait object sharing
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

    /// Evolve a single complex using the CCE (Competitive Complex Evolution) algorithm
    ///
    /// Based on Duan et al. (1994) original implementation
    fn evolve_one_complex(
        &self,
        complex: &mut Complex,
        problem: &dyn Optimisable,
        breeding_iterations: usize,
        p: usize,
        n_params: usize,
        elitism: f64,
        rng: &mut StdRng,
    ) -> usize {
        let mut evaluations = 0;

        for _ in 0..breeding_iterations {
            // Select p parents from complex using weighted probability
            let parent_indices = self.select_parents_weighted(
                complex.members.len(),
                p,
                elitism,
                rng,
            );

            // Extract parent individuals
            let mut parents: Vec<Individual> = parent_indices
                .iter()
                .map(|&idx| complex.members[idx].clone())
                .collect();

            // Sort parents by objective (best first)
            parents.sort_by(|a, b| a.objective.partial_cmp(&b.objective).unwrap());

            // Identify worst parent
            let worst_idx_in_parents = parents.len() - 1;
            let worst = &parents[worst_idx_in_parents];

            // Compute centroid without worst parent
            let centroid = self.compute_centroid(&parents[..worst_idx_in_parents]);

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
            } else {
                // If evaluation fails, use random individual
                proposal = self.random_individual(n_params, rng);
                if let Ok(obj) = self.evaluate_individual(problem, &proposal) {
                    proposal_individual = Individual::new(proposal);
                    proposal_individual.objective = obj;
                    evaluations += 1;
                }
            }

            // If proposal is worse than worst, try contraction
            if proposal_individual.objective > worst.objective {
                let contracted = self.reflect(&worst.params, &centroid.params, 0.5);

                if self.is_valid(&contracted) {
                    if let Ok(obj) = self.evaluate_individual(problem, &contracted) {
                        evaluations += 1;

                        // Use contraction if it's better
                        if obj < proposal_individual.objective {
                            proposal_individual = Individual::new(contracted);
                            proposal_individual.objective = obj;
                        }
                    }
                }

                // If still worse than worst, fallback to random
                if proposal_individual.objective > worst.objective {
                    let random_params = self.random_individual(n_params, rng);
                    if let Ok(obj) = self.evaluate_individual(problem, &random_params) {
                        proposal_individual = Individual::new(random_params);
                        proposal_individual.objective = obj;
                        evaluations += 1;
                    }
                }
            }

            // Replace worst member in complex with proposal
            let worst_idx_in_complex = parent_indices[worst_idx_in_parents];
            complex.members[worst_idx_in_complex] = proposal_individual;
        }

        evaluations
    }

    /// Select parents using weighted probability (better individuals more likely)
    ///
    /// Implements the trapezoidal weighting scheme from Duan et al. (1994)
    /// where probability weight[i] = i^elitism (with i from n down to 1)
    fn select_parents_weighted(
        &self,
        n_members: usize,
        n_parents: usize,
        elitism: f64,
        rng: &mut StdRng,
    ) -> Vec<usize> {
        let mut parents = Vec::with_capacity(n_parents);
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

            // Add selected parent
            parents.push(available[chosen_idx]);

            // Remove from available pool
            available.remove(chosen_idx);
            weights.remove(chosen_idx);
        }

        parents
    }

    /// Compute centroid of a set of individuals
    fn compute_centroid(&self, individuals: &[Individual]) -> Individual {
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

    /// Evaluate an individual's objective function
    fn evaluate_individual(&self, problem: &dyn Optimisable, params: &[f64]) -> Result<f64, String> {
        // Create mutable clone for evaluation
        let mut prob_clone = problem.clone_for_parallel();
        prob_clone.set_params(params)?;
        prob_clone.evaluate()
    }
}

/// Result of complex evolution
struct EvolutionResult {
    evaluations: usize,
}

impl Optimizer for SceUa {
    fn optimize(
        &self,
        problem: &mut dyn Optimisable,
        _progress_callback: Option<Box<dyn Fn(&OptimizationProgress) + Send + Sync>>,
    ) -> OptimizationResult {
        // Note: progress_callback is ignored because it's already in self.config
        self.optimize_detailed(problem)
    }

    fn name(&self) -> &str {
        "SCE-UA"
    }
}
