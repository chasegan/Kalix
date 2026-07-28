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
   identical results for identical models every time. No locking: the onus is
   on the user to retrieve outputs before further modification — and results
   are tied to the model that produced them, so redefining the model discards
   them rather than letting them be read against a definition that has since
   moved on (§7).
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
kalix.load_string(ini_text)     # thin alias for kalix.Model.from_string(...)
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

`[data]` and `[outputs]` hold bare lines rather than `key = value` pairs.
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
alias names one potentially-multi-column dataset, exactly as a `[data]`
entry does in the old world.

```python
m.set_input("climate_data", df)     # pd.DataFrame; returns self
```

### 6.1 Inputs must be declared

`set_input()` does not modify the model definition — it *supplies values*
for an input the definition already declares (think binding parameters to a
prepared statement). The alias must therefore exist in `[data]`;
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
[data]
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
m.patch({"data": {"climate_data": ""}}).set_input("climate_data", df).run()
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

That precedence is scoped to *this model*. `patch()` builds a new model by
reparsing the merged INI (§4.6), and the INI is the complete manifest
(§6.1) — so a file-backed alias comes back file-backed, and an override
supplied for it does not carry across the patch. Data supplied for a bare
declaration *does* survive, because there is nothing for the reparse to
fall back to: dropping it would leave the new model declared-but-unsupplied
and unable to run at all. Both follow from the same rule — the reparsed
model gets whatever the INI says, and `set_input()` state is re-applied only
where the INI leaves a hole. Re-supply a file-backed override after
patching:

```python
m.patch({"node.myreach": {"lag": "2"}}).set_input("climate_data", df).run()
```

