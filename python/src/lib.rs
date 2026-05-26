//! PyO3 bindings for kalix.
//!
//! v0.1: just Pixie (.pxt/.pxb) I/O. Functions are prefixed with `_` and re-exported
//! through the Python `kalix` package, which adds pandas/numpy ergonomics.

use kalix::io::pixie_io;
use kalix::tid::utils::{wrap_to_i64, wrap_to_u64};
use kalix::timeseries::Timeseries;
use numpy::{IntoPyArray, PyArray1, PyReadonlyArray1};
use pyo3::exceptions::{PyIOError, PyValueError};
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
                i, step_size, ts[i] - ts[i - 1]
            )));
        }
    }

    let start_timestamp = wrap_to_u64(ts[0]);

    // The writer reads `series.timestamps[..]` for per-point timestamps and
    // metadata start/end times — it doesn't derive them from start_timestamp + step_size.
    // Pre-build the wrapped u64 timestamps once and share them across all series.
    let timestamps_wrapped: Vec<u64> = ts.iter().map(|&t| wrap_to_u64(t)).collect();

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
        t.timestamps = timestamps_wrapped.clone();
        t.values = values_slice.to_vec();
        series_vec.push(t);
    }

    let base = strip_ext(path);
    let refs: Vec<&Timeseries> = series_vec.iter().collect();
    pixie_io::write_series_with_precision(base, &refs, use_64bit_precision)
        .map_err(|e| PyIOError::new_err(format!("Failed to write Pixie file: {:?}", e)))?;
    Ok(())
}

#[pymodule]
fn _native(m: &Bound<'_, PyModule>) -> PyResult<()> {
    m.add_function(wrap_pyfunction!(_read_pixie_raw, m)?)?;
    m.add_function(wrap_pyfunction!(_write_pixie_raw, m)?)?;
    Ok(())
}
