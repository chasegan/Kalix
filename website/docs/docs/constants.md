---
title: "[const]"
---

# [const]

The `[const]` section defines named fixed values, reusable from any
[dynamic expression](dynamic-expressions.md) in the model. A constant is a
single number given a name, so a value that appears in several places — a
threshold, a factor, a water-year month — is written once and referred to
everywhere else.

```ini
[const]
const.low_flow_threshold = 120
const.demand_factor = 1.15
const.wy_month = 7
```

The name *includes* the `const.` prefix, and that is exactly how you reference
it — the key on the left of the `=` and the reference in an expression are the
same string:

```ini
[node.pump]
type = unregulated_user
flow_threshold = const.low_flow_threshold
demand = const.demand_factor * data.raw_demand
```

Constants are resolved at load, wherever they sit in the file, so a `[const]`
section may appear before or after the nodes that use it.

## Optimisable

A constant is an [optimisable parameter](optimisable-parameters.md): give its
address `const.<name>` a search range in an optimisation, and the optimiser
tunes it like any node parameter. This makes `[const]` the natural home for
free values you want to calibrate — a demand multiplier, a routing coefficient,
a rule threshold.

## Rules

- Each value must be a **plain number** — not an expression. Use a
  [`[var.*]`](vars.md) block for a computed value.
- Names are bare lowercase identifiers (after the `const.` prefix).
- Constants are fixed for the whole run; they never vary with time.
