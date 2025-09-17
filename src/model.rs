use std::collections::HashMap;
use uuid::Uuid;
use crate::nodes::{Node, NodeEnum};
use crate::data_cache::DataCache;
use crate::io::csv_io::{write_ts};
use crate::misc::componenet_identification::ComponentIdentification;
use crate::misc::configuration::{Configuration};
use crate::timeseries::Timeseries;
use crate::timeseries_input::TimeseriesInput;

#[derive(Default)]
#[derive(Clone)]
pub struct Model {
    pub configuration: Configuration,
    pub inputs: Vec<TimeseriesInput>,   //For now: a vec of all the input files loaded
    pub outputs: Vec<String>,           //For now: a vec of all the data series paths we want to output
    pub data_cache: DataCache,

    // "nodes" - this is a Vec of NodeEnums. Note Vec<Node> doesn't work because Node is a
    // trait and not all Nodes have same length. The other standard method would be to use
    // dynamic dispatch --> Vec<Box<dyn Node>>.
    pub nodes: Vec<NodeEnum>,

    // Vector of tuples defining the execution order (node name, node index).
    // The node index is just the index of the node in "self.nodes" which is handy for quick
    // access.
    pub execution_order: Vec<(String, usize)>,

    // Node dictionary maps from the node name to the node index.
    pub node_dictionary: HashMap<String, usize>,
}


impl Model {
    pub fn new() -> Model {

        Model {
            configuration: Configuration::new(),
            inputs: vec![],
            outputs: vec![],
            ..Default::default()
        }
    }


    /*
    Model configuration needs to be done once, after loading the model, but not for every run.
     */
    pub fn configure(&mut self) {
        //TASKS
        //1) Define output series
        for series_name in self.outputs.iter() {
            self.data_cache.get_or_add_new_series(series_name, false);
        }
        //2) Nodes ask data_cache for idx of relevant data series for input
        self.initialize_nodes();
        //3) Read the input data from file
        // TODO: Here is where we would load data IF we wanted to read only the stuff that was required.
        // TODO: E.g. if we were doing reload on run with a subset of the data, or
        //4) Automatically determining the maximum simulation period
        self.configuration = self.auto_determine_simulation_period();
        //5) Allow the user to override the sim period
        // TODO: provide this functionality later
        //6) Load input data into the data_cache
        // TODO: Fix this. Currently it just jams data into the cache irrespective of the simulation period.
        //self.data_cache.start_timestamp = self.configuration.sim_start_timestamp; //TODO: can I delete the property "data_cache.start_timestamp"?
        for i in 0..self.inputs.len() {
            //Fill any data that might be using the column name as a reference
            //Fill any data that might be using the column number as a reference
            for full_path in [
                self.inputs[i].full_colname_path.clone(),
                self.inputs[i].full_colindex_path.clone()] {
                if let Some(idx) = self.data_cache.get_series_idx(&*full_path, false) {
                    self.data_cache.series[idx].values.clear();
                    self.data_cache.series[idx].timestamps.clear();
                    self.data_cache.series[idx].start_timestamp = self.configuration.sim_start_timestamp;
                    self.data_cache.series[idx].step_size = self.configuration.sim_stepsize;
                    for j in 0..self.inputs[i].timeseries.len() {
                        let value = self.inputs[i].timeseries.values[j];
                        self.data_cache.series[idx].push_value(value);
                    }
                }
            }
        }
        self.data_cache.set_start_and_stepsize(self.configuration.sim_start_timestamp,
                                               self.configuration.sim_stepsize);
        //7) Nodes ask data_cache for idx for modelled series they might be responsible for populating
        //TODO: I think this was already appropriately done in step 2.
    }


    pub fn run(&mut self) {

        // Initialise the node dictionary
        self.node_dictionary = HashMap::new();
        for i in 0..self.nodes.len() {
            let node_name = self.nodes[i].get_name();
            self.node_dictionary.insert(node_name, i);
        }

        //Initialise the node network
        //TODO: We shouldn't do a full initialisation again here! Maybe we need an "initialize()" and a "reset()" on each node?
        self.initialize_network();

        //Run all timesteps
        let mut step = 0;
        let mut time = self.configuration.sim_start_timestamp;
        while time <= self.configuration.sim_end_timestamp {

            //Run the network
            //println!("Step: {}, Timestamp: {} ({})", step, time, crate::tid::utils::u64_to_date_string(time as u64));
            self.run_timestep(time);

            //Increment time
            //TODO: why am I using 'time' and 'step' if I also have a concept of a 'current_step'?
            time += self.configuration.sim_stepsize;
            step += 1;
            self.data_cache.increment_current_step();
        }
    }
    
