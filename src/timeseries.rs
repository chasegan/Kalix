// Design concepts:
// -----------------
// This is a played timeseries so the model can tell the value to progress (mutable;
// we copy the next value into a cache property), and then all the nodes using the value can get it
// from there (maybe using immutable refs).

use crate::numerical::mathfn::u64_subtraction;

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
        let len = self.values.len();
        if len == 0 {
            self.push(self.start_timestamp, value);
        } else {
            self.push(self.timestamps[len - 1] + self.step_size, value);
        };
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
    Removes data on timestamps when the
     */
    pub fn mask_with(&mut self, mask: &Self) -> &mut Self {

        //TODO: is there a sensible way to apply a mask with a different step_size?
        if self.step_size != mask.step_size {
            panic!("Base data has step_size {} but mask has step_size {}. These must be the same.",
                   self.step_size, mask.step_size);
        }

        if mask.len() == 0 {
            //Clear all the data.
            self.set_all_values_to(f64::NAN);
        } else {
            //Get the index offset. I.e. how many steps is self[0] ahead of mask[0]?
            let mask_offset = u64_subtraction(self.start_timestamp / self.step_size,
                                              mask.start_timestamp / self.step_size);

            //Now for each element of self, set self.value=NAN if the mask is NAN.
            for i_self in 0..self.len() {
                let i_mask = (i_self as i64) + mask_offset;
                if i_mask < 0 {
                    // Before mask start. Implied value is f64::NAN.
                    self.values[i_self] = f64::NAN;
                } else {
                    let i_mask_usize = i_mask as usize;
                    if i_mask_usize >= mask.len() {
                        // Beyond mask end. Implied value is f64::NAN.
                        self.values[i_self] = f64::NAN;
                    } else {
                        // In range of mask. Check if value is NAN.
                        if mask.values[i_mask_usize].is_nan() {
                            self.values[i_self] = f64::NAN;
                        }
                    }
                }
            }
        }

        //Return updated self
        self
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
    Returns the standard deviation of values in the timeseries, including any non-finite values.
     */
    pub fn std_dev(&self) -> f64 {
        let n = self.values.len() as f64;
        let mean = self.mean();
        let variance = self.values.iter()
            .map(|x| (x - mean).powi(2))
            .sum::<f64>() / n;
        variance.sqrt()
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

    /*
    Set all values to given f64.
    */
    pub fn set_all_values_to(&mut self, new_value: f64) -> &mut Self {
        for i in 0..self.values.len() {
            self.values[i] = new_value;
        }
        self
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
