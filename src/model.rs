use crate::data_management::data_cache::DataCache;
use crate::hydrology::accounts::account_manager::AccountManager;
use crate::hydrology::allocation_systems::ras::RasSystem;
use crate::io::csv_io::write_ts;
use crate::io::custom_ini_parser::IniDocument;
use crate::io::error::KalixIoError;
use crate::io::pixie_io;
use crate::misc::configuration::Configuration;
use crate::misc::simulation_context::{
    clear_context, format_simulation_error, set_context_node, set_context_phase, SimPhase,
};
use crate::model_inputs::DynamicInput;
use crate::nodes::{Link, Node, NodeEnum};
use crate::ordering::simple_nodewise_ordering::SimpleNodewiseOrderingSystem;
use crate::tid::utils::u64_to_iso_datetime_string;
use crate::timeseries::Timeseries;
use crate::timeseries_input::{SourceOrigin, TimeseriesInput, TimeseriesInputDefinition};
use rustc_hash::FxHashMap;
use std::collections::{HashMap, HashSet};
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::path::PathBuf;

/// One entry in the model's interleaved execution layout: nodes and var
/// blocks run in definition order, exactly as the file reads.
#[derive(Debug, Clone, Copy)]
pub enum ExecItem {
    Node(usize),
    VarBlock(usize),
}

/// One entry in the ras slot at the top of the timestep: ras-phase var
/// blocks and [ras.*] sections, interleaved in file order — what you read
/// is what runs, within the slot exactly as within the flow pass.
#[derive(Debug, Clone, Copy)]
pub enum RasSlotItem {
    VarBlock(usize),
    Ras(usize),
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

/// When a var block evaluates within the timestep (the `phase` key).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum VarPhase {
    /// Top of the step, before the [ras.*] sections run — the assessment
    /// slot, so the day's policy reads today's value bare. Ras-phase blocks
    /// must appear before the first node section (validated at load): the
    /// file then reads exactly as the timestep runs.
    Ras,
    /// At the block's file position among the nodes, in the flow pass (the
    /// default).
    #[default]
    Flow,
}

/// A `[var.<name>]` section: published calculations, evaluated top to bottom
/// at the block's file position in the flow phase — or, with `phase = ras`,
/// in the assessment slot at the top of the step — each written to its own
/// data-cache series — so vars are readable anywhere, offset-addressable,
/// and recordable like any node output (structured_expressions_design.md §9).
/// `phase = order` is not yet implemented and is rejected at load.
#[derive(Debug, Clone)]
pub struct VarBlock {
    /// Section name minus the `var.` prefix.
    pub name: String,
    pub defs: Vec<VarDef>,
    pub phase: VarPhase,
    /// The `phase` key exactly as written, if present (round-trip fidelity).
    pub phase_explicit: Option<String>,
}

#[derive(Default, Clone)]
pub struct Model {
    // ---- Hot path: read or written every timestep during run_timestep() ----
    /// Hot path: all phases, read in run_timestep().
    ///
    /// Populated: constructed empty (`Configuration::new()`) at `Model::new()`.
    /// Parsing fills in `specified_sim_start_timestamp`/`specified_sim_end_timestamp`
    /// from `[kalix]` if given; the effective `sim_stepsize`/`sim_start_timestamp`/
    /// `sim_end_timestamp`/`sim_nsteps` are only computed later, by `configure()`'s
    /// `auto_determine_simulation_period()`.
    pub configuration: Configuration,

    /// Hot path: all phases, read/written in run_timestep().
    ///
    /// Populated: constructed empty at `Model::new()`. Output series are
    /// registered and node input references resolved during `configure()`
    /// (`configure_model_structure()`), which also loads and fills in input
    /// data; per-step values are then written during `run()`.
    pub data_cache: DataCache,

    /// Hot path: start/record, read in run_timestep().
    ///
    /// Populated: accounts/groups added while parsing `[account.*]` sections
    /// (or built programmatically); per-run state is (re)initialised by
    /// `initialize_network()` at the start of every `run()`.
    pub account_manager: AccountManager,

    /// Hot path: policy phase, read in run_timestep().
    /// Resource allocation systems ([ras.*] sections), in file order —
    /// execution order is declaration order, as for nodes and var blocks
    ///
    /// Populated: added while parsing `[ras.*]` sections; recorders are
    /// (re)initialised at the start of every `run()`.
    pub ras_systems: Vec<RasSystem>,

    /// Hot path: flow phase, read in run_timestep().
    ///
    /// Populated: during parsing or programmatic construction, via `add_node()`.
    pub nodes: Vec<NodeEnum>,

    /// Hot path: flow phase, read in run_timestep().
    /// Var blocks ([var.*] sections): published calculations executed at their
    /// file position within the flow phase (structured_expressions_design.md §9)
    ///
    /// Populated: during parsing or programmatic construction, via `add_var_block()`.
    pub var_blocks: Vec<VarBlock>,

    /// Hot path: flow phase, read in run_timestep().
    /// Interleaved execution layout: nodes and var blocks in definition order.
    /// Built as sections are added (add_node / add_var_block), so file order
    /// IS execution order for var blocks exactly as it is for nodes
    /// (node-definition-order §1 extended to calculations). Only consulted
    /// when var_blocks is non-empty; the plain node loop uses execution_order.
    ///
    /// Populated: alongside `nodes`/`var_blocks`, via `add_node()`/`add_var_block()`.
    pub exec_items: Vec<ExecItem>,

