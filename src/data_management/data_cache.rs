use rustc_hash::FxHashMap;
use crate::data_management::constants_cache::ConstantsCache;
use crate::numerical::lookup_table::TableRegistry;
use crate::tid::utils::{u64_to_iso_datetime_string, u64_to_year_month_day_and_seconds};
use crate::timeseries::Timeseries;

#[derive(Default)]
#[derive(Clone)]
pub struct DataCache {
    pub series: Vec<Timeseries>,
    pub series_name: Vec<String>,
    pub is_critical: Vec<bool>,

    /// Case-insensitive name -> index lookup (keys lowercased). Mirrors
    /// series_name so that series resolution - which nodes do for every
    /// recorder on every initialise, and the optimiser once per evaluation -
    /// is O(1) instead of a linear scan over every series name.
    name_lookup: FxHashMap<String, usize>,
    pub current_step: usize,
    pub start_timestamp: u64,
    pub current_timestamp: u64,
    pub step_size: u64,

    // Constants cache
    pub constants: ConstantsCache,

    // Named lookup tables ([table.*] sections), resolved by expressions at
    // model load. Arc-shared, so cloning the cache is cheap.
    pub tables: TableRegistry,

    // These vars for model components (incl nodes) to use if they need to know the date
    timestamp_year: i32,
    timestamp_month: u32,
    timestamp_day: u32,
    timestamp_seconds: u32, //seconds past midnight
}


/*
==========
DATA CACHE
==========

The data cache is an amalgamation of the input data, result manager and function variable manager.

Everything in the data cache is a timeseries. Every series has equivalent accessibility regardless
of where the data comes from (a timeseries input, model result, or function result). Nodes access
series based on the name during initialisation, and subsequently by integer. An optional offset
specifies the temporal offset, if you don’t want today’s value. Anything more complex (e.g. maximum
value over last 365 days) needs to be done using a function.

==========================
About dates and timestamps
==========================

For the benefit of simplicity and speed, I think I want all series in the data_cache to have the
same shape. This means they all have the same start date, and length. By having it like this, I
don’t need to worry about the timestamps for each series. The data cache can know what index we are
up to simply counting the timesteps.

One implication of this is that loading data in is probably a two-step process:
   (1) Read the data files into timeseries and then,
   (2) copy the relevant values into the data cache.
That is fine as an initial implementation.

The data_cache should keep track of the number of model steps that have passed, and the timestamp
(integer representation). Nodes can get these values from the data_cache if they ever need them.

==========
Data names
==========

The names of series in the data cache must be unique. These names (maybe I should refer to them as
“data paths”) may only contain alphanumeric chars (a-z and 0-9), periods (.), and underscores
(_). They must begin with an alphabetical character (a-z) and must not have multiple periods in
succession (e.g. ..). This should give us the ability to have conceptual folders using periods as
the delimiter.

Note: we should be similarly restrictive with node names, disallowing "." so that node names may be
used within a data path without interfering with the syntax for folder structure.
 */
impl DataCache {

    /*
    Constructor
    */
    pub fn new() -> DataCache {
        DataCache {
            constants: ConstantsCache::new(),
            ..Default::default()
        }
    }


    /*
    Delete all recorders (including data) from the result manager, and set the starting
    date for 
     */
    pub fn initialize(&mut self, start_timestamp: u64) {
        self.series = vec![];
        self.series_name = vec![];
        self.is_critical = vec![];
        self.name_lookup.clear();

        // Set up the timing
        self.start_timestamp = start_timestamp;
        self.set_current_step(0); //Reset the step counter to 0

        // Validate the constants cache
        if let Err(s) = self.constants.assert_all_constants_have_assigned_values() {
            panic!("{}", s);
        }
    }


    /*
    This updates:
      - current_timestamp on the basis of the start_timestamp, current_step, and step_size
      - timestamp_year, timestamp_month, timestamp_day, timestamp_seconds
     */
    fn update_current_timestamp(&mut self) {
        self.current_timestamp = self.start_timestamp + self.step_size * self.current_step as u64;
        (self.timestamp_year, self.timestamp_month, self.timestamp_day, self.timestamp_seconds) =
            u64_to_year_month_day_and_seconds(self.current_timestamp)
    }


    /*
    Set the step counter.
    This updates:
      - current_step which counts the model steps (from 0)
      - current_timestamp
     */
    pub fn set_current_step(&mut self, value: usize) {
        self.current_step = value;
        self.update_current_timestamp();
    }


    /*
     */
    pub fn set_start_and_stepsize(&mut self, start_timestep: u64, stepsize: u64) {
        self.start_timestamp = start_timestep;
        self.step_size = stepsize;
        self.update_current_timestamp();

        // All series within the data cache are also going to have the same start and stepsize
        for ts in &mut self.series {
            ts.start_timestamp = start_timestep;
            ts.step_size = stepsize;
        }
    }


    /*
    Gets the current calendar year
     */
    pub fn get_timestamp_year(&self) -> i32 {
        self.timestamp_year
    }


