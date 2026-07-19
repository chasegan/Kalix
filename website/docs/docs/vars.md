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
position in the model file, during the flow phase, interleaved with the
nodes. The file reads downstream, calculations included — a var block placed
after `node.headwater` reads that node's values *for this timestep*; reading
a value computed *below* the block's position is caught at run start, exactly
like an out-of-order node reference. Use `[-1, default]` to read the previous
step's value of anything, including another var:

```ini
prev_headroom = var.accounting.headroom[-1, 0.0]
```

Keys evaluate top to bottom within the block, so later keys can use this
step's earlier keys. Each value is a full expression — blocks, `assert`,
temporal functions, and `fn.*` calls all work.

## Rules

- Block names and keys are bare lowercase names (no dots).
- Every var is computed exactly once per timestep, so all readers observe
  one value.
- Forward offsets (`[+1, ...]`) are rejected — a computed series has no
  future values.
- The optional `phase` key selects when the block runs. `phase = flow` (the
  default) is supported; `phase = order` is designed but not yet implemented
  and is rejected at load.
