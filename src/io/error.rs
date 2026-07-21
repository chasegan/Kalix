/// Distinguishes a genuine filesystem/OS failure (file not found, permission
/// denied) from a content problem (bad INI syntax, malformed CSV, invalid
/// model configuration). Threaded through the whole model-load chain
/// (csv_io::read_ts -> TimeseriesInput::load -> Model::load_input_data ->
/// ini_doc_to_model_0_0_1 -> IniModelIO::*, and through model_patch.rs) so
/// the PyO3 boundary can map each kind to the right exception type
/// (OSError vs ValueError) without sniffing message text.
#[derive(Debug, thiserror::Error)]
pub enum KalixIoError {
    #[error("{0}")]
    Io(String),
    #[error("{0}")]
    Parse(String),
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
        }
    }
}

/// Any plain String error defaults to Parse. This is what makes the large
/// majority of existing `.map_err(|e| format!(...))?` sites in
/// ini_doc_model_io_0_0_1.rs compile unchanged -- `?`'s auto-Into reaches
/// this impl.
impl From<String> for KalixIoError {
    fn from(s: String) -> Self {
        KalixIoError::Parse(s)
    }
}

impl From<&str> for KalixIoError {
    fn from(s: &str) -> Self {
        KalixIoError::Parse(s.to_string())
    }
}

/// For boundary functions (run.rs) that intentionally keep a
/// `Result<_, String>` public signature.
impl From<KalixIoError> for String {
    fn from(e: KalixIoError) -> Self {
        e.to_string()
    }
}
