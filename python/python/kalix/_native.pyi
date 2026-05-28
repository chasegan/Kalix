"""Type stubs for the native (PyO3) module."""
from typing import Dict, List, Optional, Tuple

import numpy as np
from numpy.typing import NDArray

def _read_pixie_raw(path: str) -> Tuple[NDArray[np.int64], Dict[str, NDArray[np.float64]]]: ...
def _write_pixie_raw(
    path: str,
    series_names: List[str],
    timestamps_unix_seconds: NDArray[np.int64],
    values_per_series: List[NDArray[np.float64]],
    use_64bit_precision: bool,
) -> None: ...
def _simulate_from_file(
    model_path: str,
    output_path: Optional[str] = None,
    mass_balance_path: Optional[str] = None,
) -> None: ...
