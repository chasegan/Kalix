"""Simulation entry points equivalent to the Kalix CLI.

Currently: file-to-file `simulate()`. Future entry points (e.g. running a
loaded `Model` object) will live alongside, but the file-to-file convenience
is expected to remain at this layer indefinitely.
"""
from __future__ import annotations

from pathlib import Path
from typing import Optional, Union

from kalix._native import _simulate_from_file

__all__ = ["simulate"]

PathLike = Union[str, Path]


def simulate(
    model_file: PathLike,
    *,
    output_file: Optional[PathLike] = None,
    mass_balance: Optional[PathLike] = None,
) -> None:
    """Run a Kalix model from an INI file and write requested outputs to disk.

    The Python equivalent of ``kalix sim <model_file> [-o ...] [-m ...]``,
    but in-process — no separate CLI binary required.

    Parameters
    ----------
    model_file
        Path to the model ``.ini`` file.
    output_file
        If given, path to write the simulation outputs to. Format is inferred
        from the extension (``.pxb`` for the Pixie pair, ``.csv`` for CSV).
    mass_balance
        If given, path to write the mass-balance report to (plain text).

    Raises
    ------
    ValueError
        If neither ``output_file`` nor ``mass_balance`` is provided.

    Notes
    -----
    Both output kwargs are keyword-only, mirroring the CLI's flag style.
    Calling with no outputs is rejected up front — a simulation with no
    visible result is almost always a bug.
    """
    if output_file is None and mass_balance is None:
        raise ValueError(
            "simulate() needs at least one of output_file or mass_balance"
        )
    _simulate_from_file(
        str(model_file),
        str(output_file) if output_file is not None else None,
        str(mass_balance) if mass_balance is not None else None,
    )
