//! High-level run-style entry points used by the CLI and the Python bindings.
//!
//! These wrap the `Model` lifecycle (load → configure → run → write) so the
//! same code path is exercised regardless of caller. CLI presentation
//! (printlns, profiling, mass-balance) stays in `bin/kalix.rs`.

use crate::io::ini_model_io::IniModelIO;

/// Load a model from an INI file, run it, and write its outputs.
///
/// Returns `Err` on any failure in load / configure / run / write —
/// callers decide whether to panic, print, or propagate.
pub fn simulate_from_file(model_path: &str, output_path: &str) -> Result<(), String> {
    let mut m = IniModelIO::new().read_model_file(model_path)?;
    m.configure()?;
    m.run()?;
    m.write_outputs(output_path)?;
    Ok(())
}
