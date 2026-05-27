"""Round-trip tests for kalix.read_pixie / kalix.write_pixie."""
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
    path = tmp_path / "test.pxb"

    kalix.write_pixie(path, df)
    assert (tmp_path / "test.pxb").exists()
    assert (tmp_path / "test.pxt").exists()

    df2 = kalix.read_pixie(path)
    pd.testing.assert_frame_equal(df, df2, check_freq=False)


def test_read_accepts_pxt_extension(tmp_path):
    df = _make_df(50)
    base = tmp_path / "x"
    kalix.write_pixie(str(base) + ".pxb", df)

    df2 = kalix.read_pixie(str(base) + ".pxt")
    pd.testing.assert_frame_equal(df, df2, check_freq=False)


def test_read_accepts_base_path(tmp_path):
    df = _make_df(50)
    base = tmp_path / "x"
    kalix.write_pixie(str(base) + ".pxb", df)

    df2 = kalix.read_pixie(str(base))
    pd.testing.assert_frame_equal(df, df2, check_freq=False)


def test_pre1970_timestamps(tmp_path):
    """1889-01-01 round-trip — the case that exposed the wrap_to_i64 bug earlier."""
    idx = pd.date_range("1889-01-01", periods=24, freq="h", tz="UTC")
    idx.name = "time"
    df = pd.DataFrame({"v": np.arange(24, dtype=float)}, index=idx)
    path = tmp_path / "old.pxb"

    kalix.write_pixie(path, df)
    df2 = kalix.read_pixie(path)
    pd.testing.assert_frame_equal(df, df2, check_freq=False)


def test_nan_preserved(tmp_path):
    n = 10
    idx = pd.date_range("2020-01-01", periods=n, freq="h", tz="UTC")
    idx.name = "time"
    vals = np.arange(n, dtype=float)
    vals[3] = np.nan
    vals[7] = np.nan
    df = pd.DataFrame({"v": vals}, index=idx)
    path = tmp_path / "nan.pxb"

    kalix.write_pixie(path, df)
    df2 = kalix.read_pixie(path)
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
        kalix.write_pixie(tmp_path / "bad.pxb", df)


def test_write_naive_datetime_index_treated_as_utc(tmp_path):
    n = 24
    idx = pd.date_range("2020-01-01", periods=n, freq="h")  # naive
    df = pd.DataFrame({"v": np.arange(n, dtype=float)}, index=idx)

    kalix.write_pixie(tmp_path / "out.pxb", df)
    df2 = kalix.read_pixie(tmp_path / "out.pxb")

    np.testing.assert_array_equal(df2["v"].to_numpy(), df["v"].to_numpy())
    assert df2.index[0] == pd.Timestamp("2020-01-01", tz="UTC")
    assert df2.index.tz is not None


def test_write_promotes_zeroth_string_column_with_warning(tmp_path):
    df = pd.DataFrame({
        "time": ["2020-01-01", "2020-01-02", "2020-01-03"],
        "flow": [1.0, 2.0, 3.0],
    })
    with pytest.warns(UserWarning, match="auto-converted"):
        kalix.write_pixie(tmp_path / "out.pxb", df)

    df2 = kalix.read_pixie(tmp_path / "out.pxb")
    assert list(df2.columns) == ["flow"]
    assert df2.index[0] == pd.Timestamp("2020-01-01", tz="UTC")
    np.testing.assert_array_equal(df2["flow"].to_numpy(), [1.0, 2.0, 3.0])


def test_write_converts_string_index_with_warning(tmp_path):
    df = pd.DataFrame(
        {"v": [1.0, 2.0, 3.0]},
        index=pd.Index(["2020-01-01", "2020-01-02", "2020-01-03"]),
    )
    with pytest.warns(UserWarning, match="auto-converted"):
        kalix.write_pixie(tmp_path / "out.pxb", df)

    df2 = kalix.read_pixie(tmp_path / "out.pxb")
    assert df2.index[0] == pd.Timestamp("2020-01-01", tz="UTC")
    np.testing.assert_array_equal(df2["v"].to_numpy(), [1.0, 2.0, 3.0])


def test_write_rejects_integer_zeroth_column(tmp_path):
    df = pd.DataFrame({
        "epoch_ish": [0, 1, 2],
        "flow": [1.0, 2.0, 3.0],
    })
    with pytest.raises(TypeError, match="Integer/float"):
        kalix.write_pixie(tmp_path / "bad.pxb", df)


def test_write_rejects_float_zeroth_column(tmp_path):
    # Original failing case: a frame with default RangeIndex and a float
    # zeroth column should error (was the old "requires DatetimeIndex" path).
    df = pd.DataFrame({"v": [1.0, 2.0, 3.0]})
    with pytest.raises(TypeError, match="Integer/float"):
        kalix.write_pixie(tmp_path / "bad.pxb", df)


def test_write_rejects_integer_index(tmp_path):
    df = pd.DataFrame(
        {"v": [1.0, 2.0, 3.0]},
        index=pd.Index([100, 200, 300]),
    )
    with pytest.raises(TypeError, match="Integer/float"):
        kalix.write_pixie(tmp_path / "bad.pxb", df)


def test_write_does_not_mutate_input(tmp_path):
    df = pd.DataFrame({
        "time": ["2020-01-01", "2020-01-02"],
        "v": [1.0, 2.0],
    })
    original = df.copy(deep=True)

    with pytest.warns(UserWarning):
        kalix.write_pixie(tmp_path / "out.pxb", df)

    pd.testing.assert_frame_equal(df, original)


def test_cli_pixie_output_is_readable(tmp_path):
    """If the kalix CLI produces .pxt/.pxb files, we can read them.

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
        [kalix_bin, "simulate", example_models, "-o", str(out_base) + ".pxb"],
        capture_output=True,
        text=True,
        timeout=60,
    )
    assert result.returncode == 0, result.stderr
    assert (tmp_path / "sim.pxb").exists()
    assert (tmp_path / "sim.pxt").exists()

    df = kalix.read_pixie(str(out_base) + ".pxb")
    assert len(df) > 0
    assert len(df.columns) > 0
