# Python API specification — in-memory models

**Status: draft for review.** This spec settles the user-facing shape of the
expanded Python API: defining, modifying, running, and interrogating models
entirely in memory. It deliberately prioritises the interface over the
implementation (per issue #30: the interface must feel natural from the start;
the implementation can improve behind the scenes). Calibration/optimisation
integration is **out of scope** for this document and will be specified
separately once this layer settles.

Distilled from the brainstorming notes
([Notion](https://chasegan.notion.site/Brainstorming-the-Python-API-37c3cd7417a280c693d2e9745ebbb71f))
and issues #30 and #249.

---

## 1. Principles

1. **Chaining.** Every method that modifies model state returns the model
   object itself, so calls string together:
   `model.patch(s).run().get_outputs()`. Data comes out only through
   accessors.
2. **One write path.** All model modification goes through `patch()` (with
   `load_string()` for wholesale definition). There are no per-property
   setters. Any future convenience setters must be thin sugar that builds a
   snippet and calls `patch()` — the semantics never fork.
3. **Atomic modification.** Every patch re-parses and re-validates the entire
   merged model. On any failure the original model is untouched (parse into a
   new object, swap on success). A `Model` that exists is always a valid
   model.
4. **The INI text is the model.** The API reads and writes the same grammar
   the user sees in model files and docs. No parallel naming scheme, no
   second schema to maintain. New engine features are scriptable from Python
   the day they land, with zero binding work.
5. **Clean reset per run.** `run()` clears internal state and produces
   identical results for identical models every time. No locking; the onus is
   on the user to retrieve outputs before further modification.
6. **Values are raw strings.** The API does not coerce INI values to types.
   Property values go in and come out as strings (`"2"`, `"40000, 0.75"`,
   `"(const.a - 5.5)^2"`). Typing is the engine mapper's business.
7. **Pandas out, minimal dependencies.** Results return as `pd.Series` /
   `pd.DataFrame` with a UTC `DatetimeIndex` at `"s"` precision (Kalix
   supports timesteps down to 1 second, no smaller). Runtime dependencies
   remain `numpy` + `pandas` only.

---

## 2. Module surface

```python
import kalix

# Existing stateless conveniences — unchanged, remain useful indefinitely
kalix.simulate(model_file, *, output_file=None, mass_balance=None)
kalix.read_pixie(path)          # -> pd.DataFrame
kalix.write_pixie(path, df, use_64bit_precision=True)
kalix.__version__

# New
kalix.Model                     # the stateful in-memory model class
kalix.load_file(path)           # thin alias for kalix.Model.from_file(path)
```

`kalix.optimise()` is unchanged by this spec (calibration integration comes
later).

---

## 3. Constructing and loading

```python
m = kalix.Model()                       # empty model
m = kalix.Model.from_file("model.ini")  # canonical constructors
m = kalix.Model.from_string(ini_text)

m = kalix.load_file("model.ini")        # module-level alias, same object
```

Instance-level (re)loading, both returning `self`:

```python
m.load_file("model.ini")     # replace entire model with file contents
m.load_string(ini_text)      # replace entire model with this string
```

`load_string()` always means *this string is the whole model*. Partial
application is exclusively `patch()`'s job — there is no `snippet=True` flag
(the earlier `load_string(s, snippet=True)` idea is folded into
`patch(s, mode="merge")`, which is the same operation).

---

## 4. Modifying: `patch()`

```python
m.patch(snippet, mode="merge")             # default
m.patch(snippet, mode="replace")
m.patch(snippet, mode="delete", missing_ok=False)
```

`snippet` is an INI string, or a dict form (see §4.5). Returns `self`.

All modes are **section-scoped**: only sections named in the snippet are
touched. Every other section in the model is left alone.

### 4.1 `merge` (default)

Properties in the snippet are added to or updated on the named section;
properties not mentioned survive. Applied to a section that doesn't exist,
merge creates it — so `patch()` doubles as the model-building primitive.

```python
m.patch("""
[node.ABC]
inflow = 3
""")   # sets inflow on node.ABC; everything else about node.ABC untouched
```

Merge is the default because the failure modes are asymmetric: a merge where
the user meant replace leaves stale properties *visibly present* (inspectable
via `to_string()`, and candidates for validation complaints); a replace where
the user meant merge silently destroys properties, and the model may still
validate on defaults and run quietly wrong. The default makes the likely
mistake the recoverable one.

### 4.2 `replace`

The named section becomes exactly what the snippet specifies; prior
properties not restated are dropped. Creates the section if absent.
`replace` is the deletion primitive for properties (INI has no null literal,
so there is deliberately no per-property delete):

```python
# Drop nlm from a routing node: redefine the section without it
m.patch("""
[node.myreach]
type = routing
lag = 2
ds_1 = node_downstream
""", mode="replace")
```

The read–modify–write idiom (§5) means the user never restates properties
from memory.

### 4.3 `delete`

Removes the named sections entirely. The snippet must contain **only section
headers**; property lines under a delete header are an error (the stricter
behaviour — it catches a forgotten `mode=` far better than silently ignoring
the lines). Deleting a section that doesn't exist is an **error** (catches
typos); pass `missing_ok=True` for idempotent scripted teardown.

```python
m.patch("[node.old_gauge]", mode="delete")
m.patch(["node.old_gauge", "var.tmp"], mode="delete")   # list-of-names form
```

### 4.4 Changing a node's `type`

A `type` change is a redefinition, not a tweak: merging a new `type` onto a
section leaves the old type's properties behind. Validation must reject
properties that are not recognised for the section's (new) node type — which
both catches typo'd property names in general and steers `type` changes to
`mode="replace"`, where they belong. Rule of thumb: *merge tweaks, replace
redefines, and a type change is a redefinition.*

### 4.5 Dict form

`patch()` also accepts `{section_name: {key: value}}`; values are passed
through `str()`. A key mapped to the empty string emits a bare line
(list-style sections, §5.2). This is pure sugar over the string form —
identical semantics, same single write path.

```python
m.patch({"node.ABC": {"inflow": 3}})
m.patch({"outputs": {"node.ABC.dsflow": ""}})
```

### 4.6 Failure

A patch that fails to parse, or produces a model that fails validation,
raises (§9) and leaves the model exactly as it was. There is no partial
application, ever — including multi-section snippets.

---

## 5. Interrogating the model definition

Read access is a direct projection of the retained INI DOM — like `patch()`,
it requires no per-feature maintenance as the model grows.

```python
m.sections()                   # -> list[str], file order
m.has_section("node.myreach")  # -> bool
m.get_section("node.myreach")  # -> dict[str, str], file order
m.get("node.myreach.lag")      # -> str  (single-property convenience)
m.to_string()                  # -> str, round-tripped INI (formatting preserved)
m.save("model.ini")            # write to_string() to disk; returns self
```

### 5.1 `get_section()` semantics

- Returns `dict[str, str]` — clean joined values (continuation lines merged,
  comments stripped), insertion order = file order.
- It is a **snapshot**, not a live view. Mutating the returned dict does not
  touch the model; the only write path is `patch()`.
- Missing section raises (§9). Use `has_section()` to probe.

The canonical read–modify–write idiom:

```python
section = m.get_section("node.myreach")
del section["nlm"]
section["lag"] = "2"
m.patch({"node.myreach": section}, mode="replace")
```

### 5.2 List-style sections

`[inputs]` and `[outputs]` hold bare lines rather than `key = value` pairs.
The DOM (and therefore this API) represents a bare line as a key with an
empty-string value, so every section is uniformly `dict[str, str]`:

```python
m.get_section("outputs")
# {"node.node1.ds_1": "", "node.node2.ds_1": ""}
list(m.get_section("outputs"))   # the list view, when that's what you want
```

The same convention runs in reverse: an empty-string value in a patch emits a
bare line.

### 5.3 Formatting caveat

`get_section()` is the *semantic* view; `to_string()` is the
formatting-faithful view. A `get_section()` → edit → `patch(mode="replace")`
round trip rewrites that section clean — comments inside a replaced section
do not survive. (Merge preserves the comments of untouched properties.)

---

## 6. In-memory inputs

For fully disk-free workflows, datasets can be supplied directly instead of
being read from CSV paths. An in-memory input stands in for a *file*: one
alias names one potentially-multi-column dataset, exactly as an `[inputs]`
entry does in the old world.

```python
m.set_input("climate_data", df)     # pd.DataFrame; returns self
```

### 6.1 Inputs must be declared

`set_input()` does not modify the model definition — it *supplies values*
for an input the definition already declares (think binding parameters to a
prepared statement). The alias must therefore exist in `[inputs]`;
`set_input()` on an undeclared alias raises immediately. This keeps two
guarantees intact:

- **The INI remains the complete manifest of the model.** Every input the
  model consumes is visible in its definition — to `to_string()`, to a
  colleague opening the file, to the IDE. No data requirement exists only as
  ephemeral Python state.
- **Typos fail loudly at the call site.** A permissive `set_input()` would
  let `set_input("climat_data", df)` silently create an unused dataset while
  the model quietly runs against the old file — the worst kind of scenario
  error. Requiring declaration turns it into an immediate, well-named
  exception (the same failure-mode-asymmetry logic behind merge-as-default
  and strict `delete`).

Two declaration forms serve the two use cases:

```ini
[inputs]
climate_data = climate.csv   # file-backed: set_input() substitutes the
                             # file's data — the "swap scenario data without
                             # touching the model" move
observed_flows =             # declared, no backing file: MUST be supplied
                             # via set_input() before run()
```

The empty-value form declares an input that exists only at runtime. A
`run()` while any such input is unsupplied fails validation with a direct
message ("input 'observed_flows' declared but not supplied") rather than a
downstream reference-resolution error. Outside the Python API (CLI, IDE),
an empty-valued input declaration is a validation error at configure time —
nothing there can supply it, so failing is correct, not a limitation.

Adding a brand-new in-memory input to a model is therefore honestly two
steps, which chaining keeps painless:

```python
m.patch({"inputs": {"climate_data": ""}}).set_input("climate_data", df).run()
```

### 6.2 Dataset semantics

The supplied dataset becomes addressable as `data.climate_data.*` precisely
as if a file had been loaded under that alias (see
`docs/data_references.md`):

- `df.index` plays the date column: a `DatetimeIndex`, regular timestep equal
  to the simulation step, UTC (or naive, assumed UTC) — the same requirements
  as `write_pixie()`.
- Column names are addressable via `by_name`, subject to the standard name
  sanitisation (lowercased, non-alphanumerics → `_`, case-insensitive
  matching) so behaviour is identical to the same headers in a CSV.
- Column order is addressable via `by_index` (1-based, date column excluded)
  — the CSV convention.
- Values are coerced to float64.

A bare `pd.Series` is accepted as sugar for a one-column dataset: the column
is `by_index.1`, and `by_name` works too when the series has a `name`.

For a file-backed alias, supplied data takes precedence over the file (the
file is not read); the declaration in the INI remains the source of truth
for *what* the model needs, while `set_input()` decides *where the values
come from* for this session.

Open question (flagged, not settled): whether supplied inputs participate in
automatic simulation-period determination the way critical file inputs do
(proposed: yes, identically).

---

## 7. Running and results

```python
m.run()                          # returns self
m.run(progress=callback)         # callback(step, total); same spirit as optimise()

df = m.run().get_outputs()       # the canonical chained form
```

- `run()` resets all internal state first; a given model produces identical
  results every run, regardless of history. (Required for calibration; also
  just sane.)
- Simulation failure raises (§9); the model definition is unaffected and can
  be patched and re-run.

### Retrieving outputs

```python
m.get_outputs()                          # all recorded outputs -> pd.DataFrame
m.get_outputs("node.my_dam.volume")      # one name  -> pd.Series (index preserved)
m.get_outputs(["node.my_dam.volume",
               "node.my_dam.level"])     # list      -> pd.DataFrame
```

- Index: UTC `DatetimeIndex` named `"time"`, `"s"` precision, matching
  `read_pixie()`. Columns/series named by their output reference string.
- Only series listed in `[outputs]` are recorded. Requesting anything else
  raises, with a message pointing at the patch idiom:
  `m.patch({"outputs": {"node.x.dsflow": ""}}).run()`.
- Calling `get_outputs()` before any successful run raises.
- Results persist until the next `run()`. Patching does **not** clear them —
  per the no-locking principle, the onus is on the user to pull outputs
  before moving on. (`get_outputs()` after a patch returns the *pre-patch*
  run's results; retrieve before you modify.)

### Mass balance

```python
m.get_mass_balance()             # -> pd.DataFrame, after a run
```

Same report the CLI's `-m` flag produces, as a DataFrame instead of a file.

---

## 8. Copying

```python
m2 = m.copy()    # deep, independent copy of the model definition
```

Copies the definition (and in-memory inputs), not run results. The natural
tool for "branch this model and try three variants". Implementation may
simply round-trip `to_string()`.

---

## 9. Errors

All API errors derive from one root, so `except kalix.KalixError` is always a
safe catch-all:

```
KalixError(Exception)
├── ModelParseError        # snippet/string/file failed to parse
├── ModelValidationError   # parsed, but the resulting model is invalid
├── SimulationError        # run() failed
└── KeyError is raised alongside for missing sections/properties/outputs
    (via a KalixError subclass that also subclasses KeyError)
```

Messages must name the offending section/property — the atomic-swap
guarantee is only as useful as the diagnostic that accompanies the rollback.
Plain `RuntimeError` (as currently raised by `simulate()`/`optimise()`) is
reserved for the legacy stateless functions; `Model` methods raise typed
errors from day one.

---

## 10. Chaining summary

| Returns `self` (chainable) | Returns data |
|---|---|
| `load_file()`, `load_string()` | `get_section()`, `get()`, `sections()`, `has_section()` |
| `patch()` | `to_string()` |
| `set_input()` | `get_outputs()`, `get_mass_balance()` |
| `run()`, `save()` | `copy()` (returns the new `Model`) |

```python
results = (
    kalix.Model.from_file("base.ini")
    .patch({"node.myreach": {"lag": "2"}})
    .set_input("rain_gauge_a", rain)
    .run()
    .get_outputs()
)
```

---

## 11. Implementation notes (non-binding)

- PyO3 bindings in the existing `python/src/lib.rs`, per issue #30: minimal
  Python, prefer Rust, no new dependencies. The `Model` handle wraps the
  engine's `Model` + retained `IniDocument` DOM directly (no CLI, no STDIO).
- Patch atomicity = parse merged document into a fresh engine model, swap on
  success — the same retained-DOM machinery that powers lossless resave.
- `run()` releases the GIL (as `simulate()`/`optimise()` already do);
  progress callbacks re-acquire it per report, mirroring `optimise()`.
- Series cross the boundary as numpy arrays + int64 unix-second timestamps,
  assembled into pandas on the Python side (the `read_pixie` pattern).

## 12. Deliberately deferred

- **Calibration/optimisation integration** with `Model` — next spec.
- **Typed accessors / node objects** — may come later as sugar over
  `patch()`/`get_section()`; nothing here precludes them.
- **Per-property deletion under merge** — revisit only if real usage shows
  the `get_section()` → `replace` idiom is a frequent pain.
- **Sub-daily quality-of-life** in downstream tooling (bulum) — noted in the
  brainstorm, out of scope here.
