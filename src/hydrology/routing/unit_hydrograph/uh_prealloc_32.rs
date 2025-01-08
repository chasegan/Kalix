
#[derive(Default)]
#[derive(Clone)]
pub struct UHPrealloc32 {
    /*
    This is a preallocated version of UHDyn, which uses arrays instead
    of vecs. This one has a maximum capacity of 32 elements. You don't
    need to use all of them, and this will work normally for any UH with
    32 or fewer elements.
     */
    kernel: [f64; 32],
    storage: [f64; 32],
    len: usize,
}

impl UHPrealloc32 {
    pub fn new(length: usize) -> UHPrealloc32 {
        if length > 32 {
            panic!("UHPrealloc32 unit hydrograph length must not be greater than 32");
        }
        let mut answer = UHPrealloc32 {
            kernel: [0.0; 32],
            storage: [0.0; 32],
            len: length,
        };
        answer.kernel[0] = 1.0;
        answer
    }

    pub fn set_kernel(&mut self, i: usize, value: f64) {
        if i >= self.len { panic!("Tried to set kernel past specified len."); }
        self.kernel[i] = value;
    }

    /// Empties the storages and also checks that the unit hydrograph adds to 1.0
    pub fn reset(&mut self) {
        self.reset_state_to_empty();
        if (self.get_kernel_sum() - 1f64).abs() > 0.000001 {
            panic!("Kernel sum must be equal to 1");
        }
    }

    /// Empties the storages
    pub fn reset_state_to_empty(&mut self)
    {
        for i in 0..self.len {
            self.storage[i] = 0.0;
        }
    }


    pub fn get_kernel_sum(&self) -> f64 {
        let mut sum = 0f64;
        for i in 0..self.len {
            sum += self.kernel[i];
        }
        sum
    }

    pub fn run_step(&mut self, input_value: f64) -> f64 {
        for i in 0..self.len {
            self.storage[i] += input_value * self.kernel[i];
        }
        let answer = self.storage[0];
        for i in 0..=self.len {
            self.storage[i] = self.storage[i + 1];
        }
        self.storage[self.len - 1] = 0.0;
        answer
    }
}