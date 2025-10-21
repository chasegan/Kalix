// Optimisation algorithms
pub mod cmaes;
pub mod de;
pub mod sce_ua;
pub mod sp_uci;

// Optimisation framework
pub mod optimisable;
pub mod optimisable_component;
pub mod parameter_mapping;
pub mod objectives;
pub mod optimisation;

// Re-exports for convenience
pub use optimisable::{Optimisable, clone_multi};
pub use optimisable_component::OptimisableComponent;
pub use parameter_mapping::{ParameterMapping, ParameterMappingConfig, Transform};
pub use objectives::ObjectiveFunction;
pub use optimisation::OptimisationProblem;
pub use de::{DifferentialEvolution, DEConfig, DEResult, DEProgress};

// Re-export IO types for convenience
pub use crate::io::optimisation_config_io::{OptimisationConfig, AlgorithmParams};

// Legacy trait (to be potentially updated/replaced)
#[allow(unused)]
pub trait Optimiser {

    fn set_objective(&mut self);

    fn run(&mut self);

    fn get_best_objective(&mut self) -> f64;

    fn get_best_params(&mut self) -> (f64, f64);

    //fn set_reporting_callback(&mut self, func: Box<dyn FnMut(&str)>);
}
