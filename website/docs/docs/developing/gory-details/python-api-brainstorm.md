---
title: Brainstorming the Python API
---

# Brainstorming the Python API

# Summary of Outcomes

> Distilled from the brainstorming notes below. These are the decisions we landed on, ready to be turned into GitHub issue specs.

## General principles

- **Chaining is preferred.** State-modifying functions return the model object itself so calls can be strung together (e.g. `my_model.run().get_outputs()`). Accessor functions are used to pull data out.

- **The model resets cleanly on each run.** Clearing internal state every run is a hard requirement (needed for calibration). No locking mechanism; the onus is on the user to retrieve outputs before making further modifications.

- **Pandas data structures for outputs.** A single requested output returns a `pd.Series` (preserves the index); multiple outputs return a `pd.DataFrame`.

- **Consistent time precision.** DataFrames are written internally with dates at `"s"` (second) precision, since Kalix supports timesteps down to 1 second (but no smaller). Can be converted to `"ns"` downstream if needed.

- **Two ways to build a model:** load from a file, or construct/modify entirely in Python via the API (new model + load string/snippet + patch).

- **Editing via INI patches.** Modifications are applied by passing an INI snippet to `patch()`, which updates the relevant parts of the DOM and re-parses/re-validates the whole model. Crude relative to dedicated accessors, but robust, powerful, and requires no extra maintenance as the model object grows richer. Accessors may come later.

- **Note:** bulum will eventually need to be extended to accept sub-daily timesteps.

## Agreed function signatures

### Loading & constructing models

```python
import kalix

# Load a model directly from a file
my_model = kalix.load_file("model_file.ini")

# Create an empty model, then populate it
my_model = kalix.new_model()
my_model.load_file("model_file.ini")

# Load a full model from a string
my_model.load_string(whole_model_string)

# Load a partial model (snippet) from a string
my_model.load_string(snippet_string, snippet=True)
```

### Running simulations

```python
# run() returns the model (to support chaining), NOT results directly
my_model.run()

# Retrieve outputs after running
results_df = my_model.run().get_outputs()
```

### Accessing outputs

```python
# All outputs -> pd.DataFrame
results_df = my_model.get_outputs()

# Single output -> pd.Series (index preserved)
x = my_model.get_outputs("node.my_dam.volume")

# Multiple outputs -> pd.DataFrame
y = my_model.get_outputs(["node.my_dam.volume", "node.my_dam.level"])
```

### Building & modifying models

Models are edited by applying an INI snippet via `patch()`. The snippet is merged into the DOM and the whole model is re-parsed and re-validated.

```python
# Apply an INI snippet to an already-loaded model
my_model.patch(snippet, mode="merge")    # default
my_model.patch(snippet, mode="replace")
my_model.patch(snippet, mode="delete")

# Example
my_model.patch("""
[node.ABC]
inflow=1
""")  # mode="merge" -> sets/adds inflow on node.ABC, other properties untouched
```

#### Patch semantics

All modes are **section-scoped**: only sections named in the snippet are touched; any other section in the model is left alone. The mode controls what happens to a named section:

- `merge` (default) — properties in the snippet are added or updated on the existing section; properties not mentioned in the snippet survive. The everyday "tweak a value" operation. Applied to a section that doesn't exist yet, it creates it (so `patch(s, mode="merge")` doubles as a model-building primitive — likely equivalent to `load_string(s, snippet=True)`; confirm and collapse to one if so).

- `replace` — the named section is redefined to be exactly what the snippet specifies; any prior properties not in the snippet are dropped. Creates the section if it doesn't exist.

- `delete` — the named sections are removed. Only section headers are needed; decide whether property lines under a delete header are ignored or rejected (rejecting is the stricter, safer default). Deleting a non-existent section is either a no-op (friendly/idempotent) or an error (catches typos) — decision pending.

**Atomicity (hard rule):** every patch re-parses and re-validates the entire merged INI. If a patch fails to parse or validate in any mode, the original model is left completely untouched (parse into a new object, swap on success). This is the safety guarantee that justifies the re-parse approach over in-place mutation.

**Open questions / TBD**

- Confirm whether `patch(s, mode="merge")` and `load_string(s, snippet=True)` are the same operation and should be unified.

- Behaviour of `delete` on a missing section (no-op vs. error).

- Whether per-property deletion is ever needed, or whether section `replace` covers it (INI has no natural null literal, so leaning on `replace`).

- A separate `remove_section()` is no longer needed — `mode="delete"` covers section-level deletion.

---

# Things we want to do with ….

## Simultation

```python
import kalix

my_model = kalix.load_file("model_file.ini")   # or "read_file()"?

# Should you be able to do this too?
my_model = kalix.new_model()
my_model.load_file("model_file.ini")
# CKG: If we have an edit function then I think this would be a good idea as
#      you can construct the model fully in python via the API

my_model.load_string(big_log_ini_string)

# Should "run()" return results?
results_df = my_model.run()

# Or is it better for state-modifying functions to just return 
# the base object again, so we can string functions together
same_model = my_model.run()

# Stringing...
results_df = my_model.run().get_outputs()
# CKG: I like stringing them together more - especially if getting outputs 
#     out via method on the model. Though maybe should lock a model in once run
#     so you can't accidentally associate a model to the wrong inputs (i.e. guaranteed
#     to return same results and no order of execution issues) - would possibly need a
#     .copy() method that will reset the lock. Thoughts?

# The model should reset fine each run. Clearing the internal state is 
# already a must-have for me for calibration runs.

# OK makes sense/sounds good - onus is on the user to make sure they get outputs before
# any modifications then. 

# So it that the rule... any function that modifies the model state will 
# just return the model. And then we use accessor functions to get other 
# things out?

# I'm on board with that - good for chaining edits together and 
# clear when accessing results

results2_df = my_model.get_outputs() # get all outputs again? 

x = my_model.get_outputs("node.my_dam.volume") # should this be numpy array?
# CKG: pd.Series likely more appropriate, since you preserve the index? Assuming next line is DF
# Ahhhh yes agreed! 

y = my_model.get_outputs(["node.my_dam.volume", "node.my_dam.level"]) # should this be a pandas df

# Kalix supports down to 1 second timesteps (but no smaller). 
# I wonder if we should always write out df with dates internally at "s" precision.
# I guess so... ? Good to be consistent. Even if many tools default ot "ns"?

# I think so. Yep once you have a pd.Index or similar you can convert to ns if needed,
# and can parse if just a string/number.
# Aside: we'll need to edit bulum to accept sub-daily timesteps eventually haha
#.       Yeah. There are lots of Bulum features that just wont translate to sub-daily
#.       So maybe not tooo bad.
```

## Building and modifying models

```python
import kalix

my_model = kalix.load_file("model_file.ini")

my_model.add

#?
#?

my_model.edit_snippet("""
[node.ABC]
inflow=1
""") # or "[node.ABC]\ninflow=1"
# Name pending

my_model.load_string(whole_model_string)
my_model.load_string(snippet_string, snippet=True)
# CKG This is good, I think.
```
