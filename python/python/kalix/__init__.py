"""Python interface for Kalix.

v0.1 ships read/write of the Pixie format (Kalix's Gorilla-compressed
timeseries format, .pxt/.pxb paired files), exposed as pandas DataFrames.
"""
from __future__ import annotations

from pathlib import Path
from typing import Union

import numpy as np
import pandas as pd

from kalix._native import _read_pixie_raw, _write_pixie_raw

__all__ = ["read_pixie", "write_pixie", "__version__"]

__version__ = "0.1.0"

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
        Data to write. The index must be a ``DatetimeIndex`` with a regular
        timestep; each column becomes one series.
    use_64bit_precision
        ``True`` (default) writes Gorilla-double (lossless). ``False`` writes
        Gorilla-float (about half the size, single-precision).
    """
    if not isinstance(df.index, pd.DatetimeIndex):
        raise TypeError("DataFrame index must be a DatetimeIndex")
    if df.empty:
        raise ValueError("DataFrame is empty")

    # Unix seconds, UTC. astype(int64) gives nanoseconds since epoch.
    timestamps_sec = np.asarray(
        df.index.astype("int64") // 1_000_000_000, dtype=np.int64
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