    /*
    Gets the current month 1-12
    */
    pub fn get_timestamp_month(&self) -> u32 {
        self.timestamp_month
    }


    /*
    Gets the current day of the month 1-31
     */
    pub fn get_timestamp_day(&self) -> u32 {
        self.timestamp_day
    }


    /*
    Gets the current number of seconds since midnight
     */
    pub fn get_timestamp_seconds(&self) -> u32 {
        self.timestamp_seconds
    }


    /// Gets the current day of year (1-366)
    ///
    /// Accounts for leap years when calculating the day of year.
    pub fn get_day_of_year(&self) -> u32 {
        // Cumulative days at the start of each month (0-indexed month)
        // Index 0 = days before Jan, Index 1 = days before Feb, etc.
        const DAYS_BEFORE_MONTH: [u32; 12] = [0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334];

        let month_idx = (self.timestamp_month - 1) as usize;
        let mut day_of_year = DAYS_BEFORE_MONTH[month_idx] + self.timestamp_day;

        // Add 1 for leap year if we're past February
        if self.timestamp_month > 2 && Self::is_leap_year(self.timestamp_year) {
            day_of_year += 1;
        }

        day_of_year
    }

    /// Check if a year is a leap year
    fn is_leap_year(year: i32) -> bool {
        (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0)
    }


    /*
    Increase the current step by +1.
    This also updates the data_cache timestamp values.
     */
    pub fn increment_current_step(&mut self) {
        self.set_current_step(self.current_step + 1);
    }


    /*
    Looks for an exact match on the series name and returns the index of the matching series.
    Returns None if no match is found.
    */
    pub fn get_series_idx(&mut self, name: &str, flag_as_critical: bool) -> Option<usize> {
        if name.is_empty() {
            return None;
        }
        let idx = *self.name_lookup.get(&name.to_lowercase())?;
        if flag_as_critical { self.is_critical[idx] = true; }
        Some(idx)
    }


    /*
    Looks for an exact match on the series name and returns the index of the matching series.
    Returns None if no match is found.
    */
    pub fn get_existing_series_idx(&self, name: &str) -> Option<usize> {
        if name.is_empty() {
            return None;
        }
        self.name_lookup.get(&name.to_lowercase()).copied()
    }


    /*
    */
    pub fn get_or_add_new_series(&mut self, name: &str, flag_as_critical: bool) -> usize {

        if let Some(idx) = self.get_series_idx(&name, flag_as_critical) {
            idx
        } else {
            // Prep a new timeseries that inherits the data_cache's step_size. During the
            // pre-config phase of model initialisation (before set_start_and_stepsize() is
            // called) self.step_size is 0; that's fine because set_start_and_stepsize()
            // sweeps through and fixes every series's step_size when it eventually runs.
            // For series added after configuration, this ensures they pick up the correct
            // (possibly non-daily) step_size instead of silently defaulting to 86400s.
            let mut answer = Timeseries::new(self.step_size);
            answer.name = name.to_string();
            answer.start_timestamp = self.start_timestamp;
            answer.step_size = self.step_size;

            //Add it and return the idx
            let idx = self.series.len();
            self.series.push(answer);
            self.series_name.push(name.to_string());
            self.is_critical.push(flag_as_critical);
            self.name_lookup.insert(name.to_lowercase(), idx);
            idx
        }
    }


    /// Update the display name of an existing series (e.g. to match the casing
    /// specified by the modeller in the [outputs] section).
    pub fn update_series_name(&mut self, idx: usize, name: &str) {
        let old_key = self.series_name[idx].to_lowercase();
        self.name_lookup.remove(&old_key);
        self.name_lookup.insert(name.to_lowercase(), idx);
        self.series_name[idx] = name.to_string();
        self.series[idx].name = name.to_string();
    }


    /*
     */
    pub fn add_series(&mut self, name: &str, series: Timeseries) {
        let idx = self.series.len();
        self.series.push(series);
        self.series_name.push(name.to_string());
        self.is_critical.push(false);
        // First-registered name wins on lookup, matching the old linear scan.
        self.name_lookup.entry(name.to_lowercase()).or_insert(idx);
    }


    /// Reserve capacity in every series for a simulation of `n_steps` steps.
    ///
    /// Called once from `Model::configure` when the simulation length becomes
    /// known, so that per-step recording (`add_value_at_index`) never
    /// reallocates. Capacity only — lengths are untouched, because a series'
    /// length is the watermark of how far the simulation has computed it (the
    /// fail-fast contract in `get_current_value` depends on that).
    pub fn reserve_all(&mut self, n_steps: usize) {
        for ts in &mut self.series {
            ts.values.reserve_exact(n_steps.saturating_sub(ts.values.len()));
        }
    }

