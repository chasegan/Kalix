use std::cmp::min;
use std::collections::HashMap;
use uuid::Uuid;
use crate::nodes::{Node, NodeEnum};
use crate::data_cache::DataCache;
use crate::io::csv_io::{write_ts};
use crate::misc::configuration_summary::ConfigurationSummary;
use crate::timeseries::Timeseries;
use crate::timeseries_input::TimeseriesInput;

#[derive(Default)]
#[derive(Clone)]
pub struct Model {
    pub sim_start: i32,
    pub sim_end: i32,
    pub inputs: Vec<TimeseriesInput>,   //For now: a vec of all the input files loaded
    pub outputs: Vec<String>,           //For now: a vec of all the data series paths we want to output
    pub data_cache: DataCache,

    // "nodes" - this is a Vec of boxed nodes. Note Vec<Node> doesn't work because Node is a
    // trait and not all Nodes have same length. Even if I use the trick of associating the
    // node types with enum variants (as associated data) I still cant keep them on the stack
    // because I don't know how many there will be (i.e. it's still a Vec).
    // pub nodes: Vec<Box<dyn Node>>,
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
            sim_start: -1, 
            sim_end: -1, 
            inputs: vec![],
            outputs: vec![],
            ..Default::default()
        }
    }


    /*
    Model configuration needs to be done once, after loading the model, but not for every run.
     */
    pub fn configure(&mut self) -> ConfigurationSummary {
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
        self.auto_set_simulation_period();
        //5) Allow the user to override the sim period
        // TODO: provide this functionality later
        //6) Load input data into the data_cache
        for i in 0..self.inputs.len() {
            //Fill any data that might be using the column name as a reference
            //Fill any data that might be using the column number as a reference
            for full_path in [
                self.inputs[i].full_colname_path.clone(),
                self.inputs[i].full_colindex_path.clone()] {
                if let Some(idx) = self.data_cache.get_series_idx(&*full_path, false) {
                    self.data_cache.series[idx].values.clear();
                    for j in 0..self.inputs[i].timeseries.len() {
                        let value = self.inputs[i].timeseries.values[j];
                        self.data_cache.series[idx].values.push(value);
                    }
                }
            }
        }
        //7) Nodes ask data_cache for idx for modelled series they might be responsible for populating
        //TODO: I think this was already appropriately done in step 2.
        //8) Return a summary_of_the_configuration
        ConfigurationSummary {
            sim_start: self.sim_start,
            sim_end: self.sim_end
        }
    }


    pub fn run(&mut self) {
        //Initialise the node network
        //TODO: We shouldn't do a full initialisation again here! Maybe we need an "initialize()" and a "reset()" on each node?
        self.initialize_network();

        //What's the plan?
        //self.data_cache.print();

        //Run all timesteps
        for t in self.sim_start..self.sim_end {
            //let step = self.data_cache.current_step;
            //println!("Step: {}, Datetime: {}", step, t);

            self.run_timestep(t);
            self.data_cache.increment_current_step(); //TODO: why am I using 't' if I also have a concept of a 'current_step'?
        }
    }

    /*
    Determine the simulation period on the basis of the available input data
     */
    pub fn auto_set_simulation_period(&mut self) {
        // TODO: Dodgy! The proper way would be to build a mask over a default period, removing dates when any critical input was missing.

        //Look through the shortest length of the nonzero length series
        let mut n_steps = 0;
        for tsi in self.inputs.iter() {
            let len_i = tsi.len();
            if len_i > 0 {
                if n_steps == 0 {
                    n_steps = len_i;
                } else {
                    n_steps = min(n_steps, len_i);
                }
            }
        }

        self.sim_start = 0;
        self.sim_end = self.sim_start + (n_steps as i32);
    }


    #[allow(unused_variables)] //TODO: remove this and make use of unused variable t
    pub fn run_timestep(&mut self, t: i32) {
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
