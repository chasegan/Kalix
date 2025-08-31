use uuid::Uuid;
use crate::timeseries::Timeseries;

#[derive(Default)]
#[derive(Clone)]
pub struct DataCache {
    pub series: Vec<Timeseries>,
    pub series_name: Vec<String>,
    pub is_critical: Vec<bool>,
    pub current_step: usize,
}


impl DataCache {

    /*
    Constructor
    */
    pub fn new() -> DataCache {
        DataCache {
            ..Default::default()
        }
    }
    
    /*
    Delete all recorders (including data) from the result manager, and set the starting
    date for 
     */
    pub fn initialize(&mut self) {
        self.series = vec![];
        self.series_name = vec![];
        self.is_critical = vec![];
        self.set_current_step(0); //Reset the date to 0
    }


    /*
     */
    pub fn set_current_step(&mut self, value: usize) {
        self.current_step = value;
    }


    /*
     */
    pub fn increment_current_step(&mut self) {
        self.set_current_step(self.current_step + 1);
    }

    
    /*
    Add a recorder. The function returns an usize, which can be used for quick 
    access in the future.
     */
    pub fn add_recorder(&mut self, node_id: Uuid, description: &str) -> usize {
        let next_index = self.series.len();
        
        //Prep a new timeseries
        let mut answer = Timeseries::new_daily();
        let d = format!("{}_{}", node_id, description);
        answer.name = d.clone();
        
        //Add the new timeseries and return the index
        self.series.push(answer);
        self.series_name.push(d);
        self.is_critical.push(false);
        next_index
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


    /*

     */
    pub fn get_current_value(&self, series_idx: usize) -> f64 {
        let answer = self.series[series_idx].values[self.current_step];
        answer
    }


    /*
     */
    pub fn print(&self) {
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