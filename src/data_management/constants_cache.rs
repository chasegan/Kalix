use std::collections::HashMap;

#[derive(Clone, Default)]
pub struct ConstantsCache {

    // Vectors that collectively define a table of the constants in the ConstantsCache
    names: Vec<String>,
    is_assigned: Vec<bool>,
    value: Vec<f64>,

    // Dictionary to quickly look up the idx (row) in the above vectors if you only know the name
    name_idx_map: HashMap<String, usize>,
}

impl ConstantsCache {
    pub fn new() -> Self {
        Self {
            ..Default::default()
        }
    }

    // ----------- PRIVATE

    /// Extends the vectors with given values of name, is_assigned, and value. Updates the
    /// name_idx_map, and then returns the idx (which = len()-1).
    fn push(&mut self, name: String, is_assigned: bool, value: f64) -> usize {
        self.names.push(name.clone());
        self.is_assigned.push(is_assigned);
        self.value.push(value);
        let idx = self.names.len() - 1;
        self.name_idx_map.insert(name, idx);
        idx
    }

    // ----------- PUBLIC

    /// Adds a constant to the ConstantCache if it doesn't already exist, and then returns the idx.
    /// Consumers can use this to say
    ///    "Hey I'm going to want to use a constant called this. Please register the name and give
    ///    me an idx that I can use for quick access later."
    pub fn add_if_needed_and_get_idx(&mut self, name: &str) -> usize {
        if let Some(idx) = self.name_idx_map.get(name) {
            *idx
        } else {
            self.push(name.to_string(), false, 0f64)
        }
    }

    /// This provides fast access to the f64 values given an idx. Consumers can use this to say
    ///    "Here is an idx I have been provided. Give me the value."
    /// This method does not check that the constant has a valid assignment and should only be
    /// used after assert_all_constants_have_assigned_values().
    pub fn get_value(&self, idx: usize) -> f64 {
        self.value[idx]
    }

    /// Sets the value of a constant and returns the idx. If the constant does not already exist
    /// it will be added.
    pub fn set_value(&mut self, name: &str, value: f64) -> usize {
        let idx = self.add_if_needed_and_get_idx(name);
        self.value[idx] = value;
        self.is_assigned[idx] = true;
        idx
    }

    /// Get the number of constants
    pub fn len(&self) -> usize {
        self.names.len()
    }

    /// Check that all constants have been assigned values.
    /// Use this before the model run to check that everything is okay.
    pub fn assert_all_constants_have_assigned_values(&self) -> Result<(), String> {
        for i in 0..self.len() {
            if !self.is_assigned[i] {
                return Err(format!("Constant '{}' has not been assigned a value.", self.names[i]));
            }
        }
        Ok(())
    }
}