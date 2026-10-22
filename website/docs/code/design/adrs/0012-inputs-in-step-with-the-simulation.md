---
title: "ADR-0012: Inputs must be in step with the simulation; Kalix does not realign them"
---

# ADR-0012: Inputs must be in step with the simulation; Kalix does not realign them

- **Status**: Proposed
- **Date**: 2026-10-16
- **Deciders**: Chas Egan (@chasegan); proposed by Ciaran (@Eldred777)
- **Tags**: engine, io

## Context and problem statement

Two daily files can disagree on the time of day: rainfall stamped
`2020-01-01T09:00:00` and flow stamped `2020-01-01`. Until now Kalix ran such
a model silently. The simulation's timesteps came from whichever critical
input a node referenced first, and other inputs were floored onto them, so
9am data on midnight steps ran a day late. A date-only `start` on 9am data
moved the steps the same way, and a 9am timestamp was written back as a bare
date, so a saved model or output file re-read a day late. Date-only files are not
affected: two of them share midnight. The conflict arises only when a file
carries an explicit time.

The question: when inputs, or `start`/`end`, disagree on the time of day,
should Kalix realign them, and by what rule?

## Decision drivers

- Manifesto §2.2 — what you read is what runs. A realignment rule the model
  file does not show is hidden behaviour.
- Manifesto §2.3 — correctness first. Whether a 9am-to-9am rainfall day
  belongs with the midnight-to-midnight flow of the same date or the day
  before is a hydrological judgement the modeller must make.
- [ADR-0005](0005-node-definition-order.md) — the engine refuses what the
  file says rather than repairing it. Calibration already refuses observed
  data out of phase with the simulation (`Optimisation::align`).

## Considered options

1. **Reject out-of-phase inputs**, naming the inputs and their times of day.
2. **Treat date-only data as unspecified time** and align it to the
   timestamped data by calendar date.
3. **Warn and align by calendar date.**
4. **Snap to the nearest step** instead of flooring.
5. **A per-input offset option** in the model file.

## Decision

Chosen option: **1**. It assumes nothing the modeller has not written, and
leaves the pairing of misaligned data in their hands.

1. **Every input a model reads must be in step with the simulation: the same
   timestep and the same phase**, which for daily data is the time of day.
   Otherwise configure fails, naming the two inputs and their times of day.
   Kalix never shifts a value onto a different time to make it fit. The
   timesteps are still taken from the critical inputs; since they must
   agree, their order no longer changes the result.
2. **A specified `start` or `end` must fall on a step**, and Kalix writes a
   timestamp as a bare date only when it is midnight. A date-only `start` on
   9am data is rejected with the next step suggested
   (`use '2020-01-02T09:00:00'`), never snapped; a saved `start`, output
   file or export re-reads on the steps it was written from.

Options 2 and 3 hide a pairing rule in the engine, and 2 also needs date-only
versus timestamped tracked per input, including for in-memory and Pixie data
that have no text form to infer it from. Option 4 is still silent and flips
at a 12-hour offset. Option 5 is new syntax with no demand yet (Manifesto
§2.7, §2.8); it remains open as a later extension on top of option 1.

Consequence: modellers mixing 9am and midnight sources must reconcile their
files first. Accepted, because the files describe different 24-hour windows
and only the modeller knows which pairing is right.

## Worked example

```text
Error: Input 'data.inflow_9am_csv.by_name.flow' is out of step with 'data.inflow_midnight_csv.by_name.flow': 09:00:00 against 00:00:00. All inputs must share the same time of day.
```

```text
Error: Specified start '2020-01-02' is out of step with the input data, which is at 09:00:00; use '2020-01-02T09:00:00'.
```

## Enforcement

**Structural** — `Model::auto_determine_simulation_period` and the input fill
in `Model::configure` (`src/model.rs`) check every input and `start`/`end`
via `same_phase` (`src/timeseries.rs`) for the phase, and compare `step_size`
for the timestep; `needs_time_of_day` (`src/tid/utils.rs`) keeps the time of
day when writing. Tests: `src/tests/test_input_phase.rs`,
`test_save_keeps_time_of_day_on_start_and_end` in
`src/tests/test_model_io_ini.rs`, and the mixed-timestep tests in
`src/tests/test_input_validation.rs`.

## Links and references

- Related: [ADR-0005](0005-node-definition-order.md)
- Pull requests: [#475](https://github.com/chasegan/Kalix/pull/475), [#483](https://github.com/chasegan/Kalix/pull/483) (timestep check)

## Amendments

*None yet.*
