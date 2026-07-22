"""Tests for kalix.Model / kalix.load_file / kalix.load_string.

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
    model = kalix.Model().load_file(str(_MODEL_INI))
    assert isinstance(model, kalix.Model)


def test_model_from_file_classmethod_constructs_new_model():
    """`Model.from_file` is a classmethod constructor, distinct from the
    instance-level `load_file` used to reload an existing `Model`."""
    model = kalix.Model.from_file(str(_MODEL_INI))
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
    model = kalix.Model()
    result = model.load_string(_INLINE_MODEL_INI)
    assert result is model


def test_load_string_invalid_ini_raises():
    with pytest.raises(ValueError):
        kalix.Model().load_string("this is not valid kalix ini syntax {{{")


def test_module_level_load_string_returns_configured_model():
    model = kalix.load_string(_INLINE_MODEL_INI)
    assert isinstance(model, kalix.Model)


def test_model_from_string_classmethod_constructs_new_model():
    model = kalix.Model.from_string(_INLINE_MODEL_INI)
    assert isinstance(model, kalix.Model)


def test_run_completes_without_error():
    model = kalix.load_file(str(_MODEL_INI))
    result = model.run()
    assert isinstance(result, kalix.Model)


def test_run_returns_same_instance_for_chaining():
    model = kalix.load_file(str(_MODEL_INI))
    assert model.run() is model


def test_patch_merge_returns_same_instance_for_chaining():
    model = kalix.load_string(_INLINE_MODEL_INI)
    assert model.patch("[node.my_node]\ninflow = 2.0\n") is model


def test_patch_merge_can_run_after_patching():
    """A patched property is actually picked up by the model that runs --
    not just accepted and ignored. `lag` must be a positive-enough value
    that `configure()` still accepts the routing node."""
    model = kalix.load_file(str(_MODEL_INI)).patch("[node.reach2]\nlag = 5\n")
    result = model.run()
    assert isinstance(result, kalix.Model)


def test_patch_merge_adds_new_section():
    """Sections absent from the original model can be added by a patch, not
    just existing ones overridden."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    result = model.patch("[node.new_sink]\ntype = blackhole\nloc = 1, 1\n")
    assert isinstance(result, kalix.Model)


def test_patch_merge_invalid_syntax_raises_value_error():
    with pytest.raises(ValueError):
        kalix.load_string(_INLINE_MODEL_INI).patch("not valid ini [[[")


def test_patch_merge_invalid_result_raises_value_error():
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


def test_patch_merge_leaves_model_untouched_on_failure():
    """A rejected patch must not damage the already-loaded model -- it
    should still run exactly as before the failed patch attempt."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch("not valid ini [[[")
    result = model.run()
    assert isinstance(result, kalix.Model)


# --- patch(mode="replace") ----------------------------------------------
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


def test_patch_replace_returns_same_instance_for_chaining():
    model = kalix.load_string(_INLINE_MODEL_INI)
    result = model.patch("[node.my_node]\ntype = inflow\ninflow = 2.0\nloc = 0,0\n", mode="replace")
    assert result is model


def test_patch_replace_can_run_after_patching():
    """A full, valid replacement section is accepted and the model still
    runs -- unlike patch_merge, replace requires every property the
    section needs since it isn't merged with the original."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    model.patch("[node.my_node]\ntype = inflow\ninflow = 2.0\nloc = 0,0\n", mode="replace")
    result = model.run()
    assert isinstance(result, kalix.Model)


