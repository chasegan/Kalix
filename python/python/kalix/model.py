"""A Kalix hydrological model: a network of nodes and links.

Wraps the native `_native._Model` PyO3 class in a clean Python API. All
`_native` symbols are underscore-prefixed and considered private to this
module -- nothing in `_native` is meant to be imported or called directly;
go through `Model` (or the module-level convenience functions below)
instead.
"""
from __future__ import annotations

from pathlib import Path
from typing import Optional, Union
import pandas as pd

from kalix._native import _Model

__all__ = ["Model", "new_model", "load_file", "load_string"]

PathLike = Union[str, Path]

# `IniModelIO::read_model_file` (python/src/lib.rs) folds a genuine
# file-read failure and an INI parse failure into the same native OSError,
# always prefixing the former with this exact text. There's no Rust-side
# distinction to hook into without changing the shared `IniModelIO` API
# (also used by the CLI/IDE), so the split below is done here by sniffing
# for this marker. If that Rust message format ever changes, update this
# to match -- see `IniModelIO::read_model_file`.
_FILE_READ_FAILURE_MARKER = "Failed to read file '"


class Model:
    """A Kalix hydrological model.

    Construct with `Model()`, `new_model()`, or the `load_file`/`load_string`
    convenience functions, then call `run()`. Most methods are fluent
    (they return `self`) so calls can be chained, e.g.::

        model = kalix.load_file("catchment.ini").run()
    """

    def __init__(self, model_path: Optional[PathLike] = None) -> None:
        self._inner = _Model()
        if model_path is not None:
            self.load_file(model_path)

    def load_file(self, model_path: PathLike) -> "Model":
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
            self._inner._load_file(str(model_path))
        except OSError as e:
            if _FILE_READ_FAILURE_MARKER in str(e):
                raise OSError(f"Failed to load model from '{model_path}': {e}") from e
            raise ValueError(f"Model '{model_path}' is not a valid model INI: {e}") from e
        except RuntimeError as e:
            raise RuntimeError(f"Model '{model_path}' failed validation: {e}") from e
        return self

    def load_string(self, model_string: str) -> "Model":
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
        RuntimeError
            If the model parsed but failed validation.

        Notes
        -----
        There is no containing file directory, so any relative paths
        referenced inside the INI (e.g. data file inputs) are resolved
        against the current working directory.
        """
        try:
            self._inner._load_model_string(model_string)
        except OSError as e:
            raise ValueError(f"Failed to parse model string: {e}") from e
        except RuntimeError as e:
            raise RuntimeError(f"Model string failed validation: {e}") from e
        return self

    def load_snippet(self, model_string: str) -> "Model":
        """Merge a partial model ("snippet") into the model already held.

        Not yet implemented.

        Raises
        ------
        NotImplementedError
            Always -- merging a partial model into an existing one isn't
            implemented yet.
        """
        raise NotImplementedError(
            "Model.load_snippet() is not implemented yet (merging a partial "
            "model into an existing one). Use Model.load_file()/"
            "Model.load_string() to replace the whole model instead."
        )

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
            # pylint: disable=protected-access
            # This is the intended use - the underscore indicates that this is a
            # function from Rust-land
            self._inner._run()
        except RuntimeError as e:
            raise RuntimeError(f"Model run failed: {e}") from e
        return self

    def patch(self, patch_string: str, *, mode: Literal["update", "override", "delete"] = "update") -> "Model":
        """Apply parameter overrides to the currently loaded model.

        Not yet implemented.

        Raises
        ------
        NotImplementedError
            Always -- the parameter-override format is still undecided.
        """
        if mode == "update":
            self._inner._patch_update(patch_string)
        elif mode == "override":
            raise NotImplementedError("Model.patch() override mode not implemented")
        elif mode == "delete":
            raise NotImplementedError("Model.patch() delete mode not implemented")

    def get_outputs(self) -> pd.DataFrame:
        """Retrieve the model's output time series after a run.

        Not yet implemented. 

        Raises
        ------
        NotImplementedError
            Always -- outputs cannot be retrieved from Python yet.
        """
        raise NotImplementedError(
            "Model.get_outputs() is not implemented yet (output time series "
            "are not yet exposed back to Python). For now, use "
            "kalix.simulate(model_file, output_file=...) to run the model and "
            "write outputs to disk, then read them back with "
            "kalix.read_pixie()."
        )

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
    return Model().load_file(model_path)


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
    return Model().load_string(model_string)
