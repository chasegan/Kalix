---
title: "ADR-0017: Resolve every expression reference at load; check output entries for layout only"
---

# ADR-0017: Resolve every expression reference at load; check output entries for layout only

- **Status**: Proposed
- **Date**: 2026-10-29
- **Deciders**: Chas Egan (@chasegan); proposed by Ciaran (@Eldred777)
- **Tags**: engine, io, language, ide

## Context and problem statement

An expression reads values through dotted references: `data.*`, `const.*`,
`node.*`, `var.*`, `acc.*`, `ras.*`, `table.*` and `sim.*`. The `[outputs]`
section uses the same notation to name the series to record. Each reference
raises two questions: is it well formed, and does it name something that
exists?

Until #442 the second question was answered namespace by namespace, as each
was added. A missing `data.*`, `acc.*`, `ras.*`, `table.*` or `sim.*`
reference, or an unknown function, already stopped the model loading. A
missing `node.*` or `var.*` reference did not: it created an empty series on
the spot, so a lagged read returned its default for the whole run, and an
unlagged one failed mid-run as "computed later in the timestep". A missing
`const.*` reference read 0: the check for unassigned constants existed, but
nothing on the load path called it.

`[outputs]` entries were never checked for layout, so an entry that no
expression could reference was accepted, for example `node.my gr4j.dsflow`.
Nor were they checked for existence, except `data.*` entries, which must
name an input column because the column is loaded. The IDE's linter, on the
other hand, has always flagged an output entry that names nothing. That split
began as an oversight. It has turned out to be convenient: a modeller can
remove nodes without pruning the output list, and the model still runs, with
the editor pointing at the stale entries.

The question: which references must resolve, and when is that checked?

## Decision drivers

- Manifesto §2.2 — what you read is what runs. A reference that silently
  reads a default is not the calculation the modeller wrote.
- Manifesto §2.3 — behaviour on failure is never so lean that a problem
  becomes untraceable. A typo in a reference should stop the model where it
  is written, not surface as wrong numbers.
- Manifesto §2.5 — flexibility and control for expert modellers. Removing a
  node, or keeping one output list across model variants, should not block a
  run because an entry has gone stale.
- [ADR-0005](0005-node-definition-order.md) and
  [ADR-0012](0012-inputs-in-step-with-the-simulation.md) — the engine refuses
  what the file says rather than repairing it.
- [ADR-0006](0006-expression-naming.md) §2.5 — every user-defined name is
  reached through a namespace, so one rule can cover them all.

## Considered options

1. **Expression references resolve at load; output entries are checked for
   layout only, and the IDE's linter flags the stale ones.**
2. **Both resolve at load.**
3. **Per namespace**, decided as each namespace is added.
4. **Resolve at run time**, failing at the first read of a missing value.

## Decision

Chosen option: **1**. An expression computes a result, so a reference to
nothing changes the result; an `[outputs]` entry is a request to record, so
a request for nothing changes no result.

1. **Every reference an expression reads must resolve when the engine loads
   the model, in every namespace, with or without an offset.** A reference
   that names nothing is an error naming it, never a default, NaN or zero. A
   namespace added later comes with its check.
2. **A reference resolves when the model defines or writes what it names**:
   an input column, a constant, an output the node records, a var, an
   account or group field, a RAS field, a table, or a `sim.*` field.
3. **An `[outputs]` entry must be dot-separated segments, at least two, each
   made of ASCII letters, digits and underscores**, matched
   case-insensitively ([ADR-0006](0006-expression-naming.md) §2.6 and its
   2026-10-29 amendment). A malformed entry is refused when the engine loads
   the model, naming the line. Expression references take their shape from
   the grammar, which is no narrower.
4. **The engine does not check an `[outputs]` entry for a computed series
   (`node.*`, `var.*`, `acc.*`, `ras.*`) for existence; the IDE's linter
   does.** An entry that nothing writes records an empty series, and each
   output path decides how to present it. The linter flags the entry in the
   editor, so the modeller sees it without the run being refused. A `data.*`
   entry must name an input column, as every `data.*` reference must: the
   column is loaded, not computed, and a missing one is a missing input.

Option 2 lets one stale entry stop the whole run: removing a node would mean
pruning its outputs first, and an output list could no longer be shared
between model variants. Option 3 is how #442 happened: each namespace
remembered the check or forgot it. Option 4 fails only when a run reaches
the read, possibly years into the simulation or never, and cannot catch a
lagged read that returns its default.

Consequence: a misspelled but well-formed `[outputs]` entry
(`node.dam.volme`) is a lint error in the IDE and an empty or missing series
in the results, or in Python an error unless `missing_ok=True`. Accepted,
because the cost is visible and limited to that one series. §4 is held for
convenience, not principle, and is open to revision should a good reason
arrive.

## Worked example

A lagged read of an output the node doesn't record, in a model that has a
`[node.catchment]`:

```ini
inflow = node.catchment.dsflw[-1, 0.0]
```

```text
Node 'catchment' has no output 'dsflw' (in reference 'node.catchment.dsflw').
```

An `[outputs]` section:

```ini
[outputs]
node.dam.volume        # recorded
node.retired.dsflow    # runs; records nothing; the IDE's linter flags it
node.my dam.volume     # refused when the engine loads the model
```

```text
Error on line 4: Invalid output 'node.my dam.volume'. Expected dot-separated names of letters, digits and underscores, e.g. node.<name>.<output>.
```

## Enforcement

**Structural** — §1 and §2, by namespace:

- `data.*`: the input lookup and `Model::validate_data_references`
  (`src/model.rs`).
- `acc.*` and `ras.*`: the reference checks at the end of the model reader
  (`src/io/ini_model_io_versions/ini_doc_model_io_0_0_1.rs`).
- `node.*` and `var.*`: `Model::validate_computed_references`
  (`src/model.rs`), from series that `DataCache` records as read by an
  expression and written by a node recorder or var block. Tests:
  `src/tests/test_reference_validation.rs`.
- `table.*`, `sim.*` and functions: resolved when the expression is lowered,
  which fails on an unknown name.
- `const.*`: `ConstantsCache::assert_all_constants_have_assigned_values`
  (`src/data_management/constants_cache.rs`), called from
  `Model::configure_model_structure` (`src/model.rs`). The optimiser sets
  the value of a declared constant; an undeclared one is refused at load on
  every path, the optimiser's included. Tests:
  `src/tests/test_reference_validation.rs`.

§3: the `[outputs]` branch of the model reader refuses an entry unless every
segment passes `is_valid_name_segment` (`src/misc/misc_functions.rs`);
`DataCache` looks series up by lowercased name. Tests:
`src/tests/test_reference_validation.rs`.

§4, the linter's half: the `output_references` rule in the IDE's linter
schema (`kalixide/src/main/resources/linter/kalix-model-schema.json`) flags
an output entry whose node or output does not exist. It is deliberately
stricter than the engine.

**Advisory** — §1's "a namespace added later comes with its check", and the
engine's half of §4, are held by review.

## Links and references

- Related: [ADR-0005](0005-node-definition-order.md),
  [ADR-0006](0006-expression-naming.md),
  [ADR-0012](0012-inputs-in-step-with-the-simulation.md)
- Issue: [#442](https://github.com/chasegan/Kalix/issues/442); pull request
  [#498](https://github.com/chasegan/Kalix/pull/498)

## Amendments

*None yet.*
