use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;
use rustc_hash::FxHashMap;
use crate::nodes::{Node, NodeEnum, Link};
use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::hydrology::allocation_systems::ras::RasSystem;
use crate::io::csv_io::write_ts;
use crate::io::pixie_io;
use crate::io::custom_ini_parser::IniDocument;
use crate::misc::configuration::Configuration;
use crate::misc::simulation_context::{
    set_context_phase, set_context_node,
    clear_context, format_simulation_error, SimPhase
};
use crate::ordering::simple_nodewise_ordering::SimpleNodewiseOrderingSystem;
use crate::tid::utils::u64_to_iso_datetime_string;
use crate::timeseries::Timeseries;
use crate::timeseries_input::TimeseriesInput;
use crate::model_inputs::DynamicInput;

/// One entry in the model's interleaved execution layout: nodes and var
/// blocks run in definition order, exactly as the file reads.
#[derive(Debug, Clone, Copy)]
pub enum ExecItem {
    Node(usize),
    VarBlock(usize),
}

/// One `key = expression` line of a `[var.*]` section.
#[derive(Debug, Clone)]
pub struct VarDef {
    /// Bare key name as written (for serialization).
    pub key: String,
    /// The `var.<block>.<key>` series this definition writes each step.
    pub series_idx: usize,
    pub input: DynamicInput,
    /// Expression text as written (for serialization).
    pub original: String,
}

/// A `[var.<name>]` section: published calculations, evaluated top to bottom
/// at the block's file position in the flow phase, each written to its own
/// data-cache series — so vars are readable anywhere, offset-addressable,
/// and recordable like any node output (structured_expressions_design.md §9).
/// `phase = order` is not yet implemented and is rejected at load.
#[derive(Debug, Clone)]
pub struct VarBlock {
    /// Section name minus the `var.` prefix.
    pub name: String,
    pub defs: Vec<VarDef>,
    /// The `phase` key exactly as written, if present (round-trip fidelity).
    pub phase_explicit: Option<String>,
}

#[derive(Default, Clone)]
pub struct Model {
    pub configuration: Configuration,
    pub inputs: Vec<TimeseriesInput>,
    pub input_file_paths: Vec<String>,
    /// Maps file_path to the alias provided for quick lookup
    pub alias_map: HashMap<String, String>, 
    pub outputs: Vec<String>,
    pub account_manager: AccountManager,
    // Resource allocation systems ([ras.*] sections), in file order —
    // execution order is declaration order, as for nodes and var blocks
    pub ras_systems: Vec<RasSystem>,
    pub data_cache: DataCache,

    /// Working directory for resolving relative file paths
    /// - Set to model file's directory when loaded from INI file
    /// - Set to current working directory when created programmatically
    pub working_directory: PathBuf,

    // Nodes
    pub nodes: Vec<NodeEnum>,

    // Var blocks ([var.*] sections): published calculations executed at their
    // file position within the flow phase (structured_expressions_design.md §9)
    pub var_blocks: Vec<VarBlock>,

    // Interleaved execution layout: nodes and var blocks in definition order.
    // Built as sections are added (add_node / add_var_block), so file order
    // IS execution order for var blocks exactly as it is for nodes
    // (node-definition-order §1 extended to calculations).
    pub exec_items: Vec<ExecItem>,

    // Links
    pub links: Vec<Link>,

    // Adjacency lists for O(1) link lookup
    pub outgoing_links: Vec<Vec<usize>>,  // outgoing_links[node_idx] = vec of link indices
    pub incoming_links: Vec<Vec<usize>>,  // incoming_links[node_idx] = vec of link indices

    // Pre-computed execution order
    pub execution_order: Vec<usize>,

    // Ordering system
    pub simple_ordering_system: SimpleNodewiseOrderingSystem,

    // Fast node name lookup (keys are lowercase for case-insensitive matching)
    pub node_lookup: FxHashMap<String, usize>, // node_lookup[node_name.to_lowercase()] = node index

    // INI document for round-trip serialization
    pub ini_document: Option<IniDocument>,

