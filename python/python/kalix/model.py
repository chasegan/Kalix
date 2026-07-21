"""A Kalix hydrological model: a network of nodes and links.

Wraps the native `_native._Model` PyO3 class in a clean Python API. All
`_native` symbols are underscore-prefixed and considered private to this
module -- nothing in `_native` is meant to be imported or called directly;
go through `Model` (or the module-level convenience functions below)
instead.
"""
from __future__ import annotations

from typing import List, Literal, Optional
import numpy as np
import pandas as pd

from kalix._native import _Model, _PatchMode
from kalix._util import PathLike, build_time_indexed_df

__all__ = ["Model", "new_model", "load_file", "load_string"]


class Model:
    """A Kalix hydrological model.

    Construct with `Model()`, `new_model()`, or the `load_file`/`load_string`
    convenience functions, then call `run()`. Most methods are fluent
    (they return `self`) so calls can be chained, e.g.::

        model = kalix.load_file("catchment.ini").run()
    """
    # pylint: disable=protected-access

    def __init__(self) -> None:
        self._inner = _Model()

    def from_file(self, model_path: PathLike) -> "Model":
        """Load a full model from an INI file, replacing any model already held.

        The model is validated (node/link wiring, required inputs, etc.)
        before being accepted -- if loading fails, this `Model` is left
        exactly as it was.

        Parameters
        ----------
        model_path
            Path to the model ``.ini`` file. Relative paths referenced
            inside the file (e.g. data inputs) are resolved relative to the
            directory containing `model_path`.

        Returns
        -------
        Model
            `self`, for chaining.

        Raises
        ------
        OSError
            If the file could not be read (not found, permission denied, etc.).
        ValueError
            If the file was read but its contents are not a valid model INI.
        RuntimeError
            If the model parsed but failed validation.
        """
        try:
            self._inner._from_file(str(model_path))
        except OSError as e:
            raise OSError(f"Failed to load model from '{model_path}': {e}") from e
        except ValueError as e:
            raise ValueError(f"Model '{model_path}' is not a valid model INI: {e}") from e
        except RuntimeError as e:
            raise RuntimeError(f"Model '{model_path}' failed validation: {e}") from e
        return self

    def from_string(self, model_string: str) -> "Model":
        """Load a full model from an in-memory INI string, replacing any model already held.

        Parameters
        ----------
        model_string
            The complete model definition, in the same INI format as a
            ``.ini`` model file.

        Returns
        -------
        Model
            `self`, for chaining.

        Raises
        ------
        ValueError
            If the string is not a valid model INI.
        OSError
            If the string references a data file (e.g. via ``[inputs]``)
            that could not be read.
        RuntimeError
            If the model parsed but failed validation.

        Notes
        -----
        There is no containing file directory, so any relative paths
        referenced inside the INI (e.g. data file inputs) are resolved
        against the current working directory.
        """
        try:
            self._inner._from_model_string(model_string)
        except ValueError as e:
            raise ValueError(f"Failed to parse model string: {e}") from e
        except OSError as e:
            raise OSError(f"Failed to load model referenced by model string: {e}") from e
        except RuntimeError as e:
            raise RuntimeError(f"Model string failed validation: {e}") from e
        return self

    def run(self) -> "Model":
        """Configure and run the model's simulation.

        Safe to call more than once on the same `Model` -- each run resets
        node and account state and rewinds internal recording, so repeated
        calls behave as independent runs.

        Returns
        -------
        Model
            `self`, for chaining.

        Raises
        ------
        RuntimeError
            If configuration or the simulation itself fails.
        """
        try:
            # This is the intended use - the underscore indicates that this is a
            # function from Rust-land
            self._inner._run()
        except RuntimeError as e:
            raise RuntimeError(f"Model run failed: {e}") from e
        return self

    def patch(
            self,
            patch_string: str,
            *,
            mode: Literal["update", "override", "delete"] = "update",
            missing_ok: bool = False
    ) -> "Model":
        """Apply parameter overrides to the currently loaded model.

        Parameters
        ----------
        patch_string
            A partial model INI snippet naming the sections/properties to
            change, e.g. ``"[node.g]\\narea = 99\\n"``.
        mode
            ``"update"`` -- set the given properties, leaving everything else
            untouched (including properties on an existing section that the
            patch doesn't mention). ``"override"`` -- replace each named section
            wholesale with its patch definition; properties on an existing
            section that the patch omits are dropped. A section not yet present
            is appended. ``"delete"`` -- remove each named section wholesale.
            Property-level deletion is not supported -- a patch section must
            list no properties, or the call raises.
        missing_ok
            Only meaningful when mode=="delete". If True, silently ignores
            patch sections that don't exist in the model; if False (the
            default), raises instead.

        Returns
        -------
        Model
            `self`, for chaining.

        Raises
        ------
        ValueError
            If `patch_string` is not valid INI, or if applying it produces an
            invalid model. This `Model` is left untouched in that case.
        OSError
            If applying the patch references a data file (e.g. a new
            ``[inputs]`` entry) that could not be read. This `Model` is left
            untouched in that case.
        """
        if mode == "update":
            self._inner._patch(patch_string, mode=_PatchMode.Update)
        elif mode == "override":
            self._inner._patch(patch_string, mode=_PatchMode.Override)
        elif mode == "delete":
            native_mode = _PatchMode.DeleteMissingOk if missing_ok else _PatchMode.Delete
            self._inner._patch(patch_string, mode=native_mode)
        else:
            raise ValueError(f"Unknown patch mode: {mode!r}")
        return self

    def get_outputs(self, names: Optional[str | List[str]] = None) -> pd.DataFrame:
        """Retrieve the model's output time series after a run.

        Parameters
        ----------
        names
            Names of recorders to retrieve. ``None`` (default) retrieves
            every recorder declared in the model's ``[outputs]`` section.
            Matching is case-insensitive. Requesting the same name more than
            once is not an error -- it produces that many duplicate columns,
            matching `names` position-for-position.

        Returns
        -------
        DataFrame
            Index is a UTC ``DatetimeIndex`` named ``"time"``; columns are
            output names with float64 values, in request order (or
            declaration order for ``names=None``). Column names are the
            outputs' *canonical stored* names -- the casing they were
            declared under -- which may differ from the casing passed in
            `names`.

        Raises
        ------
        ValueError
            If the model has not been run yet (loading or patching a model
            resets its run state), or if a requested name is not a declared
            output, or was declared but not found/wrong length.
        """
        try:
            start, step, size, series_list = self._inner._get_outputs(names)
        except ValueError as e:
            raise ValueError(f"Failed to retrieve model outputs: {e}") from e

        timestamps_sec = start + step * np.arange(size, dtype=np.int64)
        return build_time_indexed_df(timestamps_sec, series_list)

    def __repr__(self) -> str:
        return "<kalix.Model>"


