"""Shared helpers for the `kalix` package. Private -- not part of the public API."""
from __future__ import annotations

from pathlib import Path
from typing import Dict, Union

import numpy as np
import pandas as pd

PathLike = Union[str, Path]


def build_time_indexed_df(
    timestamps_sec: np.ndarray, series_dict: Dict[str, np.ndarray]
) -> pd.DataFrame:
    """Build a DataFrame with a UTC ``DatetimeIndex`` named ``"time"``.

    ``timestamps_sec`` are whole seconds since the epoch, matching how Kalix
    stores/exchanges time internally -- pinned to second resolution so
    round-trips (e.g. through Pixie) stay dtype-lossless.
    """
    index = pd.to_datetime(timestamps_sec, unit="s", utc=True).as_unit("s")
    index.name = "time"
    return pd.DataFrame(series_dict, index=index)
