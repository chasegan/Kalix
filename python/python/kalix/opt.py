"""Optimisation entry points equivalent to the Kalix CLI.

Currently: file-to-result `optimise()`, the sister of `kalix.sim.simulate()`.
Like `simulate()` it is stateless — load a config, run the calibration, get the
answer back — with no `Model` object retained between calls.
"""
from __future__ import annotations

from pathlib import Path
from typing import Any, Dict, Optional, Union

from kalix._native import _optimise_from_file

__all__ = ["optimise"]

PathLike = Union[str, Path]


def optimise(
    config_file: PathLike,
    *,
    model_file: Optional[PathLike] = None,
    save_model: Optional[PathLike] = None,
) -> Dict[str, Any]:
    """Run a parameter optimisation from a config INI file and return the result.

    The Python equivalent of ``kalix optimise <config_file> [model_file]
    [-s <save_model>]``, but in-process — no separate CLI binary required. The
    config file specifies the algorithm, calibration terms, objective
    expression, parameter bounds, and termination criteria.

    Parameters
    ----------
    config_file
        Path to the optimisation config ``.ini`` file.
    model_file
        If given, the model ``.ini`` to calibrate. Overrides the ``model_file``
        entry in the config (mirrors the CLI's optional positional argument).
    save_model
        If given, the optimised model is written to this path as an ``.ini``
        file (mirrors the CLI's ``-s/--save-model``). The optimised model is
        also always returned under the ``"model_ini"`` key regardless.

    Returns
    -------
    dict
        Results of the run, with keys:

        * ``"best_objective"`` (float): best objective value found, lower is
          better;
        * ``"n_evaluations"`` (int): number of function evaluations performed;
        * ``"success"`` (bool): whether the optimiser terminated successfully;
        * ``"message"`` (str): the optimiser's termination message;
        * ``"parameters"`` (dict): optimised parameters as
          ``{target: physical_value}``, where targets look like
          ``"node.<name>.<param>"`` or ``"c.<constant>"``;
        * ``"optimised_model_ini"`` (str): the optimised model serialised back
          to an INI string (a lossless round-trip), ready to pass to a future
          loader.

    Notes
    -----
    Paths inside the config (``model_file`` and each term's ``observed_file``)
    are resolved relative to the current working directory, exactly as the CLI
    does. If the config specifies an ``output_file``, a results summary is
    written there as well.
    """
    return _optimise_from_file(
        str(config_file),
        str(model_file) if model_file is not None else None,
        str(save_model) if save_model is not None else None,
    )
