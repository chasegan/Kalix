"""Round-trip tests for kalix.read_kaz / kalix.write_kaz."""
from __future__ import annotations

import numpy as np
import pandas as pd
import pytest

import kalix


def _make_df(n: int = 100) -> pd.DataFrame:
    idx = pd.date_range("2020-01-01", periods=n, freq="h", tz="UTC")
    idx.name = "time"
    return pd.DataFrame(
        {
            "flow": np.sin(np.arange(n) * 0.1) * 10.0,
            "level": np.cos(np.arange(n) * 0.05) * 5.0 + 100.0,
        },
        index=idx,
    )


def test_roundtrip_basic(tmp_path):
    df = _make_df(1000)
    path = tmp_path / "test.kaz"

    kalix.write_kaz(path, df)
    assert (tmp_path / "test.kaz").exists()
    assert (tmp_path / "test.kai").exists()

    df2 = kalix.read_kaz(path)
    pd.testing.assert_frame_equal(df, df2, check_freq=False)


def test_read_accepts_kai_extension(tmp_path):
    df = _make_df(50)
    base = tmp_path / "x"
    kalix.write_kaz(str(base) + ".kaz", df)

    df2 = kalix.read_kaz(str(base) + ".kai")
    pd.testing.assert_frame_equal(df, df2, check_freq=False)


def test_read_accepts_base_path(tmp_path):
    df = _make_df(50)
    base = tmp_path / "x"
    kalix.write_kaz(str(base) + ".kaz", df)

    df2 = kalix.read_kaz(str(base))
    pd.testing.assert_frame_equal(df, df2, check_freq=False)


def test_pre1970_timestamps(tmp_path):
    """1889-01-01 round-trip — the case that exposed the wrap_to_i64 bug earlier."""
    idx = pd.date_range("1889-01-01", periods=24, freq="h", tz="UTC")
    idx.name = "time"
    df = pd.DataFrame({"v": np.arange(24, dtype=float)}, index=idx)
    path = tmp_path / "old.kaz"

    kalix.write_kaz(path, df)
    df2 = kalix.read_kaz(path)
    pd.testing.assert_frame_equal(df, df2, check_freq=False)


def test_nan_preserved(tmp_path):
    n = 10
    idx = pd.date_range("2020-01-01", periods=n, freq="h", tz="UTC")
    idx.name = "time"
    vals = np.arange(n, dtype=float)
    vals[3] = np.nan
    vals[7] = np.nan
    df = pd.DataFrame({"v": vals}, index=idx)
    path = tmp_path / "nan.kaz"

    kalix.write_kaz(path, df)
    df2 = kalix.read_kaz(path)
    np.testing.assert_array_equal(df["v"].values, df2["v"].values)


def test_write_rejects_irregular_timestep(tmp_path):
    # Build irregular index by stitching together two ranges.
    idx = pd.DatetimeIndex(
        ["2020-01-01 00:00", "2020-01-01 01:00", "2020-01-01 03:00"],
        tz="UTC",
        name="time",
    )
    df = pd.DataFrame({"v": [1.0, 2.0, 3.0]}, index=idx)
    with pytest.raises(ValueError, match="Irregular timestep"):
        kalix.write_kaz(tmp_path / "bad.kaz", df)


def test_write_requires_datetime_index(tmp_path):
    df = pd.DataFrame({"v": [1.0, 2.0, 3.0]})  # default RangeIndex
    with pytest.raises(TypeError, match="DatetimeIndex"):
        kalix.write_kaz(tmp_path / "bad.kaz", df)


def test_cli_kaz_output_is_readable(tmp_path):
    """If the kalix CLI produces .kaz/.kai files, we can read them.

    Skipped if the kalix binary isn't on PATH; this is a smoke check, not
    a strict requirement.
    """
    import shutil
    import subprocess

    kalix_bin = shutil.which("kalix") or "/Users/chas/github/Kalix/target/release/kalix"
    example_models = (
        "/Users/chas/github/Kalix/kalixide/example_models/1/model.ini"
    )
    import os
    if not os.path.exists(example_models) or not os.path.exists(kalix_bin):
        pytest.skip("kalix binary or example model unavailable")

    out_base = tmp_path / "sim"
    result = subprocess.run(
        [kalix_bin, "simulate", example_models, "-o", str(out_base) + ".kaz"],
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert result.returncode == 0, result.stderr
    assert (tmp_path / "sim.kaz").exists()
    assert (tmp_path / "sim.kai").exists()

    df = kalix.read_kaz(str(out_base) + ".kaz")
    assert len(df) > 0
    assert len(df.columns) > 0
