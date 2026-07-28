"""Tests for kalix's exception hierarchy (`kalix.error`).

These lock in the *shape* of the hierarchy -- what catches what -- rather
than which call raises which type (that lives with each feature's tests in
test_model.py). The shape is the part users write `except` clauses against,
so it is the part that must not drift.
"""
from __future__ import annotations

import pickle
from pathlib import Path

import pytest

import kalix

_REPO_ROOT = Path(__file__).resolve().parents[2]
_MODEL_INI = _REPO_ROOT / "src/tests/example_models/4/linked_model.ini"

pytestmark = pytest.mark.skipif(
    not _MODEL_INI.exists(), reason=f"example model not found at {_MODEL_INI}"
)

_BAD_INI = "this is not valid kalix ini syntax {{{"

_ALL_TYPES = [
    "KalixError",
    "ModelParseError",
    "ModelValidationError",
    "SimulationError",
    "KalixKeyError",
    "KalixRuntimeError",
]


# --- availability --------------------------------------------------------

@pytest.mark.parametrize("name", _ALL_TYPES)
def test_exception_types_are_reachable_from_the_top_level(name):
    """Docstrings and the spec both promise `except kalix.KalixError`, so a
    bare `import kalix` has to be enough -- no `import kalix.error` first."""
    assert isinstance(getattr(kalix, name), type)


@pytest.mark.parametrize("name", _ALL_TYPES)
def test_top_level_and_submodule_expose_the_same_object(name):
    """`kalix.X` and `kalix.error.X` must be the same class, or an `except`
    written against one silently fails to catch the other."""
    import kalix.error

    assert getattr(kalix, name) is getattr(kalix.error, name)


# --- hierarchy shape -----------------------------------------------------

@pytest.mark.parametrize("name", [n for n in _ALL_TYPES if n != "KalixError"])
def test_every_kalix_error_derives_from_the_root(name):
    assert issubclass(getattr(kalix, name), kalix.KalixError)


def test_kalix_key_error_is_also_a_builtin_key_error():
    """Dual base: code written against `except KeyError` before the typed
    hierarchy existed keeps working."""
    assert issubclass(kalix.KalixKeyError, KeyError)
    assert issubclass(kalix.KalixKeyError, kalix.KalixError)


def test_kalix_runtime_error_is_also_a_builtin_runtime_error():
    assert issubclass(kalix.KalixRuntimeError, RuntimeError)
    assert issubclass(kalix.KalixRuntimeError, kalix.KalixError)


@pytest.mark.parametrize(
    "name", ["ModelParseError", "ModelValidationError", "SimulationError"]
)
def test_content_errors_are_not_value_errors(name):
    """Deliberate break from the pre-hierarchy API, which raised `ValueError`
    for bad content. Pinned so the break is visible if anyone re-adds
    `ValueError` as a base to quietly restore compatibility."""
    assert not issubclass(getattr(kalix, name), ValueError)


def test_kalix_error_is_not_an_os_error():
    """Filesystem failures stay builtin `OSError` and outside the hierarchy;
    the two must never overlap."""
    assert not issubclass(kalix.KalixError, OSError)
    assert not issubclass(OSError, kalix.KalixError)


# --- the catch-all promise -----------------------------------------------

def test_kalix_error_catches_a_parse_failure():
    with pytest.raises(kalix.KalixError):
        kalix.load_string(_BAD_INI)


def test_kalix_error_catches_a_lookup_failure():
    model = kalix.load_file(str(_MODEL_INI))
    with pytest.raises(kalix.KalixError):
        model.get_section("node.nonexistent")


def test_kalix_error_catches_a_precondition_failure():
    model = kalix.load_file(str(_MODEL_INI))
    with pytest.raises(kalix.KalixError):
        model.get_outputs()


# --- the deliberate carve-out --------------------------------------------

def test_malformed_designation_is_a_builtin_value_error():
    """A designation that isn't `"<section>.<property>"` is rejected before
    the engine is consulted, so it is a caller mistake, not a modelling
    failure -- and deliberately outside `KalixError` (see kalix.error)."""
    model = kalix.load_file(str(_MODEL_INI))
    with pytest.raises(ValueError) as exc:
        model.get("no_dot_here")
    assert not isinstance(exc.value, kalix.KalixError)


def test_well_formed_designation_that_misses_is_a_kalix_error():
    """The contrast case: same method, but the model *was* consulted."""
    model = kalix.load_file(str(_MODEL_INI))
    with pytest.raises(kalix.KalixKeyError):
        model.get("node.nonexistent.area")


# --- identity ------------------------------------------------------------

@pytest.mark.parametrize("name", _ALL_TYPES)
def test_exception_module_is_reported_honestly(name):
    """`KalixKeyError`/`KalixRuntimeError` are built via `type()` rather than
    `create_exception!` (they need two bases). Without an explicit
    `__module__` they inherit the calling frame's, which at module-init time
    is `importlib._bootstrap` -- which would then show up in every
    traceback."""
    assert getattr(kalix, name).__module__ == "kalix._native"


@pytest.mark.parametrize("name", _ALL_TYPES)
def test_exceptions_survive_a_pickle_round_trip(name):
    """Unpicklable exceptions can't cross a process boundary, which would
    break reporting a failure from a worker process."""
    cls = getattr(kalix, name)
    restored = pickle.loads(pickle.dumps(cls("some message")))
    assert type(restored) is cls