def test_patch_replace_drops_properties_omitted_from_patch():
    """Unlike patch_merge, replace replaces the whole section -- a
    property that exists on the original but is omitted from the patch
    does not survive. Dropping the required `type` property here makes the
    section unparseable, surfacing as a ValueError."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch("[node.my_node]\ninflow = 2.0\n", mode="replace")


def test_patch_replace_adds_new_section():
    model = kalix.load_string(_INLINE_MODEL_INI)
    result = model.patch("[node.new_sink]\ntype = blackhole\nloc = 1, 1\n", mode="replace")
    assert isinstance(result, kalix.Model)


def test_patch_replace_invalid_syntax_raises_value_error():
    with pytest.raises(ValueError):
        kalix.load_string(_INLINE_MODEL_INI).patch("not valid ini [[[", mode="replace")


def test_patch_replace_invalid_result_raises_value_error():
    with pytest.raises(ValueError, match="node_that_does_not_exist"):
        kalix.load_string(_INLINE_MODEL_INI).patch(
            "[node.my_node]\ntype = inflow\ninflow = 2.0\nloc = 0,0\nds_1 = node_that_does_not_exist\n",
            mode="replace",
        )


def test_patch_replace_leaves_model_untouched_on_failure():
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch("not valid ini [[[", mode="replace")
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


# --- patch() dict form -----------------------------------------------------
# `patch()` also accepts `{section_name: {key: value}}` as pure sugar over
# the string form (spec sec 4.5) -- same validation, same atomicity, same
# underlying write path, just skipping manual INI-string assembly.

def test_patch_dict_merge_returns_same_instance_for_chaining():
    model = kalix.load_string(_INLINE_MODEL_INI)
    assert model.patch({"node.my_node": {"inflow": 2.0}}) is model


def test_patch_dict_merge_can_run_after_patching():
    model = kalix.load_file(str(_MODEL_INI)).patch({"node.reach2": {"lag": 5}})
    result = model.run()
    assert isinstance(result, kalix.Model)


def test_patch_dict_merge_adds_new_section():
    model = kalix.load_string(_INLINE_MODEL_INI)
    result = model.patch({"node.new_sink": {"type": "blackhole", "loc": "1, 1"}})
    assert isinstance(result, kalix.Model)


def test_patch_dict_values_are_stringified():
    """Values are passed through `str()` (sec 4.5) -- an `int` here is just
    as valid as the string form's `"3"` would be."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    model.patch({"node.my_node": {"inflow": 3}})
    result = model.run()
    assert isinstance(result, kalix.Model)


def test_patch_dict_empty_string_value_emits_bare_line():
    """A key mapped to the empty string emits a bare line, matching the
    list-style convention used by sections like [outputs] (sec 4.5, 5.2)."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    model.patch({"outputs": {"node.my_node.ds_1": ""}})
    result = model.run()
    assert isinstance(result, kalix.Model)


def test_patch_dict_merge_invalid_result_raises_value_error():
    with pytest.raises(ValueError, match="node_that_does_not_exist"):
        kalix.load_string(_INLINE_MODEL_INI).patch(
            {"node.my_node": {"ds_1": "node_that_does_not_exist"}}
        )


def test_patch_dict_merge_leaves_model_untouched_on_failure():
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch({"node.my_node": {"ds_1": "node_that_does_not_exist"}})
    result = model.run()
    assert isinstance(result, kalix.Model)


def test_patch_dict_merge_type_change_rejects_stale_properties():
    """A merge that only changes `type` leaves the old type's properties
    behind, which the new type doesn't recognise -- sec 4.4's rule holds
    for the dict form exactly as it does for the string form (sec 4.5)."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch({"node.my_node": {"type": "blackhole"}})


def test_patch_dict_replace_drops_properties_omitted_from_patch():
    """Like the string form, replace via dict does not merge with the
    original section -- omitting the required `type` here makes the
    section unparseable."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch({"node.my_node": {"inflow": 2.0}}, mode="replace")


def test_patch_dict_replace_can_run_after_patching():
    model = kalix.load_string(_INLINE_MODEL_INI)
    model.patch(
        {"node.my_node": {"type": "inflow", "inflow": 2.0, "loc": "0,0"}},
        mode="replace",
    )
    result = model.run()
    assert isinstance(result, kalix.Model)


def test_patch_dict_delete_removes_entire_section_when_empty_dict():
    """An empty dict is the dict-form equivalent of a delete header with no
    property lines under it (sec 4.3)."""
    model = kalix.load_string(_INLINE_MODEL_WITH_EXTRA_NODE_INI)
    model.patch({"node.extra_node": {}}, mode="delete")
    result = model.run()
    assert isinstance(result, kalix.Model)


def test_patch_dict_delete_rejects_properties():
    """Property-level deletion is unsupported for the dict form too -- a
    non-empty dict under a delete section is rejected outright."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch({"node.my_node": {"not_a_real_property": ""}}, mode="delete")