def new_model() -> Model:
    """Create a new, empty, unconfigured `Model`.

    Returns
    -------
    Model
        An empty model with nothing loaded. Call `load_file`/`load_string`
        (then `run`) before it does anything useful.
    """
    return Model()


def load_file(model_path: PathLike) -> Model:
    """Create a `Model` and load it from an INI file.

    Convenience for ``Model().load_file(model_path)``.

    Parameters
    ----------
    model_path
        Path to the model ``.ini`` file.

    Returns
    -------
    Model
        A new model, loaded and validated.

    Raises
    ------
    OSError
        If the file could not be read (not found, permission denied, etc.).
    ValueError
        If the file was read but its contents are not a valid model INI.
    RuntimeError
        If the model parsed but failed validation.
    """
    return Model().from_file(model_path)


def load_string(model_string: str) -> Model:
    """Create a `Model` and load it from an in-memory INI string.

    Convenience for ``Model().load_string(model_string)``.

    Parameters
    ----------
    model_string
        The complete model definition, in INI format.

    Returns
    -------
    Model
        A new model, loaded and validated.

    Raises
    ------
    ValueError
        If the string is not a valid model INI.
    RuntimeError
        If the model parsed but failed validation.

    Notes
    -----
    Unlike `load_file`, there is no containing file directory, so any
    relative paths referenced inside the INI (e.g. data file inputs) are
    resolved against the current working directory.
    """
    return Model().from_string(model_string)
