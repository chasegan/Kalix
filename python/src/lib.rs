//! PyO3 bindings for kalix.
//!
//! v0.1: just Pixie (.pxt/.pxb) I/O. Functions are prefixed with `_` and re-exported
//! through the Python `kalix` package, which adds pandas/numpy ergonomics.

use kalix::io::ini_model_io::IniModelIO;
use kalix::io::model_patch::{patch_delete, patch_override, patch_update};
use kalix::io::pixie_io;
use kalix::model::Model;
use kalix::run;
use kalix::tid::utils::{wrap_to_i64, wrap_to_u64};
use kalix::timeseries::Timeseries;
use numpy::{IntoPyArray, PyArray1, PyReadonlyArray1};
use pyo3::exceptions::{PyIOError, PyRuntimeError, PyValueError};
use pyo3::prelude::*;
use pyo3::types::PyDict;

/// Strip `.pxt` or `.pxb` extension from a path, returning the base.
fn strip_ext(path: &str) -> &str {
    let lower = path.to_ascii_lowercase();
    if lower.ends_with(".pxt") || lower.ends_with(".pxb") {
        &path[..path.len() - 4]
    } else {
        path
    }
}

/// Read a Pixie .pxt/.pxb pair into (timestamps_unix_seconds, {series_name: values_array}).
///
/// The Python wrapper assembles this into a pandas DataFrame.
#[pyfunction]
fn _read_pixie_raw<'py>(
    py: Python<'py>,
    path: &str,
) -> PyResult<(Bound<'py, PyArray1<i64>>, Bound<'py, PyDict>)> {
    let base = strip_ext(path);
    let series_list = pixie_io::read_all_series(base)
        .map_err(|e| PyIOError::new_err(format!("Failed to read Pixie file: {:?}", e)))?;

    if series_list.is_empty() {
        return Err(PyValueError::new_err("No series in file"));
    }

    // v0.1 requires all series share the same time grid.
    let first = &series_list[0];
    for s in &series_list[1..] {
        if s.start_timestamp != first.start_timestamp
            || s.step_size != first.step_size
            || s.values.len() != first.values.len()
        {
            return Err(PyValueError::new_err(
                "Series have differing time grids; v0.1 requires alignment",
            ));
        }
    }

    // Reconstruct timestamps as signed Unix seconds.
    let n = first.values.len();
    let mut timestamps: Vec<i64> = Vec::with_capacity(n);
    for i in 0..n {
        let wrapped = first
            .start_timestamp
            .wrapping_add((i as u64).wrapping_mul(first.step_size));
        timestamps.push(wrap_to_i64(wrapped));
    }
    let ts_array = timestamps.into_pyarray_bound(py);

    let dict = PyDict::new_bound(py);
    for s in series_list {
        let arr = s.values.into_pyarray_bound(py);
        dict.set_item(s.name, arr)?;
    }

    Ok((ts_array, dict))
}

/// Write a Pixie .pxt/.pxb pair from a regular-stride time grid and per-series values.
///
/// - `timestamps_unix_seconds`: 1-D numpy array (int64) of Unix seconds; must be regular.
/// - `series_names`: list of column names, one per array in `values_per_series`.
/// - `values_per_series`: list of 1-D numpy arrays (float64), same length as timestamps.
/// - `use_64bit_precision`: true → Gorilla double (lossless); false → Gorilla float.
#[pyfunction]
fn _write_pixie_raw(
    path: &str,
    series_names: Vec<String>,
    timestamps_unix_seconds: PyReadonlyArray1<i64>,
    values_per_series: Vec<PyReadonlyArray1<f64>>,
    use_64bit_precision: bool,
) -> PyResult<()> {
    if series_names.len() != values_per_series.len() {
        return Err(PyValueError::new_err(
            "series_names and values_per_series must have the same length",
        ));
    }
    let ts = timestamps_unix_seconds.as_slice()?;
    if ts.is_empty() {
        return Err(PyValueError::new_err("No data to write"));
    }

    // Derive step from first two timestamps; fall back to daily if only one point.
    let step_size: u64 = if ts.len() >= 2 {
        let diff = ts[1] - ts[0];
        if diff <= 0 {
            return Err(PyValueError::new_err(
                "Timestamps must be strictly increasing",
            ));
        }
        diff as u64
    } else {
        86400
    };

    // Sanity-check regular stride (cheap to do once; cheap to skip later if it gets noisy).
    for i in 2..ts.len() {
        if (ts[i] - ts[i - 1]) as u64 != step_size {
            return Err(PyValueError::new_err(format!(
                "Irregular timestep at index {}: expected step {}, got {}",
                i,
                step_size,
                ts[i] - ts[i - 1]
            )));
        }
    }

    let start_timestamp = wrap_to_u64(ts[0]);

    let mut series_vec: Vec<Timeseries> = Vec::with_capacity(series_names.len());
    for (name, arr) in series_names.into_iter().zip(values_per_series.iter()) {
        let values_slice = arr.as_slice()?;
        if values_slice.len() != ts.len() {
            return Err(PyValueError::new_err(format!(
                "Series '{}' has {} values, expected {}",
                name,
                values_slice.len(),
                ts.len()
            )));
        }
        let mut t = Timeseries::new(step_size);
        t.name = name;
        t.start_timestamp = start_timestamp;
        t.values = values_slice.to_vec();
        series_vec.push(t);
    }

    let base = strip_ext(path);
    let refs: Vec<&Timeseries> = series_vec.iter().collect();
    pixie_io::write_series_with_precision(base, &refs, use_64bit_precision)
        .map_err(|e| PyIOError::new_err(format!("Failed to write Pixie file: {:?}", e)))?;
    Ok(())
}

