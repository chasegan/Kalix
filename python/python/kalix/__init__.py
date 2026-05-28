"""Python interface for Kalix.

v0.1 ships read/write of the Pixie format (Kalix's Gorilla-compressed
timeseries format, .pxt/.pxb paired files), exposed as pandas DataFrames.
"""
from __future__ import annotations

import warnings
from importlib.metadata import PackageNotFoundError, version as _pkg_version
from pathlib import Path
from typing import Union

import numpy as np
import pandas as pd

from kalix._native import _read_pixie_raw, _simulate_from_file, _write_pixie_raw

__all__ = ["read_pixie", "simulate", "write_pixie", "__version__"]

try:
    __version__ = _pkg_version("kalix")
except PackageNotFoundError:
    __version__ = "0.0.0+unknown"

PathLike = Union[str, Path]


def read_pixie(path: PathLike) -> pd.DataFrame:
    """Read a Pixie .pxt/.pxb paired file into a pandas DataFrame.

    Parameters
    ----------
    path
        Path to either the .pxt or .pxb file (or the base path without
        extension). Both files must exist alongside each other.

    Returns
    -------
    DataFrame
        Index is a UTC ``DatetimeIndex`` named ``"time"``; columns are series
        names with float64 values.
    """
    timestamps_sec, series_dict = _read_pixie_raw(str(path))
    index = pd.to_datetime(timestamps_sec, unit="s", utc=True)
    index.name = "time"
    return pd.DataFrame(series_dict, index=index)


def simulate(
    model_file: PathLike,
    *,
    output_file: PathLike,
) -> None:
    """Run a Kalix model from an INI file and write its outputs to disk.

    The Python equivalent of ``kalix sim <model_file> -o <output_file>``,
    but in-process — no separate CLI binary required.

    Parameters
    ----------
    model_file
        Path to the model ``.ini`` file.
    output_file
        Path to write outputs to. The format is inferred from the extension
        (``.pxb`` for the Pixie pair, ``.csv`` for CSV).

    Notes
    -----
    ``output_file`` is keyword-only — call as
    ``kalix.simulate("m.ini", output_file="out.pxb")``. This mirrors the
    CLI's flag-style and leaves room for additional keyword-only outputs
    (mass balance, profiling) to be added without breaking callers.
    """
    _simulate_from_file(str(model_file), str(output_file))


def _is_default_range_index(idx: pd.Index) -> bool:
    return (
        isinstance(idx, pd.RangeIndex)
        and idx.start == 0
        and idx.step == 1
    )


def _coerce_to_datetime_index(df: pd.DataFrame) -> pd.DataFrame:
    """Return a df with a tz-aware UTC DatetimeIndex, shallow-copying if needed.

    Heuristics for non-DatetimeIndex inputs:
      * default ``RangeIndex(0, n, 1)``: promote and drop the zeroth column;
      * otherwise: convert the existing index in place.

    Integer/float dtypes are never auto-interpreted as datetimes (avoids
    silently treating values as epoch nanoseconds). A ``UserWarning`` is
    emitted on any auto-conversion path.
    """
    idx = df.index

    if isinstance(idx, pd.DatetimeIndex):
        if idx.tz is None:
            df = df.copy(deep=False)
            df.index = idx.tz_localize("UTC")
        return df

    if _is_default_range_index(idx):
        if df.shape[1] == 0:
            raise ValueError(
                "DataFrame has no DatetimeIndex and no columns to promote"
            )
        column_name = df.columns[0]
        candidate = df.iloc[:, 0]
        source_desc = f"column '{column_name}'"
    else:
        column_name = None
        candidate = pd.Series(idx)
        source_desc = "index"

    # Reject numerics outright — pd.to_datetime would silently treat them as ns.
    if candidate.dtype.kind in "iufb":
        raise TypeError(
            f"Cannot interpret {source_desc} as datetimes: dtype is "
            f"{candidate.dtype}. Integer/float values are not auto-converted "
            "(this avoids silently misinterpreting them as epoch nanoseconds). "
            "Set a DatetimeIndex explicitly before calling write_pixie."
        )

    try:
        new_index = pd.to_datetime(candidate, utc=True, errors="raise")
    except (ValueError, TypeError) as e:
        raise TypeError(
            f"Cannot convert {source_desc} to a DatetimeIndex: {e}"
        ) from e

    new_index = pd.DatetimeIndex(new_index)
    new_index.name = "time"

    df = df.copy(deep=False)
    if column_name is not None:
        df = df.drop(columns=[column_name])
    df.index = new_index

    warnings.warn(
        f"write_pixie: auto-converted {source_desc} to a UTC DatetimeIndex.",
        UserWarning,
        stacklevel=3,
    )
    return df


def write_pixie(
    path: PathLike,
    df: pd.DataFrame,
    use_64bit_precision: bool = True,
) -> None:
    """Write a pandas DataFrame to a Pixie .pxt/.pxb paired file.

    Parameters
    ----------
    path
        Output path. Any ``.pxt`` or ``.pxb`` extension is stripped; both
        files are always written using the resulting base path.
    df
        Data to write. Preferred form: a ``DatetimeIndex`` (tz-aware UTC) with
        a regular timestep, one series per column.

        If the index is not a ``DatetimeIndex``, two heuristics are tried
        (each emits a ``UserWarning``):

        * default ``RangeIndex(0, n, 1)``: the zeroth column is promoted and
          dropped from the body;
        * any other non-default index: the index itself is converted.

        Conversion uses ``pd.to_datetime(..., utc=True)``. Integer or float
        dtypes are rejected to avoid silently misinterpreting values as
        epoch nanoseconds.

        A naive ``DatetimeIndex`` is localised to UTC silently.
    use_64bit_precision
        ``True`` (default) writes Gorilla-double (lossless). ``False`` writes
        Gorilla-float (about half the size, single-precision).
    """
    df = _coerce_to_datetime_index(df)
    if df.empty:
        raise ValueError("DataFrame is empty")

    # Explicit unit conversion: pandas 3.0 defaults to µs (was ns in 2.x), so
    # don't assume astype("int64") gives nanoseconds.
    timestamps_sec = np.asarray(
        df.index.tz_convert("UTC").as_unit("s").asi8, dtype=np.int64
    )

    series_names = [str(col) for col in df.columns]
    values_per_series = [
        np.ascontiguousarray(df[col].to_numpy(), dtype=np.float64)
        for col in df.columns
    ]

    _write_pixie_raw(
        str(path),
        series_names,
        timestamps_sec,
        values_per_series,
        bool(use_64bit_precision),
    )
