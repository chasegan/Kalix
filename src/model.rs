use std::collections::{HashMap, VecDeque};
use rustc_hash::FxHashMap;
use crate::nodes::{Node, NodeEnum, Link};
use crate::data_management::data_cache::DataCache;
use crate::io::csv_io::{write_ts};
use crate::io::custom_ini_parser::IniDocument;
use crate::misc::configuration::Configuration;
use crate::tid::utils::u64_to_iso_datetime_string;
use crate::timeseries::Timeseries;
use crate::timeseries_input::TimeseriesInput;

#[derive(Default, Clone)]
pub struct Model {
    pub configuration: Configuration,
    pub inputs: Vec<TimeseriesInput>,
    pub input_file_paths: Vec<String>,
    pub outputs: Vec<String>,
    pub data_cache: DataCache,

    // Nodes
    pub nodes: Vec<NodeEnum>,

    // Links
    pub links: Vec<Link>,

    // Adjacency lists for O(1) link lookup
    pub outgoing_links: Vec<Vec<usize>>,  // outgoing_links[node_idx] = vec of link indices
    pub incoming_links: Vec<Vec<usize>>,  // incoming_links[node_idx] = vec of link indices

    // Pre-computed execution order
    // (topologically sorted using Kahn's Algorithm)
    pub execution_order: Vec<usize>,

    // Fast node name lookup
    pub node_lookup: FxHashMap<String, usize>, // node_lookup[node_name] = node index

    // INI document for round-trip serialization
    pub ini_document: Option<IniDocument>,
}


impl Model {
    pub fn new() -> Model {
        Model {
            configuration: Configuration::new(),
            inputs: vec![],
            input_file_paths: vec![],
            outputs: vec![],
            ..Default::default()
        }
    }

    /// Adds a node to the model and returns its index
    pub fn add_node(&mut self, node: NodeEnum) -> usize {
        let idx = self.nodes.len();
        let name = node.get_name().to_string();

        self.nodes.push(node);
        self.outgoing_links.push(Vec::new());
        self.incoming_links.push(Vec::new());
        self.node_lookup.insert(name.clone(), idx);

        idx
    }

    /// Adds a link between two nodes
    pub fn add_link(&mut self, from_node: usize, to_node: usize, from_outlet: u8, to_inlet: u8) -> usize {
        let link_idx = self.links.len();
        let link = Link::new(from_node, to_node, from_outlet, to_inlet);

        self.links.push(link);
        self.outgoing_links[from_node].push(link_idx);
        self.incoming_links[to_node].push(link_idx);

        link_idx
    }

    /// Gets a node index by name
    pub fn get_node_idx(&self, name: &str) -> Option<usize> {
        self.node_lookup.get(name).copied()
    }


    /*
    Model configuration needs to be done once, after loading the model, but not for every run.
     */
    pub fn configure(&mut self) -> Result<(), String> {

        //TASKS
        //1) Define output series
        for series_name in self.outputs.iter() {
            self.data_cache.get_or_add_new_series(series_name, false);
        }

        //2) Nodes ask data_cache for idx of relevant data series for input
        self.initialize_nodes()?;

        //3) Read the input data from file
        // TODO: Here is where we would load data IF we wanted to read only the stuff that was required.
        // TODO: E.g. if we were doing reload on run with a subset of the data, or

        //4) Automatically determining the maximum simulation period
        self.configuration = self.auto_determine_simulation_period()?;

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

        // Return
        Ok(())
    }


    pub fn run(&mut self) -> Result<(), String> {

        //Initialise the node network
        //TODO: We shouldn't do a full initialisation again here!
        // Maybe we need an "initialize()" and a "reset()" on each node?
        self.initialize_network()?;

        //Run all timesteps
        self.data_cache.set_current_step(0);
        while self.data_cache.current_timestamp <= self.configuration.sim_end_timestamp {

            //Run the network
            self.run_timestep(self.data_cache.current_timestamp);

            //Increment time
            self.data_cache.increment_current_step();
        }

        // Return success
        Ok(())
    }
    
    pub fn run_with_interrupt<F>(&mut self, interrupt_check: F, mut progress_callback: Option<Box<dyn FnMut(u64, u64)>>) -> Result<bool, String> 
    where
        F: Fn() -> bool,
    {
        //Initialise the node network
        self.initialize_network()?;
        
        //Calculate total steps for progress reporting
        let total_steps = ((self.configuration.sim_end_timestamp - self.configuration.sim_start_timestamp) 
            / self.configuration.sim_stepsize) + 1;
        
        //Run all timesteps
        self.data_cache.set_current_step(0);
        while self.data_cache.current_timestamp <= self.configuration.sim_end_timestamp {

            // Check for interrupt at start of each timestep
            if interrupt_check() {
                return Ok(false); // Simulation was interrupted
            }
            
            //Run the network
            self.run_timestep(self.data_cache.current_timestamp);
            
            //Report progress if callback provided
            if let Some(ref mut callback) = progress_callback {
                let step = self.data_cache.current_step as u64;
                callback(step, total_steps);
            }
            
            //Increment time
            self.data_cache.increment_current_step();
        }
        
        Ok(true) // Simulation completed successfully
    }

