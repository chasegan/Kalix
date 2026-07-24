/// Why a model failed to load. Threaded through the whole model-load chain
/// (csv_io::read_ts -> TimeseriesInput::load -> Model::load_input_data ->
/// ini_doc_to_model_0_0_1 -> IniModelIO::*, and through model_patch.rs) so the
/// PyO3 boundary can map each kind to the right exception type without sniffing
/// message text.
///
/// There is deliberately no `From<String>`: every site names its variant. Nor
/// the reverse -- `run.rs`'s `Result<_, String>` boundaries call `.to_string()`
/// by hand.
/// 
/// The mapper reads and assembles in one pass, so `Validate` and `Parse` both
/// arise from the same call.
#[derive(Debug, thiserror::Error)]
pub enum KalixIoError {
    /// The filesystem said no.
    #[error("{0}")]
    Io(String),
    /// A value could not be read: bad number/date/expression, wrong-length
    /// value list, unrecognised keyword, INI syntax error.
    #[error("{0}")]
    Parse(String),
    /// The assembled model is invalid. 
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