def test_patch_dict_delete_ignores_missing_section_when_missing_ok():
    model = kalix.load_string(_INLINE_MODEL_INI)
    result = model.patch({"node.missing": {}}, mode="delete", missing_ok=True)
    assert isinstance(result.run(), kalix.Model)


def test_patch_dict_delete_rejects_missing_section_when_not_missing_ok():
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch({"node.missing": {}}, mode="delete")


def test_patch_dict_multi_section_is_atomic_on_failure():
    """A multi-section dict patch where one section is invalid must not
    partially apply the sections that were individually fine -- no partial
    application, ever, including multi-section snippets (sec 4.6)."""
    model = kalix.load_string(_INLINE_MODEL_WITH_EXTRA_NODE_INI)
    model.run()
    baseline = model.get_outputs()

    with pytest.raises(ValueError):
        model.patch({
            "node.my_node": {"inflow": 999.0},
            "node.extra_node": {"bogus_property": "1"},
        })

    result = model.run()
    pd.testing.assert_frame_equal(result.get_outputs(), baseline)


# --- patch(mode="delete") with list-of-names form (sec 4.3) ---------------
# A second independent leaf node alongside `node.extra_node`, so both can be
# named in a single list-form delete without either depending on the other.
_INLINE_MODEL_WITH_TWO_EXTRA_NODES_INI = (
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
    "[node.extra_node2]\n"
    "loc = 2,2\n"
    "type = inflow\n"
    "inflow = 3.0\n"
    "\n"
    "[outputs]\n"
    "node.my_node.ds_1\n"
)


def test_patch_list_delete_returns_same_instance_for_chaining():
    model = kalix.load_string(_INLINE_MODEL_WITH_EXTRA_NODE_INI)
    result = model.patch(["node.extra_node"], mode="delete")
    assert result is model


def test_patch_list_delete_removes_single_named_section():
    model = kalix.load_string(_INLINE_MODEL_WITH_EXTRA_NODE_INI)
    model.patch(["node.extra_node"], mode="delete")
    result = model.run()
    assert isinstance(result, kalix.Model)


def test_patch_list_delete_removes_multiple_named_sections():
    """The list form's whole point is naming several sections in one call
    (sec 4.3's `["node.old_gauge", "var.tmp"]` example)."""
    model = kalix.load_string(_INLINE_MODEL_WITH_TWO_EXTRA_NODES_INI)
    model.patch(["node.extra_node", "node.extra_node2"], mode="delete")
    result = model.run()
    assert isinstance(result, kalix.Model)


def test_patch_list_delete_ignores_missing_section_when_missing_ok():
    model = kalix.load_string(_INLINE_MODEL_INI)
    result = model.patch(["node.missing"], mode="delete", missing_ok=True)
    assert isinstance(result.run(), kalix.Model)


def test_patch_list_delete_rejects_missing_section_when_not_missing_ok():
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch(["node.missing"], mode="delete")


