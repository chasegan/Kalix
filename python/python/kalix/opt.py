"""Optimisation entry points equivalent to the Kalix CLI.

Currently: file-to-result `optimise()`, the sister of `kalix.sim.simulate()`.
Like `simulate()` it is stateless — load a config, run the calibration, get the
answer back — with no `Model` object retained between calls.
"""
from __future__ import annotations

import sys
import time
from pathlib import Path
from typing import Any, Callable, Dict, Optional, Union

from kalix._native import _optimise_from_file

__all__ = ["optimise"]

PathLike = Union[str, Path]
Progress = Dict[str, Any]
ProgressCallback = Callable[[Progress], Any]


def _in_notebook() -> bool:
    """True if running inside a Jupyter/IPython kernel (not a plain terminal)."""
    try:
        from IPython import get_ipython  # always present inside a kernel
    except Exception:
        return False
    ip = get_ipython()
    return ip is not None and ip.__class__.__name__ == "ZMQInteractiveShell"


def _default_reporter() -> Optional[ProgressCallback]:
    """Build the built-in in-place progress reporter for the current environment.

    Returns a throttled callback that overwrites a single status line, or
    ``None`` when there's nowhere sensible to render (non-interactive runs, e.g.
    a script with redirected output or CI), in which case the run is silent.

    Uses only the standard library plus ``IPython`` (which is always available
    inside a notebook kernel) — no third-party dependencies.
    """
    def line(p: Progress) -> str:
        return (
            f"Optimising… {p['n_evaluations']:,} evals "
            f"· best {p['best_objective']:.6g} "
            f"· {p['elapsed_seconds']:.1f}s"
        )

    if _in_notebook():
        from IPython.display import clear_output

        def report(p: Progress) -> None:
            clear_output(wait=True)
            print(line(p))

        return report

    if sys.stderr.isatty():
        def report(p: Progress) -> None:
            print("\r\033[K" + line(p), end="", file=sys.stderr, flush=True)

        return report

    return None  # non-interactive: stay silent


def _throttle(callback: ProgressCallback, min_interval: float = 0.2) -> ProgressCallback:
    """Wrap a reporter so it fires at most once per ``min_interval`` seconds.

    Keeps notebook and terminal output light regardless of how often the
    optimiser reports (it calls back once per generation, which can be many
    times a second on a fast model).
    """
    state: Dict[str, Optional[float]] = {"last": None}

    def throttled(p: Progress) -> None:
        now = time.monotonic()
        last = state["last"]
        # The first call always fires; subsequent ones are rate-limited. (Don't
        # seed `last` with 0.0 — time.monotonic()'s reference is often boot time,
        # so on a freshly-booted machine the first call would be suppressed.)
        if last is None or now - last >= min_interval:
            state["last"] = now
            callback(p)

    return throttled


def optimise(
    config_file: PathLike,
    *,
    model_file: Optional[PathLike] = None,
    save_model: Optional[PathLike] = None,
    progress: Union[None, bool, ProgressCallback] = None,
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
        also always returned under the ``"optimised_model_ini"`` key regardless.
    progress
        Controls progress feedback during the run:

        * ``None`` (default) — show a built-in, in-place status line. In a
          Jupyter notebook it updates a single output line; in an interactive
          terminal it rewrites a line on stderr; when output is non-interactive
          (script/CI/redirected) the run is silent. No extra dependencies.
        * a callable — called once per generation with a progress ``dict``
          (keys: ``n_evaluations``, ``best_objective``, ``elapsed_seconds``).
          The built-in line is suppressed — your callback is the only output, so
          you can drive a ``tqdm`` bar, a live plot, a log, etc. Exceptions it
          raises are swallowed so a faulty callback can't abort the run.
        * ``False`` — fully silent.

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
    # Resolve the `progress` argument into the native callback (or None).
    finalize = None  # optional cleanup after the run (e.g. terminal newline)
    if progress is False:
        native_progress = None
    elif callable(progress):
        native_progress = progress  # user-supplied: passed through verbatim
    else:  # None / True / anything else → built-in default reporter
        reporter = _default_reporter()
        native_progress = _throttle(reporter) if reporter is not None else None
        if reporter is not None and not _in_notebook():
            # Close off the rewritten stderr line with a newline.
            def finalize() -> None:
                print(file=sys.stderr)

    result = _optimise_from_file(
        str(config_file),
        str(model_file) if model_file is not None else None,
        str(save_model) if save_model is not None else None,
        native_progress,
    )

    if finalize is not None:
        finalize()

    return result