    /// Hot path: top of run_timestep().
    /// The ras slot: ras-phase var blocks and [ras.*] sections interleaved
    /// in file order (assessments read bare by anything below them; a
    /// section above an assessment cannot see it — loud unwritten-read).
    ///
    /// Populated: built by the INI reader in file order; rebuilt as
    /// vars-then-sections in `initialize_network()` if a programmatic
    /// construction left it out of step with the item counts.
    pub ras_slot: Vec<RasSlotItem>,

    /// Hot path: flow phase, read in run_timestep().
    /// `exec_items` minus the ras-phase var blocks — what the flow pass
    /// actually interleaves. The plain node loop is used instead when this
    /// contains no var blocks (see `flow_has_var_blocks`).
    ///
    /// Populated: derived from `exec_items` in `initialize_network()`.
    pub flow_exec_items: Vec<ExecItem>,

    /// Hot path: flow phase, read in run_timestep(). True when
    /// `flow_exec_items` interleaves at least one var block — the once-per-step
    /// choice between the plain node loop and the interleaved loop.
    ///
    /// Populated: derived in `initialize_network()`.
    pub flow_has_var_blocks: bool,

    /// Hot path: flow phase, read in run_timestep().
    ///
    /// Populated: during parsing or programmatic construction, via `add_link()`.
    pub links: Vec<Link>,

    /// Hot path: flow phase, read in run_timestep().
    /// Adjacency list for O(1) link lookup.
    /// `outgoing_links[node_idx]` = vec of link indices
    ///
    /// Populated: an empty vec is pushed per node by `add_node()`; link
    /// indices are appended by `add_link()`.
    pub outgoing_links: Vec<Vec<usize>>,

    /// Hot path: ordering setup, read in initialize_network().
    /// Adjacency list for O(1) link lookup.
    /// `incoming_links[node_idx]` = vec of link indices
    ///
    /// Populated: an empty vec is pushed per node by `add_node()`; link
    /// indices are appended by `add_link()`.
    pub incoming_links: Vec<Vec<usize>>,

    /// Hot path: flow phase, read in run_timestep().
    /// Pre-computed execution order, rebuilt by check_execution_order() at
    /// initialize_network() time; walked every step when there are no var blocks.
    ///
    /// Populated: computed by `check_execution_order()`, called from both
    /// `configure()` and `initialize_network()` (start of every `run()`).
    pub execution_order: Vec<usize>,

    /// Hot path: order phase, read in run_timestep().
    ///
    /// Populated: initialised by `initialize_network()` at the start of every
    /// `run()`, once the execution order is resolved.
    pub simple_ordering_system: SimpleNodewiseOrderingSystem,

    // ---- Cold path: load/configure/serialize time only, not touched per-step ----
    /// Pre-run input sources ([data] entries), one per file/alias. Each source
    /// holds its own data columns (see TimeseriesInputDefinition). Folds together
    /// what used to be three loosely-coupled fields (the per-column data, the
    /// file paths, and the alias map).
    ///
    /// Populated: during parsing, via `load_input_data()`/`declare_alias()`;
    /// a declared alias's data may later be supplied in-memory via `set_input()`.
    pub input_sources: Vec<TimeseriesInputDefinition>,

    /// Requested recorders from ini document.
    ///
    /// Populated: during parsing, from the `[outputs]` section.
    pub outputs: Vec<String>,

    /// Working directory for resolving relative file paths
    /// - Set to model file's directory when loaded from INI file
    /// - Set to current working directory when created programmatically
    ///
    /// Populated: at construction/load time; fixed thereafter.
    pub working_directory: PathBuf,

    /// Fast node name lookup (keys are lowercase for case-insensitive matching).
    ///
    /// Populated: alongside `nodes`, via `add_node()`.
    pub node_lookup: FxHashMap<String, usize>, // node_lookup[node_name.to_lowercase()] = node index

    /// INI document for round-trip serialization.
    ///
    /// Populated: at load time, when parsed from an INI file/string; `None`
    /// for models built programmatically.
    pub ini_document: Option<IniDocument>,

    /// Canonical render of the model exactly as loaded, captured before any
    /// programmatic change. Comparing this against a canonical render at save
    /// time identifies which sections actually changed, so a formatting-
    /// preserving save can re-emit only those (state-diff). `None` for models
    /// built programmatically, where there is nothing to preserve.
    ///
    /// Populated: at load time, alongside `ini_document`.
    pub baseline_canonical: Option<IniDocument>,
}

/// Outcome of looking a named output up in the data cache, see
/// `Model::lookup_output_series`.
enum OutputLookup<'a> {
    Populated(&'a Timeseries),
    WrongLength(usize),
    NotFound,
}

