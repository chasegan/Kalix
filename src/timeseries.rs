// Design concepts:
// -----------------
// This is a played timeseries so the model can tell the value to progress (mutable;
// we copy the next value into a cache property), and then all the nodes using the value can get it
// from there (maybe using immutable refs).

#[derive(Clone)]
#[derive(Default)]
pub struct Timeseries {
    //Name, start timestamp, and step_size
    pub name: String,              //The name of the timeseries
    pub start_timestamp: u64,      //The timestamp to be used for the first value
    pub step_size: u64,            //The amount of time between consecutive timestamps. (Notionally in seconds).

    //Vectors
    pub values: Vec<f64>,          //All the values
    pub timestamps: Vec<u64>,      //All the timestamps in Unix timestamps offset from i64 to u64

    //Player
    pub next_played_index: usize,  //The index of next value being 'played' by the Timeseries
    pub current_played_value: f64, //The value being 'played'
}

impl Timeseries {
    pub fn new_daily() -> Timeseries {
        Timeseries {
            name: "Unnamed timeseries".to_string(),
            start_timestamp: 0,
            step_size: 86400,
            values: Vec::with_capacity(64_000usize),
            timestamps: Vec::with_capacity(64_000usize),
            next_played_index: 0,
            current_played_value: f64::NAN,
        }
    }


    /*
    Restarts the in-built player by setting current_value=NAN, and next_played_index=0
     */
    pub fn restart_player(&mut self) -> &mut Timeseries {
        self.next_played_index = 0;
        self.current_played_value = f64::NAN;
        self
    }


    /*
    Adds a new value to the end of the Timeseries. Useful for building a timeseries. Method accepts
    a timestamp u64.
    */
    pub fn push(&mut self, timestamp: u64, value: f64) {
        self.timestamps.push(timestamp);
        self.values.push(value)
    }


    /*
    Adds a new value to the end of the Timeseries. Automatically determines the next timestamp
    based on previous one and the step_size (or uses start_timestamp if there are no timestamps yet).
     */
    pub fn push_value(&mut self, value: f64) {
        let mut new_timestamp = self.start_timestamp;
        let len = self.values.len();
        if len > 0 {
            new_timestamp = self.timestamps[len - 1] + self.step_size;
        }
        self.push(new_timestamp, value);
    }


    /*
    Moves the Timeseries internal player to the next value.
     */
    pub fn next(&mut self) -> usize {
        if self.next_played_index < self.values.len() {
            self.current_played_value = self.values[self.next_played_index];
            self.next_played_index += 1;
        } else {
            if self.values.len() > 0 {
                self.current_played_value = self.values[0usize];
                self.next_played_index = 1usize;
            } else {
                self.current_played_value = f64::NAN;
                self.next_played_index = 0usize;
            }
        }
        self.next_played_index
    }


    pub fn print(&self) {
        let name = &self.name;
        let n = self.values.len();
        println!("Name: {name}");
        println!("Points: {n}");
        println!(
            "Mean:{}, Sum: {}, Nonzero: {}, Finite: {}",
            self.mean(),
            self.sum(),
            self.count_nonzero(),
            self.count_finite(),
        );
        const MAX_PRINTING_POINTS: usize = 24;
        if n <= MAX_PRINTING_POINTS {
            for i in 0..n {
                println!("  {}: {}: {}", i, self.timestamps[i], self.values[i]);
            }
        } else {
            for i in 0..(MAX_PRINTING_POINTS - 1) {
                println!("  {}: {}: {}", i, self.timestamps[i], self.values[i]);
            }
            println!("  ..");
            println!(
                "  {}: {}: {}",
                n - 1,
                self.timestamps[n - 1],
                self.values[n - 1]
            );
        }
    }

    /*
    Returns the sum of all values in the timeseries, including any non-finite values.
     */
    pub fn sum(&self) -> f64 {
        self.values.iter().sum()
    }

    /*
    Returns the mean of all values in the timeseries, including any non-finite values.
     */
    pub fn mean(&self) -> f64 {
        self.sum() / (self.values.len() as f64)
    }

    /*
    Returns the number of values in the timeseries.
     */
    pub fn len(&self) -> usize {
        self.values.len()
    }

    /*
    Counts the non-missing (non-NaN) values in a timeseries.
    */
    pub fn count_not_missing(&self) -> usize {
        self.values.iter().filter(|&x| !f64::is_nan(*x)).count()
    }

    /*
    Counts the finite values in a timeseries. This means all values that are not NaN, and not
    infinite.
    */
    pub fn count_finite(&self) -> usize {
        self.values.iter().filter(|&x| f64::is_finite(*x)).count()
    }

    /*
    Counts the nonzero values in a timeseries.
    */
    pub fn count_nonzero(&self) -> usize {
        self.values.iter().filter(|&x| (*x) != 0.0 && !f64::is_nan(*x)).count()
    }
}

/// Create a new vector of a given value
/// TODO: there is probably a better way than this
pub fn new_vector(value: f64, length: i32) -> Vec<f64> {
    let mut answer = Vec::with_capacity(length as usize);
    for _i in 0..length {
        answer.push(value);
    }
    answer
}