def test_patch_list_merge_rejects_list_form():
    """The list-of-names shorthand only has a sensible meaning for delete --
    passing it under merge/replace must raise rather than silently create
    empty sections."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch(["node.my_node"])


def test_patch_list_replace_rejects_list_form():
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch(["node.my_node"], mode="replace")


def test_patch_list_delete_leaves_model_untouched_on_failure():
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError):
        model.patch(["node.missing"], mode="delete")
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
    model.load_file(str(_MODEL_INI))
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


def test_get_outputs_accepts_bare_string_name():
    """A single name may be passed as a bare `str`, not just `[str]` --
    the signature advertises `Optional[str | List[str]]`."""
    model = kalix.load_file(str(_MODEL_INI)).run()
    df = model.get_outputs("node.node1.dsflow")
    assert list(df.columns) == ["node.node1.dsflow"]


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
    model.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError, match="has not been run"):
        model.get_outputs()


def test_get_outputs_on_inline_model():
    model = kalix.load_string(_INLINE_MODEL_INI).run()
    df = model.get_outputs()
    assert list(df.columns) == ["node.my_node.ds_1"]
    assert (df["node.my_node.ds_1"] == 1.0).all()
    assert len(df) == 10  # 2000-01-01 to 2000-01-10 inclusive, daily default step
    assert df.index[0] == pd.Timestamp("2000-01-01", tz="UTC")


# --- get_outputs() casing --------------------------------------------------
# [outputs] declares "Node.MyNode.DsFlow" in deliberately mixed case, to pin
# that name matching is case-insensitive but the returned column always
# carries the casing declared in [outputs] -- not whatever casing the caller
# passed to get_outputs(), and not lower-cased either.

_MIXED_CASE_OUTPUT_INI = (
    "[kalix]\n"
    "start = 2000-01-01T00:00:00\n"
    "end = 2000-01-05T00:00:00\n"
    "\n"
    "[node.my_node]\n"
    "loc = 0,0\n"
    "type = inflow\n"
    "inflow = 1.0\n"
    "\n"
    "[outputs]\n"
    "Node.My_Node.Ds_1\n"
)


def test_get_outputs_column_name_matches_declared_casing():
    model = kalix.load_string(_MIXED_CASE_OUTPUT_INI).run()
    df = model.get_outputs()
    assert list(df.columns) == ["Node.My_Node.Ds_1"]


def test_get_outputs_explicit_name_lookup_is_case_insensitive():
    """Requesting the output under a different casing than it was declared
    under still succeeds -- lookup is case-insensitive."""
    model = kalix.load_string(_MIXED_CASE_OUTPUT_INI).run()
    df = model.get_outputs(["node.my_node.ds_1"])
    assert len(df) > 0


def test_get_outputs_explicit_name_returns_declared_casing():
    """...but the returned column is still named with the casing declared
    in [outputs], not the casing passed to get_outputs()."""
    model = kalix.load_string(_MIXED_CASE_OUTPUT_INI).run()
    df = model.get_outputs(["node.my_node.ds_1"])
    assert list(df.columns) == ["Node.My_Node.Ds_1"]


# --- Interrogating the model definition (spec sec. 5) -----------------------


def test_sections_lists_names_in_file_order():
    model = kalix.load_string(_INLINE_MODEL_INI)
    assert model.sections() == ["kalix", "node.my_node", "outputs"]


def test_has_section_true_for_existing_section():
    model = kalix.load_string(_INLINE_MODEL_INI)
    assert model.has_section("node.my_node") is True


def test_has_section_false_for_missing_section():
    model = kalix.load_string(_INLINE_MODEL_INI)
    assert model.has_section("node.nonexistent") is False


def test_get_section_returns_properties_as_dict():
    model = kalix.load_string(_INLINE_MODEL_INI)
    assert model.get_section("node.my_node") == {
        "loc": "0,0",
        "type": "inflow",
        "inflow": "1.0",
    }


def test_get_section_is_a_snapshot_not_a_live_view():
    """Mutating the returned dict must not touch the model (sec. 5.1) --
    the only write path is `patch()`."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    section = model.get_section("node.my_node")
    section["type"] = "mutated"
    del section["loc"]
    assert model.get_section("node.my_node") == {
        "loc": "0,0",
        "type": "inflow",
        "inflow": "1.0",
    }


