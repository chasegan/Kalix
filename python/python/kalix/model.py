"""A Kalix hydrological model: a network of nodes and links.

Wraps the native `_native._Model` PyO3 class in a clean Python API. All
`_native` symbols are underscore-prefixed and considered private to this
module -- nothing in `_native` is meant to be imported or called directly;
go through `Model` (or the module-level convenience functions below)
instead.
"""
from __future__ import annotations
from collections.abc import Mapping

from typing import Callable, List, Literal, Optional, overload
import numpy as np
import pandas as pd

from kalix._native import _Model, _PatchMode
from kalix._util import PathLike, build_time_indexed_df

__all__ = ["Model", "load_file", "load_string"]

# A dict-form patch: {section: {property: value}}
PatchDict = Mapping[str, Mapping[str, object]]


class Model:
    """A Kalix hydrological model.

    Construct with `Model()` (empty), `Model.from_file()`/`Model.from_string()`
    (loaded), or the module-level `load_file`/`load_string` convenience
    functions (equivalent to the `from_*` classmethods), then call `run()`.
    Most methods are fluent (they return `self`) so calls can be chained,
    e.g.::

        model = kalix.load_file("catchment.ini").run()
    """
    # pylint: disable=protected-access

    def __init__(self, *, _inner: Optional[_Model] = None) -> None:
        if _inner is None:
            _inner = _Model()
        self._inner = _inner

    @classmethod
    def from_file(cls, model_path: PathLike) -> "Model":
        """Construct a new `Model` loaded from an INI file.

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
        kalix.ModelParseError
            If the file was read but its contents are not a valid model INI.
        kalix.ModelValidationError
            If the model parsed but failed validation.
        """
        return cls().load_file(model_path)

    @classmethod
    def from_string(cls, model_string: str) -> "Model":
        """Construct a new `Model` loaded from an in-memory INI string.

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
        kalix.ModelParseError
            If the string is not a valid model INI.
        kalix.ModelValidationError
            If the model parsed but failed validation.

        Notes
        -----
        Unlike `from_file`, there is no containing file directory, so any
        relative paths referenced inside the INI (e.g. data file inputs) are
        resolved against the current working directory.
        """
        return cls().load_string(model_string)

    def copy(self) -> "Model":
        """Create a deep, independent copy of this model."""
        return Model(_inner=self._inner.copy())

    def load_file(self, model_path: PathLike) -> "Model":
        """Replace the model this instance holds with the contents of an INI file.

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
        kalix.ModelParseError
            If the file was read but its contents are not a valid model INI.
        kalix.ModelValidationError
            If the model parsed but failed validation.
        """
        try:
            self._inner._from_file(str(model_path))
        except OSError as e:
            raise OSError(f"Failed to load model from '{model_path}': {e}") from e
        return self

    def load_string(self, model_string: str) -> "Model":
        """Replace the model this instance holds with an in-memory INI string.

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
        kalix.ModelParseError
            If the string is not a valid model INI.
        OSError
            If the string references a data file (e.g. via ``[data]``)
            that could not be read.
        kalix.ModelValidationError
            If the model parsed but failed validation.

        Notes
        -----
        There is no containing file directory, so any relative paths
        referenced inside the INI (e.g. data file inputs) are resolved
        against the current working directory.
        """
        try:
            self._inner._from_model_string(model_string)
        except OSError as e:
            raise OSError(f"Failed to load model referenced by model string: {e}") from e
        return self

    def run(self, progress: Optional[Callable[[int, int], None]] = None) -> "Model":
        """Configure and run the model's simulation.

        Safe to call more than once on the same `Model` -- each run resets
        node and account state and rewinds internal recording, so repeated
        calls behave as independent runs.

        Parameters
        ----------
        progress
            Optional callback invoked as ``progress(step, total)`` after each
            completed timestep, in the same spirit as `kalix.optimise`'s
            progress callback. Runs with the GIL re-acquired, so it can
            safely touch Python state; any exception it raises is swallowed
            so a faulty reporter can't abort the run.

        Returns
        -------
        Model
            `self`, for chaining.

        Raises
        ------
        kalix.ModelValidationError
            If the model could not be configured for a run.
        kalix.SimulationError
            If the simulation itself fails.
        """
        self._inner._run(progress)
        return self

    @overload
    def patch(
        self,
        patch_content: str | PatchDict,
        *,
        mode: Literal["merge", "replace", "delete"] = "merge",
        missing_ok: bool = False
    ) -> "Model": ...

    @overload
    def patch(
        self,
        patch_content: list[str],
        *,
        mode: Literal["delete"],
        missing_ok: bool = False
    ) -> "Model": ...

    def patch(
            self,
            patch_content: str | PatchDict | list[str],
            *,
            mode: Literal["merge", "replace", "delete"] = "merge",
            missing_ok: bool = False
    ) -> "Model":
        """Apply parameter overrides to the currently loaded model.

        Parameters
        ----------
        patch_content
            A partial model INI snippet naming the sections/properties to
            change, e.g. ``"[node.g]\\narea = 99\\n"``; the dict-form
            equivalent, e.g. ``{"node.g": {"area": 99}}``; or, only when
            ``mode="delete"``, a plain list of section names to remove, e.g.
            ``["node.old_gauge", "var.tmp"]``. Passing a list with any other
            `mode` raises -- listing bare names has no sensible meaning for
            merge/replace.
        mode
            ``"merge"`` -- set the given properties, leaving everything else
            untouched (including properties on an existing section that the
            patch doesn't mention). ``"replace"`` -- replace each named section
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
            If `patch_content` is a list and `mode` is not `"delete"` --
            rejected here, before the engine is consulted, so this is a
            plain builtin `ValueError` (see `kalix.error`).
        kalix.ModelParseError
            If `patch_content` is not valid INI, after any dict/list
            conversion.
        kalix.ModelValidationError
            If the patch applies but produces an invalid model.
        kalix.KalixKeyError
            If ``mode="delete"`` names a section the model doesn't have and
            `missing_ok` is ``False``.
        kalix.KalixRuntimeError
            If this `Model` is empty -- there is nothing to patch, so load a
            model first.
        OSError
            If applying the patch references a data file (e.g. a new
            ``[data]`` entry) that could not be read.

        In every failing case this `Model` is left exactly as it was -- the
        patch is applied to a copy and swapped in only on success.
        """
        if isinstance(patch_content, str):
            # Already a raw INI snippet -- assembled below as-is. Handled first
            # so `str` is out of the union before the Mapping branch: otherwise
            # a type checker can't prove str isn't a Mapping and leaves a
            # `str & Mapping[Unknown, object]` intersection whose value type is
            # `object`, breaking the `.items()` access below.
            pass
        elif isinstance(patch_content, list):
            if mode != "delete":
                raise ValueError(
                    "The list-of-names form of `patch_content` is only valid "
                    f"with mode='delete' (got mode={mode!r})"
                )
            patch_content = '\n'.join('[' + name.strip() + ']' for name in patch_content)
        else:
            # Dict form: {section: {property: value}} -> INI text.
            lines = []
            for section_name, section_content in patch_content.items():
                lines.append('[' + str(section_name) + ']')
                for property_name, property_value in section_content.items():
                    if property_value == "":
                        lines.append(property_name)
                    else:
                        lines.append(
                            property_name +
                            " = " +
                            str(property_value)
                        )
            patch_content = '\n'.join(lines)

        if mode == "merge":
            self._inner._patch(patch_content, mode=_PatchMode.Merge)
        elif mode == "replace":
            self._inner._patch(patch_content, mode=_PatchMode.Replace)
        elif mode == "delete":
            native_mode = _PatchMode.DeleteMissingOk if missing_ok else _PatchMode.Delete
            self._inner._patch(patch_content, mode=native_mode)
        else:
            raise ValueError(f"Unknown patch mode: {mode!r}")
        return self

    def get_outputs(
        self,
        names: Optional[str | List[str]] = None,
        missing_ok: bool = False,
    ) -> pd.DataFrame:
        """Retrieve the model's output time series after a run.

        Parameters
        ----------
        names
            Names of recorders to retrieve. ``None`` (default) retrieves
            every recorder declared in the model's ``[outputs]`` section.
            Matching is case-insensitive. Requesting the same name more than
            once is not an error -- it produces that many duplicate columns,
            matching `names` position-for-position.
        missing_ok
            Only affects explicitly requested `names` (has no effect when
            `names` is ``None``). If ``False`` (default), a requested name
            that is undeclared, not found, or wrong length raises
            ``kalix.KalixKeyError``. If ``True``, any such name instead comes
            back as an all-zero column of the correct simulation length, so a
            mix of valid and missing names in one call returns a mix of real
            and zero-filled columns, in request order.

        Returns
        -------
        DataFrame
            Index is a UTC ``DatetimeIndex`` named ``"time"``; columns are
            output names with float64 values, in request order (or
            declaration order for ``names=None``). Column names are the
            outputs' *canonical stored* names -- the casing they were
            declared under -- which may differ from the casing passed in
            `names`. Zero-filled stand-ins (`missing_ok=True`) carry the
            requested casing instead, since there is no canonical name to
            fall back on.

        Raises
        ------
        kalix.KalixRuntimeError
            If the model has not been run yet (loading or patching a model
            resets its run state) -- this check applies regardless of
            `missing_ok`.
        kalix.KalixKeyError
            When `missing_ok` is ``False``, if a requested name is not a
            declared output, or was declared but not found/wrong length.
        """
        if isinstance(names, str):
            names = [names]
        start, step, size, series_list = self._inner._get_outputs(names, missing_ok)

        timestamps_sec = start + step * np.arange(size, dtype=np.int64)
        return build_time_indexed_df(timestamps_sec, series_list)

    def get_mass_balance(self) -> pd.DataFrame:
        """Retrieve the model's mass balance report after a run.

        The same per-node balance the CLI's ``-m`` flag writes to a file,
        returned as a `DataFrame` instead.

        Returns
        -------
        DataFrame
            One row per node, columns ``"node"``, ``"type"``, and
            ``"mass_balance"`` (ML/timestep), in the report's grouping order
            (by node type, then alphabetically by node name).

        Raises
        ------
        kalix.KalixRuntimeError
            If the model has not been run yet (loading or patching a model
            resets its run state).
        """
        names, types, values = self._inner._get_mass_balance()
        return pd.DataFrame({"node": names, "type": types, "mass_balance": values})

    def __repr__(self) -> str:
        return "<kalix.Model>"

    def sections(self) -> list:
        """List the model's INI section names, in file order.

        Returns
        -------
        list[str]
            Section names as they appear in the model, e.g. ``"node.myreach"``.
        """
        return self._inner._sections()

    def has_section(self, section_name: str) -> bool:
        """Check whether a section exists in the model.

        Parameters
        ----------
        section_name
            The section name, e.g. ``"node.myreach"``.

        Returns
        -------
        bool
            True if the section is present.
        """
        return self._inner._has_section(section_name)

    def get_section(self, section_name: str) -> dict[str, str]:
        """Retrieve a section's properties as a plain dict.

        This is a snapshot, not a live view -- mutating the returned dict
        does not touch the model; the only write path is `patch()`. A
        list-style section (e.g. ``[data]``, ``[outputs]``) comes back
        with each bare line as a key mapped to an empty-string value.

        Parameters
        ----------
        section_name
            The section name, e.g. ``"node.myreach"``.

        Returns
        -------
        dict[str, str]
            Property name -> value, in file order.

        Raises
        ------
        kalix.KalixKeyError
            If the section does not exist. Use `has_section()` to probe.
        """
        return self._inner._get_section(section_name)

    def get(self, property_designation: str) -> str:
        """Retrieve a single property's value by dotted designation.

        Parameters
        ----------
        property_designation
            A dotted ``"<section>.<property>"`` string, e.g.
            ``"node.myreach.lag"``. The section name is everything before
            the last dot, so section names that themselves contain dots
            (as node section names do) are handled correctly.

        Returns
        -------
        str
            The property's value.

        Raises
        ------
        ValueError
            If `property_designation` is not of the form
            ``"<section>.<property>"`` -- a malformed argument is a caller
            mistake, so this is a plain builtin `ValueError` and is
            deliberately *not* a `kalix.KalixError`.
        kalix.KalixKeyError
            If the designation is well formed but the section or property
            does not exist.
        """
        return self._inner._get_property_by_designation(property_designation)

    def to_string(self) -> str:
        """Convert the model back to its INI representation.

        Returns
        -------
        str
            The round-tripped INI text, with original formatting preserved
            for unchanged properties.

        Raises
        ------
        kalix.KalixRuntimeError
            If this `Model` has no INI document to serialise (i.e. nothing
            has been loaded into it yet).
        """
        return self._inner._to_string()

    def save(self, filename: PathLike) -> "Model":
        """Write the model's INI representation to a file.

        Parameters
        ----------
        filename
            Path to write to.

        Returns
        -------
        Model
            `self`, for chaining.

        Raises
        ------
        kalix.KalixRuntimeError
            If this `Model` has no INI document to write (i.e. nothing has
            been loaded into it yet) -- checked before the file is touched.
        OSError
            If the file could not be written (bad path, permission denied,
            etc.).
        """
        self._inner._save(str(filename))
        return self

    def set_input(self, alias: str, data: "pd.DataFrame | pd.Series") -> "Model":
        """Supply in-memory data for a declared `[data]` alias.

        `alias` must already be declared in `[data]` -- either a bare
        declaration (``observed_flows =``) or an aliased file (``climate_data
        = climate.csv``, where the supplied data takes precedence over the
        file). `set_input()` fills an existing declaration; it does not
        create one -- declare the alias first (e.g. via `patch()`) if it
        isn't there yet.

        Parameters
        ----------
        alias
            The `[data]` alias to supply data for.
        data
            A `pd.DataFrame` (or bare `pd.Series`, accepted as sugar for a
            one-column frame) indexed by a `DatetimeIndex` with a regular
            step equal to the simulation timestep -- the same requirement as
            `write_pixie()`. A naive index is assumed UTC. Column names are
            addressable via `by_name` (standard sanitisation); column order
            via `by_index` (1-based) -- both exactly as if a file had been
            loaded under this alias. Values are coerced to float64.

        Returns
        -------
        Model
            `self`, for chaining.

        Raises
        ------
        kalix.KalixKeyError
            If `alias` is not declared in the model's ``[data]`` section --
            the only engine-side failure this call has.
        TypeError
            If `data` is not indexed by a `pd.DatetimeIndex`.
        ValueError
            If the index is empty or its step is not regular, or if a column
            is not the same length as the index. Like the `TypeError` above,
            these are argument problems rejected before the engine is
            consulted, so they stay builtin (see `kalix.error`).
        """
        if isinstance(data, pd.Series):
            data = data.to_frame(name=data.name if data.name is not None else "value")

        index = data.index
        if not isinstance(index, pd.DatetimeIndex):
            raise TypeError(
                f"set_input() requires a DatetimeIndex, got {type(index).__name__}"
            )
        index = index.tz_localize("UTC") if index.tz is None else index.tz_convert("UTC")

        # Explicit unit conversion: pandas 3.0 defaults to microseconds (was
        # nanoseconds in 2.x), so don't assume astype("int64") gives seconds.
        timestamps_sec = np.asarray(index.as_unit("s").asi8, dtype=np.int64)
        column_names = [str(col) for col in data.columns]
        values_per_column = [
            np.ascontiguousarray(data[col].to_numpy(), dtype=np.float64)
            for col in data.columns
        ]

        self._inner._set_input(alias, column_names, timestamps_sec, values_per_column)
        return self


def load_file(model_path: PathLike) -> Model:
    """Create a `Model` and load it from an INI file.

    Module-level alias for `Model.from_file`.

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
    kalix.ModelParseError
        If the file was read but its contents are not a valid model INI.
    kalix.ModelValidationError
        If the model parsed but failed validation.
    """
    return Model.from_file(model_path)


def load_string(model_string: str) -> Model:
    """Create a `Model` and load it from an in-memory INI string.

    Module-level alias for `Model.from_string`.

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
    kalix.ModelParseError
        If the string is not a valid model INI.
    kalix.ModelValidationError
        If the model parsed but failed validation.

    Notes
    -----
    Unlike `load_file`, there is no containing file directory, so any
    relative paths referenced inside the INI (e.g. data file inputs) are
    resolved against the current working directory.
    """
    return Model.from_string(model_string)
