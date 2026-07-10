// Optimisation algorithms
pub mod de;
pub mod sce;

// Optimisation framework
pub mod optimisable;
pub mod optimisable_component;
pub mod parameter_mapping;
pub mod genes;
/// The individual objectives. Private: `objectives` is the single public facade,
/// re-exporting them so there is one path to each type.
mod objective_functions;
pub mod objectives;
pub mod optimisation;
pub mod optimizer_trait;
pub mod factory;

// Re-exports for convenience
pub use optimisable::Optimisable;
pub use optimisable_component::OptimisableComponent;
pub use parameter_mapping::{ParameterMapping, ParameterMappingConfig, Transform};
pub use genes::{Gene, GeneMode};
pub use objectives::{ObjectiveFunction, SdebObjective};
pub use optimisation::OptimisationProblem;
pub use optimizer_trait::{Optimizer, OptimizationProgress, OptimizationResult};
pub use de::{DifferentialEvolution, DEConfig, DEResult};
pub use sce::{Sce, SceConfig};
pub use factory::{create_optimizer, create_optimizer_with_callback, create_de_optimizer, create_de_optimizer_with_callback, create_optimizer_instance, OptimizerInstance, OptimizerFactoryError};

// Re-export IO types for convenience
pub use crate::io::optimisation_config_io::{OptimisationConfig, AlgorithmParams};

