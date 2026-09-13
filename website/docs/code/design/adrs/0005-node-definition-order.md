---
title: "ADR-0005: Node definition order is execution order"
---

# ADR-0005: Node definition order is execution order

- **Status**: Accepted
- **Date**: 2026-07-03 (recorded 2026-09-14, retrospectively, from the
  `node-definition-order` manifesto)
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: engine, io

## Context and problem statement

A Kalix model file lists its nodes, and the engine steps them in the order
they are listed. A link from a node defined later to one defined earlier
would therefore route water to a node that has already run this timestep.
During the 2026-07 engine review the obvious remedy was on the table: a
topological sort at load time, so the modeller could write nodes in any
order and the engine would arrange them. Twenty lines, once.

The question is whether the engine should sort, or should refuse
out-of-order files and require the modeller (and every tool that writes a
model) to keep them ordered.

## Decision drivers

- Manifesto §2.2 — transparency by default: **what you read is what runs**.
  The file is the model; the engine executes it as written rather than
  silently rearranging it.
- Manifesto §3 — transparency is never traded for convenience; a tool that
  wants to rearrange a model does so in the file, visibly.
- Manifesto §2.3 — behaviour on failure never so lean that a problem becomes
  untraceable: an out-of-order file is a real fact about the modeller's
  understanding of their network, and should be surfaced.

## Considered options

1. **Topological sort at load time** — accept any order; the engine computes
   an execution order and runs that.
2. **Validate, never sort** — the engine checks that every link points down
   the file and refuses to run otherwise, naming the offending pair.

## Decision

Chosen option: **validate, never sort**.

### 1. Principle: what you read is what runs

A hidden sort would break transparency in a way no documentation could
repair — the file would say one order, the engine would run another, and
the modeller could no longer reason about their model by reading it.
Keeping definition order authoritative means:

- **The file reads downstream.** A reader follows the water: headwaters at
  the top, outlet at the bottom, the same journey the simulation takes. A
  Kalix model file is a legible description of a river system, not a bag of
  sections.
- **Diffs and reviews follow the physics.** Version control shows changes in
  network order; two modellers reviewing a file walk it the same way the
  engine does.
- **Debugging needs no mental machinery.** "What ran before this node?" is
  answered by scrolling up — never by simulating a sort algorithm in your
  head.

### 2. Rules

1. **Every link's upstream node is defined before its downstream node.** The
   engine checks this at initialisation and refuses to run otherwise, naming
   the offending pair: `Node 'X' must be defined before 'Y'`. It never
   reorders on the modeller's behalf.
2. **The engine contains no topological sort, and none may be added.** If a
   future need arises to arrange nodes automatically (e.g. an IDE
   convenience), it belongs in tooling that **rewrites the file, visibly** —
   never in engine memory, where the file and the execution would silently
   disagree.
3. **Independent branches run in file order.** Where the network alone does
   not force an order (parallel tributaries), the modeller's ordering is the
   execution order. This is a feature: a sorted engine would have to invent
   a tie-break rule, and execution order among branches would then depend
   on invisible machinery. In Kalix the modeller decides, by writing the
   file.
4. **Model-generating tools emit topological order.** Anything that writes a
   Kalix model (the IDE, scripts, `generate_models.py`-style generators)
   must emit nodes upstream-first, because the engine will not repair order
   for them.

### Consequences

- ✅ The file is a faithful account of execution; every downstream promise —
  reviewability, diff-ability, "read the file to understand the model" —
  holds.
- ✅ One visible rule (the file's order) instead of a hidden tie-break.
- ✅ An out-of-order file is a teaching moment: the modeller learns
  something true about their network the moment they run it.
- ❌ Modellers and tools must maintain order as networks grow. Accepted
  deliberately: it is the same price as keeping any text artifact
  organised, and the payoff is a file a stranger can read.

## Pros and cons of the options

### Option 1: Topological sort at load time

- ✅ Easy to write; modellers can write nodes in any order.
- ❌ Makes the file lie: the moment order stops mattering, the file stops
  being a faithful account of execution.
- ❌ Topological orders are not unique. A sorter must break ties, and any
  tie-break (definition order? alphabetical? link insertion order?) is a
  hidden rule the modeller must learn.
- ❌ A silent sort teaches nothing and hides a misunderstanding.

### Option 2: Validate, never sort

- ✅ The one rule stays visible: the file's order.
- ✅ Cold-path work — a check at initialisation, nothing in the loop
  ([ADR-0004](0004-performance-on-the-hot-path.md) §3.5).
- ❌ Modellers and generators carry the ordering burden.

## Worked example

Valid — the file reads downstream, and executes exactly as written:

```ini
[node.headwater]      ; runs 1st
type = inflow
ds_1 = weir

[node.weir]           ; runs 2nd
type = storage
ds_1 = outlet

[node.outlet]         ; runs 3rd
type = gauge
```

Invalid — `weir` links downstream to a node defined above it. The engine
refuses with `Node 'weir' must be defined before 'outlet'` rather than
quietly reordering:

```ini
[node.outlet]         ; defined 1st, but receives weir's water
type = gauge

[node.weir]
type = storage
ds_1 = outlet         ; error: links may only point down the file
```

## Enforcement

**Structural** — `Model::check_execution_order` (`src/model.rs`) refuses
out-of-order files at initialisation, and no sorting code exists in the
engine. Rule 4 (tool obligations) is **Advisory**, held by review.

## Links and references

- Related: [ADR-0004](0004-performance-on-the-hot-path.md).
- Origin: engine review step 15, 2026-07.

## Amendments

*None.*
