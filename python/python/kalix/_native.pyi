"""Type stubs for the native (PyO3) module."""
from typing import Any, Callable, Dict, List, Optional, Tuple

import numpy as np
from numpy.typing import NDArray

# pylint: disable=unused-argument

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
def _optimise_from_file(
    config_path: str,
    model_path: Optional[str] = None,
    save_model_path: Optional[str] = None,
    progress: Optional[Callable[[Dict[str, Any]], Any]] = None,
) -> Dict[str, Any]: ...

class _Model:
    def __init__(self) -> None: ...
    def _from_file(self, model_path: str) -> "_Model": ...
    def _from_model_string(self, model_string: str) -> "_Model": ...
    def _run(self) -> "_Model": ...
    def _patch_update(self, patch_string: str) -> "_Model": ...
    def _patch_override(self, patch_string: str) -> "_Model": ...
    def _patch_delete(self, patch_string: str) -> "_Model": ...
    def _get_outputs(
        self, names: Optional[List[str]] = None
    ) -> Tuple[int, int, int, Dict[str, NDArray[np.float64]]]: ...  # start is signed (real epoch seconds)