    /// Canonical render of the model exactly as loaded, captured before any
    /// programmatic change. Comparing this against a canonical render at save
    /// time identifies which sections actually changed, so a formatting-
    /// preserving save can re-emit only those (state-diff). `None` for models
    /// built programmatically, where there is nothing to preserve.
    pub baseline_canonical: Option<IniDocument>,
}


impl Model {
    pub fn new() -> Model {
        Model {
            configuration: Configuration::new(),
            inputs: vec![],
            input_file_paths: vec![],
            outputs: vec![],
            working_directory: std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")),
            alias_map: HashMap::new(),
            ..Default::default()
        }
    }

    /// Clone for repeated re-runs (optimiser workers): everything the
    /// simulation needs, nothing it doesn't. The round-trip INI documents are
    /// never cloned (they exist only for saving), and the raw input files are
    /// skipped when the model is already configured - their data lives in the
    /// data_cache by then.
    pub fn clone_for_run(&self) -> Model {
        let configured = !self.execution_order.is_empty();
        Model {
            configuration: self.configuration.clone(),
            inputs: if configured { vec![] } else { self.inputs.clone() },
            input_file_paths: vec![],
            outputs: self.outputs.clone(),
            account_manager: self.account_manager.clone(),
            ras_systems: self.ras_systems.clone(),
            data_cache: self.data_cache.clone(),
            working_directory: self.working_directory.clone(),
            nodes: self.nodes.clone(),
            var_blocks: self.var_blocks.clone(),
            exec_items: self.exec_items.clone(),
            links: self.links.clone(),
            outgoing_links: self.outgoing_links.clone(),
            incoming_links: self.incoming_links.clone(),
            execution_order: self.execution_order.clone(),
            simple_ordering_system: self.simple_ordering_system.clone(),
            node_lookup: self.node_lookup.clone(),
            ini_document: None,
            baseline_canonical: None,
            alias_map: self.alias_map.clone(),
        }
    }

    /// Adds a node to the model and returns its index
    pub fn add_node(&mut self, node: NodeEnum) -> usize {
        let idx = self.nodes.len();
        let name = node.get_name().to_string();

        self.nodes.push(node);
        self.outgoing_links.push(Vec::new());
        self.incoming_links.push(Vec::new());
        self.node_lookup.insert(name.to_lowercase(), idx);
        self.exec_items.push(ExecItem::Node(idx));

        idx
    }