def test_get_section_missing_section_raises_key_error():
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(KeyError, match="node.nonexistent"):
        model.get_section("node.nonexistent")


def test_get_section_list_style_section_has_empty_string_values():
    """`[outputs]` is list-style: bare lines come back as keys mapped to
    empty-string values (sec. 5.2)."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    assert model.get_section("outputs") == {"node.my_node.ds_1": ""}


def test_get_returns_property_value_by_dotted_designation():
    model = kalix.load_string(_INLINE_MODEL_INI)
    assert model.get("node.my_node.inflow") == "1.0"


def test_get_handles_section_names_that_themselves_contain_dots():
    """Section names are dotted (e.g. "node.my_node"), so `get()` must split
    on the *last* dot, not the first."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    assert model.get("node.my_node.type") == "inflow"


def test_get_missing_section_raises_key_error():
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(KeyError, match="node.nonexistent"):
        model.get("node.nonexistent.inflow")


def test_get_missing_property_raises_key_error():
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(KeyError, match="nonexistent"):
        model.get("node.my_node.nonexistent")


def test_get_malformed_designation_without_dot_raises_key_error():
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(KeyError):
        model.get("no_dot_here")


def test_to_string_round_trips_model_content():
    model = kalix.load_string(_INLINE_MODEL_INI)
    text = model.to_string()
    assert isinstance(text, str)
    assert "node.my_node" in text
    assert kalix.load_string(text).sections() == model.sections()


def test_save_writes_to_string_content_to_disk_and_returns_self(tmp_path):
    model = kalix.load_string(_INLINE_MODEL_INI)
    out_path = tmp_path / "saved.ini"

    result = model.save(str(out_path))

    assert result is model
    assert out_path.read_text() == model.to_string()


def test_save_accepts_pathlib_path(tmp_path):
    model = kalix.load_string(_INLINE_MODEL_INI)
    out_path = tmp_path / "saved.ini"
    model.save(out_path)
    assert out_path.exists()


# --- set_input() ------------------------------------------------------------
# A model with a bare declared alias ("obs =", no backing file) plus an
# inflow node addressing both a named and an indexed column of it -- the
# minimal shape set_input() is meant to fill.

_MODEL_WITH_DECLARED_INPUT_INI = (
    "[kalix]\n"
    "start = 2000-01-01T00:00:00\n"
    "end = 2000-01-05T00:00:00\n"
    "\n"
    "[inputs]\n"
    "obs =\n"
    "\n"
    "[node.src]\n"
    "loc = 0,0\n"
    "type = inflow\n"
    "inflow = data.obs.by_name.flow\n"
    "ds_1 = sink\n"
    "\n"
    "[node.sink]\n"
    "loc = 1,1\n"
    "type = blackhole\n"
    "\n"
    "[outputs]\n"
    "node.src.dsflow\n"
)


def _obs_frame():
    index = pd.date_range("2000-01-01", periods=5, freq="D")
    return pd.DataFrame({"flow": [1.0, 2.0, 3.0, 4.0, 5.0]}, index=index)


def test_set_input_returns_same_instance_for_chaining():
    model = kalix.load_string(_MODEL_WITH_DECLARED_INPUT_INI)
    result = model.set_input("obs", _obs_frame())
    assert result is model


def test_set_input_supplies_a_declared_alias_and_model_runs():
    """A bare declaration configure() would otherwise reject as "declared but
    not supplied" is filled by set_input(), and the values it supplies flow
    straight through an inflow node's dsflow output."""
    model = kalix.load_string(_MODEL_WITH_DECLARED_INPUT_INI)
    model.set_input("obs", _obs_frame())
    df = model.run().get_outputs()
    assert list(df["node.src.dsflow"]) == [1.0, 2.0, 3.0, 4.0, 5.0]


