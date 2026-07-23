"""Kalix's exception hierarchy.

Defined in Rust (`kalix._native`, see `python/src/error.rs`) -- this module
is a thin re-export.
"""
from kalix._native import (
    KalixError,
    ModelParseError,
    ModelValidationError,
    SimulationError,
    KalixKeyError,
    KalixRuntimeError,
)

__all__ = [
    "KalixError",
    "ModelParseError",
    "ModelValidationError",
    "SimulationError",
    "KalixKeyError",
    "KalixRuntimeError",
]