    /// Adds a var block at the current position in the execution layout —
    /// definition order is execution order for calculations too.
    pub fn add_var_block(&mut self, block: VarBlock) -> usize {
        let idx = self.var_blocks.len();
        self.var_blocks.push(block);
        self.exec_items.push(ExecItem::VarBlock(idx));
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

    /// Gets a node index by name (case-insensitive)
    pub fn get_node_idx(&self, name: &str) -> Option<usize> {
        self.node_lookup.get(&name.to_lowercase()).copied()
    }

    /// Model configuration needs to be done once, after loading the model, but not for every run.
    /// May be used to validate a model.
    pub fn configure(&mut self) -> Result<(), String> {

        //TASKS
        //1) Define output series
        for series_name in self.outputs.iter() {
            let idx = self.data_cache.get_or_add_new_series(series_name, false);

            //If the series was already registered in the data_cache by another part of the model, its
            //name may have different casing (upper/lower/mixed). Here we update the name to ensure
            //that when it is written to the output file, its appears with the same casing as the
            //user specified in the outputs section.
            self.data_cache.update_series_name(idx, series_name);
        }

        //2) Nodes ask data_cache for idx of relevant data series for input
        self.initialize_nodes()?;

        //3) Read the input data from file
        // TODO: Here is where we would load data IF we wanted to read only the stuff that was required.
        //       E.g. if we were doing reload on run with a subset of the data, or

        //4) Determine simulation period
        //5) Supports sim period specified by user (done in the same step)
        self.auto_determine_simulation_period()?;

        //6) Load input data into the data_cache, properly aligned with simulation period
        for i in 0..self.inputs.len() {
            let input_ts = &self.inputs[i].timeseries;

            // Validate that input step size matches simulation step size
            if input_ts.step_size != self.configuration.sim_stepsize {
                return Err(format!(
                    "Input timeseries '{}' has step_size {} but simulation requires step_size {}",
                    input_ts.name, input_ts.step_size, self.configuration.sim_stepsize
                ));
            }

            // Calculate how many timesteps we need for the simulation
            let sim_steps = 1 + ((self.configuration.sim_end_timestamp
                - self.configuration.sim_start_timestamp)
                / self.configuration.sim_stepsize) as usize;

            //Fill any data that might be using the column name as a reference
            //Fill any data that might be using the column number as a reference
            //Also fill alias paths if they exist
            let mut paths_to_fill = vec![
                self.inputs[i].full_colname_path.clone(),
                self.inputs[i].full_colindex_path.clone(),
            ];
            if let Some(alias_colname) = &self.inputs[i].alias_colname_path {
                paths_to_fill.push(alias_colname.clone());
            }
            if let Some(alias_colindex) = &self.inputs[i].alias_colindex_path {
                paths_to_fill.push(alias_colindex.clone());
            }
            for full_path in paths_to_fill {
                if let Some(idx) = self.data_cache.get_series_idx(&*full_path, false) {
                    self.data_cache.series[idx].values.clear();
                    self.data_cache.series[idx].start_timestamp = self.configuration.sim_start_timestamp;
                    self.data_cache.series[idx].step_size = self.configuration.sim_stepsize;

                    // For each simulation timestep, find corresponding input value
                    for step in 0..sim_steps {
                        let sim_timestamp = self.configuration.sim_start_timestamp
                            + (step as u64 * self.configuration.sim_stepsize);

                        // Find value at this timestamp in input data
                        let value = if sim_timestamp >= input_ts.start_timestamp {
                            let steps_from_input_start = (sim_timestamp - input_ts.start_timestamp)
                                / input_ts.step_size;
                            let input_idx = steps_from_input_start as usize;

                            if input_idx < input_ts.values.len() {
                                input_ts.values[input_idx]
                            } else {
                                f64::NAN  // Beyond input data range
                            }
                        } else {
                            f64::NAN  // Before input data starts
                        };

                        self.data_cache.series[idx].push_value(value);
                    }
                }
            }
        }
        self.data_cache.set_start_and_stepsize(self.configuration.sim_start_timestamp,
                                               self.configuration.sim_stepsize);

        // Reserve capacity in every cache series for the whole simulation, so
        // per-step recording never reallocates. Capacity only: series lengths
        // remain the computed-this-far watermark that the fail-fast read
        // contract depends on (see DataCache::get_current_value).
        self.data_cache.reserve_all(self.configuration.sim_nsteps as usize);

        //7) Nodes ask data_cache for idx for modelled series they might be responsible for populating
        //TODO: I think this was already appropriately done in step 2.

        //8) Validate that all data.* references correspond to actual input file columns.
        //   This catches typos in non-critical data references that the existing
        //   validation in auto_determine_simulation_period() doesn't check.
        //   Note: We only check that the reference is valid (exists in an input file),
        //   not that it has values - non-critical data is allowed to have missing values.
        for idx in 0..self.data_cache.series.len() {
            let name = &self.data_cache.series_name[idx];
            if name.starts_with("data.") {
                let name_lower = name.to_lowercase();
                let mut found = false;
                for ts in self.inputs.iter() {
                    if name_lower == ts.full_colindex_path || name_lower == ts.full_colname_path {
                        found = true;
                        break;
                    }
                    // Also check alias paths if they exist
                    if let Some(alias_colname) = &ts.alias_colname_path {
                        if name_lower == *alias_colname {
                            found = true;
                            break;
                        }
                    }
                    if let Some(alias_colindex) = &ts.alias_colindex_path {
                        if name_lower == *alias_colindex {
                            found = true;
                            break;
                        }
                    }
                }
                if !found {
                    return Err(format!(
                        "Data reference '{}' was not found in any input file. Check for typos in your model file.",
                        name
                    ));
                }
            }
        }

        // Return
        Ok(())
    }

    pub fn run(&mut self) -> Result<(), String> {
        self.run_with_interrupt(|| false, None).map(|_| ())
    }

    pub fn run_with_interrupt<F>(
        &mut self, interrupt_check: F, 
        mut progress_callback: Option<Box<dyn FnMut(u64, u64)>>
    ) -> Result<bool, String>
    where
        F: Fn() -> bool,
    {
        //Initialise the node network
        self.initialize_network()?;

        //Initialise the water management systems
        self.account_manager.initialize(&mut self.data_cache);
        for ras in &mut self.ras_systems {
            ras.initialize_recorders(&mut self.data_cache);
        }

        // Clear any stale simulation context
        clear_context();

        //Calculate total steps for progress reporting
        let total_steps = ((self.configuration.sim_end_timestamp - self.configuration.sim_start_timestamp)
            / self.configuration.sim_stepsize) + 1;

        //Run all timesteps. catch_unwind wraps the WHOLE loop, not each step:
        //one landing pad instead of one per timestep, and no per-step
        //optimisation barrier. Error reporting is unchanged - the simulation
        //context is thread-local, and data_cache.current_timestamp still holds
        //the failing step's timestamp after the unwind.
        self.data_cache.expr_state.reset(); // expression state to init templates (fresh run)
        self.data_cache.set_current_step(0);
        let outcome = catch_unwind(AssertUnwindSafe(|| {
            while self.data_cache.current_timestamp <= self.configuration.sim_end_timestamp {

                // Check for interrupt at start of each timestep
                if interrupt_check() {
                    return false; // Simulation was interrupted
                }

                // Run the network
                self.run_timestep(self.data_cache.current_timestamp);

                //Report progress if callback provided
                if let Some(ref mut callback) = progress_callback {
                    let step = self.data_cache.current_step as u64;
                    callback(step, total_steps);
                }

                //Increment time
                self.data_cache.increment_current_step();
            }
            true // Simulation completed successfully
        }));

        match outcome {
            Ok(completed) => {
                // Clear context on completion or interruption
                clear_context();
                Ok(completed)
            }
            Err(panic_info) => Err(format_simulation_error(
                panic_info,
                self.data_cache.current_timestamp,
                |idx| self.nodes.get(idx).map(|n| n.get_name().to_string()),
            )),
        }
    }

    /// Determine the simulation period on the basis of the available input data
    pub fn auto_determine_simulation_period(&mut self) -> Result<(), String> {

        // Get a vec of the critical data from the data_cache
        let civ = self.data_cache.get_critical_input_names();

        // If there is no critical input data, return a default configuration.
        if civ.len() == 0 {
            // Go with the specified sim period
            match self.configuration.specified_sim_start_timestamp {
                Some(timestamp) => {
                    self.configuration.sim_start_timestamp = timestamp;
                }
                None => {
                    return Err("There is no critical input data. Please specify start and end.".to_string());
                }
            }
            match self.configuration.specified_sim_end_timestamp {
                Some(timestamp) => {
                    self.configuration.sim_end_timestamp = timestamp;
                }
                None => {
                    return Err("There is no critical input data. Please specify start and end.".to_string());
                }
            }
            if self.configuration.sim_start_timestamp > self.configuration.sim_end_timestamp {
                return Err("Specified start date is before end date.".to_string());
            }

            // Default to daily step size and calculate n_steps //TODO: make this customisable
            self.configuration.sim_stepsize = 86400;
            self.configuration.sim_nsteps = 1 + (self.configuration.sim_end_timestamp -
                self.configuration.sim_start_timestamp) / self.configuration.sim_stepsize;

            // Return
            return Ok(());
        }

        // Go through all the critical inputs and make sure they are all in the model.
        // As you find them, you can go ahead and update the mask of data availability.
        let mut critical_data_availability_mask: Option<Timeseries> = None;
        for ci in civ {

            let ci_lower = ci.to_lowercase();

            // Searching for timeseries that matches ci
            let mut found : bool = false;
            for ts in self.inputs.iter() {
                let matches = (ci_lower == ts.full_colindex_path)
                    || (ci_lower == ts.full_colname_path)
                    || (ts.alias_colindex_path.as_ref().map_or(false, |p| ci_lower == *p))
                    || (ts.alias_colname_path.as_ref().map_or(false, |p| ci_lower == *p));

                if matches {
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

        // Update the configuration
        let n_steps = (end_index - start_index) as u64;
        let start_timestamp = mask.start_timestamp + (start_index as u64 * mask.step_size);
        let end_timestamp = mask.start_timestamp + ((end_index - 1) as u64 * mask.step_size);
        self.configuration.sim_stepsize = mask.step_size;
        self.configuration.sim_start_timestamp = start_timestamp;
        self.configuration.sim_end_timestamp = end_timestamp;
        self.configuration.sim_nsteps = n_steps;

        // Override with dates specified in the model, if relevant
        match self.configuration.specified_sim_start_timestamp {
            Some(timestamp) => {
                if (timestamp < self.configuration.sim_start_timestamp) ||
                    (timestamp > self.configuration.sim_end_timestamp) {
                    return Err("Specified start inconsistent with input data.".to_string());
                }
                self.configuration.sim_start_timestamp = timestamp;
                self.configuration.sim_nsteps = 1 + (self.configuration.sim_end_timestamp -
                    self.configuration.sim_start_timestamp) / self.configuration.sim_stepsize;
            }
            None => {}
        }
        match self.configuration.specified_sim_end_timestamp {
            Some(timestamp) => {
                if (timestamp < self.configuration.sim_start_timestamp) ||
                    (timestamp > self.configuration.sim_end_timestamp) {
                    return Err("Specified end inconsistent with input data.".to_string());
                }
                self.configuration.sim_end_timestamp = timestamp;
                self.configuration.sim_nsteps = 1 + (self.configuration.sim_end_timestamp -
                    self.configuration.sim_start_timestamp) / self.configuration.sim_stepsize;
            }
            None => {}
        }

        // Return ok
        Ok(())
    }

    pub fn run_timestep(&mut self, _t: u64) {

        // Accounting policy: [ras.*] systems run in file order at the top of
        // the step, before ordering and flow — today's orders and takes see
        // today's announcements (kalix-allocation-components.md §3.3).
        for ras in &self.ras_systems {
            ras.run(&mut self.data_cache, &mut self.account_manager);
        }

        // Post-policy, pre-take snapshot: publishes acc.*.opening_balance and
        // resets the per-step debit tally
        self.account_manager.start_of_step(&mut self.data_cache);

        // Execute order phase
        set_context_phase(SimPhase::Ordering);
        self.simple_ordering_system.run_ordering_phase(&mut self.nodes, &mut self.data_cache, &mut self.account_manager);

        // Execute nodes and var blocks with flow phase, interleaved in
        // definition order (file position IS execution position for var
        // blocks, per node-definition-order §1 extended to calculations).
        set_context_phase(SimPhase::Flow);
        // Two loop shapes, chosen once per timestep: models without var
        // blocks — the overwhelmingly common case — run the original plain
        // node loop, byte-identical to before var blocks existed (adding a
        // per-item match to that loop was a measured regression). Only
        // models that actually interleave var blocks pay for the dispatch.
        // The node body is duplicated in both branches deliberately: a shared
        // &mut self helper can't be called while iterating a borrowed field,
        // and the disjoint field borrows only work with the body inline.
        if self.var_blocks.is_empty() {
            for &node_idx in &self.execution_order {
                // Set node context for error reporting (just stores the index)
                set_context_node(node_idx);

                // Run the node's flow phase
                self.nodes[node_idx].run_flow_phase(&mut self.data_cache, &mut self.account_manager);

                // Immediately propagate outflows to downstream nodes
                for &link_idx in &self.outgoing_links[node_idx] {
                    let link = &self.links[link_idx];
                    let outflow = self.nodes[node_idx].remove_dsflow(link.from_outlet);

                    if outflow > 0.0 {
                        self.nodes[link.to_node].add_usflow(outflow, link.to_inlet);
                    }
                }
            }
        } else {
            for &exec_item in &self.exec_items {
                match exec_item {
                    ExecItem::Node(node_idx) => {
                        set_context_node(node_idx);
                        self.nodes[node_idx].run_flow_phase(&mut self.data_cache, &mut self.account_manager);
                        for &link_idx in &self.outgoing_links[node_idx] {
                            let link = &self.links[link_idx];
                            let outflow = self.nodes[node_idx].remove_dsflow(link.from_outlet);
                            if outflow > 0.0 {
                                self.nodes[link.to_node].add_usflow(outflow, link.to_inlet);
                            }
                        }
                    }
                    ExecItem::VarBlock(vb_idx) => {
                        // Evaluate the block's definitions top to bottom,
                        // each written to its series — computed exactly once
                        // per step, so every reader observes one value.
                        for def_idx in 0..self.var_blocks[vb_idx].defs.len() {
                            let value = self.var_blocks[vb_idx].defs[def_idx]
                                .input
                                .get_value(&mut self.data_cache);
                            let series_idx = self.var_blocks[vb_idx].defs[def_idx].series_idx;
                            self.data_cache.add_value_at_index(series_idx, value);
                        }
                    }
                }
            }
        }

        // Accounting recorders
        self.account_manager.record_results(&mut self.data_cache);
    }

    pub fn initialize_network(&mut self) -> Result<(), String> {

        // Initialize the nodes and execution order
        self.initialize_nodes()?;
        self.check_execution_order()?;
        // TODO: why am I doing the execution order here in "initialize_network"? Cant we just do this once during configure?

        // Initialise the ordering system
        // TODO: I am doing this in "initialize_network" because it relies on execution order being resolved (which we do above).
        self.simple_ordering_system.initialize(
            &mut self.nodes, &self.links, &self.incoming_links
        );

        // Return
        Ok(())
    }


    pub fn empty_input_data(&mut self) {
        self.inputs.clear();
    }

    /// Resolve a file path relative to the model's working directory.
    /// Supports absolute, relative, and trailhead (`^/`) paths.
    fn resolve_path(&self, path: &str) -> Result<PathBuf, String> {
        let mut kp = crate::io::kalix_path::KalixPath::parse(path)?;
        kp.resolve(&self.working_directory)?;
        Ok(kp.resolved)
    }

    /// Load input data from a file and store it in the model's inputs vector.
    /// Responsible for remembering how the input was loaded (original path, alias) and for resolving the path.
    /// Construction of the TimeseriesInput is delegated to the TimeseriesInput::load function.
    pub fn load_input_data(
        &mut self, 
        file_path: &str, 
        alias: Option<&str>
    ) -> Result<usize, String> {
        // Remember the ORIGINAL input file path (for serialization/display)
        self.input_file_paths.push(file_path.to_string());
        // Remember also the alias if provided
        if let Some(alias_str) = alias {
            self.alias_map.insert(file_path.to_string(), alias_str.to_string());
        }

        // Resolve the path (supports absolute, relative, and trailhead paths)
        let resolved_path = self.resolve_path(file_path)?;

        // Load all the data using the resolved path
        let resolved_path_str = resolved_path.to_str()
            .ok_or_else(|| format!("Invalid path: {}", file_path))?;
        let mut x = TimeseriesInput::load(resolved_path_str, alias)?;
        let len = x.len();
        self.inputs.append(&mut x);
        Ok(len)
    }

    /// Check execution order.
    ///
    /// Definition order IS execution order (per node-definition-order §2):
    /// the engine validates that every link points down the file and refuses
    /// otherwise. It deliberately does NOT topologically sort - the model
    /// file must remain a faithful, readable account of what runs.
    fn check_execution_order(&mut self) -> Result<(), String> {

        // Execution order according to node index
        self.execution_order.clear();
        for node_idx in 0..self.nodes.len() {
            self.execution_order.push(node_idx);
        }

        // Check execution order is consistent with flow phase using link info:
        // The node below each link must have a higher index than the node above the link
        for link in &self.links {
            //println!("{} -> {}", link.from_node, link.to_node);
            if link.from_node >= link.to_node {
                let from_name = self.nodes[link.from_node].get_name();
                let to_name = self.nodes[link.to_node].get_name();
                return Err(format!(
                    "Node '{}' must be defined before '{}'",
                    from_name, to_name
                ));
            }
        }

        // Done
        Ok(())
    }

    /// Initialize all the nodes
    fn initialize_nodes(&mut self) -> Result<(), String> {
        for i in 0..self.nodes.len() {
            self.nodes[i].initialise(&mut self.data_cache, &mut self.account_manager)?
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

        // Now put all the sections together: the preferred order first, then
        // any node types not in the list (e.g. newly added ones) so nothing
        // silently vanishes from the report.
        let preferred_order = [
            "inflow",
            "sacramento", "gr4j",
            "regulated_user", "unregulated_user", "order_control", "loss",
            "storage", "routing",
            "splitter", "confluence", "gauge",
            "blackhole"];
        for type_name in preferred_order {
            if let Some(s) = report_section_dict.remove(type_name) {
                report.push_str(&s);
                report.push_str("\n");
            }
        }
        let mut leftovers: Vec<_> = report_section_dict.into_iter().collect();
        leftovers.sort_by(|a, b| a.0.cmp(&b.0));
        for (_, s) in leftovers {
            report.push_str(&s);
            report.push_str("\n");
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
            if let Some(alias) = &input.alias {
                println!("  Alias: {} (also accessible as {} and {})",
                    alias,
                    input.alias_colname_path.as_ref().unwrap_or(&String::new()),
                    input.alias_colindex_path.as_ref().unwrap_or(&String::new()));
            }
            i += 1;
        }
    }

    /// Collects the output series that are valid to export — those whose length matches the
    /// simulation horizon (`sim_nsteps`). An output declared in `[outputs]` but never
    /// populated by any component (e.g. an invalid recorder) is left empty in the data cache;
    /// such series are silently omitted so that one bad recorder does not fail the whole
    /// export. Returned in the order the outputs are declared.
    pub(crate) fn collect_output_series(&self) -> Vec<&Timeseries> {
        let expected_len = self.configuration.sim_nsteps as usize;
        let mut vec_ts: Vec<&Timeseries> = Vec::new();
        for output_name in &self.outputs {
            if let Some(idx) = self.data_cache.get_existing_series_idx(output_name) {
                let ts = &self.data_cache.series[idx];
                if ts.values.len() == expected_len {
                    vec_ts.push(ts);
                }
            }
        }
        vec_ts
    }

    /// Output series to export, in declaration order.
    ///
    /// `names = None` selects all declared outputs, silently omitting any that
    /// are unpopulated (see `collect_output_series`).
    /// Named series that are undeclared or unpopulated are an error.
    ///
    /// Used for Python bindings.
    pub fn get_output_series(
        &self,
        output_names: Option<Vec<String>>,
    ) -> Result<Vec<&Timeseries>, String> {
        match output_names {
            None => Ok(self.collect_output_series()),
            Some(vec_names) => {
                let expected_len = self.configuration.sim_nsteps as usize;
                let mut vec_ts: Vec<&Timeseries> = Vec::new();
                let names_hash: HashSet<String> =
                    HashSet::from_iter(self.outputs.iter().map(|x| x.to_lowercase()));
                for output_name in vec_names {
                    if names_hash.contains(&output_name.to_lowercase()) {
                        Ok(())
                    } else {
                        Err(format!(
                            "Output {} undeclared in model [outputs]",
                            output_name
                        ))
                    }?;
                    match self.data_cache.get_existing_series_idx(&output_name) {
                        Some(idx) => {
                            let ts = &self.data_cache.series[idx];
                            let ts_len = ts.values.len();
                            if ts_len == expected_len {
                                vec_ts.push(ts);
                                Ok(())
                            } else {
                                Err(format!("Output series {} found but wrong length, length {} but expected {}", output_name, ts_len, expected_len))
                            }
                        }
                        None => Err(format!("Output {} not found", output_name)),
                    }?;
                }
                Ok(vec_ts)
            }
        }
    }

    pub fn write_outputs(&self, filename: &str) -> Result<(), String> {
        let vec_ts = self.collect_output_series();

        // Dispatch by extension: .pxb or .pxt → paired Pixie format,
        // anything else → CSV.
        let lower = filename.to_ascii_lowercase();
        if lower.ends_with(".pxb") || lower.ends_with(".pxt") {
            let base_path = &filename[..filename.len() - 4];
            pixie_io::write_series(base_path, &vec_ts)
                .map_err(|e| format!("Could not write file {}: {:?}", filename, e))
        } else {
            write_ts(filename, vec_ts)
                .map_err(|_| format!("Could not write file {}", filename))
        }
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
