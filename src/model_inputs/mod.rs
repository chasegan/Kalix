/// Model Inputs Module
///
/// This module contains types and utilities for defining model inputs.
/// Model inputs can be simple data references, constants, or dynamic
/// function expressions that are evaluated at each timestep.
///
/// # Types
///
/// - `InputDataDefinition`: Simple reference to a timeseries in the data cache
/// - `DynamicInput`: Flexible input supporting constants, data references, or function expressions

pub mod input_data_definition;
pub mod dynamic_input;

pub use input_data_definition::InputDataDefinition;
pub use dynamic_input::DynamicInput;
