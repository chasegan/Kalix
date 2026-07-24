"""Python interface for Kalix.

Top-level façade — re-exports the most common user-facing names. Underlying
implementations live in `kalix.io` (Pixie read/write), `kalix.sim`
(simulation entry points), `kalix.opt` (optimisation entry points), and
`kalix.error` (the exception hierarchy). Power users may import from those
submodules directly.
"""
from __future__ import annotations

from importlib.metadata import PackageNotFoundError, version as _pkg_version

from kalix.io import read_pixie, write_pixie
from kalix.opt import optimise
from kalix.sim import simulate
from kalix.model import Model, load_file, load_string
from kalix.error import (
    KalixError,
    ModelParseError,
    ModelValidationError,
    SimulationError,
    KalixKeyError,
    KalixRuntimeError,
)

__all__ = [
    "simulate",
    "optimise",
    "read_pixie",
    "write_pixie",
    "Model",
    "load_file",
    "load_string",
    "KalixError",
    "ModelParseError",
    "ModelValidationError",
    "SimulationError",
    "KalixKeyError",
    "KalixRuntimeError",
    "__version__"
]

try:
    __version__ = _pkg_version("kalix")
except PackageNotFoundError:
    __version__ = "0.0.0+unknown"