    pub fn run_with_interrupt<F>(&mut self, interrupt_check: F, mut progress_callback: Option<Box<dyn FnMut(u64, u64)>>) -> Result<bool, String> 
    where
        F: Fn() -> bool,
    {
        //Initialise the node network
        self.initialize_network();
        
        //Calculate total steps for progress reporting
        let total_steps = ((self.configuration.sim_end_timestamp - self.configuration.sim_start_timestamp) 
            / self.configuration.sim_stepsize) + 1;
        
        //Run all timesteps
        let mut step = 0;
        let mut time = self.configuration.sim_start_timestamp;
        while time <= self.configuration.sim_end_timestamp {
            // Check for interrupt at start of each timestep
            if interrupt_check() {
                return Ok(false); // Simulation was interrupted
            }
            
            //Run the network
            self.run_timestep(time);
            
            //Report progress if callback provided
            if let Some(ref mut callback) = progress_callback {
                callback(step, total_steps);
            }
            
            //Increment time
            time += self.configuration.sim_stepsize;
            step += 1;
            self.data_cache.increment_current_step();
        }
        
        Ok(true) // Simulation completed successfully
    }

    /*
    Determine the simulation period on the basis of the available input data
     */
    pub fn auto_determine_simulation_period(&self) -> Configuration {

        // Get a vec of the critical data from the data_cache
        let civ = self.data_cache.get_critical_input_names();
        // println!("Number of critical inputs: {}", civ.len());
        // for i in 0..civ.len() {
        //     println!("Critical input [{}]: {}", i, civ[i]);
        // }

        // If there is no critical input data, return a default configuration.
        if civ.len() == 0 {
            return Configuration::new();
        }

        // Go through all the critical inputs and make sure they are all in the model.
        // As you find them, you can go ahead and update the mask of data availability.
        let mut critical_data_availability_mask: Option<Timeseries> = None;
        for ci in civ {

            // println!("Searching for timeseries that matches ci: {}", ci);
            let mut found : bool = false;

            for ts in self.inputs.iter() {
                // println!("Timeseries: {} {}", ts.full_colindex_path, ts.full_colname_path);
                if (ci == ts.full_colindex_path) || (ci == ts.full_colname_path) {

                    // println!("Got it!");
                    found = true;
                    // This timeseries appears to be the one we're looking for!
                    // If it is a critical input AND THE SOURCE IS A FILE then the model run
                    // will be limited by the data available in the file.
                    if ts.source_path != "" {
                        match critical_data_availability_mask {
                            None => {
                                //This is the first critical data file
                                critical_data_availability_mask = Some(ts.timeseries.clone());
                                // println!("Initial mask based on {}", ts.source_path);
                            }
                            Some(ref mut mask) => {
                                mask.mask_with(&ts.timeseries);
                                // println!("Mask updated based on {}", ts.source_path);
                            }
                        }
                    } else {
                        // println!("Mask not influenced by {}", ts.source_path);
                    }
                }
            }

            if !found {
                // If we reach this code, it means ci is a critical input which was not matched by any
                // timeseries in self.inputs
                // TODO: improve error response by returning a Result rather than panicking
                panic!("Input data has nothing matching this critical input: {}", ci);
            }
        }

        // Now in principle the model could run for any sequence where critical_data_availability_mask
        // has values. Like Fors, we are going to default to the first period.
        let mask = critical_data_availability_mask.unwrap();
        let mut start_index = 0;          //start at here
        let mut end_index = mask.len();   //end at here (exclusive)

        //Look for the start_index
        //Start and 0 and break when we find the first non-nan value.
        for i in 0..mask.len() {
            if !mask.values[i].is_nan() {
                start_index = i;
                break;
            }
        }

        //Look for the end_index
        //Start at start_index and then break when we find the first nan value.
        for i in start_index..mask.len() {
            if mask.values[i].is_nan() {
                end_index = i;
                break;
            }
        }

        // Return the configuration
        //TODO: change sim_start to u64 and delete cast
        // println!("Mask start_timestamp: {}", mask.start_timestamp);
        // println!("Mask start_index: {}", start_index);
        // println!("Mask end_index: {}", end_index);
        let nsteps = (end_index - start_index) as u64;
        let start_timestamp = mask.start_timestamp + (start_index as u64 * mask.step_size);
        let end_timestamp = mask.start_timestamp + ((end_index - 1) as u64 * mask.step_size);
        Configuration {
            sim_stepsize: mask.step_size,
            sim_start_timestamp: start_timestamp,
            sim_end_timestamp: end_timestamp,
            sim_nsteps: nsteps,
        }
    }


    #[allow(unused_variables)] //TODO: remove this and make use of unused variable t
    pub fn run_timestep(&mut self, t: u64) {
        for (name, i_ref) in self.execution_order.iter() {
            let i = i_ref.clone();

            //  Run node i
            self.nodes[i].run_flow_phase(&mut self.data_cache);

            // Move water on all the links of node i
            for link in self.nodes[i].get_ds_links() {
                match link.node_identification {
                    ComponentIdentification::Indexed { idx: ds_node_idx } => {
                        let dsflow = self.nodes[i].remove_outflow(0);
                        self.nodes[ds_node_idx].add_inflow(dsflow, 0);
                    },
                    ComponentIdentification::None => { },
                    _ => { panic!("This should have been converted to indexed type during initialization."); }
                }
            }
        }
    }


    
    pub fn initialize_network(&mut self) {

        // Initialize all the nodes
        self.initialize_nodes();

        // Initialize the execution order
        self.initialize_execution_order();
    }


