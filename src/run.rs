//! High-level run-style entry points used by the CLI and the Python bindings.
//!
//! These wrap the `Model` lifecycle (load → configure → run → optional writes)
//! so the same code path is exercised regardless of caller. CLI-only concerns
//! (profiling, mass-balance verification) stay in `bin/kalix.rs`.

use crate::io::ini_model_io::IniModelIO;

/// Load a model from an INI file, run it, and write optional outputs.
///
/// Both output paths are optional — callers may pass `None` to skip a given
/// output (useful e.g. for benchmarking the run on its own). Returns `Err`
/// on any failure in load / configure / run / write.
pub fn simulate_from_file(
    model_path: &str,
    output_path: Option<&str>,
    mass_balance_path: Option<&str>,
) -> Result<(), String> {
    let mut m = IniModelIO::new().read_model_file(model_path)?;
    m.configure()?;
    m.run()?;
    if let Some(p) = output_path {
        m.write_outputs(p)?;
    }
    if let Some(p) = mass_balance_path {
        let report = m.generate_mass_balance_report();
        std::fs::write(p, report).map_err(|e| e.to_string())?;
    }
    Ok(())
}
