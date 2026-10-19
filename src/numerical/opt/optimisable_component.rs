/// The two parameters a ratio parameter is made of: setting the ratio sets
/// `numerator = ratio * denominator` from the denominator the component holds
/// at that moment ("sarvaonpctim" sets "sarva" from "pctim"). Both are names
/// `set_param` accepts.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Ratio {
    pub numerator: &'static str,
    pub denominator: &'static str,
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
    /// # Ratio parameters
    /// Some parameters are ratios of two others: setting one sets its
    /// numerator from the denominator the component holds at that moment.
    /// For example, "sarvaonpctim" sets sarva = value * pctim. See `ratio_of`.
    fn set_param(&mut self, name: &str, value: f64) -> Result<(), String>;

    /// The numerator and denominator of `name`, when `name` is a ratio:
    /// "sarvaonpctim" is "sarva" over "pctim". The optimiser sets ratios last,
    /// after every other mapped parameter, so each is computed from the
    /// candidate's own denominator, and refuses a ratio mapped beside its
    /// numerator.
    fn ratio_of(&self, _name: &str) -> Option<Ratio> {
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

    /// The parameter names to offer a modeller, for the IDE and the
    /// `get_optimisable_params` command. Not necessarily every name
    /// `set_param` accepts: a component that offers a ratio leaves out the
    /// numerator the ratio sets, since the two cannot be mapped together.
    ///
    /// Default implementation returns empty list (no introspection).
    fn list_params(&self) -> Vec<String> {
        vec![]
    }
}