    pub fn empty_input_data(&mut self) {
        self.inputs.clear();
    }


    pub fn load_input_data(&mut self, file_path: &str) -> Result<usize, String> {
        let mut x = TimeseriesInput::load(file_path)?;
        self.inputs.append(&mut x);
        Ok(x.len())
    }


    fn initialize_execution_order(&mut self) {
        // TASK 2
        // Use links to build a vector of tuples (node name & node index) indicating suitable
        // execution order. This order should preserve the order of "node index" wherever
        // possible. Having the node index in the tuple lets us step through the execution order
        // during a run without having to search for the node ID every time.
        //
        // One way to do this is to have a list "unsorted_node_idxs" initially containing
        // all the nodes, and then find the first one which is not downstream of any other one.
        // Place it in execution order, and remove it from our list. Repeat until there are
        // no more nodes in our list.
        self.execution_order = vec![];
        let mut unsorted_node_idxs: Vec<usize> = (0..self.nodes.len()).collect();

        loop {
            // Check if we are done!
            if unsorted_node_idxs.len() == 0 {
                break;
            }

            let idx_next = self.find_next_node_idx(&unsorted_node_idxs);
            match idx_next {
                None => { panic!("Is the model cyclic?!"); },
                Some(idx) => {
                    let guid_idx = (self.nodes[idx].get_name(), idx);
                    //println!("Execution order {:#?}, {:#?}", 1 + self.execution_order.len(), guid_idx);
                    self.execution_order.push(guid_idx);
                    unsorted_node_idxs.retain(|x| *x != idx);
                },
            }
        }
    }

    // TODO: Keep in mind this is done in order of definition. Hopefully order will never matter.
    fn initialize_nodes(&mut self) {
        for i in 0..self.nodes.len() {
            self.nodes[i].initialise(&mut self.data_cache, &self.node_dictionary);
        }
    }
    
    // Add a node to the model.
    // DONE BUT NOT TESTED
    pub fn add_node(&mut self, node_enum: NodeEnum) {
        let name= node_enum.get_name();
        if self.get_node(&name).is_some() {
            panic!("A node with that name already exists!")
        }
        self.nodes.push(node_enum);
    }


    /// This function decides which node to execute next.
    /// For now the logic is: return the first node we find which is not
    /// downstream of any other node in the list. If the model is cyclic,
    /// None will be returned. Otherwise Some(usize) containing the idx
    /// of the answer node.
    fn find_next_node_idx(&self, node_indexes: &Vec<usize>) -> Option<usize> {

        for &prospect_node_idx in node_indexes.iter() {
            let mut found_link_ending_at_node = false;

            // Check all the other nodes for ds links ending at prospect_node.
            for &i in node_indexes.iter() {
                let links = self.nodes[i].get_ds_links();
                for link in links {
                    match link.node_identification {
                        ComponentIdentification::Indexed { idx} => {
                            if idx == prospect_node_idx {
                                found_link_ending_at_node = true;
                            }
                        },
                        ComponentIdentification::None => continue,
                        ComponentIdentification::Named { name: _ } =>
                            panic!("Software bug: this link should have been converted to an indexed type during initialization.")
                    }
                }
            }
            if !found_link_ending_at_node {
                return Some(prospect_node_idx);
            }
        }
        None
    }


    /// Returns a reference to the node with a given ID
    pub fn get_node(&self, name: &str) -> Option<&NodeEnum> {
        for x in &self.nodes {
            if x.get_name() == name {
                return Some(x);
            }
        }
        None
    }


    /// Prints all the inputs to the console, one on each line.
    pub fn print_inputs(&self) {
        let mut i = 0;
        for input in &self.inputs {
            println!("Input: {} {} {}", i, input.full_colname_path, input.full_colindex_path);
            i += 1;
        }
    }


    pub fn write_outputs(&self, filename: &str) -> Result<(), String> {
        //TODO: I feel like I should be able to borrow references and temporarily create a
        //TODO: Vec<&Timeseries> for the purposes of writing before returning the borrow and
        //TODO: leaving ownership of the series in the data cache.
        let mut vec_ts: Vec<Timeseries> = vec![];
        for output_name in &self.outputs {
            let idx = self.data_cache.get_existing_series_idx(output_name).unwrap();
            let ts = self.data_cache.series[idx].clone();
            vec_ts.push(ts);
        }
        let result = write_ts(filename, vec_ts);
        if result.is_err() {
            return Err(format!("Could not write file {}", filename));
        }
        Ok(())
    }
}
