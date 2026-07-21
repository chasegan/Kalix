"""Tests for kalix.Model / kalix.new_model / kalix.load_file / kalix.load_string.

Uses the repo's `linked_model` example (a small multi-node network with
GR4J, Sacramento, inflow, and routing nodes) rather than a synthetic fixture,
mirroring test_roundtrip.py's use of the same model for simulate() tests.
"""
from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd
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
    with pytest.raises(ValueError):
        kalix.load_file(str(bad))


def test_model_load_file_fluent_method(tmp_path):
    """The instance method mirrors the module-level function and is chainable."""
    model = kalix.new_model().from_file(str(_MODEL_INI))
    assert isinstance(model, kalix.Model)


def test_model_load_file_rejects_dangling_downstream_link(tmp_path):
    """A downstream link to a nonexistent node is caught during INI parsing
    itself (node/link wiring), not the later `configure()` validation step --
    so it surfaces as a ValueError (content is invalid), not an OSError
    (which is reserved for genuine file-read failures)."""
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
    with pytest.raises(ValueError, match="node_that_does_not_exist"):
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
    result = model.from_string(_INLINE_MODEL_INI)
    assert result is model


def test_load_string_invalid_ini_raises():
    with pytest.raises(ValueError):
        kalix.new_model().from_string("this is not valid kalix ini syntax {{{")


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


def test_patch_update_returns_same_instance_for_chaining():
    model = kalix.load_string(_INLINE_MODEL_INI)
    assert model.patch("[node.my_node]\ninflow = 2.0\n") is model


def test_patch_update_can_run_after_patching():
    """A patched property is actually picked up by the model that runs --
    not just accepted and ignored. `lag` must be a positive-enough value
    that `configure()` still accepts the routing node."""
    model = kalix.load_file(str(_MODEL_INI)).patch("[node.reach2]\nlag = 5\n")
    result = model.run()
    assert isinstance(result, kalix.Model)


def test_patch_update_adds_new_section():
    """Sections absent from the original model can be added by a patch, not
    just existing ones overridden."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    result = model.patch("[node.new_sink]\ntype = blackhole\nloc = 1, 1\n")
    assert isinstance(result, kalix.Model)


def test_patch_update_invalid_syntax_raises_value_error():
    with pytest.raises(ValueError):
        kalix.load_string(_INLINE_MODEL_INI).patch("not valid ini [[[")


def test_patch_update_invalid_result_raises_value_error():
    """A syntactically valid patch that produces an invalid model (here, a
    downstream link to a nonexistent node) is still a ValueError, not a
    silent success or a panic."""
    with pytest.raises(ValueError, match="node_that_does_not_exist"):
        kalix.load_string(_INLINE_MODEL_INI).patch(
            "[node.my_node]\nds_1 = node_that_does_not_exist\n"
        )


def test_patch_referencing_missing_input_file_raises_oserror():
    """A patch that introduces an [inputs] entry pointing at a nonexistent
    file must raise OSError, not ValueError -- same Io/Parse distinction as
    the load path (see test_load_string_missing_referenced_input_file_raises_oserror)."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(OSError):
        model.patch("[inputs]\n./does_not_exist_test_model_py.csv\n")


def test_patch_update_leaves_model_untouched_on_failure():
    """A rejected patch must not damage the already-loaded model -- it
    should still run exactly as before the failed patch attempt."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch("not valid ini [[[")
    result = model.run()
    assert isinstance(result, kalix.Model)


# --- patch(mode="override") ----------------------------------------------
# A second, self-contained node so whole-section addition can be exercised
# without disturbing `node.my_node` (mirrors model_patch.rs's use of an
# extra leaf node nothing links to downstream).
_INLINE_MODEL_WITH_EXTRA_NODE_INI = (
    "[kalix]\n"
    "start = 2000-01-01T00:00:00\n"
    "end = 2000-01-10T00:00:00\n"
    "\n"
    "[node.my_node]\n"
    "loc = 0,0\n"
    "type = inflow\n"
    "inflow = 1.0\n"
    "\n"
    "[node.extra_node]\n"
    "loc = 1,1\n"
    "type = inflow\n"
    "inflow = 2.0\n"
    "\n"
    "[outputs]\n"
    "node.my_node.ds_1\n"
)


def test_patch_override_returns_same_instance_for_chaining():
    model = kalix.load_string(_INLINE_MODEL_INI)
    result = model.patch("[node.my_node]\ntype = inflow\ninflow = 2.0\nloc = 0,0\n", mode="override")
    assert result is model


def test_patch_override_can_run_after_patching():
    """A full, valid replacement section is accepted and the model still
    runs -- unlike patch_update, override requires every property the
    section needs since it isn't merged with the original."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    model.patch("[node.my_node]\ntype = inflow\ninflow = 2.0\nloc = 0,0\n", mode="override")
    result = model.run()
    assert isinstance(result, kalix.Model)


