pub mod cmaes;
pub mod de;
pub mod sce_ua;
pub mod sp_uci;

#[allow(unused)]
pub trait Optimiser {

    fn set_objective(&mut self);

    fn run(&mut self);

    fn get_best_fitness(&mut self) -> f64;

    fn get_best_params(&mut self) -> (f64, f64);

    //fn set_reporting_callback(&mut self, func: Box<dyn FnMut(&str)>);
}