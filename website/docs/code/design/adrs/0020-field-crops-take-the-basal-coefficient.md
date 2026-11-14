---
title: "ADR-0020: Field crops take the basal coefficient only"
---

# ADR-0020: Field crops take the basal coefficient only

- **Status**: Proposed
- **Date**: 2026-11-14
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: engine, hydrology, io

## Context and problem statement

FAO-56 offers two ways to turn reference evapotranspiration into a crop's: a
single coefficient Kc, with the soil surface's evaporation averaged in, and the
dual coefficient Kcb + Ke, with the surface tracked day by day. The field node
grew up with `kc` and gained `kcb` later (issue #511). Two coefficient models
in one node meant two fallows, an `interception` deduction that stood in for
surface evaporation under `kc`, a curve number that worked for one kind of
crop and not the other, and an "or, with `kcb`" clause in every docs section.
The question was whether to keep both.

## Decision drivers

- Manifesto §2.8 — less is more: one soil, one runoff law, one fallow.
- Manifesto §2.3 — proven hydrology: FAO-56 recommends the dual coefficient
  for daily soil-water balances, which is what the node runs; pyfao56 is dual
  only; the Kingsthorpe lysimeters showed the single coefficient misses the
  wet-surface days.
- Manifesto §2.2 — what you read is what runs: no conversion hidden in the
  parser.

## Considered options

1. **`kcb` only**, with `tew` and `rew` compulsory and `interception`
   defaulting to 0.
2. **Keep both**, as before.
3. **Keep `kc` as a legacy mode** behind the crop's declaration.
4. **Accept `kc` and convert it in the parser** (Kcb = Kc − 0.05 and so on).

## Decision

Option 1. A `[crop.*]` section declares `kcb`; a `kc` line is refused at load
with the conversion rule in the message. Every field carries its evaporation
layer. `interception` stays as Source Irrigator's optional deduction, default
0, because the layer evaporates small rains itself.

The conversion is documented, not coded (option 4 rejected): FAO-56 states it,
it is three edits per crop, and a rule inside the parser would be the kind of
hidden adjustment §2.2 forbids. Option 3 is the drift §2.8 warns of. Option 2
was the state of affairs that prompted this record.

## Consequences

- Published Kc tables (FAO-56 Table 12, WATERpak, the NSW river-system
  models) convert by the rule in the field docs, "Coming from a single
  coefficient": 0.15 for the initial stage, 0.05 off the mid-season value,
  0.05 to 0.10 off the end.
- Models written before 0.4.10 need `kcb` on each crop and `tew` and `rew` on
  each field; the load error says so.
- The Lower Balonne comparison against IQQM Crop Model 2, which used `kc` with
  a calendar multiplier, is complete and is not repeated.

## Enforcement

The parser and the IDE linter refuse `kc`; the engine refuses a field without
`tew` and `rew`. Tests in `test_crop_sections.rs` and `test_field_crops.rs`
pin both messages.