def test_patch_override_drops_properties_omitted_from_patch():
    """Unlike patch_update, override replaces the whole section -- a
    property that exists on the original but is omitted from the patch
    does not survive. Dropping the required `type` property here makes the
    section unparseable, surfacing as a ValueError."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch("[node.my_node]\ninflow = 2.0\n", mode="override")


def test_patch_override_adds_new_section():
    model = kalix.load_string(_INLINE_MODEL_INI)
    result = model.patch("[node.new_sink]\ntype = blackhole\nloc = 1, 1\n", mode="override")
    assert isinstance(result, kalix.Model)


def test_patch_override_invalid_syntax_raises_value_error():
    with pytest.raises(ValueError):
        kalix.load_string(_INLINE_MODEL_INI).patch("not valid ini [[[", mode="override")


def test_patch_override_invalid_result_raises_value_error():
    with pytest.raises(ValueError, match="node_that_does_not_exist"):
        kalix.load_string(_INLINE_MODEL_INI).patch(
            "[node.my_node]\ntype = inflow\ninflow = 2.0\nloc = 0,0\nds_1 = node_that_does_not_exist\n",
            mode="override",
        )


def test_patch_override_leaves_model_untouched_on_failure():
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch("not valid ini [[[", mode="override")
    result = model.run()
    assert isinstance(result, kalix.Model)


# --- patch(mode="delete") -------------------------------------------------

def test_patch_delete_returns_same_instance_for_chaining():
    model = kalix.load_string(_INLINE_MODEL_WITH_EXTRA_NODE_INI)
    result = model.patch("[node.extra_node]\n", mode="delete")
    assert result is model


def test_patch_delete_removes_entire_section_when_no_properties_listed():
    """Deleting a leaf section that nothing else references leaves behind
    a model that still runs -- confirming the section is actually gone,
    not just left inert."""
    model = kalix.load_string(_INLINE_MODEL_WITH_EXTRA_NODE_INI)
    model.patch("[node.extra_node]\n", mode="delete")
    result = model.run()
    assert isinstance(result, kalix.Model)


# A storage node so deleting its `dimensions` table can be exercised. Unlike
# gr4j's `params` (which silently falls back to `Gr4j::new()` defaults when
# absent), a storage node's `dimensions` table has no safe fallback -- with
# fewer than 2 rows, `configure()` rejects it outright.
_INLINE_MODEL_WITH_STORAGE_NODE_INI = (
    "[kalix]\n"
    "start = 2000-01-01T00:00:00\n"
    "end = 2000-01-10T00:00:00\n"
    "\n"
    "[node.my_storage]\n"
    "loc = 0,0\n"
    "type = storage\n"
    "dimensions = 90, 0, 0, 0,\n"
    "             91, 100, 1, 0,\n"
    "ds_1 = bh\n"
    "\n"
    "[node.bh]\n"
    "loc = 1,1\n"
    "type = blackhole\n"
)


def test_patch_delete_of_required_property_fails_validation_on_patch():
    """Property-level deletion is unsupported, so naming a property here --
    even a required one like `dimensions` -- is rejected outright (same rule
    as `test_patch_delete_rejects_properties`), not deferred to `run()`."""
    model = kalix.load_string(_INLINE_MODEL_WITH_STORAGE_NODE_INI)
    with pytest.raises(ValueError):
        model.patch("[node.my_storage]\ndimensions =\n", mode="delete")


def test_patch_delete_ignores_missing_section_when_missing_ok():
    model = kalix.load_string(_INLINE_MODEL_INI)
    result = model.patch("[node.missing]\n", mode="delete", missing_ok=True)
    assert isinstance(result, kalix.Model)
    assert isinstance(result.run(), kalix.Model)


def test_patch_delete_rejects_missing_section_when_not_missing_ok():
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch("[node.missing]\n", mode="delete")


def test_patch_delete_rejects_properties():
    """Property-level deletion is not supported -- a patch section listing
    any properties is rejected outright, even if that property doesn't
    exist on the model."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch("[node.my_node]\nnot_a_real_property =\n", mode="delete")


