---
title: "ADR-0015: Resolve every expression reference at load; check output entries for layout only"
---

# ADR-0015: Resolve every expression reference at load; check output entries for layout only

- **Status**: Proposed
- **Date**: 2026-10-29 (§1 was already the engine's behaviour for every
  namespace except `node.*`, `var.*` and `const.*`; recorded here)
- **Deciders**: Proposed by Ciaran (@Eldred777)
- **Tags**: engine, io, language

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
Existence is checked for `data.*`, `acc.*` and `ras.*` entries, but not for
`node.*` and `var.*` entries. An unchecked entry naming nothing is handled
differently on each output path: the IDE receives an empty series, the CLI
leaves the column out of its CSV, and Python raises an error for a series
requested by name unless `missing_ok=True`, which returns a zero-filled
series.

The question: which references must resolve, and when is that checked?

## Decision drivers

- Manifesto §2.2 — what you read is what runs. A reference that silently
  reads a default is not the calculation the modeller wrote.
- Manifesto §2.3 — behaviour on failure is never so lean that a problem
  becomes untraceable. A typo in a reference should stop the model where it
  is written, not surface as wrong numbers.
- Manifesto §2.5 — flexibility and control for expert modellers. A long
  output list kept across model variants should not block a run because one
  entry has gone stale.
- [ADR-0005](0005-node-definition-order.md) and
  [ADR-0012](0012-inputs-in-step-with-the-simulation.md) — the engine refuses
  what the file says rather than repairing it.
- [ADR-0006](0006-expression-naming.md) §2.5 — every user-defined name is
  reached through a namespace, so one rule can cover them all.

## Considered options

1. **Expression references resolve at load; output entries are checked for
   layout only.**
2. **Both resolve at load.**
3. **Per namespace**, decided as each namespace is added.
4. **Resolve at run time**, failing at the first read of a missing value.

## Decision

Chosen option: **1**. An expression computes a result, so a reference to
nothing changes the result; an `[outputs]` entry is a request to record, so
a request for nothing changes no result.

1. **Every reference an expression reads must resolve when the model loads,
   in every namespace, with or without an offset.** A reference that names
   nothing is an error naming it, never a default, NaN or zero. A namespace
   added later comes with its check.
2. **A reference resolves when the model defines or writes what it names**:
   an input column, a constant, an output the node's type records, a var, an
   account or group field, a RAS field, a table, or a `sim.*` field.
3. **An `[outputs]` entry must be dot-separated segments, at least two, each
   made of ASCII letters, digits and underscores**, matched
   case-insensitively ([ADR-0006](0006-expression-naming.md) §2.6 and its
   2026-10-29 amendment). A malformed entry is refused at load, naming the
   line. Expression references get the same shape from the grammar.
4. **A `node.*` or `var.*` entry in `[outputs]` is not checked for
   existence.** An entry that nothing writes stays empty; each output path
   decides how to present an empty series. `data.*`, `acc.*` and `ras.*`
   entries keep the existence checks they already had, and are outside this
   decision.

Option 2 lets one stale entry stop the whole run, and an output list could
no longer be shared between model variants. Option 3 is how #442 happened:
each namespace remembered the check or forgot it. Option 4 fails only when
a run reaches the read, possibly years into the simulation or never, and
cannot catch a lagged read that returns its default.

Consequence: a misspelled but well-formed `[outputs]` entry
(`node.dam.volme`) is not caught at load. It shows up as an empty or missing
series, or in Python as an error or a zero-filled series. Accepted, because
the cost is visible in the results and limited to that one series.

## Worked example

A lagged read of an output the node's type doesn't record, in a model that
has a `[node.catchment]`:

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
node.retired.dsflow    # accepted; nothing writes it, so it records nothing
node.my dam.volume     # refused at load
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
  `Model::configure_model_structure` (`src/model.rs`). The optimiser assigns
  its constants before `configure`. Tests:
  `src/tests/test_reference_validation.rs`.

§3: the `[outputs]` branch of the model reader refuses an entry unless every
segment passes `is_valid_name_segment` (`src/misc/misc_functions.rs`);
`DataCache` looks series up by lowercased name. Tests:
`src/tests/test_reference_validation.rs`.

**Advisory** — §1's "a namespace added later comes with its check", and §4,
are held by review.

## Links and references

- Related: [ADR-0005](0005-node-definition-order.md),
  [ADR-0006](0006-expression-naming.md),
  [ADR-0012](0012-inputs-in-step-with-the-simulation.md)
- Issue: [#442](https://github.com/chasegan/Kalix/issues/442)

## Amendments

*None yet.*
