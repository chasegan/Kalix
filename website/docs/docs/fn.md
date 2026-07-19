---
title: "[fn]"
---

# [fn]

User-defined functions let you write a formula or rule once and call it from
any [dynamic expression](dynamic-expressions.md). Definitions live in a single
`[fn]` section — the signature is the key, so a function's name is written
exactly once — and calls use the `fn.*` namespace:

```ini
[fn]
storage_frac(v, cap) = v / cap
new_wy() = sim.new_month && sim.month == 7
net_demand(pop, doy) = {
    base = pop * const.per_capita;
    peak = 1 + 0.3 * sin(2 * 3.14159 * doy / 365);
    base * peak
    }
```

```ini
[node.township]
type = regulated_user
order = fn.net_demand(data.town.by_name.population, sim.day_of_year)
```

Like tables, functions are passive: the `[fn]` section can appear anywhere in
the model file, including after the nodes that call its functions.

## Signatures

- The key is the signature: `name(a, b)`, or `name()` for a function of no
  arguments. Calls bind positionally — the first argument is `a`, the second
  is `b`.
- Signatures are fixed. There are no default arguments and no overloads: two
  definitions with the same name are a load error even if their argument
  counts differ.
- Names and parameters are strictly lowercase (a lowercase letter, then
  lowercase letters, digits, or underscores), and may not take the name of
  any inbuilt function, temporal function, or reserved word. Calls, like all
  references, match case-insensitively.

## Bodies

A body is a plain expression or a `{ ... }`
[program block](dynamic-expressions.md#program-blocks), and may reference
`data.*`, `node.*`, `const.*`, `sim.*`, `table.*`, other `fn.*` functions — and
`this.`.

**`this.` rebinds to the calling node.** That turns a function into a rule
template: define a standard loss rule or licence check once, use it at fifty
nodes, and at each one `this.` reads that node's own outputs.

```ini
[fn]
prev_own() = this.inflow[-1, 0.0]
```

Functions may call other functions, but not themselves — recursion, direct or
mutual, is rejected when the model loads, with the cycle named.

## Cost and behaviour

Calls cost nothing at runtime: every call site is expanded when the model
loads, with arguments evaluated exactly once and the body's locals kept
private, so a function used at fifty nodes runs exactly as fast as fifty
pasted copies — including short-circuiting. A call inside an untaken `if`
branch does not execute, so an `assert` in a function body acts as a
*precondition*: it fires only when the call actually runs. (A
[temporal function](dynamic-expressions.md#temporal-functions) inside a body
gets its own state per call site and still advances every step, so its value
never depends on branching history.)
