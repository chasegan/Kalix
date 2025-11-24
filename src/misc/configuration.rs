
#[derive(Debug)]
#[derive(Clone)]
#[derive(Default)]
pub struct Configuration {
    pub specified_sim_start_timestamp: Option<u64>, //If specified in model - the time at the start of the FIRST simulated timestep.
    pub specified_sim_end_timestamp: Option<u64>,   //If specified in model - the time at the start of the LAST simulated timestep.

    pub sim_stepsize: u64,                          //Size of each timestep in seconds.
    pub sim_start_timestamp: u64,                   //The time (u64 representation) at the start of the FIRST simulated timestep.
    pub sim_end_timestamp: u64,                     //The time (u64 representation) at the start of the LAST simulated timestep.
    pub sim_nsteps: u64,                            //The number of simulated timesteps including the FIRST and LAST.
}

impl Configuration {
    pub fn new() -> Configuration {
        Configuration {
            specified_sim_end_timestamp: None,
            specified_sim_start_timestamp: None,
            sim_stepsize: 1,
            sim_start_timestamp: 0,
            sim_end_timestamp: 0,
            sim_nsteps: 1, //1 + ((sim_end_timestamp - sim_start_timestamp) / sim_stepsize)
        }
    }
}