def test_set_input_accepts_a_bare_series_as_one_column_frame():
    """A `pd.Series` is sugar for a single-column frame, named after the
    series (falling back to "value" if the series itself has no name) --
    here the series is named "flow", matching the alias's by_name lookup."""
    index = pd.date_range("2000-01-01", periods=5, freq="D")
    series = pd.Series([1.0, 2.0, 3.0, 4.0, 5.0], index=index, name="flow")

    model = kalix.load_string(_MODEL_WITH_DECLARED_INPUT_INI)
    model.set_input("obs", series)
    df = model.run().get_outputs()
    assert list(df["node.src.dsflow"]) == [1.0, 2.0, 3.0, 4.0, 5.0]


def test_set_input_column_addressable_by_index_too():
    ini = _MODEL_WITH_DECLARED_INPUT_INI.replace(
        "data.obs.by_name.flow", "data.obs.by_index.1"
    )
    model = kalix.load_string(ini)
    model.set_input("obs", _obs_frame())
    df = model.run().get_outputs()
    assert list(df["node.src.dsflow"]) == [1.0, 2.0, 3.0, 4.0, 5.0]


def test_set_input_naive_index_assumed_utc():
    """A tz-naive DatetimeIndex is localized to UTC rather than rejected --
    mirroring write_pixie()'s handling of naive indexes."""
    index = pd.date_range("2000-01-01", periods=5, freq="D", tz=None)
    assert index.tz is None
    frame = pd.DataFrame({"flow": [1.0, 2.0, 3.0, 4.0, 5.0]}, index=index)

    model = kalix.load_string(_MODEL_WITH_DECLARED_INPUT_INI)
    model.set_input("obs", frame)
    df = model.run().get_outputs()
    assert list(df["node.src.dsflow"]) == [1.0, 2.0, 3.0, 4.0, 5.0]


def test_set_input_non_datetime_index_raises_type_error():
    frame = pd.DataFrame({"flow": [1.0, 2.0, 3.0]}, index=[0, 1, 2])
    model = kalix.load_string(_MODEL_WITH_DECLARED_INPUT_INI)
    with pytest.raises(TypeError):
        model.set_input("obs", frame)


def test_set_input_undeclared_alias_raises_value_error():
    """set_input() fills an existing [inputs] declaration -- it does not
    create one, so an alias absent from [inputs] altogether is rejected."""
    model = kalix.load_string(_INLINE_MODEL_INI)
    with pytest.raises(ValueError, match="not declared"):
        model.set_input("obs", _obs_frame())


def test_set_input_irregular_timestep_raises_value_error():
    index = pd.DatetimeIndex(
        ["2000-01-01", "2000-01-02", "2000-01-04", "2000-01-05", "2000-01-06"], tz="UTC"
    )
    frame = pd.DataFrame({"flow": [1.0, 2.0, 3.0, 4.0, 5.0]}, index=index)
    model = kalix.load_string(_MODEL_WITH_DECLARED_INPUT_INI)
    with pytest.raises(ValueError):
        model.set_input("obs", frame)


def test_set_input_resets_has_run():
    """Supplying new input data invalidates a prior run's results, same as
    patch() (sec. get_outputs()'s "has not been run" contract)."""
    model = kalix.load_string(_MODEL_WITH_DECLARED_INPUT_INI)
    model.set_input("obs", _obs_frame())
    model.run()
    model.set_input("obs", _obs_frame() * 2)
    with pytest.raises(ValueError, match="has not been run"):
        model.get_outputs()
    df = model.run().get_outputs()
    assert list(df["node.src.dsflow"]) == [2.0, 4.0, 6.0, 8.0, 10.0]


