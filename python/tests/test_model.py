"""Tests for kalix.Model / kalix.new_model / kalix.load_file / kalix.load_string.

Uses the repo's `linked_model` example (a small multi-node network with
GR4J, Sacramento, inflow, and routing nodes) rather than a synthetic fixture,
mirroring test_roundtrip.py's use of the same model for simulate() tests.
"""
from __future__ import annotations

from pathlib import Path

import pytest

import kalix

# Resolve relative to this test file -> portable across machines and CI.
_REPO_ROOT = Path(__file__).resolve().parents[2]
_MODEL_INI = _REPO_ROOT / "src/tests/example_models/4/linked_model.ini"

pytestmark = pytest.mark.skipif(
    not _MODEL_INI.exists(), reason=f"example model not found at {_MODEL_INI}"
)


def test_new_model_returns_model():
    model = kalix.new_model()
    assert isinstance(model, kalix.Model)


def test_load_file_returns_configured_model():
    model = kalix.load_file(str(_MODEL_INI))
    assert isinstance(model, kalix.Model)


def test_load_file_missing_file_raises():
    with pytest.raises(OSError):
        kalix.load_file(str(_REPO_ROOT / "does_not_exist.ini"))


def test_load_file_invalid_ini_raises(tmp_path):
    bad = tmp_path / "bad.ini"
    bad.write_text("this is not valid kalix ini syntax {{{")
    with pytest.raises(OSError):
        kalix.load_file(str(bad))


def test_model_load_file_fluent_method(tmp_path):
    """The instance method mirrors the module-level function and is chainable."""
    model = kalix.new_model().load_file(str(_MODEL_INI))
    assert isinstance(model, kalix.Model)


def test_model_load_file_rejects_dangling_downstream_link(tmp_path):
    """A downstream link to a nonexistent node is caught during INI parsing
    itself (node/link wiring), not the later `configure()` validation step --
    so it surfaces as an OSError, same as any other parse failure."""
    broken = tmp_path / "broken.ini"
    broken.write_text(
        "[kalix]\n"
        "\n"
        "[node.only_node]\n"
        "type = inflow\n"
        "loc = 0,0\n"
        "inflow = 1.0\n"
        "ds_1 = node_that_does_not_exist\n"
    )
    with pytest.raises(OSError, match="node_that_does_not_exist"):
        kalix.load_file(str(broken))


# A self-contained model (no external file inputs) so loading it as a bare
# string doesn't depend on the current working directory.
_INLINE_MODEL_INI = (
    "[kalix]\n"
    "start = 2000-01-01T00:00:00\n"
    "end = 2000-01-10T00:00:00\n"
    "\n"
    "[node.my_node]\n"
    "loc = 0,0\n"
    "type = inflow\n"
    "inflow = 1.0\n"
    "\n"
    "[outputs]\n"
    "node.my_node.ds_1\n"
)


def test_load_string_returns_configured_model():
    model = kalix.new_model()
    result = model.load_string(_INLINE_MODEL_INI)
    assert result is model


def test_load_string_invalid_ini_raises():
    with pytest.raises(OSError):
        kalix.new_model().load_string("this is not valid kalix ini syntax {{{")


def test_module_level_load_string_returns_configured_model():
    model = kalix.load_string(_INLINE_MODEL_INI)
    assert isinstance(model, kalix.Model)


def test_run_completes_without_error():
    model = kalix.load_file(str(_MODEL_INI))
    result = model.run()
    assert isinstance(result, kalix.Model)


def test_run_returns_same_instance_for_chaining():
    model = kalix.load_file(str(_MODEL_INI))
    assert model.run() is model


# --- not-yet-implemented methods: must raise a clean NotImplementedError,
# never a raw pyo3_runtime.PanicException from the underlying Rust todo!(). ---

def test_load_snippet_raises_not_implemented():
    with pytest.raises(NotImplementedError, match="load_snippet"):
        kalix.new_model().load_snippet(_INLINE_MODEL_INI)


def test_patch_raises_not_implemented():
    with pytest.raises(NotImplementedError, match="patch"):
        kalix.load_file(str(_MODEL_INI)).patch("c.x = 1.0")


def test_get_outputs_raises_not_implemented():
    """Raises cleanly even after a real run (not conditional on run state)."""
    with pytest.raises(NotImplementedError, match="get_outputs"):
        kalix.load_file(str(_MODEL_INI)).run().get_outputs()


# --- informative error messages -----------------------------------------

def test_load_file_missing_file_message_is_informative():
    path = _REPO_ROOT / "does_not_exist.ini"
    with pytest.raises(OSError, match=r"does_not_exist\.ini"):
        kalix.load_file(str(path))


def test_load_file_invalid_ini_message_is_informative(tmp_path):
    bad = tmp_path / "bad.ini"
    bad.write_text("this is not valid kalix ini syntax {{{")
    with pytest.raises(OSError, match=r"bad\.ini"):
        kalix.load_file(str(bad))


def test_load_string_invalid_message_is_informative():
    with pytest.raises(OSError, match="model string"):
        kalix.load_string("this is not valid kalix ini syntax {{{")


def test_load_file_accepts_pathlib_path():
    model = kalix.load_file(_MODEL_INI)  # a Path, not str(_MODEL_INI)
    assert isinstance(model, kalix.Model)


def test_model_direct_construction():
    model = kalix.Model()
    assert isinstance(model, kalix.Model)
    model.load_file(str(_MODEL_INI))
    assert isinstance(model, kalix.Model)