/// Why `Model::get_output_series` could not return a requested output.
#[derive(Debug, thiserror::Error)]
pub enum OutputLookupError {
    /// The requested name is not listed in the model's `[outputs]`.
    #[error("Output {0} undeclared in model [outputs]")]
    Undeclared(String),
    /// Declared, but no series is registered under that name at all.
    #[error("Output {0} not found")]
    NotFound(String),
    /// Declared and registered, but empty — nothing ever recorded to it.
    /// Reached when `[outputs]` names something no component produces, so the
    /// message points at the declaration rather than at the empty series.
    #[error(
        "Output {0} is declared in [outputs] but nothing recorded it \
         -- check the name matches a series the model actually produces"
    )]
    Unpopulated(String),
    /// Populated, but not `sim_nsteps` long. Unlike the variants above this
    /// is not a naming problem: reaching it after a successful run means a
    /// recorder wrote a different number of steps than the run took.
    #[error(
        "Output series {name} found but wrong length, length {actual} but expected {expected}"
    )]
    WrongLength {
        name: String,
        actual: usize,
        expected: usize,
    },
}

impl Model {
    pub fn new() -> Model {
        Model {
            configuration: Configuration::new(),
            input_sources: vec![],
            outputs: vec![],
            working_directory: std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")),
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
            input_sources: if configured {
                vec![]
            } else {
                self.input_sources.clone()
            },
            outputs: self.outputs.clone(),
            account_manager: self.account_manager.clone(),
            ras_systems: self.ras_systems.clone(),
            data_cache: self.data_cache.clone(),
            working_directory: self.working_directory.clone(),
            nodes: self.nodes.clone(),
            var_blocks: self.var_blocks.clone(),
            exec_items: self.exec_items.clone(),
            ras_slot: self.ras_slot.clone(),
            flow_exec_items: self.flow_exec_items.clone(),
            flow_has_var_blocks: self.flow_has_var_blocks,
            links: self.links.clone(),
            outgoing_links: self.outgoing_links.clone(),
            incoming_links: self.incoming_links.clone(),
            execution_order: self.execution_order.clone(),
            simple_ordering_system: self.simple_ordering_system.clone(),
            node_lookup: self.node_lookup.clone(),
            ini_document: None,
            baseline_canonical: None,
        }
    }

