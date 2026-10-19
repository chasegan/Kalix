/// What a derived parameter is made of: setting it writes `writes` from the
/// value `from` holds at that moment ("sarvaonpctim" writes "sarva" from
/// "pctim"). Both are names `set_param` accepts.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Derived {
    pub writes: &'static str,
    pub from: &'static str,
}

/// Trait for model components that support parameter optimisation
///
/// This is a minimal interface that allows getting/setting parameters by name.
/// Model components (like nodes, constants, etc.) implement this to expose their optimisable parameters.
/// The trait only handles parameter access - bounds, transforms, and gene mappings
/// are handled at the optimisation configuration level.
pub trait OptimisableComponent {
    /// Set a parameter by name (physical space, not normalised)
    ///
    /// # Arguments
    /// * `name` - Parameter name (e.g., "lztwm", "uzk", "sarvaonpctim")
    /// * `value` - Parameter value in physical space
    ///
    /// # Derived Parameters
    /// Some parameters are "derived": setting one writes another parameter,
    /// computed from the values the component holds at that moment. For
    /// example, "sarvaonpctim" sets sarva = value * pctim. See `derives`.
    fn set_param(&mut self, name: &str, value: f64) -> Result<(), String>;

    /// What `name` writes and from what, when `name` is derived from others:
    /// "sarvaonpctim" writes "sarva" from "pctim". The optimiser sets such a
    /// name after the component's other mapped parameters, so it is computed
    /// from the candidate's values, and refuses it alongside the one it writes.
    fn derives(&self, _name: &str) -> Option<Derived> {
        None
    }

    /// Get a parameter by name (physical space)
    ///
    /// Must succeed for every name `set_param` can set and fail for any name
    /// it never could: the optimiser reads each mapped name once, up front,
    /// to check the address.
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
