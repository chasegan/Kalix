use crate::timeseries::Timeseries;

pub struct UHDyn {
    kernel: Vec<f64>,
    storage: Vec<f64>,
}

impl UHDyn {
    pub fn new(length: i32) -> UHDyn {
        let l = length as usize;
        let mut answer = UHDyn {
            kernel: Vec::with_capacity(l),
            storage: Vec::with_capacity(l),
        };
        for _ in 0..length {
            answer.kernel.push(0.0);
            answer.storage.push(0.0);
        }
        answer.kernel[0] = 1.0;
        return answer;
    }

    pub fn set_kernel(&mut self, i: i32, value: f64) {
        let u = i as usize;
        self.kernel[u] = value;
    }

    pub fn reset(&mut self) {
        for i in 0..self.storage.len() {
            self.storage[i] = 0.0;
        }
        if (self.get_kernel_sum() - 1f64).abs() > 0.000001 {
            panic!("Kernel sum must be equal to 1");
        }
    }

    pub fn get_kernel_sum(&self) -> f64 {
        let mut sum = 0f64;
        for i in 0..self.kernel.len() {
            sum += self.kernel[i];
        }
        sum
    }

    pub fn run_step(&mut self, input_value: f64) -> f64 {
        let n = self.kernel.len();
        for i in 0..n {
            self.storage[i] += input_value * self.kernel[i];
        }
        let answer = self.storage[0];
        for i in 0..n - 1 {
            self.storage[i] = self.storage[i + 1];
        }
        self.storage[n - 1] = 0.0;
        return answer;
    }

    pub fn run(&mut self, input_timeseries: Timeseries) -> Timeseries {
        //Create an output timeseries and set up the metadata
        let mut answer = Timeseries::new_daily();

        //Reset the internal state of the unit hydrograph and run it
        self.reset();
        for input in input_timeseries.values.iter() {
            let output = self.run_step(*input);
            answer.push_value(output)
        }

        //Return the results
        return answer;
    }
}