    /// Clone for an independent model and definition, everything post-parse,
    /// ready to configure and run.
    pub fn clone_for_new_model(&self) -> Model {
        Model {
            configuration: self.configuration.clone(),
            input_sources: self.input_sources.clone(),
            outputs: self.outputs.clone(),
            account_manager: self.account_manager.clone(),
            ras_systems: self.ras_systems.clone(),
            data_cache: self.data_cache.clone(),
            working_directory: self.working_directory.clone(),
            nodes: self.nodes.clone(),
            var_blocks: self.var_blocks.clone(),
            exec_items: self.exec_items.clone(),
            ras_slot: self.ras_slot.clone(),
            flow_exec_items: self.flow_exec_items.clone(),
            flow_has_var_blocks: self.flow_has_var_blocks,
            links: self.links.clone(),
            outgoing_links: self.outgoing_links.clone(),
            incoming_links: self.incoming_links.clone(),
            execution_order: vec![],
            simple_ordering_system: SimpleNodewiseOrderingSystem::new(),
            node_lookup: self.node_lookup.clone(),
            ini_document: self.ini_document.clone(),
            baseline_canonical: self.baseline_canonical.clone(),
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
    pub fn add_link(
        &mut self,
        from_node: usize,
        to_node: usize,
        from_outlet: u8,
        to_inlet: u8,
    ) -> usize {
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

    /// The structural half of [`configure`](Self::configure): register output
    /// series and initialise nodes (link wiring, data-reference resolution) --
    /// every check that needs no input *data*. Split out so `load`/`patch` can
    /// validate a model's shape without requiring its `[data]` to be supplied
    /// yet (e.g. a bare declaration awaiting `set_input()`). `configure()` runs
    /// this first, then the data-dependent steps that only `run()` needs.
    fn configure_model_structure(&mut self) -> Result<(), String> {
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

        //3) Validate link ordering (structural only, no input data required).
        //   Also re-checked in initialize_network() before every run(), since
        //   that call additionally resets per-run node/ordering state -- but
        //   without it here, structural link errors were invisible to callers
        //   that only ever reach configure_model_structure() (e.g. via
        //   validate_model_structure(), used by the Python API's
        //   from_file/from_model_string) and never call run().
        self.check_execution_order()?;

        Ok(())
    }

    /// Every `data.*` series must resolve to an actual input column. Internal
    /// building block for `configure()` and `validate_model_structure()`,
    /// which need different tolerance for aliases still awaiting `set_input()`:
    ///
    /// - `allow_pending_declarations = false` (used by `configure()`, after its
    ///   own "declared but not supplied" check has already rejected any input
    ///   that's still unfilled): a reference into an open declaration is an
    ///   error, same as any other unresolved reference.
    /// - `allow_pending_declarations = true` (used by `validate_model_structure()`,
    ///   which runs at load/patch time, before `set_input()` gets a chance to
    ///   run): a reference into a still-open declared-but-unsupplied alias is
    ///   skipped rather than reported as unknown -- it isn't a typo, just pending.
    fn validate_data_references(&self, allow_pending_declarations: bool) -> Result<(), String> {
        let pending_aliases: HashSet<String> = if allow_pending_declarations {
            self.input_sources
                .iter()
                .filter_map(|s| match s {
                    TimeseriesInputDefinition::Declaration { alias } => Some(alias.to_lowercase()),
                    _ => None,
                })
                .collect()
        } else {
            HashSet::new()
        };

        for idx in 0..self.data_cache.series.len() {
            let name = &self.data_cache.series_name[idx];
            if name.starts_with("data.") {
                let name_lower = name.to_lowercase();
                // segment 1 of "data.<alias>.<...>" is the alias/source name
                if let Some(alias) = name_lower.split('.').nth(1) {
                    if pending_aliases.contains(alias) {
                        continue;
                    }
                }
                let mut found = false;
                for ts in self.input_columns() {
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
        Ok(())
    }

    /// Model configuration needs to be done once, after loading the model, but not for every run.
    /// May be used to validate a model.
    pub fn configure(&mut self) -> Result<(), String> {
        //TASKS
        //1) Define output series
        //2) Nodes ask data_cache for idx of relevant data series for input
        //   (both delegated to configure_model_structure)
        self.configure_model_structure()?;

        //2b) Reject any input that was declared but never supplied. Nothing in
        //   the engine can supply a bare declaration, so an empty-valued
        //   [data] entry is a configure-time error rather than a downstream
        //   reference-resolution failure. Deferred until here (not part of the
        //   structural step) so load/patch can accept a not-yet-supplied
        //   declaration that set_input() will fill before run().
        let undeclared: Vec<&str> = self
            .input_sources
            .iter()
            .filter_map(|s| match s {
                TimeseriesInputDefinition::Declaration { alias } => Some(alias.as_str()),
                _ => None,
            })
            .collect();
        if !undeclared.is_empty() {
            return Err(format!(
                "input '{}' declared but not supplied",
                undeclared.join("', '")
            ));
        }

        //3) Read the input data from file
        // TODO: Here is where we would load data IF we wanted to read only the stuff that was required.
        //       E.g. if we were doing reload on run with a subset of the data, or

        //4) Determine simulation period
        //5) Supports sim period specified by user (done in the same step)
        self.auto_determine_simulation_period()?;

        //6) Load input data into the data_cache, properly aligned with simulation period.
        //   Iterate the input_sources field directly (source -> column) rather than
        //   the input_columns() helper: the inner fill takes &mut self.data_cache, and
        //   the disjoint-field borrow only holds when input_sources is borrowed as a
        //   direct field access.
        for source in &self.input_sources {
            for col in source.columns() {
                let input_ts = &col.timeseries;

                // Validate that input step size matches simulation step size
                if input_ts.step_size != self.configuration.sim_stepsize {
                    return Err(format!(
                        "Input timeseries '{}' has step_size {} but simulation requires step_size {}",
                        input_ts.name, input_ts.step_size, self.configuration.sim_stepsize
                    ));
                }

                // Calculate how many timesteps we need for the simulation
                let sim_steps = 1
                    + ((self.configuration.sim_end_timestamp
                        - self.configuration.sim_start_timestamp)
                        / self.configuration.sim_stepsize) as usize;

                //Fill any data that might be using the column name as a reference
                //Fill any data that might be using the column number as a reference
                //Also fill alias paths if they exist
                let mut paths_to_fill = vec![
                    col.full_colname_path.clone(),
                    col.full_colindex_path.clone(),
                ];
                if let Some(alias_colname) = &col.alias_colname_path {
                    paths_to_fill.push(alias_colname.clone());
                }
                if let Some(alias_colindex) = &col.alias_colindex_path {
                    paths_to_fill.push(alias_colindex.clone());
                }
                for full_path in paths_to_fill {
                    if let Some(idx) = self.data_cache.get_series_idx(&*full_path, false) {
                        self.data_cache.series[idx].values.clear();
                        self.data_cache.series[idx].start_timestamp =
                            self.configuration.sim_start_timestamp;
                        self.data_cache.series[idx].step_size = self.configuration.sim_stepsize;

                        // For each simulation timestep, find corresponding input value
                        for step in 0..sim_steps {
                            let sim_timestamp = self.configuration.sim_start_timestamp
                                + (step as u64 * self.configuration.sim_stepsize);

                            // Find value at this timestamp in input data
                            let value = if sim_timestamp >= input_ts.start_timestamp {
                                let steps_from_input_start =
                                    (sim_timestamp - input_ts.start_timestamp) / input_ts.step_size;
                                let input_idx = steps_from_input_start as usize;

                                if input_idx < input_ts.values.len() {
                                    input_ts.values[input_idx]
                                } else {
                                    f64::NAN // Beyond input data range
                                }
                            } else {
                                f64::NAN // Before input data starts
                            };

                            self.data_cache.series[idx].push_value(value);
                        }
                    }
                }
            }
        }
        self.data_cache.set_start_and_stepsize(
            self.configuration.sim_start_timestamp,
            self.configuration.sim_stepsize,
        );

        // Reserve capacity in every cache series for the whole simulation, so
        // per-step recording never reallocates. Capacity only: series lengths
        // remain the computed-this-far watermark that the fail-fast read
        // contract depends on (see DataCache::get_current_value).
        self.data_cache
            .reserve_all(self.configuration.sim_nsteps as usize);

        //7) Nodes ask data_cache for idx for modelled series they might be responsible for populating
        //TODO: I think this was already appropriately done in step 2.

        //8) Validate that all data.* references correspond to actual input file columns.
        //   This catches typos in non-critical data references that the existing
        //   validation in auto_determine_simulation_period() doesn't check.
        //   Note: We only check that the reference is valid (exists in an input file),
        //   not that it has values - non-critical data is allowed to have missing values.
        self.validate_data_references(false)?;

        // Return
        Ok(())
    }

    /// Validate a model's shape and its `data.*` references without requiring
    /// `[data]` to be fully supplied yet -- the load/patch-time counterpart
    /// to `configure()`, which additionally requires the simulation period and
    /// input data itself.
    pub fn validate_model_structure(&mut self) -> Result<(), String> {
        self.configure_model_structure()?;
        self.validate_data_references(true)?;
        Ok(())
    }

    pub fn run(&mut self) -> Result<(), String> {
        self.run_with_interrupt(|| false, None).map(|_| ())
    }

    pub fn run_with_interrupt<F>(
        &mut self,
        interrupt_check: F,
        mut progress_callback: Option<Box<dyn FnMut(u64, u64)>>,
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
        let total_steps = ((self.configuration.sim_end_timestamp
            - self.configuration.sim_start_timestamp)
            / self.configuration.sim_stepsize)
            + 1;

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
                    return Err(
                        "There is no critical input data. Please specify start and end."
                            .to_string(),
                    );
                }
            }
            match self.configuration.specified_sim_end_timestamp {
                Some(timestamp) => {
                    self.configuration.sim_end_timestamp = timestamp;
                }
                None => {
                    return Err(
                        "There is no critical input data. Please specify start and end."
                            .to_string(),
                    );
                }
            }
            if self.configuration.sim_start_timestamp > self.configuration.sim_end_timestamp {
                return Err("Specified start date is before end date.".to_string());
            }

            // Default to daily step size and calculate n_steps //TODO: make this customisable
            self.configuration.sim_stepsize = 86400;
            self.configuration.sim_nsteps = 1
                + (self.configuration.sim_end_timestamp - self.configuration.sim_start_timestamp)
                    / self.configuration.sim_stepsize;

            // Return
            return Ok(());
        }

        // Go through all the critical inputs and make sure they are all in the model.
        // As you find them, you can go ahead and update the mask of data availability.
        let mut critical_data_availability_mask: Option<Timeseries> = None;
        for ci in civ {
            let ci_lower = ci.to_lowercase();

            // Searching for timeseries that matches ci
            let mut found: bool = false;
            for ts in self.input_columns() {
                let matches = (ci_lower == ts.full_colindex_path)
                    || (ci_lower == ts.full_colname_path)
                    || (ts
                        .alias_colindex_path
                        .as_ref()
                        .map_or(false, |p| ci_lower == *p))
                    || (ts
                        .alias_colname_path
                        .as_ref()
                        .map_or(false, |p| ci_lower == *p));

                if matches {
                    found = true;

                    // This column is the critical input we're looking for. Its data
                    // limits the simulation period — this holds for both file-backed
                    // and in-memory sources, and every column reaching this loop
                    // carries real data (declarations have none), so it always
                    // contributes to the mask.
                    match critical_data_availability_mask {
                        None => {
                            //This is the first critical data source
                            critical_data_availability_mask = Some(ts.timeseries.clone());
                        }
                        Some(ref mut mask) => {
                            mask.mask_with(&ts.timeseries);
                        }
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
                if (timestamp < self.configuration.sim_start_timestamp)
                    || (timestamp > self.configuration.sim_end_timestamp)
                {
                    return Err("Specified start inconsistent with input data.".to_string());
                }
                self.configuration.sim_start_timestamp = timestamp;
                self.configuration.sim_nsteps = 1
                    + (self.configuration.sim_end_timestamp
                        - self.configuration.sim_start_timestamp)
                        / self.configuration.sim_stepsize;
            }
            None => {}
        }
        match self.configuration.specified_sim_end_timestamp {
            Some(timestamp) => {
                if (timestamp < self.configuration.sim_start_timestamp)
                    || (timestamp > self.configuration.sim_end_timestamp)
                {
                    return Err("Specified end inconsistent with input data.".to_string());
                }
                self.configuration.sim_end_timestamp = timestamp;
                self.configuration.sim_nsteps = 1
                    + (self.configuration.sim_end_timestamp
                        - self.configuration.sim_start_timestamp)
                        / self.configuration.sim_stepsize;
            }
            None => {}
        }

        // Return ok
        Ok(())
    }

    pub fn run_timestep(&mut self, _t: u64) {
        // Sizes first — static, so publishing before the [ras.*] loop makes
        // acc.<x>.size bare-readable everywhere in the step, announce-time
        // assessments included. No-op unless a size series is registered.
        self.account_manager.publish_sizes(&mut self.data_cache);

        // The ras slot: assessments (phase = ras var blocks) and policy
        // ([ras.*] sections) interleaved in file order at the top of the
        // step, before ordering and flow — today's orders and takes see
        // today's announcements (kalix-allocation-components.md §3.3), and
        // a section reads bare any assessment written above it.
        for i in 0..self.ras_slot.len() {
            match self.ras_slot[i] {
                RasSlotItem::VarBlock(vb_idx) => {
                    for def_idx in 0..self.var_blocks[vb_idx].defs.len() {
                        let value = self.var_blocks[vb_idx].defs[def_idx]
                            .input
                            .get_value(&mut self.data_cache);
                        let series_idx = self.var_blocks[vb_idx].defs[def_idx].series_idx;
                        self.data_cache.add_value_at_index(series_idx, value);
                    }
                }
                RasSlotItem::Ras(ras_idx) => {
                    self.ras_systems[ras_idx].run(&mut self.data_cache, &mut self.account_manager);
                }
            }
        }

        // Post-policy, pre-take snapshot: publishes acc.*.opening_balance and
        // resets the per-step debit tally
        self.account_manager.start_of_step(&mut self.data_cache);

        // Execute order phase
        set_context_phase(SimPhase::Ordering);
        self.simple_ordering_system.run_ordering_phase(
            &mut self.nodes,
            &mut self.data_cache,
            &mut self.account_manager,
        );

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
        if !self.flow_has_var_blocks {
            for &node_idx in &self.execution_order {
                // Set node context for error reporting (just stores the index)
                set_context_node(node_idx);

                // Run the node's flow phase
                self.nodes[node_idx]
                    .run_flow_phase(&mut self.data_cache, &mut self.account_manager);

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
            for &exec_item in &self.flow_exec_items {
                match exec_item {
                    ExecItem::Node(node_idx) => {
                        set_context_node(node_idx);
                        self.nodes[node_idx]
                            .run_flow_phase(&mut self.data_cache, &mut self.account_manager);
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
        // Partition the var blocks by phase (performance §3.5: decided here,
        // never per step): ras-phase blocks run in the ras slot at the top of
        // the step; the flow pass interleaves the rest at file position,
        // falling back to the plain node loop when none remain.
        self.flow_exec_items.clear();
        let mut ras_phase_blocks: Vec<usize> = Vec::new();
        for &item in &self.exec_items {
            match item {
                ExecItem::VarBlock(vb_idx) if self.var_blocks[vb_idx].phase == VarPhase::Ras => {
                    ras_phase_blocks.push(vb_idx);
                }
                other => self.flow_exec_items.push(other),
            }
        }
        self.flow_has_var_blocks = self.flow_exec_items.iter()
            .any(|i| matches!(i, ExecItem::VarBlock(_)));

        // The ras slot interleaves assessments and [ras.*] sections in file
        // order; the INI reader builds it. A programmatic construction that
        // bypassed the reader gets the natural default: assessments first,
        // then the sections, each in declaration order.
        if self.ras_slot.len() != ras_phase_blocks.len() + self.ras_systems.len() {
            self.ras_slot.clear();
            self.ras_slot.extend(ras_phase_blocks.iter().map(|&i| RasSlotItem::VarBlock(i)));
            self.ras_slot.extend((0..self.ras_systems.len()).map(RasSlotItem::Ras));
        }

        // Initialize the nodes and execution order
        self.initialize_nodes()?;
        self.check_execution_order()?;
        // TODO: why am I doing the execution order here in "initialize_network"? Cant we just do this once during configure?

        // Initialise the ordering system
        // TODO: I am doing this in "initialize_network" because it relies on execution order being resolved (which we do above).
        self.simple_ordering_system
            .initialize(&mut self.nodes, &self.links, &self.incoming_links)?;

        // Return
        Ok(())
    }

    pub fn empty_input_data(&mut self) {
        self.input_sources.clear();
    }

    /// Every input data column across all sources, flattened. The read-only
    /// convenience for consumers that don't care which source a column came
    /// from (validation, sim-period determination). Borrows all of `self`, so
    /// it can't be used where another `self` field is mutated in the same loop —
    /// iterate the `input_sources` field directly there.
    pub fn input_columns(&self) -> impl Iterator<Item = &TimeseriesInput> {
        self.input_sources.iter().flat_map(|s| s.columns())
    }

    /// Resolve a file path relative to the model's working directory.
    /// Supports absolute, relative, and trailhead (`^/`) paths.
    fn resolve_path(&self, path: &str) -> Result<PathBuf, String> {
        let mut kp = crate::io::kalix_path::KalixPath::parse(path)?;
        kp.resolve(&self.working_directory)?;
        Ok(kp.resolved)
    }

    /// Declare an input alias with no backing data (an empty-valued `[data]`
    /// entry). Nothing here supplies it, so `configure()` will reject it unless
    /// something fills it in first (e.g. `set_input()`).
    pub fn declare_alias(&mut self, alias: &str) {
        self.input_sources
            .push(TimeseriesInputDefinition::Declaration {
                alias: alias.to_string(),
            });
    }

    /// Load input data from a file and store it as a new input source.
    /// Responsible for remembering how the input was loaded (original path,
    /// alias) and for resolving the path. Construction of the per-column
    /// TimeseriesInputs is delegated to TimeseriesInput::load. Returns the
    /// number of columns loaded.
    pub fn load_input_data(
        &mut self,
        file_path: &str,
        alias: Option<&str>,
    ) -> Result<usize, KalixIoError> {
        // Resolve the path (supports absolute, relative, and trailhead paths)
        let resolved_path = self.resolve_path(file_path).map_err(KalixIoError::Io)?;

        // Load all the data using the resolved path
        let resolved_path_str = resolved_path
            .to_str()
            .ok_or_else(|| KalixIoError::Io(format!("Invalid path: {}", file_path)))?;
        let columns = TimeseriesInput::load(resolved_path_str, alias)?;
        let len = columns.len();

        // Remember the ORIGINAL file path (for serialization/display), not the
        // resolved one, so a round-trip preserves what the user wrote.
        self.input_sources
            .push(TimeseriesInputDefinition::FileDefinition {
                origin: SourceOrigin::File {
                    path: file_path.to_string(),
                    alias: alias.map(|a| a.to_string()),
                },
                columns,
            });
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

    /// Each node's mass balance per timestep (ML/timestep), as
    /// `(node_name, type_name, value)`, ordered by node type (preferred
    /// types first, then any others alphabetically) and by node name within
    /// a type. `generate_mass_balance_report` and the Python `_get_mass_balance`
    /// binding are both projections of this same ordered list, so the text
    /// report and the DataFrame can't drift apart.
    pub fn get_mass_balance_data(&self) -> Vec<(String, String, f64)> {
        let mut remaining_nodes: Vec<String> = self
            .nodes
            .iter()
            .map(|node| node.get_name().to_string())
            .collect();
        remaining_nodes.sort();

        let mut by_type: HashMap<String, Vec<(String, f64)>> = HashMap::new();
        for node_name in &remaining_nodes {
            let node = self.get_node(node_name).unwrap();
            let type_name = node.get_type_as_string();
            let mbal_per_timestep =
                node.get_mass_balance() / (self.configuration.sim_nsteps as f64);
            by_type
                .entry(type_name)
                .or_default()
                .push((node_name.clone(), mbal_per_timestep));
        }

        // Preferred type order first, then any other type names (e.g. newly
        // added ones) alphabetically, so nothing silently vanishes.
        let preferred_order = [
            "inflow",
            "sacramento",
            "gr4j",
            "regulated_user",
            "unregulated_user",
            "order_control",
            "loss",
            "storage",
            "routing",
            "splitter",
            "confluence",
            "gauge",
            "blackhole",
        ];

        let mut result = Vec::with_capacity(remaining_nodes.len());
        for type_name in preferred_order {
            if let Some(nodes) = by_type.remove(type_name) {
                for (name, value) in nodes {
                    result.push((name, type_name.to_string(), value));
                }
            }
        }
        let mut leftover_types: Vec<_> = by_type.into_iter().collect();
        leftover_types.sort_by(|a, b| a.0.cmp(&b.0));
        for (type_name, nodes) in leftover_types {
            for (name, value) in nodes {
                result.push((name, type_name.clone(), value));
            }
        }
        result
    }

    pub fn generate_mass_balance_report(&self) -> String {
        let data = self.get_mass_balance_data();

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

        // Section per node type, in `data`'s order; a type change in the
        // (already-ordered) list starts a new section.
        let mut current_type: Option<&str> = None;
        for (node_name, type_name, mbal_per_timestep) in &data {
            if current_type != Some(type_name.as_str()) {
                if current_type.is_some() {
                    report.push_str("\n");
                }
                report.push_str(format!("{} NODES\n", type_name.to_uppercase()).as_str());
                current_type = Some(type_name.as_str());
            }
            report.push_str(format!("  {}, {}\n", node_name, mbal_per_timestep).as_str());
        }
        if current_type.is_some() {
            report.push_str("\n");
        }

        // Sum in node-name order (not `data`'s type-grouped order): these
        // values nearly cancel to zero, so floating-point addition is not
        // associative here -- summation order must match the pre-refactor
        // behaviour (alphabetical by name) or the total's rounding residual
        // shifts and trips the regression suite's tight tolerance on this line.
        let mut by_name: Vec<&(String, String, f64)> = data.iter().collect();
        by_name.sort_by(|a, b| a.0.cmp(&b.0));
        let total_mbal: f64 = by_name.iter().map(|(_, _, v)| v).sum();

        // Write the total line
        report.push_str("----------------------------------\n");
        report.push_str(format!("TOTAL = {}\n", total_mbal).as_str());
        report.push_str("----------------------------------\n");

        // Return
        report
    }

    /// Prints all the input sources to the console, one column on each line.
    pub fn print_inputs(&self) {
        // Renders a source's identity line, e.g. "Source (file): climate.csv [alias: climate]".
        fn print_source_origin(kind: &str, origin: &SourceOrigin) {
            match origin {
                SourceOrigin::Alias(a) => println!("Source ({}): {}", kind, a),
                SourceOrigin::File { path, alias } => println!(
                    "Source ({}): {}{}",
                    kind,
                    path,
                    alias
                        .as_ref()
                        .map(|a| format!(" [alias: {}]", a))
                        .unwrap_or_default()
                ),
            }
        }
        for source in &self.input_sources {
            match source {
                TimeseriesInputDefinition::Declaration { alias } => {
                    println!("Source (declared, no data): {}", alias);
                }
                TimeseriesInputDefinition::FileDefinition { origin, .. } => {
                    print_source_origin("file", origin);
                }
                TimeseriesInputDefinition::InMemoryDefinition { origin, .. } => {
                    print_source_origin("in-memory", origin);
                }
            }
            let mut i = 0;
            for col in source.columns() {
                println!(
                    "  Input: {} {} {}",
                    i, col.full_colname_path, col.full_colindex_path
                );
                if let Some(alias) = &col.alias {
                    println!(
                        "    Alias: {} (also accessible as {} and {})",
                        alias,
                        col.alias_colname_path.as_ref().unwrap_or(&String::new()),
                        col.alias_colindex_path.as_ref().unwrap_or(&String::new())
                    );
                }
                i += 1;
            }
        }
    }

    /// Result of looking up a named output series in the data cache, checked
    /// against the simulation horizon (`sim_nsteps`). The single place that
    /// defines "populated" for an output — both `collect_output_series` and
    /// `get_output_series` go through this so the rule can't drift between them.
    fn lookup_output_series(&self, name: &str, expected_len: usize) -> OutputLookup<'_> {
        match self.data_cache.get_existing_series_idx(name) {
            None => OutputLookup::NotFound,
            Some(idx) => {
                let ts = &self.data_cache.series[idx];
                if ts.values.len() == expected_len {
                    OutputLookup::Populated(ts)
                } else {
                    OutputLookup::WrongLength(ts.values.len())
                }
            }
        }
    }

    /// Collects the output series that are valid to export — those whose length matches the
    /// simulation horizon (`sim_nsteps`). An output declared in `[outputs]` but never
    /// populated by any component (e.g. an invalid recorder) is left empty in the data cache;
    /// such series are silently omitted so that one bad recorder does not fail the whole
    /// export. Returned in the order the outputs are declared.
    pub(crate) fn collect_output_series(&self) -> Vec<&Timeseries> {
        let expected_len = self.configuration.sim_nsteps as usize;
        self.outputs
            .iter()
            .filter_map(
                |output_name| match self.lookup_output_series(output_name, expected_len) {
                    OutputLookup::Populated(ts) => Some(ts),
                    OutputLookup::NotFound | OutputLookup::WrongLength(_) => None,
                },
            )
            .collect()
    }

    /// Builds a zero-filled `Timeseries` of the simulation's length, used as
    /// the `missing_ok` stand-in for a requested output that is undeclared,
    /// not found, or the wrong length. Named with whatever casing the caller
    /// originally requested, since there may be no canonical declared name
    /// to fall back on (e.g. an undeclared name).
    fn zero_output_series(&self, requested_name: &str, expected_len: usize) -> Timeseries {
        let mut ts = Timeseries::new(self.configuration.sim_stepsize);
        ts.start_timestamp = self.configuration.sim_start_timestamp;
        ts.name = requested_name.to_string();
        ts.values = vec![0.0; expected_len];
        ts
    }

    /// Output series to export, in declaration order.
    ///
    /// `names = None` selects all declared outputs, silently omitting any that
    /// are unpopulated (see `collect_output_series`). Named series that are
    /// undeclared or unpopulated are an error, unless `missing_ok` is `true`,
    /// in which case such a requested name is instead returned as a
    /// zero-filled series of the simulation's length (see
    /// `zero_output_series`) rather than failing the whole call. Name
    /// matching is case-insensitive throughout. Requesting the same output
    /// more than once is not an error - the returned vector has exactly one
    /// entry per requested name, in request order (i.e. it is never
    /// deduplicated).
    ///
    /// Each returned `Timeseries` carries its *canonical stored* name (see
    /// `Timeseries::name`) - the casing it was registered/declared under -
    /// which may differ from the casing a caller requested it with. Zero-fill
    /// stand-ins carry the requested casing instead, since there may be no
    /// canonical name to use.
    ///
    /// Used for Python bindings.
    pub fn get_output_series(
        &self,
        output_names: Option<Vec<String>>,
        missing_ok: bool,
    ) -> Result<Vec<Timeseries>, OutputLookupError> {
        let Some(vec_names) = output_names else {
            return Ok(self.collect_output_series().into_iter().cloned().collect());
        };

        let expected_len = self.configuration.sim_nsteps as usize;
        let names_hash: HashSet<String> =
            HashSet::from_iter(self.outputs.iter().map(|x| x.to_lowercase()));
        let mut vec_ts: Vec<Timeseries> = Vec::with_capacity(vec_names.len());
        for output_name in vec_names {
            if !names_hash.contains(&output_name.to_lowercase()) {
                if missing_ok {
                    vec_ts.push(self.zero_output_series(&output_name, expected_len));
                    continue;
                }
                return Err(OutputLookupError::Undeclared(output_name));
            }
            match self.lookup_output_series(&output_name, expected_len) {
                OutputLookup::Populated(ts) => vec_ts.push(ts.clone()),
                OutputLookup::WrongLength(len) => {
                    if missing_ok {
                        vec_ts.push(self.zero_output_series(&output_name, expected_len));
                        continue;
                    }
                    // Empty is the common, user-caused case (an `[outputs]`
                    // entry naming a series nothing produces); any other
                    // length is a recorder disagreeing with the run.
                    return Err(if len == 0 {
                        OutputLookupError::Unpopulated(output_name)
                    } else {
                        OutputLookupError::WrongLength {
                            name: output_name,
                            actual: len,
                            expected: expected_len,
                        }
                    });
                }
                OutputLookup::NotFound => {
                    if missing_ok {
                        vec_ts.push(self.zero_output_series(&output_name, expected_len));
                        continue;
                    }
                    return Err(OutputLookupError::NotFound(output_name));
                }
            }
        }
        Ok(vec_ts)
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
            write_ts(filename, vec_ts).map_err(|_| format!("Could not write file {}", filename))
        }
    }

    /// Update a node's parameter in the attached INI document
    /// This is typically used after parameter optimisation
    pub fn update_node_parameter_in_ini(
        &mut self,
        node_name: &str,
        param_name: &str,
        value: &str,
    ) -> Result<(), String> {
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
