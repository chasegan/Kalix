/// A fixed-size circular buffer that stores f64 values.
/// Initialized with zeros. Supports inserting a new value and retrieving the oldest.
pub struct FifoBuffer {
    data: Vec<f64>,
    head: usize,
}

impl FifoBuffer {
    /// Create a new buffer of the given length, initialized with zeros.
    pub fn new(len: usize) -> Self {
        Self {
            data: vec![0.0; len],
            head: 0,
        }
    }

    /// Insert a new value and return the oldest value.
    /// If buffer has zero capacity, returns the input value immediately (passthrough).
    pub fn push(&mut self, value: f64) -> f64 {
        if self.data.is_empty() {
            return value;
        }
        let oldest = self.data[self.head];
        self.data[self.head] = value;
        self.head = (self.head + 1) % self.data.len();
        oldest
    }

    /// Returns the number of elements in the buffer.
    pub fn len(&self) -> usize {
        self.data.len()
    }

    /// Returns true if the buffer has zero capacity.
    pub fn is_empty(&self) -> bool {
        self.data.is_empty()
    }

    /// Reset all values to zero.
    pub fn reset(&mut self) {
        self.data.fill(0.0);
        self.head = 0;
    }
}
