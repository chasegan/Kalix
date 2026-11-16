---
title: "ADR-0022: A node's mass balance is what its links carry"
---

# ADR-0022: A node's mass balance is what its links carry

- **Status**: Proposed
- **Date**: 2026-11-16
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: engine, io, cli, python

## Context and problem statement

The mass balance report gives one number per node, and the regression suite
pins it for every model. Each node kept that number itself, as the water it
put at its outlets less the water it received. Whether a link took the water
away did not matter. A splitter read 0 with its effluent going nowhere, and a
gauge at the end of the system read 0 with the river leaving through it.

Issue [#519](https://github.com/chasegan/Kalix/issues/519) wants the user
nodes to present their extractions at `ds_2`. A user would then always read
0, and the report would stop showing what users take.

The questions were what a node's mass balance should mean, and how the engine
should work it out.

## Decision drivers

- Manifesto §2.2 — transparency. Water that leaves the model should show at
  the node where it leaves.
- Manifesto §2.3 — the mass balance is a correctness tool. It should not
  carry noise that hides a real change, and it should check what it can.
- Manifesto §2.4 and [ADR-0004](0004-performance-on-the-hot-path.md) — the
  bookkeeping runs every step, so every option was measured.

## Considered options

1. **Keep the node's own account**: what it put at its outlets less what it
   received.
2. **Keep a running total on each link**, and work each node's balance out
   from its links when the report is written.
3. **Measure on the node every step**: subtract what arrives and add what a
   link takes.
4. **The node's own account, less the water left at its outlets that have no
   link.**

## Decision

Chosen option: **4**. It gives the new meaning, keeps the exact values the
nodes already report, and costs nothing a model without unlinked outlets can
measure.

1. **A node's mass balance is the water its outgoing links carried away less
   the water its incoming links brought, over the run.** The report gives it
   as a mean per timestep. Water at an outlet with no link has left the model
   at that node. A gauge with no downstream link reads minus its inflow, and
   a catchment with no downstream link reads zero.
2. **It is worked out as the node's own figure less its outfalls.** Each node
   keeps `internal_mbal`: the water it put at all its outlets less the water
   it received. An outfall is an outlet that can hold water and has no link.
   The model lists the outfalls at setup, takes what is at each one once per
   step, and keeps a total. The report takes a node's outfall totals off its
   own figure. The two forms are equal, and this one subtracts nothing for a
   node whose outlets are all linked.
3. **A storage's own figure is outflow less inflow, whether or not its level
   is forced.** The forced-level adjustment is not a term of its own. It
   shows through the figure as a change in volume does.
4. **Each node type says which of its outlets can hold water without a link**,
   per outlet, in one exhaustive match with no wildcard arm
   (`NodeEnum::can_strand_water`), in the manner of
   [ADR-0008](0008-ordering-knowledge-in-exhaustive-matches.md) §2 and §3.
5. **Measurement is skipped only where the answer follows from the links
   alone.** A node with no link in or out left everything it emitted, so what
   it left is its own figure, and its outlets are not swept. An outfall's
   total is never taken from another node's figure.
6. **`TOTAL` is a check.** It is the sum of what the nodes say they emitted
   less what left at outfalls. It is zero, apart from rounding, when every
   node put at its outlets what its own figure says.
7. **Rejected: a balance measured from link flows** (options 2 and 3). The
   exact values the nodes report are declared, not measured: a user subtracts
   its diversion, and a confluence or splitter does no arithmetic. The
   engine's own arithmetic gains or loses a rounding unit of water at those
   nodes each step, and a measurement sees it. It is not to be re-proposed
   without new information.

### Consequences

- ✅ Water with nowhere to go is located. When the meaning changed, seventeen
  of the forty regression models had it, at thirty nodes, where before it
  showed only in `TOTAL`.
- ✅ A node with every outlet linked reads what it read before, to the last
  digit. Of the 490 node values then in the regression suite, 459 did not
  change as text.
- ✅ No measurable cost without outfalls: −1.0% to +0.4% on speed tests 2, 4,
  5, 7, 8 and 10 against the commit before. An outfall costs about 1 ns a
  step. A model with 100 of them measured +0.6%. Minimum of 30 interleaved
  runs, one machine (Apple M5).
- ✅ The blackhole is a modelling choice and no longer a bookkeeping need.
- ❌ A node with both an outfall and a linked outlet reads a difference of two
  run totals, and can carry noise in its last digits.
- ❌ A catchment with no downstream link reads zero whatever its runoff, so a
  report says nothing about it. Seven regression models were catchments
  alone and were given a gauge.
- ❌ A negative volume at a linked outlet is counted by the node and carried
  by no link, since a transfer moves only positive water. `TOTAL` is then not
  zero. Links are not meant to carry negative flow.
- ❌ A report saved by 0.4.10 or earlier can differ at nodes with an unlinked
  outlet, and at storages with a forced level, with no change in the
  simulation.

## Pros and cons of the options

The percentages are simulation time against `main`, by minimum over 20 to 30
interleaved runs on an Apple M5, for speed tests 2 and 5. Those two have the
cheapest nodes and the most links.

### Option 1: The node's own account

- ✅ No change.
- ❌ Blind to water that goes nowhere, and to a user's take once it is
  presented at an outlet.

### Option 2: A running total on each link

- ✅ One mechanism, and nodes take no part.
- ❌ A balance is the difference of two whole-run totals. A user that read
  `-20` read `-20.000000000000128`, and a splitter that read `0` read about
  1.4e-12.
- ❌ `TOTAL` is zero by construction, so it checks nothing.

### Option 3: Measured on the node every step

- ❌ +14.0% and +7.3% with both sides counted as the water moves. +0.9% and
  +4.7% with the inflow side counted in the flow phase. Confluences,
  splitters, gauges and order controls did no balance arithmetic before, and
  here they start to.
- ❌ Still not exact at a splitter or a confluence, for the reason in §7.

### Option 4: The node's own account, less its outfalls

- ✅ Exact wherever every outlet is linked.
- ✅ `TOTAL` checks the nodes' own figures against what left.
- ❌ Two parts: the node's figure and the model's outfalls. Accepted: the
  first is what the node knows, and the second is what only the model knows.

## Enforcement

- **Structural** — §4: the match is exhaustive, so a new node type does not
  compile until it has answered.
- **Held by test** — §1, §2 and §5: `src/tests/test_mass_balance_outfalls.rs`,
  and the field's return flow in `src/tests/test_field_soil.rs`. The storage
  and field tests fail if their arms of the match are narrowed. §3:
  `regression_tests/simulations/33_storage_forced_level`.
- **Held by the regression suite** — §6: every model's `mbal.txt` pins its
  `TOTAL` at zero to rounding.
- **Advisory** — §5's "never taken from another node's figure" and §7 are
  held by review. Nothing stops a wildcard arm returning to the match in §4.

## Links and references

- Issue: [#519](https://github.com/chasegan/Kalix/issues/519)
- Related: [ADR-0004](0004-performance-on-the-hot-path.md),
  [ADR-0008](0008-ordering-knowledge-in-exhaustive-matches.md)
- Docs: the `-m` option in `website/docs/docs/cli.md`

## Amendments

- *2026-11-16* — The consequence about a negative volume at a linked outlet
  has fewer ways to arise. An inflow node and a gauge no longer put negative
  water at an outlet when their input is negative, and no node is meant to.
  [ADR-0023](0023-negative-inputs-add-no-water.md) records that, and the
  run-time check that was measured and left out.
