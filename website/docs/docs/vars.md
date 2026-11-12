---
title: "[var.*]"
---

# [var.*]

Var blocks hold named calculations that are computed once per timestep and
published as series — readable from any
[dynamic expression](dynamic-expressions.md), recordable in
[`[outputs]`](model-outputs.md), and addressable with the offset syntax. They
replace the old workaround of parking calculations on a dummy gauge's
`reference_flow`.

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

## Position is meaning

Unlike tables and functions, var blocks are **active**: they execute at their
position in the model file, interleaved with the nodes — by default during
the flow phase (see `phase` below for the others). The file reads
downstream, calculations included — a var block placed after
`node.headwater` reads that node's values *for this timestep*; reading a
value computed *below* the block's position is caught at run start, exactly
like an out-of-order node reference. Use `[-1, default]` to read the previous
step's value of anything, including another var:

```ini
prev_headroom = var.accounting.headroom[-1, 0.0]
```

Keys evaluate top to bottom within the block, so later keys can use this
step's earlier keys. Each value is a full expression — blocks, `assert`,
temporal functions, and `fn.*` calls all work.

## Self-reference — `this`

Inside a definition, `this` is the var's own series — the counter, ratchet,
and held-assessment idioms without spelling the full name, so a rename never
has to edit the definition's internal references:

```ini
[var.state]
wy_count = this[-1, 0] + fn.is_startwy()               # a counter
assessment = if(sim.new_month, fn.assess(), this[-1, 0]) # a monthly hold
```

`this` always needs an offset (`this[-1, default]`) — a var can never read
its own not-yet-written value — and takes no field (`this.x` is an error).
It refers to the *enclosing definition* only: `this` inside an `[fn]` body
names the function, never the var that calls it. Saved files keep `this` as
written.

## Rules

- Block names and keys are bare lowercase names (no dots).
- Every var is computed exactly once per timestep, so all readers observe
  one value.
- Forward offsets (`[+1, ...]`) are rejected — a computed series has no
  future values.
- The optional `phase` key selects when the block runs. `phase = flow` (the
  default) runs at file position among the nodes. `phase = ras` runs in the
  **ras slot** at the top of the step, interleaved with the `[ras.*]`
  sections **in file order** — an assessment above a policy section is read
  by it bare, today's value, no `[-1, 0]` needed: a daily-ratcheted
  allocation assessment becomes `action = allocate(var.assessment.aa)`.
  What you read is what runs, within the slot exactly as among the nodes
  (a section above an assessment cannot see it — the usual loud error).
  Ras-phase blocks must appear before the first node section (a load error
  otherwise), so the file reads exactly as the timestep runs: assessments
  and policy, then the network. They may read each other bare (earlier
  blocks and keys), `ras.<name>.fired`, `.pct`, `.amount` and `.undistributed` of sections above, plus
  `acc.<x>.size` and anything at `[-1, 0]`; reading same-step flow or
  ordering output is the usual loud error.
- `phase = order` runs the block at its file position in the **order
  phase**, which walks the file bottom-up, the way orders travel upstream.
  Blocks run in reverse file order; keys inside a block still run top to
  bottom. A block reads bare the order-phase results of nodes *below* it
  (for example `node.user.order`, `node.ctrl.ds_1_order`), and nodes
  *above* it read the block bare in their order-phase inputs (a storage's
  `target_level`, an order control's `set_order`). The whole order phase
  runs before the flow phase, so flow-phase expressions anywhere read order
  vars bare too. Reading a node above the block, or this step's flow
  results, is the usual loud error; use `[-1, default]` for those. Order
  blocks run even in a model with no regulated zone.

    Not every output of a node is written in its order phase. An order
    control records only `ds_1_order` in its order phase, and the rest
    (`order`, `set_order`, `min_order`, `max_order`, `order_due` among them)
    in its flow phase, so an order var reads those at `[-1, default]`. A node below the last node that can place an order, or
    outside every regulated zone, is not visited in the order phase at all,
    so its order outputs (a regulated user's `order`, say) are never
    written: an order var reading one bare is the usual loud error, and at
    `[-1, default]` it reads the default every step.

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
