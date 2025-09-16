
/// Identification holds an optional reference (by name or index) to a model element such as a
/// node. During model initialization we can find and the index of the component in the containing
/// vector and populate the idx value for faster subsequent lookup.
#[derive(Clone, Default)]
pub enum ComponentIdentification {
    #[default]
    None,
    Named { name: String },
    Indexed { idx: usize },
}