    /// Determine the simulation period on the basis of the available input data
    pub fn auto_determine_simulation_period(&self) -> Result<Configuration, String> {

        // Get a vec of the critical data from the data_cache
        let civ = self.data_cache.get_critical_input_names();

        // If there is no critical input data, return a default configuration.
        if civ.len() == 0 {
            return Ok(Configuration::new());
        }

        // Go through all the critical inputs and make sure they are all in the model.
        // As you find them, you can go ahead and update the mask of data availability.
        let mut critical_data_availability_mask: Option<Timeseries> = None;
        for ci in civ {

            let ci_lower = ci.to_lowercase();

            // Searching for timeseries that matches ci
            let mut found : bool = false;
            for ts in self.inputs.iter() {
                if (ci_lower == ts.full_colindex_path) || (ci_lower == ts.full_colname_path) {
                    found = true;

                    // This timeseries appears to be the one we're looking for!
                    // If it is a critical input AND THE SOURCE IS A FILE then the model run
                    // will be limited by the data available in the file.
                    if ts.source_path != "" {
                        match critical_data_availability_mask {
                            None => {
                                //This is the first critical data file
                                // println!("Initial mask based on {}", ts.source_path);
                                critical_data_availability_mask = Some(ts.timeseries.clone());
                            }
                            Some(ref mut mask) => {
                                // println!("Mask updated based on {}", ts.source_path);
                                mask.mask_with(&ts.timeseries);
                            }
                        }
                    } else {
                        // println!("Mask not influenced by {}", ts.source_path);
                    }
                }
            }

            if !found {
                return Err(format!("Could not find input data: {}", ci));
            }
        }

        // The model could run for any sequence where critical_data_availability_mask has values.
        // Like Fors, we are going to default to the first period.
        let mask = critical_data_availability_mask.unwrap();

        //Look for the start.
        //Start and 0 and break when we find the first non-nan value.
        let mut start_index = 0;
        for i in 0..mask.len() {
            if !mask.values[i].is_nan() {
                start_index = i;
                break;
            }
        }

        //Look for the end (exclusive)
        //Start at start_index and then break when we find the first nan value.
        let mut end_index = mask.len();
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
        let n_steps = (end_index - start_index) as u64;
        let start_timestamp = mask.start_timestamp + (start_index as u64 * mask.step_size);
        let end_timestamp = mask.start_timestamp + ((end_index - 1) as u64 * mask.step_size);
        Ok(Configuration {
            sim_stepsize: mask.step_size,
            sim_start_timestamp: start_timestamp,
            sim_end_timestamp: end_timestamp,
            sim_nsteps: n_steps,
        })
    }


    pub fn run_timestep(&mut self, _t: u64) {

        // Execute nodes in topological order with immediate flow propagation
        for &node_idx in &self.execution_order {

            // Run the node's flow phase
            self.nodes[node_idx].run_flow_phase(&mut self.data_cache);

            // Immediately propagate outflows to downstream nodes
            for &link_idx in &self.outgoing_links[node_idx] {
                let link = &self.links[link_idx];
                let outflow = self.nodes[node_idx].remove_dsflow(link.from_outlet);

                if outflow > 0.0 {
                    self.nodes[link.to_node].add_usflow(outflow, link.to_inlet);
                }
            }
        }
    }


    pub fn initialize_network(&mut self) -> Result<(), String> {

        // Initialize the nodes and execution order
        self.initialize_nodes()?;
        self.determine_execution_order()?;

        // Return
        Ok(())
    }


    pub fn empty_input_data(&mut self) {
        self.inputs.clear();
    }


    pub fn load_input_data(&mut self, file_path: &str) -> Result<usize, String> {
        // Remember the input file path
        self.input_file_paths.push(file_path.to_string());

        // And load all the data
        let mut x = TimeseriesInput::load(file_path)?;
        self.inputs.append(&mut x);
        Ok(x.len())
    }


    /// Determine execution order using Kahn's algorithm (O(V+E) complexity)
    fn determine_execution_order(&mut self) -> Result<(), String> {
        let num_nodes = self.nodes.len();
        let mut in_degree = vec![0; num_nodes];

        // Calculate in-degrees for all nodes
        for link in &self.links {
            in_degree[link.to_node] += 1;
        }

        // Initialize queue with nodes that have no incoming edges
        let mut queue: VecDeque<usize> = in_degree
            .iter()
            .enumerate()
            .filter_map(|(idx, &degree)| if degree == 0 { Some(idx) } else { None })
            .collect();

        self.execution_order.clear();

        // Process nodes in topological order
        while let Some(node_idx) = queue.pop_front() {
            self.execution_order.push(node_idx);

            // Reduce in-degree for all downstream nodes
            for &link_idx in &self.outgoing_links[node_idx] {
                let to_node = self.links[link_idx].to_node;
                in_degree[to_node] -= 1;

                if in_degree[to_node] == 0 {
                    queue.push_back(to_node);
                }
            }
        }

        // Check the results
        if self.execution_order.len() != num_nodes {
            // This happens when the model is cyclic
            Err("Closed cycle detected in the model network!".to_string())
        } else {
            Ok(())
        }
    }