def test_patch_delete_invalid_syntax_raises_value_error():
    with pytest.raises(ValueError):
        kalix.load_string(_INLINE_MODEL_INI).patch("not valid ini [[[", mode="delete")


def test_patch_delete_invalid_result_raises_value_error():
    """Deleting a mid-chain node used as another node's downstream leaves
    a dangling link, caught at parse time (like the analogous update/override
    tests above)."""
    with pytest.raises(ValueError):
        kalix.load_file(str(_MODEL_INI)).patch("[node.reach4]\n", mode="delete")


def test_patch_delete_leaves_model_untouched_on_failure():
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch("not valid ini [[[", mode="delete")
    result = model.run()
    assert isinstance(result, kalix.Model)


# --- informative error messages -----------------------------------------

def test_load_file_missing_file_message_is_informative():
    path = _REPO_ROOT / "does_not_exist.ini"
    with pytest.raises(OSError, match=r"does_not_exist\.ini"):
        kalix.load_file(str(path))


def test_load_file_invalid_ini_message_is_informative(tmp_path):
    bad = tmp_path / "bad.ini"
    bad.write_text("this is not valid kalix ini syntax {{{")
    with pytest.raises(ValueError, match=r"bad\.ini"):
        kalix.load_file(str(bad))


def test_load_string_invalid_message_is_informative():
    with pytest.raises(ValueError, match="model string"):
        kalix.load_string("this is not valid kalix ini syntax {{{")


def test_load_file_distinguishes_missing_file_from_invalid_content(tmp_path):
    """The split comes from a typed Io/Parse distinction threaded through the
    whole read chain in Rust (not from sniffing the error message in
    Python) -- lock in that a missing file and a bad file get different,
    non-overlapping exception types."""
    missing = tmp_path / "does_not_exist.ini"
    bad = tmp_path / "bad.ini"
    bad.write_text("this is not valid kalix ini syntax {{{")

    with pytest.raises(OSError) as missing_exc:
        kalix.load_file(str(missing))
    with pytest.raises(ValueError) as bad_exc:
        kalix.load_file(str(bad))

    assert not isinstance(missing_exc.value, ValueError)
    assert not isinstance(bad_exc.value, OSError)


def test_load_string_missing_referenced_input_file_raises_oserror():
    """A model string that parses fine but references a missing input CSV
    must raise OSError, not ValueError -- a real filesystem problem must not
    be mistaken for a syntax error (the bug this typed error chain fixes)."""
    ini = (
        "[kalix]\n"
        "start = 2000-01-01T00:00:00\n"
        "end = 2000-01-10T00:00:00\n"
        "\n"
        "[inputs]\n"
        "./does_not_exist_test_model_py.csv\n"
        "\n"
        "[node.my_node]\n"
        "loc = 0,0\n"
        "type = inflow\n"
        "inflow = 1.0\n"
    )
    with pytest.raises(OSError):
        kalix.load_string(ini)


def test_load_file_missing_referenced_input_file_raises_oserror(tmp_path):
    """Same as test_load_string_missing_referenced_input_file_raises_oserror,
    but via a model file rather than an in-memory string."""
    model_file = tmp_path / "model.ini"
    model_file.write_text(
        "[kalix]\n"
        "start = 2000-01-01T00:00:00\n"
        "end = 2000-01-10T00:00:00\n"
        "\n"
        "[inputs]\n"
        "./does_not_exist_test_model_py.csv\n"
        "\n"
        "[node.my_node]\n"
        "loc = 0,0\n"
        "type = inflow\n"
        "inflow = 1.0\n"
    )
    with pytest.raises(OSError):
        kalix.load_file(str(model_file))


def test_load_file_accepts_pathlib_path():
    model = kalix.load_file(_MODEL_INI)  # a Path, not str(_MODEL_INI)
    assert isinstance(model, kalix.Model)


def test_model_direct_construction():
    model = kalix.Model()
    assert isinstance(model, kalix.Model)
    model.from_file(str(_MODEL_INI))
    assert isinstance(model, kalix.Model)


# --- get_outputs() --------------------------------------------------------

