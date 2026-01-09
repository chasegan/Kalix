use crate::data_management::constants_cache::ConstantsCache;
use crate::tid::utils::{u64_to_year_month_day_and_seconds};
use crate::timeseries::Timeseries;

#[derive(Default)]
#[derive(Clone)]
pub struct DataCache {
    pub series: Vec<Timeseries>,
    pub series_name: Vec<String>,
    pub is_critical: Vec<bool>,
    pub current_step: usize,
    pub start_timestamp: u64,
    pub current_timestamp: u64,
    pub step_size: u64,

    // Constants cache
    pub constants: ConstantsCache,

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
        for i in 0..self.series_name.len() {
            if self.series_name[i] == name {
                if flag_as_critical { self.is_critical[i] = true; }
                return Some(i);
            }
        }
        None
    }


    /*
    Looks for an exact match on the series name and returns the index of the matching series.
    Returns None if no match is found.
    */
    pub fn get_existing_series_idx(&self, name: &str) -> Option<usize> {
        if name.is_empty() {
            return None;
        }
        for i in 0..self.series_name.len() {
            if self.series_name[i] == name {
                return Some(i);
            }
        }
        None
    }


    /*
    */
    pub fn get_or_add_new_series(&mut self, name: &str, flag_as_critical: bool) -> usize {

        if let Some(idx) = self.get_series_idx(&name, flag_as_critical) {
            idx
        } else {
            //Prep a new timeseries
            let mut answer = Timeseries::new_daily();
            answer.name = name.to_string();
            answer.start_timestamp = self.start_timestamp;

            //Add it and return the idx
            let idx = self.series.len();
            self.series.push(answer);
            self.series_name.push(name.to_string());
            self.is_critical.push(flag_as_critical);
            idx
        }
    }


    /*
     */
    pub fn add_series(&mut self, name: &str, series: Timeseries) {
        self.series.push(series);
        self.series_name.push(name.to_string());
        self.is_critical.push(false);
    }


    /*
    Add a new result value to a given recorder (specified by index)
     */
    pub  fn add_value_at_index(&mut self, series_idx: usize, value: f64) {
        //Make sure the series has enough values
        //TODO: this is dirty. We shouldn't have to check this every single time.
        while self.series[series_idx].len() <= self.current_step {
            self.series[series_idx].push_value(f64::NAN); //Extend the series by adding a NAN
        }

        //Set the value
        self.series[series_idx].values[self.current_step] = value;
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
    /// # Safety & Performance
    ///
    /// This method does NOT perform bounds checking for maximum performance in the hot path.
    /// It will panic if:
    /// - `series_idx` is out of bounds
    /// - `current_step` exceeds the series length
    ///
    /// Use only indices obtained from `get_or_add_new_series()` and ensure the current
    /// timestep is valid before calling.
    pub fn get_current_value(&self, series_idx: usize) -> f64 {
        self.series[series_idx].values[self.current_step]
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
            println!("{}, {}, {}:{}", self.series_name[i], start_date, self.series[i].timestamps.len(), self.series[i].values.len());
        }
    }


    /*
    Moved this code to own function. This seems a bit weird and dirty.
     */
    fn get_start_date(series: &Timeseries) -> String {
        if series.len() > 0 {
            series.timestamps[0].to_string()
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