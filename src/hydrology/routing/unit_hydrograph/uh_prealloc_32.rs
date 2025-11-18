
#[derive(Default)]
#[derive(Clone)]
pub struct UHPrealloc32 {
    /*
    This is a preallocated version of UHDyn, which uses arrays instead
    of vecs. This one has a maximum capacity of 32 elements. You don't
    need to use all of them, and this will work normally for any UH with
    32 or fewer elements.

    Uses a circular buffer to avoid shifting the entire array each timestep.
     */
    kernel: [f64; 32],
    storage: [f64; 32],
    len: usize,
    head: usize,  // Circular buffer head pointer
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
            head: 0,
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

    /// Empties the storages and resets the circular buffer head
    pub fn reset_state_to_empty(&mut self)
    {
        for i in 0..self.len {
            self.storage[i] = 0.0;
        }
        self.head = 0;
    }


    pub fn get_kernel_sum(&self) -> f64 {
        let mut sum = 0f64;
        for i in 0..self.len {
            sum += self.kernel[i];
        }
        sum
    }

    pub fn run_step(&mut self, input_value: f64) -> f64 {
        // Add input weighted by kernel at circular positions
        for i in 0..self.len {
            let pos = (self.head + i) % self.len;
            self.storage[pos] += input_value * self.kernel[i];
        }

        // Get output from current head position
        let answer = self.storage[self.head];

        // Zero out the position we just output from
        self.storage[self.head] = 0.0;

        // Advance the head pointer (circular)
        self.head = (self.head + 1) % self.len;

        answer
    }
}