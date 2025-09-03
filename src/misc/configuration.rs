
#[derive(Debug)]
#[derive(Clone)]
#[derive(Default)]
pub struct Configuration {
    pub sim_stepsize: u64,              //Size of each timestep in seconds.
    pub sim_start_timestamp: u64,       //The time (i32 representation) at the start of the first simulated timestep.
    pub sim_end_timestamp: u64,         //The time (i32 representation) at the start of the last simulated timestep.
    pub sim_nsteps: u64,                //The number of simulated timesteps including the first and last.\
}

impl Configuration {
    pub fn new() -> Configuration {
        Configuration {
            sim_stepsize: 1,
            sim_start_timestamp: 0,
            sim_end_timestamp: 0,
            sim_nsteps: 1, //1 + ((sim_end_timestamp - sim_start_timestamp) / sim_stepsize)
        }
    }
}