    /*
    Add a new result value to a given recorder (specified by index)
     */
    pub fn add_value_at_index(&mut self, series_idx: usize, value: f64) {
        let series = &mut self.series[series_idx];
        let len = series.len();
        if len == self.current_step {
            // Common case: recording the next step, into preallocated capacity.
            series.push_value(value);
        } else if len > self.current_step {
            // Re-recording a step (e.g. a re-run over an existing cache).
            series.values[self.current_step] = value;
        } else {
            // A writer skipped some steps: pad the gap with NaN.
            while series.len() < self.current_step {
                series.push_value(f64::NAN);
            }
            series.push_value(value);
        }
    }


    /// Get the current value of a data series at the current timestep.
    ///
    /// # Arguments
    ///
    /// * `series_idx` - The index of the series (obtained from `get_or_add_new_series`)
    ///
    /// # Returns
    ///
    /// The value at the current timestep.
    ///
    /// # Fail-fast contract
    ///
    /// A series' length is the watermark of how far the simulation has computed
    /// it. Reading a value that has not been written yet — an expression
    /// referencing something computed later in the timestep — panics with a
    /// message naming the series, so modellers learn immediately that the
    /// reference is illegal. The check is a single always-false-predicted
    /// branch on the hot path; the panic path is compiled cold.
    pub fn get_current_value(&self, series_idx: usize) -> f64 {
        match self.series[series_idx].values.get(self.current_step) {
            Some(&value) => value,
            None => self.unwritten_value_panic(series_idx),
        }
    }

    /// Cold panic path for `get_current_value`: only ever reached on a run
    /// that is already failing, so the message can afford to be helpful.
    #[cold]
    #[inline(never)]
    fn unwritten_value_panic(&self, series_idx: usize) -> ! {
        let name = &self.series_name[series_idx];
        panic!(
            "Expression references '{}', which has no value yet at {} — it is \
             computed later in the timestep. To use the previous timestep's \
             value, add an offset, e.g. '{}[-1, 0.0]'.",
            name,
            u64_to_iso_datetime_string(self.current_timestamp),
            name
        )
    }

    /// Get a value from a data series with a temporal offset.
    ///
    /// # Arguments
    ///
    /// * `series_idx` - The index of the series (obtained from `get_or_add_new_series`)
    /// * `offset` - Temporal offset: -ve = past, 0 = current, +ve = future
    ///
    /// # Returns
    ///
    /// The value at (current_step + offset). Returns NaN if the target step is
    /// outside the available data range.
    ///
    /// # Performance
    ///
    /// Optimised for the hot path with minimal overhead.
    pub fn get_value_with_offset(&self, series_idx: usize, offset: isize) -> f64 {
        let target_step = self.current_step as isize + offset;
        if target_step < 0 || target_step as usize >= self.series[series_idx].len() {
            f64::NAN
        } else {
            self.series[series_idx].values[target_step as usize]
        }
    }

    /// Get a value from a data series with a temporal offset and user-specified default.
    ///
    /// # Arguments
    ///
    /// * `series_idx` - The index of the series
    /// * `offset` - Temporal offset: -ve = past, 0 = current, +ve = future
    /// * `default_value` - Value to return when target step is outside available data range
    ///
    /// # Performance
    ///
    /// Optimised for the hot path with minimal overhead:
    /// - Single comparison
    /// - Direct array access
    #[inline]
    pub fn get_value_with_offset_or_default(&self, series_idx: usize, offset: isize, default_value: f64) -> f64 {
        let target_step = self.current_step as isize + offset;
        if target_step < 0 || target_step as usize >= self.series[series_idx].len() {
            default_value
        } else {
            self.series[series_idx].values[target_step as usize]
        }
    }



    /*
     */
    pub fn get_critical_input_names(&self) -> Vec<&str> {
        let mut critical_inputs: Vec<&str> = vec![];
        for idx in 0..self.series.len() {
            if self.is_critical[idx] {
                let name = self.series[idx].name.as_str();
                critical_inputs.push(name);
            }
        }
        critical_inputs
    }


    /*
     */
    pub fn print(&self) {
        println!("Data cache has {} series elements", self.series.len());
        println!("Current step: {}", self.current_step);
        println!("Start timestamp: {}", self.start_timestamp);
        for i in 0..self.series.len() {
            let start_date = Self::get_start_date(&self.series[i]);
            println!("{}, {}, {}", self.series_name[i], start_date, self.series[i].values.len());
        }
    }


    /*
    Moved this code to own function. This seems a bit weird and dirty.
     */
    fn get_start_date(series: &Timeseries) -> String {
        if series.len() > 0 {
            series.timestamp_at(0).to_string()
        } else {
            String::from("-")
        }
    }
}

// ============================================================================
// OptimisableComponent Implementation - for optimising constants
// ============================================================================

use crate::numerical::opt::optimisable_component::OptimisableComponent;

impl OptimisableComponent for DataCache {
    fn set_param(&mut self, name: &str, value: f64) -> Result<(), String> {
        // Set constant value by name
        self.constants.set_value(name, value);
        Ok(())
    }

    fn get_param(&self, name: &str) -> Result<f64, String> {
        // Get constant value by name
        self.constants.get_value_by_name(name)
    }

    fn list_params(&self) -> Vec<String> {
        // List all constant names
        self.constants.list_names()
    }
}