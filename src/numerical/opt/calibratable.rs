/// Trait for model components that support parameter calibration
///
/// This is a minimal interface that allows getting/setting parameters by name.
/// Nodes (like Sacramento, GR4J) implement this to expose their calibratable parameters.
/// The trait only handles parameter access - bounds, transforms, and gene mappings
/// are handled at the calibration configuration level.
pub trait Calibratable {
    /// Set a parameter by name (physical space, not normalized)
    ///
    /// # Arguments
    /// * `name` - Parameter name (e.g., "lztwm", "uzk", "sarva_on_pctim")
    /// * `value` - Parameter value in physical space
    ///
    /// # Derived Parameters
    /// Some parameters may be "derived" - they update multiple underlying values.
    /// For example, "sarva_on_pctim" might set sarva = value * pctim.
    fn set_param(&mut self, name: &str, value: f64) -> Result<(), String>;

    /// Get a parameter by name (physical space)
    ///
    /// # Arguments
    /// * `name` - Parameter name
    ///
    /// # Returns
    /// Parameter value in physical space, or error if parameter doesn't exist
    fn get_param(&self, name: &str) -> Result<f64, String>;

    /// List available parameter names (for introspection/validation)
    ///
    /// Default implementation returns empty list (no introspection).
    /// Override to provide list of all supported parameter names.
    fn list_params(&self) -> Vec<String> {
        vec![]
    }
}