/// Run a simulation from an INI model file and write optional outputs to disk.
///
/// Output format is inferred from the file extension (`.csv`, `.pxb`, etc.)
/// by `Model::write_outputs`. The GIL is released during the run so other
/// Python threads can make progress on long simulations.
#[pyfunction]
#[pyo3(signature = (model_path, output_path=None, mass_balance_path=None))]
fn _simulate_from_file(
    py: Python<'_>,
    model_path: &str,
    output_path: Option<&str>,
    mass_balance_path: Option<&str>,
) -> PyResult<()> {
    py.allow_threads(|| {
        run::simulate_from_file(model_path, output_path, mass_balance_path)
            .map_err(PyRuntimeError::new_err)
    })
}

/// Run a parameter optimisation from an INI config file and return the outcome.
///
/// The in-process equivalent of `kalix optimise <config> [model] [-s save]`.
/// Paths inside the config are resolved relative to the current working
/// directory, exactly as the CLI does. The GIL is released during the run so
/// other Python threads make progress (optimisation is long and multi-threaded).
///
/// Returns a dict with: `best_objective`, `n_evaluations`, `success`,
/// `message`, `parameters` ({target: physical_value}), and
/// `optimised_model_ini` (the optimised model serialised back to an INI string).
///
/// `progress`, if given, must be a Python callable. It is invoked once per
/// generation with a dict of `{n_evaluations, best_objective, elapsed_seconds}`.
/// The callback runs on the optimiser's thread with the GIL re-acquired; any
/// exception it raises is swallowed so a faulty reporter can't abort the run.
#[pyfunction]
#[pyo3(signature = (config_path, model_path=None, save_model_path=None, progress=None))]
fn _optimise_from_file<'py>(
    py: Python<'py>,
    config_path: &str,
    model_path: Option<&str>,
    save_model_path: Option<&str>,
    progress: Option<PyObject>,
) -> PyResult<Bound<'py, PyDict>> {
    use kalix::numerical::opt::OptimizationProgress;

    // Adapt the optional Python callable into a Rust progress callback. The
    // optimiser calls this once per generation from a single thread while the
    // GIL is released, so we re-acquire the GIL to call into Python.
    let progress_callback: Option<Box<dyn Fn(&OptimizationProgress) + Send + Sync>> =
        progress.map(|callable| {
            let cb: Box<dyn Fn(&OptimizationProgress) + Send + Sync> =
                Box::new(move |p: &OptimizationProgress| {
                    Python::with_gil(|py| {
                        let d = PyDict::new_bound(py);
                        let _ = d.set_item("n_evaluations", p.n_evaluations);
                        let _ = d.set_item("best_objective", p.best_objective);
                        let _ = d.set_item("elapsed_seconds", p.elapsed.as_secs_f64());
                        // Ignore any exception raised by the user's callback.
                        let _ = callable.call1(py, (d,));
                    });
                });
            cb
        });

    let outcome = py
        .allow_threads(|| {
            run::optimise_from_file(config_path, model_path, save_model_path, progress_callback)
        })
        .map_err(PyRuntimeError::new_err)?;

    let params = PyDict::new_bound(py);
    for (target, value) in &outcome.parameters {
        params.set_item(target, value)?;
    }

    let result = PyDict::new_bound(py);
    result.set_item("best_objective", outcome.best_objective)?;
    result.set_item("n_evaluations", outcome.n_evaluations)?;
    result.set_item("success", outcome.success)?;
    result.set_item("message", outcome.message)?;
    result.set_item("parameters", params)?;
    result.set_item("optimised_model_ini", outcome.optimised_model_ini)?;
    Ok(result)
}

// --------------
// Model bindings
// --------------

/// Mirrors `Model.patch()`'s `mode` argument (and, for deletes, `missing_ok`)
/// on the Python side.
#[pyclass]
#[pyo3(name = "_PatchMode")]
#[derive(Clone, Copy, PartialEq, Eq)]
enum PatchMode {
    Update,
    Override,
    Delete,
    DeleteMissingOk,
}