_DECLARED_OUTPUTS = [
    "node.my_gr4j_node.dsflow",
    "node.my_gr4j_node.runoff_volume",
    "node.node1.dsflow",
    "node.node2.dsflow",
    "node.node3.dsflow",
    "node.reach2.volume",
    "node.reach2.dsflow",
    "node.reach3.volume",
    "node.reach3.dsflow",
    "node.reach4.volume",
    "node.reach4.dsflow",
    "node.reach5.volume",
    "node.reach5.dsflow",
    "node.my_sacr_node.dsflow",
]


def test_get_outputs_returns_dataframe_after_run():
    model = kalix.load_file(str(_MODEL_INI)).run()
    df = model.get_outputs()
    assert isinstance(df, pd.DataFrame)
    assert set(df.columns) == set(_DECLARED_OUTPUTS)


def test_get_outputs_index_is_named_time_and_utc():
    model = kalix.load_file(str(_MODEL_INI)).run()
    df = model.get_outputs()
    assert df.index.name == "time"
    assert isinstance(df.index, pd.DatetimeIndex)
    assert df.index.tz is not None


def test_get_outputs_columns_are_float64():
    model = kalix.load_file(str(_MODEL_INI)).run()
    df = model.get_outputs()
    for col in df.columns:
        assert df[col].dtype == np.float64


def test_get_outputs_with_explicit_names_selects_subset():
    model = kalix.load_file(str(_MODEL_INI)).run()
    df = model.get_outputs(["node.node1.dsflow", "node.node3.dsflow"])
    assert list(df.columns) == ["node.node1.dsflow", "node.node3.dsflow"]


def test_get_outputs_row_count_matches_simulation_length():
    model = kalix.load_file(str(_MODEL_INI)).run()
    df = model.get_outputs()
    assert len(df) > 0
    # Every declared output must share the same simulation length.
    assert len(df) == len(model.get_outputs(["node.node1.dsflow"]))


def test_get_outputs_undeclared_name_raises_value_error():
    model = kalix.load_file(str(_MODEL_INI)).run()
    with pytest.raises(ValueError, match="undeclared"):
        model.get_outputs(["node.not_a_real_output.dsflow"])


def test_get_outputs_before_run_with_explicit_name_raises_value_error():
    """An unrun model fails fast with a message naming the actual problem,
    not silently empty/absent data."""
    model = kalix.load_file(str(_MODEL_INI))
    with pytest.raises(ValueError, match="has not been run"):
        model.get_outputs(["node.node1.dsflow"])


def test_get_outputs_before_run_with_no_names_raises_value_error():
    """`names=None` fails fast on an unrun model too -- an empty DataFrame
    would silently hide a forgotten run()."""
    model = kalix.load_file(str(_MODEL_INI))
    with pytest.raises(ValueError, match="has not been run"):
        model.get_outputs()


def test_get_outputs_after_patch_raises_until_rerun():
    """Patching invalidates previous results: the pre-patch outputs no
    longer describe the model now held, so get_outputs() must refuse until
    the patched model has been run again."""
    model = kalix.load_file(str(_MODEL_INI)).run()
    model.patch("[node.reach2]\nlag = 5\n")
    with pytest.raises(ValueError, match="has not been run"):
        model.get_outputs()
    df = model.run().get_outputs()
    assert isinstance(df, pd.DataFrame)
    assert len(df) > 0


def test_get_outputs_after_failed_patch_still_works():
    """A rejected patch leaves the model -- and its run results -- intact,
    so get_outputs() keeps working."""
    model = kalix.load_file(str(_MODEL_INI)).run()
    with pytest.raises(ValueError):
        model.patch("not valid ini [[[")
    df = model.get_outputs()
    assert len(df) > 0


def test_get_outputs_after_reload_raises_until_rerun():
    """Loading a new model into an existing instance discards the previous
    run's results."""
    model = kalix.load_file(str(_MODEL_INI)).run()
    model.from_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError, match="has not been run"):
        model.get_outputs()


def test_get_outputs_on_inline_model():
    model = kalix.load_string(_INLINE_MODEL_INI).run()
    df = model.get_outputs()
    assert list(df.columns) == ["node.my_node.ds_1"]
    assert (df["node.my_node.ds_1"] == 1.0).all()
    assert len(df) == 10  # 2000-01-01 to 2000-01-10 inclusive, daily default step
    assert df.index[0] == pd.Timestamp("2000-01-01", tz="UTC")
