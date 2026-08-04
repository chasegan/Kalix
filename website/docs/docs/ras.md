---
title: "[ras.*]"
---

# [ras.*]

A `[ras.*]` section is a **resource allocation system**: a named rule that does
one thing, on one trigger, to the [accounts](accounts.md) of one or more groups.
Together the `[ras.*]` sections *are* the water sharing plan — every credit,
debit, reset, and announcement is authored here, in the model file, and nothing
else moves a balance except a water user's take.

```ini
[ras.annual_reset]
targets = acc.entitlements
trigger = start_water_year(7)
action  = reset_allocation

[ras.announce]
targets = acc.entitlements
trigger = start_water_year(7)
action  = allocate(table.alloc_curve(node.paradise_dam.volume[-1, 0]))
```

Each section is three properties — **to whom, when, and what**.

## Anatomy

| Property | Meaning |
| --- | --- |
| `targets` | One or more [account group](accounts.md) references (`acc.<group>`), comma-separated. The action applies to every account in them. |
| `trigger` | When the action fires (below). |
| `action` | What it does (below). |

RAS sections run **at the top of every timestep**, before the ordering and flow
phases — so a user's orders and takes see the day's announcements. When several
sections fire on the same day they run in **file order**, which is how priority
tiers and reset-then-announce sequences are expressed. A section may sit
anywhere in the file — above the nodes, below them, or beside the ones it
concerns; only the order of the `[ras.*]` sections relative to each other
matters.

## Triggers

A trigger is either a **calendar keyword** or a **[dynamic
expression](dynamic-expressions.md)**:

| Trigger | Fires |
| --- | --- |
| `every_step` | Every timestep. |
| `start_month` | First timestep of each month. |
| `start_year` | First timestep of each calendar year. |
| `start_water_year(m)` | First timestep of month `m` (1–12) each year. `m` may be a literal or a `const.*` reference. |
| *any expression* | Every timestep the expression is non-zero. |

Expression triggers are **level-semantic**: the action applies on *every* step
the condition holds, not once on the rising edge — "while the dam spills, forfeit
5%" is a daily debit for each day of the spill. Because a RAS runs before the
flow phase, reading a value computed later in the step (a node output, a var)
needs the previous-step offset: `node.dam.spill[-1, 0] > 0`.

## Actions

**Stencilled actions** apply to each target account independently. Arguments are
expressions, evaluated once per firing:

| Action | Effect |
| --- | --- |
| `set_full` | Balance → account size. |
| `set_empty` | Balance → 0. |
| `set(x)` | Balance → `x` (clamped to `[0, size]`). |
| `set_fraction(x)` | Balance → `x` × size. |
| `credit(x)` | Add `x`. |
| `credit_fraction(x)` | Add `x` × the account's own size (negative `x` debits). Lets one block serve accounts of different sizes. |
| `debit(x)` | Subtract `x`. |
| `roll_cap(n)` | Roll an n-period cap: bank the closing period's debits, and credit back the debits expiring out of the window (those from n periods ago). Fired at a water-year trigger this is a rolling cap over n consecutive water years (Source's "Moving Water Year" usage limit); `roll_cap(1)` behaves as an annual cap. |
| `scale(x)` | Multiply the balance by `x`. |
| `reduce_to(x)` | Lower the balance to `x` if it is above (a carryover limit). |
| `carryover(x)` | Set each target's paired carryover account (its [`co_acc`](accounts.md#co_acc) column) to `x` × the target's own balance, clamped to the pool's `[0, size]` — the pool size *is* the carryover cap. `x = 0` is a denial year: the pool is written off (set to zero), not left alone. The target's own balance is never touched; resetting it stays a separate, composable action. Every target account must declare a `co_acc` (a load error otherwise). |

**Announcement actions** implement announced allocation:

| Action | Effect |
| --- | --- |
| `allocate(pct)` | Raise each account's [allocation](accounts.md) to `pct`% of its size, never lowering it. `pct` is a percentage (0–100; above 100 is allowed). |
| `reset_allocation` | Start a new allocation period — balance and use-to-date both return to zero. |

See [Allocation systems](allocation-systems.md#how) for how `allocate` and
`reset_allocation` combine into a working announced-allocation scheme, including
priority tiers and resource assessment.

## Recordable series

| Series | Meaning |
| --- | --- |
| `ras.<name>.fired` | 1 on steps the trigger fired, else 0. |
| `ras.<name>.pct` | The percentage an `allocate` action last announced (carried forward between firings). |

Both are opt-in via [`[outputs]`](model-outputs.md), like any recorder.

## Rules

- Exactly one `trigger` and one `action` per section. Related steps are
  consecutive sections; there is no second ordering rule inside a section.
- `allocate` takes a single target group.
- Everything a RAS reads is an ordinary expression, so any resource assessment —
  storage volume, minimum inflows, a lookup curve, reserve balances — is authored,
  not built in.
