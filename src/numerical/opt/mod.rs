// Optimization algorithms
pub mod cmaes;
pub mod de;
pub mod sce_ua;
pub mod sp_uci;

// Calibration framework
pub mod optimisable;
pub mod calibratable;
pub mod calibratable_impls;
pub mod parameter_mapping;
pub mod objectives;
pub mod calibration;

// Re-exports for convenience
pub use optimisable::{Optimisable, clone_multi};
pub use calibratable::Calibratable;
pub use parameter_mapping::{ParameterMapping, CalibrationConfig, Transform};
pub use objectives::ObjectiveFunction;
pub use calibration::CalibrationProblem;
pub use de::{DifferentialEvolution, DEConfig, DEResult, DEProgress};

// Re-export IO types for convenience
pub use crate::io::calibration_config_io::CalibrationIniConfig;

// Legacy trait (to be potentially updated/replaced)
#[allow(unused)]
pub trait Optimiser {

    fn set_objective(&mut self);

    fn run(&mut self);

    fn get_best_fitness(&mut self) -> f64;

    fn get_best_params(&mut self) -> (f64, f64);

    //fn set_reporting_callback(&mut self, func: Box<dyn FnMut(&str)>);
}