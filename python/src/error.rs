//! Kalix's Python-facing exception hierarchy.
//!
//! ```text
//! KalixError(Exception)
//! ├── ModelParseError                 -- a value in the content could not be read
//! ├── ModelValidationError            -- the model described is invalid
//! ├── SimulationError                 -- run() failed
//! ├── KalixKeyError(KeyError)         -- missing sections/properties/outputs
//! └── KalixRuntimeError(RuntimeError) -- equivalent to RuntimeError
//! ```
//! so `except kalix.KalixError` is always a safe catch-all for anything the
//! engine reports.
//!
//! Exception: malformed arguments are caught before consulting the engine, so
//! it stays outside this hierarchy.

use pyo3::create_exception;
use pyo3::exceptions::{PyException, PyKeyError, PyRuntimeError};
use pyo3::sync::GILOnceCell;
use pyo3::prelude::{Bound, Py, PyAnyMethods, PyErr, PyModule, PyModuleMethods, PyResult, Python};
use pyo3::types::{PyDict, PyTuple, PyType};

create_exception!(
    kalix._native,
    KalixError,
    PyException,
    "Root of every Kalix-specific exception; `except kalix.KalixError` is \
     always a safe catch-all."
);
create_exception!(
    kalix._native,
    ModelParseError,
    KalixError,
    "A value in a model could not be read -- a bad number or date, a \
     wrong-length value list, an unrecognised keyword."
);
create_exception!(
    kalix._native,
    ModelValidationError,
    KalixError,
    "A model was read, but the model it describes is invalid."
);
create_exception!(
    kalix._native,
    SimulationError,
    KalixError,
    "A `run()` call failed."
);

/// Cached `KalixKeyError` type object, populated once by `register()`.
static KALIX_KEY_ERROR: GILOnceCell<Py<PyType>> = GILOnceCell::new();
static KALIX_RUNTIME_ERROR: GILOnceCell<Py<PyType>> = GILOnceCell::new();

/// Build a new `KalixKeyError` instance carrying `msg`, ready to raise as a
/// `PyErr` -- the hand-rolled equivalent of the `SomeError::new_err(...)`
/// that `create_exception!` generates for single-base types (see
/// `register()` for why `KalixKeyError` can't just use that macro).
pub fn new_kalix_key_error(py: Python<'_>, msg: impl AsRef<str>) -> PyErr {
    let ty = KALIX_KEY_ERROR
        .get(py)
        .expect("KalixKeyError not registered -- register() must run at module init")
        .bind(py);
    match ty.call1((msg.as_ref(),)) {
        Ok(instance) => PyErr::from_value_bound(instance),
        Err(e) => e,
    }
}

/// Build a new `KalixRuntimeError` instance carrying `msg`, ready to raise as
/// a `PyErr` -- the hand-rolled equivalent of the `SomeError::new_err(...)`
/// that `create_exception!` generates for single-base types (see
/// `register()` for why `KalixRuntimeError` can't just use that macro).
pub fn new_kalix_runtime_error(py: Python<'_>, msg: impl AsRef<str>) -> PyErr {
    let ty = KALIX_RUNTIME_ERROR
        .get(py)
        .expect("KalixRuntimeError not registered -- register() must run at module init")
        .bind(py);
    match ty.call1((msg.as_ref(),)) {
        Ok(instance) => PyErr::from_value_bound(instance),
        Err(e) => e,
    }
}

/// Register the whole hierarchy on the `_native` module, so `kalix.error`
/// (Python) can simply re-export them.
///
/// `KalixKeyError` needs *two* bases -- `KalixError` and the builtin
/// `KeyError` -- so both `except kalix.KalixError` and `except KeyError`
/// catch it. `create_exception!` only supports a single base, so this one is
/// assembled by hand via Python's own `type()` builtin instead, and cached
/// for reuse by `new_kalix_key_error()`.
///
/// Both hand-rolled types must have `__module__` set explicitly: `type()`
/// otherwise infers it from the calling frame, which during module init is
/// `importlib._bootstrap`. That name would then show up in every traceback,
/// and pickling would fail (the class isn't reachable at that path), which
/// in turn breaks propagating one of these across a process boundary.
pub fn register(m: &Bound<'_, PyModule>) -> PyResult<()> {
    let py = m.py();

    // Matches the `kalix._native` the `create_exception!` types report.
    let module_name = "kalix._native";

    m.add("KalixError", py.get_type_bound::<KalixError>())?;
    m.add("ModelParseError", py.get_type_bound::<ModelParseError>())?;
    m.add(
        "ModelValidationError",
        py.get_type_bound::<ModelValidationError>(),
    )?;
    m.add("SimulationError", py.get_type_bound::<SimulationError>())?;

    let bases = PyTuple::new_bound(
        py,
        [
            py.get_type_bound::<KalixError>().into_any(),
            py.get_type_bound::<PyKeyError>().into_any(),
        ],
    );
    let namespace = PyDict::new_bound(py);
    namespace.set_item("__module__", module_name)?;
    let builtins = PyModule::import_bound(py, "builtins")?;
    let key_error_ty = builtins
        .getattr("type")?
        .call1(("KalixKeyError", bases, namespace))?
        .downcast_into::<PyType>()
        .expect("type() must return a PyType");
    KALIX_KEY_ERROR
        .set(py, key_error_ty.clone().unbind())
        .expect("register() must only run once");
    m.add("KalixKeyError", key_error_ty)?;

    let runtime_bases = PyTuple::new_bound(
        py,
        [
            py.get_type_bound::<KalixError>().into_any(),
            py.get_type_bound::<PyRuntimeError>().into_any(),
        ],
    );
    let runtime_namespace = PyDict::new_bound(py);
    runtime_namespace.set_item("__module__", module_name)?;
    let runtime_error_ty = builtins
        .getattr("type")?
        .call1(("KalixRuntimeError", runtime_bases, runtime_namespace))?
        .downcast_into::<PyType>()
        .expect("type() must return a PyType");
    KALIX_RUNTIME_ERROR
        .set(py, runtime_error_ty.clone().unbind())
        .expect("register() must only run once");
    m.add("KalixRuntimeError", runtime_error_ty)?;

    Ok(())
}