    // Initialize all the nodes
    // TODO: Keep in mind this is done in order of definition. Hopefully order will never matter.
    fn initialize_nodes(&mut self) -> Result<(), String> {
        for i in 0..self.nodes.len() {
            self.nodes[i].initialise(&mut self.data_cache)?
        }
        Ok(())
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


    ///
    pub fn generate_mass_balance_report(&self) -> String {

        let mut report = "".to_string();
        report.push_str("==================================\n");
        report.push_str("MASS BALANCE REPORT\n");
        report.push_str("==================================\n");

        // Global stuff
        report.push_str(format!("  Node count: {}\n", self.nodes.len()).as_str());
        report.push_str(format!("  Timesteps: {}\n", self.configuration.sim_nsteps).as_str());
        report.push_str(format!("  Stepsize (s): {}\n", self.configuration.sim_stepsize).as_str());
        let start_str = u64_to_iso_datetime_string(self.configuration.sim_start_timestamp);
        let end_str = u64_to_iso_datetime_string(self.configuration.sim_end_timestamp);
        report.push_str(format!("  Period: {}, {}\n", start_str, end_str).as_str());
        report.push_str(format!("  Note: units are ML/timestep\n\n").as_str());

        // Remaining nodes (<--- here is where you might allow people to organise nodes manually
        let mut remaining_nodes: Vec<String> = self.nodes
            .iter().map(|node| node.get_name().to_string()).collect();
        remaining_nodes.sort();

        // Get keys and values in sorted order
        // let mut items: Vec<(&String, &f64)> = blah.iter().collect();
        // items.sort_by_key(|&(key, _)| key);

        // Keep track of the total
        let mut total_mbal = 0f64;

        // Nodes by type
        let mut report_section_dict: HashMap<String, String> = HashMap::new();
        for node_name in &remaining_nodes {

            // Get the section for this node type (start that section if needed)
            let node = self.get_node(node_name).unwrap();
            let type_name = node.get_type_as_string();
            if !report_section_dict.contains_key(&type_name) {
                report_section_dict.insert(type_name.clone(), format!("{} NODES\n", type_name.to_uppercase()));
            }

            // Add a line for this node
            let mbal_per_timestep = node.get_mass_balance() / (self.configuration.sim_nsteps as f64);
            let mut section = report_section_dict.remove(&type_name).unwrap();
            section.push_str(format!("  {}, {}\n", node_name, mbal_per_timestep).as_str());
            report_section_dict.insert(type_name.clone(), section);

            //Keep track of the total
            total_mbal += mbal_per_timestep;
        }

        // Now put all the sections together
        for type_name in [
            "inflow",
            "sacramento", "gr4j",
            "user", "loss",
            "storage", "routing",
            "splitter", "confluence", "gauge",
            "blackhole"] {
            match report_section_dict.get(type_name) {
                Some(s) => {
                    report.push_str(s);
                    report.push_str("\n");
                }
                None => {}
            }
        }

        // Write the total line
        report.push_str("----------------------------------\n");
        report.push_str(format!("TOTAL = {}\n", total_mbal).as_str());
        report.push_str("----------------------------------\n");

        // Return
        report
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

        let mut vec_ts: Vec<&Timeseries> = Vec::new();
        for output_name in &self.outputs {
            let idx = self.data_cache.get_existing_series_idx(output_name).unwrap();
            vec_ts.push(&self.data_cache.series[idx]);
        }

        write_ts(filename, vec_ts)
            .map_err(|_| format!("Could not write file {}", filename))
    }

    /// Update a node's parameter in the attached INI document
    /// This is typically used after parameter optimisation
    pub fn update_node_parameter_in_ini(&mut self, node_name: &str, param_name: &str, value: &str) -> Result<(), String> {
        if let Some(ref mut ini_doc) = self.ini_document {
            let section_name = format!("node.{}", node_name);
            ini_doc.set_property(&section_name, param_name, value);
            Ok(())
        } else {
            Err("Model does not have an attached INI document".to_string())
        }
    }

    /// Save the model's INI document to a file
    /// This preserves the original formatting for unchanged properties
    pub fn save_ini_to_file(&self, path: &str) -> Result<(), String> {
        if let Some(ref ini_doc) = self.ini_document {
            let content = ini_doc.to_string();
            std::fs::write(path, content)
                .map_err(|e| format!("Failed to write INI file '{}': {}", path, e))
        } else {
            Err("Model does not have an attached INI document".to_string())
        }
    }

    /// Get the INI document as a string
    pub fn get_ini_string(&self) -> Result<String, String> {
        if let Some(ref ini_doc) = self.ini_document {
            Ok(ini_doc.to_string())
        } else {
            Err("Model does not have an attached INI document".to_string())
        }
    }
}
