"""Kalix's exception hierarchy.

Defined in Rust (`kalix._native`, see `python/src/error.rs`) -- this module
is a thin re-export::

    KalixError(Exception)
    |-- ModelParseError                 -- snippet/string/file failed to parse
    |-- ModelValidationError            -- parsed, but the model is invalid
    |-- SimulationError                 -- run() failed
    |-- KalixKeyError(KeyError)         -- missing sections/properties/outputs
    `-- KalixRuntimeError(RuntimeError) -- equivalent to RuntimeError

``except kalix.KalixError`` is a safe catch-all for anything the engine
reports.

The deliberate exception: an argument that is malformed *before* the engine
is ever consulted is a caller mistake, not a modelling failure, so it raises
a builtin `ValueError` and stays outside this hierarchy. The sole case today
is `Model.get()` given a designation that isn't of the form
``"<section>.<property>"``. A well-formed designation naming something that
doesn't exist is an engine lookup, and raises `KalixKeyError`.
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
