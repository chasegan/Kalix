# kalix (Python)

Python interface for [Kalix](https://kalixproject.org).

Current functionality:
- run simulations from INI model files (in-process, no separate CLI binary)
- run parameter optimisations from config files (in-process)
- read and write Pixie files (`.pxt`, `.pxb`)
- load, inspect, edit and run models in memory (a stateful `Model` class)

Planned:
- richer optimisation control (in-memory configs)

## Design

The package binds the Rust engine directly via [PyO3](https://pyo3.rs/) rather
than driving the engine's stdio session protocol (which exists to serve
KalixIDE). This keeps the Python surface free to grow in whatever direction
suits Python workflows, independent of the GUI's needs.

The basic functions are deliberately **stateless** and mirror the two main
CLI subcommands — `simulate` and `optimise` — so that moving from the command
line to Python feels the same: point at files, get results back.

Above them sits the stateful `Model` class — load → inspect → mutate → run,
all in memory. It loads from a file or a string, edits the model from INI
snippets (validated before anything is accepted), accepts pre-declared inputs
straight from `DataFrame`s, runs, and returns results and mass balance as
`DataFrame`s. Nothing has to touch the disk between load and result, and
nothing is hidden: what a `Model` holds is the same INI text you would write
by hand, and `to_string()` gives it back.

## Install

```bash
pip install kalix
```

## Usage

### Stateless functions

#### Run a simulation

```python
import kalix

kalix.simulate("model.ini", output_file="results.pxb")
df = kalix.read_pixie("results.pxb")
print(df.head())
```

The Python equivalent of `kalix sim model.ini -o results.pxb`, but in-process —
no separate CLI binary required. Output format is inferred from the extension
(`.pxb` for the Pixie pair, `.csv` for CSV).

Both outputs are keyword-only, and at least one is required:

```python
# Outputs + mass-balance report
kalix.simulate("model.ini", output_file="results.pxb", mass_balance="mb.txt")

# Mass-balance only
kalix.simulate("model.ini", mass_balance="mb.txt")
```

#### Run an optimisation

```python
import kalix

result = kalix.optimise("calibration.ini", model_file="initial_model.ini", save_model="final_model.ini")

print(result["best_objective"])      # lower is better
print(result["parameters"])          # {"node.my_sac.uzfwm": 42.7, ...}
print(result["optimised_model_ini"]) # string copy of the final model ini
```

The Python equivalent of `kalix optimise calibration.ini`, but in-process. The
config `.ini` defines the algorithm, calibration terms, objective expression,
parameter bounds, and termination criteria. Unlike `simulate`, `optimise`
returns a result dictionary:

| key | meaning |
|---|---|
| `best_objective` | best objective value found (lower is better) |
| `n_evaluations` | number of function evaluations performed |
| `success` | whether the optimiser terminated successfully |
| `message` | the optimiser's termination message |
| `parameters` | optimised parameters as `{target: physical_value}` |
| `optimised_model_ini` | the optimised model serialised back to an INI string |

Two keyword-only options mirror the CLI's flags:

```python
# Override the config's model_file (CLI: positional [model_file])
kalix.optimise("calibration.ini", model_file="other_model.ini")

# Also write the optimised model to disk (CLI: -s/--save-model)
kalix.optimise("calibration.ini", save_model="tuned.ini")
```

Paths inside the config (`model_file`, each term's `observed_file`) are
resolved relative to the current working directory, exactly as the CLI does.
If the config specifies an `output_file`, a results summary is written there
too.

#### Read / write Pixie files

```python
df = kalix.read_pixie("results.pxb")
print(df.head())

kalix.write_pixie("out.pxb", df)
```

The DataFrame index is a UTC `DatetimeIndex`; each column is one timeseries.

`read_pixie` accepts either extension (or no extension) and finds both files:

```python
kalix.read_pixie("results.pxb")  # same as
kalix.read_pixie("results.pxt")  # same as
kalix.read_pixie("results")
```

`write_pixie` prefers a tz-aware `DatetimeIndex`, but will try to coerce other
inputs (emitting a `UserWarning` when it does):

- a naive `DatetimeIndex` is localised to UTC;
- a default `RangeIndex(0, n, 1)` triggers promotion of the zeroth column to
  the index (and drops it from the body);
- any other non-`DatetimeIndex` is passed through `pd.to_datetime(..., utc=True)`.

Integer and float dtypes are never auto-interpreted as datetimes (this would
silently misread values as epoch nanoseconds). Set a `DatetimeIndex` explicitly
in those cases.

### Model API

This section provides a practical overview. Please review docstrings for
information regarding errors and method call signatures.

#### Creating and running a `Model` instance

```python
import kalix
import pandas as pd

# A model is instantiated from load_* or Model.from_*
model: kalix.Model
model = kalix.load_file("model.ini")
model = kalix.load_string("...")           # same as
model = kalix.Model.from_file("model.ini") # same as
model = kalix.Model.from_string("...")     # same as

model.run()

# Outputs must be declared in the model's [outputs] section
df: pd.DataFrame
df = model.get_outputs()  # every declared output
df = model.get_outputs(["node.a.dsflow", "node.b.dsflow"])
df = model.get_outputs("node.a.dsflow")

mass_balance: pd.DataFrame = model.get_mass_balance()

# Most methods return self, so calls chain:
df = kalix.load_file("model.ini").run().get_outputs()
```

The DataFrame output from the model API follows the same conventions as the
Pixie file reader — the index is a UTC `DatetimeIndex`, and each column is one
timeseries, labelled as it was declared in the model's `[outputs]` section.

A model may be run more than once — each `run()` resets node and account state,
so repeated runs are independent.

#### In-memory editing of `Model`s

Models are edited in memory via `patch()`. It accepts a model snippet (a
string or a dictionary, both shown below) and one of three modes. A patch that
is malformed, or that would result in an invalid model, leaves the `Model`
exactly as it was — the patch is applied to a copy and swapped in only on
success.

The following patch adds a fictitious new node `node.c`, adds its `dsflow` to
`[outputs]` and hooks it in to `[node.b]`.

```python
model = kalix.load_file("model.ini")

model.patch("""
[node.b]
ds_1 = node.c

[node.c]
type = gauge
loc = 0, 0

[outputs]
node.c.dsflow
""")

df = model.run().get_outputs()
```

`Model.patch()` also accepts dictionaries with structure `{section: {property:
value}}`, where a blank `value` emits a bare `property` line.

```python
model.patch({"node.b": {"ds_1": "node.c"},
             "node.c": {"type": "gauge",
                        "loc":  "0, 0"},
             "outputs": {"node.c.dsflow": ""}
})
```

The mode is keyword-only and defaults to `merge`:

| mode | effect |
|---|---|
| `merge` | set the named properties, leaving everything else on the section untouched |
| `replace` | replace each named section wholesale — properties the patch omits are dropped |
| `delete` | remove each named section wholesale; property-level deletion is not supported |

A section the model doesn't yet have is appended under `merge` and `replace`.
Under `delete`, a section that isn't there is an error unless `missing_ok=True`
is passed. Because `delete` needs only section names, it also accepts a plain
list of them — the one form the other modes reject:

```python
model.patch(["node.c", "node.d"], mode="delete")
model.patch("[node.c]\n[node.d]\n", mode="delete")       # the same, as INI text
model.patch(["node.c"], mode="delete", missing_ok=True)  # otherwise throws
```

#### Supplying inputs from memory

`set_input()` supplies data for a `[data]` alias from a `DataFrame` (or a bare
`Series`, taken as a one-column frame) instead of from a file. The alias must
already be declared — either bare, or pointing at a file, in which case the
supplied data takes precedence. `set_input()` fills a declaration; it does not
create one, so declare it first (via `patch()`) if it isn't there.

The following snippet indicates how this method should be used.

```python
model = kalix.load_string("""
...
[data]
obs
...
""")

index = pd.date_range("2000-01-01", periods=5, freq="D")
model.set_input("obs", pd.DataFrame({"flow": [1.0, 2.0, 3.0, 4.0, 5.0]}, index=index))

df = model.run().get_outputs()
```

The frame must carry a `DatetimeIndex` with a regular step equal to the
simulation timestep — the same requirement as `write_pixie()` — and a naive
index is assumed to be UTC. Columns are addressable by name
(`data.obs.by_name.flow`, with the standard sanitisation) or by 1-based
position (`data.obs.by_index.1`), exactly as if a file had been loaded under
the alias. Values are coerced to float64.

Like `patch()`, supplying new input data invalidates any prior run's results,
so `run()` again before reading outputs.

#### Inspecting the model

The model's INI document is readable without serialising it back out:

```python
model.sections()                 # ["kalix", "data", "node.a", ...], in file order
model.has_section("node.a")      # True
model.get_section("node.a")      # {"type": "gauge", "loc": "0, 0", ...}
model.get("node.a.loc")          # "0, 0"
```

`get()` takes a dotted `"<section>.<property>"` designation and splits on the
*last* dot, so section names that themselves contain dots — as node sections
do — resolve correctly. Values come back as strings, exactly as written in the
INI; parse them yourself if you want numbers.

`get_section()` returns a snapshot, not a live view: mutating the returned dict
does not touch the model, and `patch()` remains the only write path. A
list-style section such as `[data]` or `[outputs]` comes back with each bare
line as a key mapped to an empty string.

`get_section()` and `get()` are strict about lookups that miss — use
`has_section()` to probe first, or catch `KalixKeyError`.

There is no write-through counterpart — no `put()`, and assigning into the
returned dict changes nothing. Read-modify-write means handing the edited
snapshot back to `patch()`:

```python
section = model.get_section("node.a")   # {"type": "gauge", "loc": "0, 0"}
section["loc"] = "10, 20"
model.patch({"node.a": section}, mode="replace")
```

The two halves are deliberately symmetric: what `get_section()` hands out is
exactly what `patch()` takes back, down to the bare-line convention, so
`[outputs]` and `[data]` round-trip the same way. `replace` above swaps the
section for the snapshot wholesale; `merge` would apply only the keys the
snapshot still carries.

#### Serialisation and copying

```python
ini_text = model.to_string()   # round-tripped INI
model.save("edited_model.ini") # ...or straight to disk

variant = model.copy()         # deep, independent copy
```

`to_string()` preserves the original formatting of properties the model hasn't
changed, so a load → edit → save round trip produces a readable diff rather
than a wholesale rewrite. `save()` writes that same text to a file, and returns
`self` so it can sit mid-chain.

`copy()` is a deep copy: the definition and any data supplied via `set_input()`
come with it, and thereafter the two models are fully independent — patching
one cannot leak into the other. Run results do not come with it; a fresh copy
starts out unrun.

## Building from source

Requires Rust (1.70+) and [maturin](https://www.maturin.rs/).

```bash
# Editable install for development
cd python
maturin develop --release
# uv run maturin develop --release --uv

# Build a wheel
maturin build --release
# uv run maturin build --release -- uv
```

## Tests

### Running tests directly

```bash
maturin develop
pytest python/tests
# uv run maturin develop --uv
# uv run pytest
```

### Running tests with tox

Tox is configured to test against multiple Python versions (3.9-3.14):

```bash
# Run tests in development environment (fast, uses current Python)
uv run tox -e dev

# Run tests against all configured Python versions
uv run tox

# Run tests for specific Python version
uv run tox -e py312

# List all available test environments
uv run tox list

# Run tests in parallel
uv run tox -p auto
```