#[pyclass]
#[pyo3(name = "_Model")]
struct PyModel {
    pub inner: Model,
    /// True only while `inner` holds a complete set of results from a
    /// finished `_run()`. Any operation that replaces or modifies the model
    /// (loading, patching) resets it, so `_get_outputs` can fail fast with a
    /// clear message instead of serving absent or stale results.
    has_run: bool,
}

#[pymethods]
impl PyModel {
    /// Construct an empty, unconfigured model.
    #[new]
    fn new() -> PyResult<Self> {
        Ok(PyModel {
            inner: Model::new(),
            has_run: false,
        })
    }

    /// Load a model from an INI file, replacing any model already held.
    /// Validates via `Model::configure` before accepting; leaves `self`
    /// untouched on failure.
    fn _from_file<'py>(
        mut slf: PyRefMut<'py, Self>,
        model_path: &str,
    ) -> PyResult<PyRefMut<'py, Self>> {
        let mut model = IniModelIO::read_model_file(model_path)
            .map_err(|e| PyIOError::new_err(format!("Failed to load model: {}", e)))?;
        // Verification step
        model
            .configure()
            .map_err(|e| PyRuntimeError::new_err(format!("Failed to validate model: {}", e)))?;
        // Model OK, swap into inner
        slf.inner = model;
        slf.has_run = false;
        Ok(slf)
    }

    /// Load a model from an in-memory INI string. Like `_load_file`, but
    /// relative paths inside the INI resolve against the current working
    /// directory (there's no containing file directory).
    fn _from_model_string<'py>(
        mut slf: PyRefMut<'py, Self>,
        model_string: &str,
    ) -> PyResult<PyRefMut<'py, Self>> {
        let mut model = IniModelIO::read_model_string(model_string)
            .map_err(|e| PyIOError::new_err(format!("Failed to load model: {}", e)))?;
        // Verification step
        model
            .configure()
            .map_err(|e| PyRuntimeError::new_err(format!("Failed to validate model: {}", e)))?;
        // Model OK, swap into inner
        slf.inner = model;
        slf.has_run = false;
        Ok(slf)
    }

    /// Configure and run the model's simulation.
    fn _run(mut slf: PyRefMut<'_, Self>) -> PyResult<PyRefMut<'_, Self>> {
        // A failed configure/run leaves no trustworthy results behind.
        slf.has_run = false;
        slf.inner.configure().map_err(PyRuntimeError::new_err)?;
        slf.inner.run().map_err(PyRuntimeError::new_err)?; // self-resetting
        slf.has_run = true;
        Ok(slf)
    }

    /// Apply parameter overrides to the currently loaded model. Leaves
    /// `self` untouched on failure (mirrors `_load_file`).
    fn _patch<'py>(
        mut slf: PyRefMut<'py, Self>,
        mode: PatchMode,
        patch_string: &str,
    ) -> PyResult<PyRefMut<'py, Self>> {
        let new_model = match mode {
            PatchMode::Update => patch_update(&slf.inner, patch_string),
            PatchMode::Override => patch_override(&slf.inner, patch_string),
            PatchMode::Delete => patch_delete(&slf.inner, patch_string, false),
            PatchMode::DeleteMissingOk => patch_delete(&slf.inner, patch_string, true),
        }
        .map_err(PyValueError::new_err)?;
        slf.inner = new_model;
        slf.has_run = false;
        Ok(slf)
    }

    /// Retrieve the model's output time series after a run.
    ///
    /// Returns
    ///     - start: i64 (real, unwrapped epoch seconds)
    ///     - step: u64
    ///     - size: usize
    ///     - {name: array} PyDict
    fn _get_outputs<'py>(
        &mut self,
        py: Python<'py>,
        names: Option<Vec<String>>,
    ) -> PyResult<(i64, u64, usize, Bound<'py, PyDict>)> {
        if !self.has_run {
            return Err(PyValueError::new_err(
                "The model has not been run yet (loading/patching invalidates the results). \
                 Call run() before get_outputs()",
            ));
        }
        let outputs = self
            .inner
            .get_output_series(names)
            .map_err(PyValueError::new_err)?;
        let (start, step, size) = match outputs.first() {
            Some(first) => (
                wrap_to_i64(first.start_timestamp),
                first.step_size,
                first.values.len(),
            ),
            None => (0i64, 0u64, 0usize),
        };
        let dict = PyDict::new_bound(py);
        for ts in outputs {
            let arr = ts.values.clone().into_pyarray_bound(py);
            dict.set_item(&ts.name, arr)?;
        }
        Ok((start, step, size, dict))
    }
}

#[pymodule]
fn _native(m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add_function(wrap_pyfunction!(_read_pixie_raw, m)?)?;
    m.add_function(wrap_pyfunction!(_write_pixie_raw, m)?)?;
    m.add_function(wrap_pyfunction!(_simulate_from_file, m)?)?;
    m.add_function(wrap_pyfunction!(_optimise_from_file, m)?)?;
    m.add_class::<PyModel>()?;
    m.add_class::<PatchMode>()?;
    Ok(())
}
