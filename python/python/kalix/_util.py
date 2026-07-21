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
    index = pd.to_datetime(timestamps_sec, unit="s", utc=True).as_unit("s")
    index.name = "time"
    items = list(series.items()) if isinstance(series, dict) else list(series)
    if not items:
        return pd.DataFrame(index=index)
    names, arrays = zip(*items)
    df = pd.DataFrame(dict(enumerate(arrays)), index=index)
    df.columns = list(names)
    return df