Flagged, not settled: the asymmetry is defensible but it is quiet — the
model reverts to real file data rather than failing, so a forgotten
re-`set_input()` runs against the wrong inputs without complaint. Revisit
if that bites in practice; the alternative is to carry file-backed
overrides across a patch the way declarations already are.

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
m.get_outputs([...], missing_ok=True)    # zero-fill anything absent (§7.1)
```

- Index: UTC `DatetimeIndex` named `"time"`, `"s"` precision, matching
  `read_pixie()`. Columns/series named by their output reference string.
- Only series listed in `[outputs]` are recorded. Requesting anything else
  raises, with a message pointing at the patch idiom:
  `m.patch({"outputs": {"node.x.dsflow": ""}}).run()`.
- Calling `get_outputs()` before any successful run raises.
- Results do not outlive the model they describe. `run()` replaces them;
  anything that redefines the model — `patch()`, `load_file()`,
  `load_string()`, `set_input()` — discards them, and `get_outputs()` raises
  `KalixRuntimeError` until the next `run()`. **Retrieve before you
  modify.** This is deliberately stricter than the no-locking principle
  requires: serving a *pre-patch* run's results against a *post-patch*
  definition silently mislabels which model produced them, and the numbers
  look perfectly plausible either way. Failing loudly costs a re-run; the
  alternative costs a wrong answer nobody notices.

### 7.1 `missing_ok`

`missing_ok` applies only to explicitly requested `names` (it has no effect
on `names=None`, which returns exactly what was recorded). Default `False`:
a requested name that is undeclared, unpopulated, or the wrong length raises
`KalixKeyError`. With `missing_ok=True` each such name instead comes back as
an all-zero column of full simulation length, in request order, so one call
can mix real and stand-in columns:

```python
df = m.run().get_outputs(["node.a.dsflow", "node.absent.dsflow"], missing_ok=True)
# node.absent.dsflow -> [0.0, 0.0, ...]
```

This exists for the sweep case — assembling one uniformly-shaped frame
across model variants that don't all declare the same outputs, where a
`try`/`except` per name per variant is the worse code. It is opt-in, and
deliberately so: zero is a *valid flow*, so a zero-filled column is
indistinguishable from a real one downstream. `missing_ok=True` is a
statement that the caller has already decided absence means zero for their
purposes. Never reach for it to make an unexplained `KalixKeyError` go away
— that exception is usually telling you an output is not declared in
`[outputs]`, and the fix is the patch idiom above.

Column naming follows the same rule as elsewhere: a real column carries its
*canonical declared* casing, which may differ from the casing requested
(lookup is case-insensitive); a zero-filled stand-in carries the requested
casing, there being no canonical name to fall back on.

Requesting the same name twice is not an error — it yields that many
duplicate columns, matching `names` position-for-position.

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

Every error the **engine** reports derives from one root, so
`except kalix.KalixError` is a safe catch-all for modelling failures:

```
KalixError(Exception)
├── ModelParseError                 # snippet/string/file failed to parse
├── ModelValidationError            # parsed, but the resulting model is invalid
├── SimulationError                 # the simulation itself failed
├── KalixKeyError(KeyError)         # missing sections/properties/outputs/aliases
└── KalixRuntimeError(RuntimeError) # state/precondition failures (see below)
```

The two mixin classes subclass their builtin counterpart as well as
`KalixError`, so pre-existing `except KeyError` / `except RuntimeError` code
keeps working. The hierarchy is declared in `python/src/error.rs` and
re-exported through `kalix.error` and the top-level `kalix` namespace.

Messages must name the offending section/property — the atomic-swap
guarantee is only as useful as the diagnostic that accompanies the rollback.

### 9.1 Caller errors stay builtin

An argument that is malformed *before the engine is consulted* is a caller
mistake, not a modelling failure. Those raise builtin `ValueError` /
`TypeError` and deliberately sit **outside** `KalixError`: catching
`KalixError` should not swallow a typo in your own calling code. Current
cases:

- `get()` given a designation that isn't `"<section>.<property>"`
  (`ValueError`). A *well-formed* designation naming something absent did
  consult the model, so that is `KalixKeyError`.
- `patch()` given a list of section names with any `mode` other than
  `"delete"` (`ValueError`), or a `mode="delete"` snippet carrying property
  lines rather than bare section headers (`ValueError`).
- `set_input()` given data not indexed by a `DatetimeIndex` (`TypeError`),
  or with an empty/irregular index or mismatched column lengths
  (`ValueError`).

`OSError` likewise stays builtin throughout: a missing or unreadable file is
a filesystem fact, not a statement about the model. The distinction is typed
all the way through the Rust read chain, so a missing file and a malformed
file are never conflated.

### 9.2 Parse vs. validate

The engine's `KalixIoError` carries the distinction the Python types expose,
in three variants — `Io`, `Parse`, `Validate` — and the boundary
(`io_err_to_py`) is a straight one-to-one mapping onto `OSError`,
`ModelParseError`, and `ModelValidationError`. The line between the latter
two:

- **`ModelParseError` — a value could not be read.** A number that isn't
  numeric, a malformed date or expression, a value list of the wrong length,
  an unrecognised enumerated value (`type = nonesuch`), an identifier that
  breaks the naming grammar, a malformed embedded table. Also genuine INI
  syntax errors from the parser itself.
- **`ModelValidationError` — the assembled model doesn't hold together.**
  A required property absent (`Missing 'type'`), a parameter not applicable
  to its node's type, a reference to a node/account/field that doesn't
  exist, a duplicate declaration, a value outside a semantic range, an
  unsupported feature.

Both can arise from the same call: the INI mapper reads and assembles in one
pass, so `load_file()` can raise either. What decides it is the *nature of
the complaint*, not how far through the file the engine had got.

### 9.3 Where each type is raised

| Type | Raised by |
|---|---|
| `ModelParseError` | `load_file()`, `load_string()`, `patch()` — a value in the content could not be read |
| `ModelValidationError` | the same three, when the model they describe doesn't hold together; also `run()`, when the model cannot be configured |
| `SimulationError` | `run()`, when the simulation itself fails |
| `KalixKeyError` | `get()`, `get_section()`, `get_outputs()` naming an output that isn't there (with `missing_ok=False`), `patch(mode="delete")` (with `missing_ok=False`), `set_input()` on an undeclared alias |
| `KalixRuntimeError` | `get_outputs()`/`get_mass_balance()` before `run()`; `get_outputs()` on an output whose recorded series is a length other than the run's; `to_string()`/`save()`/`patch()` on a model with no INI document |

The split within `get_outputs()` is the general rule in miniature. Three
different things can go wrong with a requested name and all three are the
caller **naming something that isn't there**, so all three are
`KalixKeyError` — but each says so differently, which is the point of
keeping them apart: the name is absent from `[outputs]`; or it is declared
and nothing answers to it; or it is declared and registered but empty,
because `[outputs]` names a series the model never produces (a typo there
is the usual cause, and the message says so rather than reporting a length
of zero). The fourth case is different in kind: a *populated* series whose
length isn't the run's. The name resolved and the data contradicts the run
that just succeeded — engine state, not a bad key — so that one is
`KalixRuntimeError`.

The engine keeps the four apart as typed variants (`OutputLookupError` in
`src/model.rs`) so the boundary maps them without reading message text.

### 9.4 Deviations from earlier drafts

- The stateless `simulate()` / `optimise()` entry points still raise plain
  `RuntimeError`, unchanged and outside the hierarchy. Migrating them is
  deferred; they take a file path and return nothing, so they have no
  `Model` state to describe.

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
