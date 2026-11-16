---
title: "ADR-0023: An input that asks a node for negative water adds none, and is recorded as given"
---

# ADR-0023: An input that asks a node for negative water adds none, and is recorded as given

- **Status**: Proposed
- **Date**: 2026-11-16
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: engine, io

## Context and problem statement

A link carries only positive water. Four inputs could still ask a node for
negative water: an inflow node's `inflow` and `expected_inflow`, a
confluence's `expected_inflow`, and a gauge's `force_flow`. A negative `inflow` was added to the flow passing
through, so it took water from the river, and at the head of a river it put
negative water at the outlet, which the link dropped without a word. A
negative `force_flow` did the same.

Such values are common and mostly not meant. An inflow written as the
difference of two series is negative by rounding whenever the two are equal.
In `34_storage_sid_leslie` the forcing flux fed to an inflow node is
negative on 15,869 of 49,854 steps, by less than 8e-9 ML.

The questions were what a node does with a negative value, and whether the
engine should also check for negative water at run time.

## Decision drivers

- Manifesto §2.2 — nothing is hidden from the modeller.
- Manifesto §2.3 — a node that puts negative water at an outlet is a wrong
  answer, and a problem must be traceable.
- Manifesto §2.4 and [ADR-0004](0004-performance-on-the-hot-path.md) — a
  check in the flow phase is paid for by every model.
- Manifesto §2.5 — a modeller should be able to write `inflow = x - y`.

## Considered options

1. **Leave it**: a negative inflow takes water from the river, and a link
   drops what is negative at an outlet.
2. **Refuse**: stop the run on a negative value of these inputs.
3. **Take a negative value as none, and record what was used.**
4. **Take a negative value as none, and record the value as given.**
5. **Check every outlet at run time** and stop the run on negative water.

## Decision

Chosen option: **4**. The node cannot produce negative water, the modeller
need not guard every expression against rounding, and the results still show
what the expression gave.

1. **No node puts negative water at an outlet.** Each node is written so that
   it cannot. There is no check at run time.
2. **A negative `inflow` adds no water.** An inflow node adds water and never
   takes it. Water is taken from a river by a loss node or a user.
3. **A negative `expected_inflow` is no expected inflow**, on an inflow node
   and on a confluence. The node does not order water upstream for water it
   will not take.
4. **A negative `force_flow` sets the gauge's downstream flow to zero.**
5. **The result of the same name records the value as it was given.** A
   negative `inflow`, `expected_inflow` or `force_flow` shows in the results,
   beside a downstream flow that shows what the node did.
6. **A value that is not a number is left as it is.** No link carries it, and
   a `force_flow` that is not a number leaves the flow unforced, as before.
7. **Rejected: refusing a negative value** (option 2). It would stop a model
   on a rounding negative, and every such expression would need `max(x, 0)`
   around it. This is not the repair that
   [ADR-0005](0005-node-definition-order.md) and
   [ADR-0012](0012-inputs-in-step-with-the-simulation.md) refuse. The value
   in the file is the value in the results, beside a downstream flow that
   shows what the node did with it, so nothing is hidden.
8. **Rejected: a check at every outlet at run time** (option 5). It was built
   and measured. It cost 1.1% to 2.2% on speed tests 4 and 10 in three
   sessions, and across the 40 regression and 11 speed models it found one
   thing, the inflow above. It is not to be re-proposed without new
   information.

### Consequences

- ✅ A modeller can see a negative input in the results, and need not guard
  against one.
- ✅ No cost in the step: −0.5% to +0.5% against main on speed tests 1, 2, 4,
  5 and 10, which is noise. Minimum of 30 interleaved runs, one machine
  (Apple M5).
- ❌ A negative inflow no longer takes water from the river. With 100
  arriving and an inflow of −30, the node passed on 70 and now passes on 100.
  A model that used an inflow that way changes.
- ❌ On a step with a negative inflow, the `inflow` result and the node's
  mass balance differ: the balance counts the water added.
- ❌ §1 is held by how each node is written. Not every node type has been
  examined for it.

## Enforcement

- **Held by test** — §2 to §6: `src/tests/test_negative_flow.rs`, and for
  the confluence
  `test_a_negative_expected_inflow_orders_no_extra_water` in
  `src/tests/test_confluence_order_routing.rs`. No test covers an
  `expected_inflow` that is not a number.
- **Advisory** — §1, held by review. If a node did put negative water at a
  linked outlet, the link would drop it and the mass balance report's `TOTAL`
  would not be zero ([ADR-0022](0022-mass-balance-is-what-the-links-carry.md)
  §6).

## Links and references

- Related: [ADR-0022](0022-mass-balance-is-what-the-links-carry.md),
  [ADR-0004](0004-performance-on-the-hot-path.md)
- Docs: `website/docs/docs/inflow.md`, `website/docs/docs/gauge.md`

## Amendments

*None yet.*
