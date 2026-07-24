/// Distinguishes a genuine filesystem/OS failure (file not found, permission
/// denied) from a content problem (bad INI syntax, malformed CSV, invalid
/// model configuration). Threaded through the whole model-load chain
/// (csv_io::read_ts -> TimeseriesInput::load -> Model::load_input_data ->
/// ini_doc_to_model_0_0_1 -> IniModelIO::*, and through model_patch.rs) so
/// the PyO3 boundary can map each kind to the right exception type
/// (OSError vs ValueError) without sniffing message text.
#[derive(Debug, thiserror::Error)]
pub enum KalixIoError {
    /// File error
    #[error("{0}")]
    Io(String),
    /// Error with parsing model
    #[error("{0}")]
    Parse(String),
    /// Invalid model
    #[error("{0}")]
    Validate(String),
}

impl KalixIoError {
    /// Prepend context while preserving the variant. Use this instead of
    /// `format!("{ctx}{e}")` (which would flatten to a plain String and
    /// erase the distinction) wherever a lower-layer error needs a
    /// line-number/file-name prefix added.
    pub fn with_context(self, prefix: &str) -> Self {
        match self {
            KalixIoError::Io(msg) => KalixIoError::Io(format!("{prefix}{msg}")),
            KalixIoError::Parse(msg) => KalixIoError::Parse(format!("{prefix}{msg}")),
            KalixIoError::Validate(msg) => KalixIoError::Validate(format!("{prefix}{msg}")),
        }
    }
}
