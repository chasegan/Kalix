---
title: "[var.*]"
---

# [var.*]

Var blocks hold named calculations that are computed once per timestep and
published as series — readable from any
[dynamic expression](dynamic-expressions.md), recordable in
[`[outputs]`](model-outputs.md), and addressable with the offset syntax.

```ini
[var.accounting]
inflow_wy = sum_since(node.headwater.ds_1, fn.new_wy())
dry_spell = steps_since(node.headwater.ds_1 > const.low_flow_threshold)
headroom = {
    cap = const.annual_cap;
    assert(cap > 0);
    cap - var.accounting.inflow_wy
    }
```

```ini
[outputs]
var.accounting.inflow_wy
var.accounting.headroom
```

The section name is the namespace: key `headroom` in `[var.accounting]` is
the series `var.accounting.headroom`, everywhere.

!!! note "`phase` is reserved"
    In a var block, the reserved key `phase` sets when the block runs (see
    [Phase](#phase)). It may not be used to define a variable.

## Position is meaning

Unlike tables and functions, var blocks are **active**. They execute at their
position in the model file, within their phase. By default that is the flow
phase, interleaved with the nodes (see [`phase`](#phase) below for the
others).

The file reads downstream, calculations included. A var block placed after
`node.headwater` reads that node's values *for this timestep*. Reading a
value computed later in the step (below the block, in the flow and ras
phases) is caught on the first timestep, by the same check as an
out-of-order node reference. Use `[-1, default]` to read the previous
step's value of anything, including another var:

```ini
prev_headroom = var.accounting.headroom[-1, 0.0]
```

Keys evaluate top to bottom within the block, so later keys can use this
step's earlier keys. Each value is a full expression — blocks, `assert`,
temporal functions, and `fn.*` calls all work.

## Self-reference — `this`

Inside a definition, `this` is the var's own series and enables the following idioms:

- a **counter** adds to its previous value;
- a **ratchet** moves only one way, such as a running maximum;
- a **held** value is recomputed on some steps and keeps its previous value
  on the others, such as a monthly assessment (see below).

`this` saves spelling the var's full name, so a rename never has to edit the
definition's internal references:

```ini
[var.state]
wy_count = this[-1, 0] + fn.is_startwy()                  # a counter
peak_flow = max(this[-1, 0], node.headwater.ds_1)         # a ratchet
assessment = if(sim.new_month, fn.assess(), this[-1, 0])  # a monthly hold
```

A held value is idiomatically the stateful function
[`latch(x, condition, init)`](dynamic-expressions.md#common-functions),
which samples `x` when `condition` holds and keeps the last sample
otherwise; `init` must be a literal. Where the hold is the var's whole
definition, `latch(x, condition, init)` and
`if(condition, x, this[-1, init])` give identical results:

```ini
assessment = latch(fn.assess(), sim.new_month, 0)         # the same monthly hold
```

`this` always needs an offset (`this[-1, default]`) — a var can never read
its own not-yet-written value — and takes no field (`this.x` is an error).
It refers to the *enclosing definition* only: `this` inside an `[fn]` body
names the function, never the var that calls it. Saved files keep `this` as
written.

## Rules

- Block names and keys are bare lowercase names (no dots).
- Every var is computed once per timestep, so all readers observe one
  value.
- Forward offsets (`[+1, ...]`) are rejected — a computed series has no
  future values.
- The optional `phase` key selects when the block runs: `ras`, `order` or
  `flow` (the default). See [Phase](#phase).

## Phase

A block runs in one of three phases. Each timestep runs them in this order:

- `phase = ras` runs in the **ras slot** at the top of the step,
  interleaved with the `[ras.*]` sections in file order.
- `phase = order` runs in the **order phase**, after the ras slot and before
  the flow phase. Blocks run in reverse file order, bottom to top; keys
  within a block still run top to bottom.
- `phase = flow` (the default) runs at file position among the nodes in the
  flow phase.

See [`[ras.*]`](ras.md#anatomy) and [Ordering](ordering.md#the-ordering-system)
for where the ras slot and the order phase sit in the step.

### The ras phase

A ras-phase block runs in the ras slot, interleaved with the `[ras.*]`
sections in file order. A policy section below an assessment reads it bare,
as today's value, with no `[-1, 0]` needed. A daily-ratcheted allocation
assessment therefore becomes `action = allocate(var.assessment.aa)`. What
you read is what runs, within the slot as among the nodes. A section above
an assessment cannot see it, which is the usual loud error.

Ras-phase blocks must appear before the first node section (a load error
otherwise), so the file reads in the order the timestep runs: assessments
and policy, then the network. They may read bare earlier blocks and keys,
`ras.<name>.fired`, `.pct`, `.amount` and `.undistributed` of sections
above, and `acc.<x>.size`, plus anything at `[-1, 0]`. Reading this step's
flow or order output is the usual loud error.

### The order phase

An order-phase block runs at its file position in the order phase, which
walks the file bottom-up, the way orders travel upstream. A block reads bare
the order-phase results of nodes *below* it (for example `node.user.order`,
`node.ctrl.ds_1_order`), and nodes *above* it read the block bare in their
order-phase inputs (a storage's `target_level`, an order control's
`set_order`). The ras slot runs before the order phase, so an order-phase
block reads ras-phase vars bare too. The whole order phase runs before the
flow phase, so flow-phase expressions anywhere read order-phase vars bare.
Reading a node above the block, or this step's flow results, is the usual
loud error; use `[-1, default]` for those. Order-phase blocks run even in a
model with no regulated zone.

Not every output of a node is written in its order phase. An order control
records only `ds_1_order` in its order phase, and the rest (`order`,
`set_order`, `min_order`, `max_order`, `order_due` among them) in its flow
phase, so an order-phase var reads those at `[-1, default]`. A node below
the last node that can place an order, or outside every regulated zone, is
not visited in the order phase at all, so its order outputs (a regulated
user's `order`, say) are never written. An order-phase var reading one bare
is the usual loud error, and at `[-1, default]` it reads the default every
step.

```ini
[node.dam]
...
ds_1 = top_up

[node.top_up]
type = order_control
set_order = var.top_up.order
ds_1 = irrigator
...

[var.top_up]
phase = order
order = node.irrigator.order * 1.1   # this step's order, read bare

[node.irrigator]
type = regulated_user
...
```
