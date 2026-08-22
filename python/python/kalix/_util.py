"""Shared helpers for the `kalix` package. Private -- not part of the public API."""
from __future__ import annotations

from pathlib import Path
from typing import Dict, Iterable, Tuple, Union

import numpy as np
import pandas as pd

PathLike = Union[str, Path]

TimeSeriesData = Union[Dict[str, np.ndarray], Iterable[Tuple[str, np.ndarray]]]


def build_time_indexed_df(
    timestamps_sec: np.ndarray, series: TimeSeriesData
) -> pd.DataFrame:
    """Build a DataFrame with a UTC ``DatetimeIndex`` named ``"time"``.

    ``timestamps_sec`` are whole seconds since the epoch, matching how Kalix
    stores/exchanges time internally -- pinned to second resolution so
    round-trips (e.g. through Pixie) stay dtype-lossless.

    ``series`` is either a ``{name: array}`` dict (names must be unique), or
    an iterable of ``(name, array)`` pairs -- the latter allows repeated
    names, producing that many duplicate columns rather than silently
    collapsing them the way a dict would.
    """
    # Build the index without ever materialising nanoseconds. `pd.to_datetime(...,
    # unit="s")` converts via ns first on pandas 2.x and raises OutOfBoundsDatetime
    # outside 1677-2262 -- a demotion with `.as_unit("s")` afterwards is too late to
    # help. Long runs are well outside that window (year 0 is ~-6.2e10 s, year 9999
    # ~2.5e11 s; either times 1e9 overflows int64). Going through `datetime64[s]`
    # keeps the full representable range available on both pandas 2.x and 3.x.
    seconds = np.asarray(timestamps_sec, dtype=np.int64).astype("datetime64[s]")
    index = pd.DatetimeIndex(seconds).tz_localize("UTC")
    index.name = "time"
    items = list(series.items()) if isinstance(series, dict) else list(series)
    if not items:
        return pd.DataFrame(index=index)
    names, arrays = zip(*items)
    df = pd.DataFrame(dict(enumerate(arrays)), index=index)
    df.columns = list(names)
    return df
