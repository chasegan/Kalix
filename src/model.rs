use std::collections::HashMap;
use uuid::Uuid;
use crate::nodes::{Node, NodeEnum};
use crate::data_cache::DataCache;
use crate::io::csv_io::{write_ts};
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

    // Vector of tuples defining the links (upstream node uuid, downstream node uuid).
    pub links: Vec<(Uuid, Uuid)>,

    // Vector of tuples defining the execution order (node uuid, node index).
    // The node index is just the index of the node in "nodes" which is handy for quick access.
    pub execution_order: Vec<(Uuid, usize)>,
    pub node_dictionary: HashMap<Uuid, usize>,
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

    /*
    Determine the simulation period on the basis of the available input data
     */
    pub fn auto_determine_simulation_period(&self) -> Configuration {

        // Get a vec of the critical data from the data_cache
        let civ = self.data_cache.get_critical_input_names();
        println!("Number of critical inputs: {}", civ.len());
        for i in 0..civ.len() {
            println!("Critical input [{}]: {}", i, civ[i]);
        }

        // If there is no critical input data, return a default configuration.
        if civ.len() == 0 {
            return Configuration::new();
        }

        // Go through all the critical inputs and make sure they are all in the model.
        // As you find them, you can go ahead and update the mask of data availability.
        let mut critical_data_availability_mask: Option<Timeseries> = None;
        for ci in civ {

            println!("Searching for timeseries that matches ci: {}", ci);
            let mut found : bool = false;

            for ts in self.inputs.iter() {
                println!("Timeseries: {} {}", ts.full_colindex_path, ts.full_colname_path);
                if (ci == ts.full_colindex_path) || (ci == ts.full_colname_path) {

                    println!("Got it!");
                    found = true;
                    // This timeseries appears to be the one we're looking for!
                    // If it is a critical input AND THE SOURCE IS A FILE then the model run
                    // will be limited by the data available in the file.
                    if ts.source_path != "" {
                        match critical_data_availability_mask {
                            None => {
                                //This is the first critical data file
                                critical_data_availability_mask = Some(ts.timeseries.clone());
                                println!("Initial mask based on {}", ts.source_path);
                            }
                            Some(ref mut mask) => {
                                mask.mask_with(&ts.timeseries);
                                println!("Mask updated based on {}", ts.source_path);
                            }
                        }
                    } else {
                        println!("Mask not influenced by {}", ts.source_path);
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
        println!("Mask start_timestamp: {}", mask.start_timestamp);
        println!("Mask start_index: {}", start_index);
        println!("Mask end_index: {}", end_index);
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
        for ex_tuple in self.execution_order.iter() {
            let id = ex_tuple.0;
            let i = ex_tuple.1;

            //Get a reference to node i
            //let mut n = &self.nodes[i];

            //Run node i
            self.nodes[i].run_flow_phase(&mut self.data_cache);
            //self.nodes[i].run_flow_phase(&mut self.data_cache);

            //TODO: here is where I need to pass water from the node into the next one
            if let Some(ds_id) = self.find_ds_node(id) {
                let dsflow = self.nodes[i].remove_all(0); //take it from outlet 0.
                let ds_no = self.node_dictionary[&ds_id];
                self.nodes[ds_no].add(dsflow, 0); //add it to inlet 0
            }
        }
    }


    
    pub fn initialize_network(&mut self) {
        // Initialize the execution order
        self.initialize_execution_order();

        // Initialize all the nodes
        self.initialize_nodes();
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
        // TASK 1
        // Make a hash table that maps from node id (guid), to node index (their index in
        // self.nodes).
        self.node_dictionary = HashMap::new();
        for i in 0..self.nodes.len() {
            let node_id = self.nodes[i].get_id();
            self.node_dictionary.insert(node_id, i);
        }

        // TASK 2
        // Use links to build a vector of tuples (node ID & node index) indicating suitable
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

            let idx_next = self.find_next_node(&unsorted_node_idxs);
            match idx_next {
                None => { panic!("Is the model cyclic?!"); },
                Some(idx) => {
                    let guid_idx = (self.nodes[idx].get_id(), idx);
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
            self.nodes[i].initialise(&mut self.data_cache);
        }
    }
    
    // Add a node to the model.
    // DONE BUT NOT TESTED
    pub fn add_node(&mut self, node_enum: NodeEnum) {
        //TODO: Maybe it's a good idea for the uuid to be an Option<Uuid> so it can be
        //   None to start with and it can receive a unique id when it gets added to a
        //   model.
        let id= node_enum.get_id();
        if self.get_node(id).is_some() {
            panic!("A node with that Uuid already exists!")
        }
        self.nodes.push(node_enum);
    }


    /// Add a link between two nodes.
    /// DONE BUT NOT TESTED
    pub fn add_link(&mut self, upstream_node_id: Uuid, downstream_node_id: Uuid) {
        //TODO: maybe I want to panic if the link already exists
        self.links.push((upstream_node_id, downstream_node_id));
    }


    /// Remove a link between two nodes.
    /// DONE BUT NOT TESTED
    pub fn remove_link(&mut self, upstream_node_id: Uuid, downstream_node_id: Uuid) {
        let mut link_found  = true;
        while link_found {
            link_found = false;
            for i in 0..self.links.len() {
                if (self.links[i].0 == upstream_node_id) && (self.links[i].1 == downstream_node_id) {
                    self.links.remove(i);
                    link_found = true;
                    break;
                }
            }
        }
    }

    ///What node is downstream of this one?
    ///TODO: this method just finds the first one, whereas nodes might have multiple. Maybe I need to rethink the purpose for this function, which is to move the water between nodes.
    pub fn find_ds_node(&self, node_id: Uuid) -> Option<Uuid> {
        for i in 0..self.links.len() {
            if self.links[i].0 == node_id {
                return Some(self.links[i].1)
            }
        }
        None
    }

    /// Removes all downstream links from given node
    /// DONE BUT NOT TESTED
    pub fn remove_ds_links(&mut self, node_id: Uuid) {
        let mut link_found  = true;
        while link_found {
            link_found = false;
            for i in 0..self.links.len() {
                if self.links[i].0 == node_id {
                    self.links.remove(i);
                    link_found = true;
                    break;
                }
            }
        }
    }


    /// Removes all upstream links from given node
    /// DONE BUT NOT TESTED
    pub fn remove_us_links(&mut self, node_id: Uuid) {
        let mut link_found  = true;
        while link_found {
            link_found = false;
            for i in 0..self.links.len() {
                if self.links[i].1 == node_id {
                    self.links.remove(i);
                    link_found = true;
                    break;
                }
            }
        }
    }


    /// Given the provided nodes, which one is not downstream of any other one?
    /// If the model is cyclic, None will be returned. Otherwise Some(usize)
    /// containing the idx of the node.
    fn find_next_node(&self, node_indexes: &Vec<usize>) -> Option<usize> {
        for idx in node_indexes {
            let guid = self.nodes[*idx].get_id();
            let mut can_be_next = true;
            for (us_guid, ds_guid) in &self.links {
                if guid == *ds_guid {
                    let us_idx = self.node_dictionary[&us_guid];
                    if node_indexes.contains(&us_idx) {
                        can_be_next = false;
                        break;
                    }
                }
            }
            if can_be_next {
                return Some(*idx);
            }
        }
        None
    }


    /// Returns a reference to the node with a given ID
    pub fn get_node(&self, id: Uuid) -> Option<&NodeEnum> {
        for x in &self.nodes {
            if x.get_id() == id {
                return Some(x);
            }
        }
        None
    }


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