def test_set_input_overrides_an_aliased_file():
    """An `[inputs]` entry that already names a file (`climate = ...`) is
    still a valid target for set_input() -- the supplied data takes
    precedence over the file."""
    ini = (
        "[kalix]\n"
        "start = 2000-01-01T00:00:00\n"
        "end = 2000-01-05T00:00:00\n"
        "\n"
        "[inputs]\n"
        f"climate = {_REPO_ROOT / 'src/tests/example_data/test.csv'}\n"
        "\n"
        "[node.src]\n"
        "loc = 0,0\n"
        "type = inflow\n"
        "inflow = data.climate.by_name.flow\n"
        "ds_1 = sink\n"
        "\n"
        "[node.sink]\n"
        "loc = 1,1\n"
        "type = blackhole\n"
        "\n"
        "[outputs]\n"
        "node.src.dsflow\n"
    )
    model = kalix.load_string(ini)
    model.set_input("climate", _obs_frame())
    df = model.run().get_outputs()
    assert list(df["node.src.dsflow"]) == [1.0, 2.0, 3.0, 4.0, 5.0]


def test_set_input_column_values_coerced_to_float64():
    index = pd.date_range("2000-01-01", periods=5, freq="D")
    frame = pd.DataFrame({"flow": [1, 2, 3, 4, 5]}, index=index)  # int64
    model = kalix.load_string(_MODEL_WITH_DECLARED_INPUT_INI)
    model.set_input("obs", frame)
    df = model.run().get_outputs()
    assert list(df["node.src.dsflow"]) == [1.0, 2.0, 3.0, 4.0, 5.0]


# A two-column supply: one node reads the alias by column name, the other by
# 1-based index, so a multi-column frame's columns must stay independently
# addressable (not collapsed to the first, nor mis-ordered).
_MODEL_WITH_TWO_INPUT_COLUMNS_INI = (
    "[kalix]\n"
    "start = 2000-01-01T00:00:00\n"
    "end = 2000-01-05T00:00:00\n"
    "\n"
    "[inputs]\n"
    "obs =\n"
    "\n"
    "[node.rain_src]\n"
    "loc = 0,0\n"
    "type = inflow\n"
    "inflow = data.obs.by_name.rain\n"
    "ds_1 = sink\n"
    "\n"
    "[node.flow_src]\n"
    "loc = 1,0\n"
    "type = inflow\n"
    "inflow = data.obs.by_index.2\n"
    "ds_1 = sink\n"
    "\n"
    "[node.sink]\n"
    "loc = 2,2\n"
    "type = blackhole\n"
    "\n"
    "[outputs]\n"
    "node.rain_src.dsflow\n"
    "node.flow_src.dsflow\n"
)


def test_set_input_multi_column_frame_addresses_each_column_independently():
    """A DataFrame with several columns fills one alias whose columns stay
    separately addressable -- `rain` by name, the second column by index --
    so neither collapses to the first nor swaps order."""
    index = pd.date_range("2000-01-01", periods=5, freq="D")
    frame = pd.DataFrame(
        {
            "rain": [1.0, 2.0, 3.0, 4.0, 5.0],
            "flow": [10.0, 20.0, 30.0, 40.0, 50.0],
        },
        index=index,
    )
    model = kalix.load_string(_MODEL_WITH_TWO_INPUT_COLUMNS_INI)
    model.set_input("obs", frame)
    df = model.run().get_outputs()
    assert list(df["node.rain_src.dsflow"]) == [1.0, 2.0, 3.0, 4.0, 5.0]
    assert list(df["node.flow_src.dsflow"]) == [10.0, 20.0, 30.0, 40.0, 50.0]


def test_set_input_tz_aware_index_converted_to_utc():
    """A tz-aware index is converted to UTC (not just stripped): a midnight
    New York timestamp lands on the correct UTC instant, so the data still
    aligns to a UTC-midnight simulation step."""
    index = pd.date_range("2000-01-01", periods=5, freq="D", tz="UTC").tz_convert(
        "America/New_York"
    )
    frame = pd.DataFrame({"flow": [1.0, 2.0, 3.0, 4.0, 5.0]}, index=index)
    model = kalix.load_string(_MODEL_WITH_DECLARED_INPUT_INI)
    model.set_input("obs", frame)
    df = model.run().get_outputs()
    assert list(df["node.src.dsflow"]) == [1.0, 2.0, 3.0, 4.0, 5.0]
