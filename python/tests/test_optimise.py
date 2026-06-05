"""Tests for kalix.optimise (the stateless, file-to-result calibration entry).

Uses a tiny self-contained Beale-function model: a single inflow node whose
inflow expression *is* the Beale function of two constants, minimised at
(x, y) = (3, 0.5) with value 0. No external input data is required, so the
fixtures are generated inline and the test runs in well under a second.
"""
from __future__ import annotations

import re
import textwrap

import pytest

import kalix


def _constant(ini: str, name: str) -> float:
    """Pull the numeric value of a `[constants]` entry from a model INI string."""
    m = re.search(rf"^{re.escape(name)}\s*=\s*([-\d.eE+]+)", ini, re.MULTILINE)
    assert m is not None, f"{name} not found in optimised model INI"
    return float(m.group(1))

# The Beale function as a kalix inflow expression over constants c.x, c.y.
_BEALE_MODEL = textwrap.dedent("""\
    [kalix]
    start = 2000-01-01T00:00:00
    end = 2000-01-10T00:00:00

    [constants]
    c.x = 0.46
    c.y = 0.34

    [node.my_node]
    loc = 0,0
    type = inflow
    inflow = (1.5 - c.x + c.x*c.y)^2 + (2.25 - c.x + c.x * c.y^2)^2 + (2.625 - c.x + c.x * c.y^3)^2
    recession_factor = 0

    [outputs]
    node.my_node.ds_1
""")


def _write_fixtures(tmp_path, *, model_file_in_config=True):
    """Write model, observed-zeros, and config files; return the config path."""
    model = tmp_path / "beale_model.ini"
    model.write_text(_BEALE_MODEL)

    zeros = tmp_path / "zeros.csv"
    lines = ["Datetime,obs"] + [f"2000-01-{d:02d},0.0" for d in range(1, 11)]
    zeros.write_text("\n".join(lines) + "\n")

    # Own template line, no trailing newline — keeps indentation uniform so
    # textwrap.dedent works; an empty value just leaves a blank line (ignored).
    model_line = f"model_file = {model}" if model_file_in_config else ""
    cfg = tmp_path / "opt.ini"
    cfg.write_text(textwrap.dedent(f"""\
        [optimisation]
        {model_line}
        objective_expression = term1
        algorithm = SCE
        complexes = 4
        termination_evaluations = 2000
        n_threads = 1
        random_seed = 12345

        [term.term1]
        simulated = node.my_node.ds_1
        observed_file = {zeros}
        observed_series = 1
        statistic = MAE

        [parameters]
        c.x = lin_range(g(1),-4.5,4.5)
        c.y = lin_range(g(2),-4.5,4.5)
    """))
    return cfg, model


def test_optimise_finds_beale_minimum(tmp_path):
    cfg, _ = _write_fixtures(tmp_path)
    res = kalix.optimise(cfg)

    assert set(res) == {
        "best_objective", "n_evaluations", "success",
        "message", "parameters", "optimised_model_ini",
    }
    assert isinstance(res["best_objective"], float)
    assert isinstance(res["n_evaluations"], int) and res["n_evaluations"] > 0
    assert res["success"] is True
    assert isinstance(res["message"], str)

    # Beale minimum is at (3, 0.5) with objective 0.
    assert res["best_objective"] == pytest.approx(0.0, abs=1e-3)
    assert res["parameters"]["c.x"] == pytest.approx(3.0, abs=1e-2)
    assert res["parameters"]["c.y"] == pytest.approx(0.5, abs=1e-2)


def test_optimise_returns_optimised_model_ini(tmp_path):
    cfg, _ = _write_fixtures(tmp_path)
    res = kalix.optimise(cfg)

    ini = res["optimised_model_ini"]
    assert isinstance(ini, str) and "[constants]" in ini
    # The tuned constants are serialised back into the model string and match
    # the returned physical parameters (value-formatting-agnostic).
    assert _constant(ini, "c.x") == pytest.approx(res["parameters"]["c.x"])
    assert _constant(ini, "c.y") == pytest.approx(res["parameters"]["c.y"])


def test_optimise_save_model_writes_file(tmp_path):
    cfg, _ = _write_fixtures(tmp_path)
    out = tmp_path / "tuned.ini"
    res = kalix.optimise(cfg, save_model=out)

    assert out.exists()
    saved = out.read_text()
    assert saved == res["optimised_model_ini"]


def test_optimise_model_file_override(tmp_path):
    """A config without model_file still runs when model_file is passed in."""
    cfg, model = _write_fixtures(tmp_path, model_file_in_config=False)
    res = kalix.optimise(cfg, model_file=model)
    assert res["success"] is True


def test_optimise_missing_model_raises(tmp_path):
    """No model_file in config and none passed → a clear error."""
    cfg, _ = _write_fixtures(tmp_path, model_file_in_config=False)
    with pytest.raises(RuntimeError, match="model_file"):
        kalix.optimise(cfg)


def test_optimise_raises_on_missing_config(tmp_path):
    with pytest.raises(RuntimeError):
        kalix.optimise(tmp_path / "does_not_exist.ini")


def test_optimise_requires_keyword_model_file(tmp_path):
    """model_file and save_model are keyword-only, mirroring the CLI's flags."""
    cfg, model = _write_fixtures(tmp_path, model_file_in_config=False)
    with pytest.raises(TypeError):
        kalix.optimise(cfg, model)  # type: ignore[misc]
