---
title: "ADR-0012: Inputs must share the simulation grid; Kalix does not realign them"
---

# ADR-0012: Inputs must share the simulation grid; Kalix does not realign them

- **Status**: Proposed
- **Date**: 2026-10-16
- **Deciders**: Chas Egan (@chasegan); proposed by Ciaran (@Eldred777)
- **Tags**: engine, io

## Context and problem statement

A model's simulation grid is a start time with regularly spaced intervals.
The data provided to a model inherently has its own time grid, and two daily
data files may disagree on the time of day they are aligned to, e.g.
rainfall aligned to 9am and a flow sequence aligned to midnight. This ADR
records that the grid must agree across all input files provided — Kalix
will not realign them.

Until now Kalix accepted such models silently. The grid came from whichever
critical input a node referenced first. Other inputs were looked up by
flooring onto that grid, so 9am data on a midnight grid ran a day late. A
date-only `start` on 9am data moved the grid in the same way.

The question: when inputs (or a specified `start`/`end`) disagree on the
time of day, should Kalix realign them, and if so, by what rule?

## Decision drivers

- Manifesto §2.2 — what you read is what runs. A realignment rule the model
  file doesn't show is hidden behaviour.
- Manifesto §2.3 — correctness first. Whether 9am-to-9am rainfall belongs
  with a midnight-to-midnight flow is a hydrological judgement.
- Manifesto §2.8 — less is more.
- [ADR-0005](0005-node-definition-order.md) — the same shape of decision:
  the engine validates what the file says and refuses, rather than repairing
  it behind the modeller's back.
- Precedent — calibration already refuses observed data whose grid is out
  of phase with the simulation (`Optimisation::align`).

## Considered options

1. **Reject out-of-phase inputs** — configure fails with a message naming
   the inputs and their times of day.
2. **Treat date-only data as unspecified time** — align date-only inputs to
   the timestamped data by calendar date, and reject only timestamped inputs
   that disagree.
3. **Warn and align by calendar date** — realign everything and print a
   warning.
4. **Snap to the nearest grid point** — look inputs up by rounding instead
   of flooring.
5. **A per-input offset option** — a model-file setting declaring how to
   read a file's times.

## Decision

Chosen option: **1, reject out-of-phase inputs**. It does not assume
knowledge that the modeller has not provided, and leaves the decision on how
to treat misaligned data in the modeller's hands. Option 2 was preferred at
first and set aside on 2026-10-16 for the reasons below.

1. **Every input a model reads must share the simulation grid's step and
   phase** (start time mod step; for daily data, the time of day).
   Otherwise configure fails, naming the inputs and their times of day.
   Kalix never shifts a value onto a different time to make it fit.
2. **A specified `start` or `end` must lie on the grid.** Otherwise
   configure fails and suggests the on-grid value (e.g.
   `use '2020-01-02T09:00:00'`). A date-only value means midnight, so with
   9am data it is rejected rather than snapped.
3. **The grid is still taken from the critical inputs.** Since they must
   agree, which one is registered first no longer changes the result.

### Consequences

- ✅ No silent day shifts; reordering node sections can no longer change the
  output times.
- ✅ The rule is one sentence and matches calibration.
- ✅ The check is cold-path work at configure time, nothing in the loop
  ([ADR-0004](0004-performance-on-the-hot-path.md) §3.5).
- ❌ Modellers mixing 9am and midnight sources must reconcile their files
  before running.
    - Accepted: the files really do describe different 24-hour windows, and
      only the modeller knows which pairing is right.

## Pros and cons of the options

### Option 1: Reject

- ✅ Transparent and correct by construction; small change.
- ❌ Mixed-source models need data preparation first.

### Option 2: Date-only data as unspecified time

- ✅ Common mixed cases (dated flows, timestamped rain) run without data
  preparation.
- ❌ The pairing rule (same calendar date, most overlap, or the step ending
  on the date) is a hidden choice.
- ❌ It needs date-only vs timestamped tracked per input, including for
  in-memory and Pixie data, which have no text format to infer it from.

### Option 3: Warn and align by date

- ✅ Models just run.
- ❌ Warnings are easy to miss in the CLI, Python and IDE.
- ❌ It needs a rule for which time of day wins.

### Option 4: Snap to nearest

- ✅ One-line change.
- ❌ Still silent, and it flips at a 12-hour offset (09:00 rounds one way,
  15:00 the other).

### Option 5: Per-input offset option

- ✅ Explicit, and it supports deliberate mixing.
- ❌ New syntax (reader, writer, linter, docs) with no demand yet
  (Manifesto §2.7, §2.8). It remains open as a future extension on top of
  option 1.

## Worked example

Daily inputs `inflow_9am.csv` (`2020-01-01T09:00:00`, ...) and
`inflow_midnight.csv` (`2020-01-01`, ...) in one model:

```text
Error: Input 'data.inflow_9am_csv.by_name.flow' is on a grid at 09:00:00, but 'data.inflow_midnight_csv.by_name.flow' is at 00:00:00. All inputs must share the same time of day.
```

`start = 2020-01-02` with 9am data:

```text
Error: Specified start '2020-01-02' is not on the input data's grid at 09:00:00; use '2020-01-02T09:00:00'.
```

## Enforcement

**Structural** — `Model::auto_determine_simulation_period` (`src/model.rs`)
checks critical inputs and `start`/`end`, and the input fill in
`Model::configure` checks every referenced input, all via
`Timeseries::same_phase` (`src/timeseries.rs`), which `Optimisation::align`
also uses. Tests: `src/tests/test_input_phase.rs`.

## Links and references

- Related: [ADR-0004](0004-performance-on-the-hot-path.md),
  [ADR-0005](0005-node-definition-order.md)
- Precedent: `Optimisation::align` (`src/numerical/opt/optimisation.rs`)

## Amendments

