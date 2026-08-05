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
needs the previous-step offset: `node.dam.spill[-1, 0] > 0`. The exception is
[`phase = ras` var blocks](vars.md) — they share the top-of-step slot with the
RAS sections, interleaved in file order, so an assessment written *above* a
section reads bare: assess daily in a var, credit monthly with
`allocate(var.assessment.aa)`.

## Actions <a name="actions"></a>

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

Every action writes only to its **targets** — "what can touch this account?"
is answered by the sections that name it. Rules that move value between
paired accounts are authored with [`self.pair`](#self), always writing the
target side.

### Per-account arguments — `self` <a name="self"></a>

Inside an action argument — and only there — the expression may read the
target account's own live state through `self`. The argument is then
evaluated **per target account**, so one section can apply a per-account rule
to a whole group:

```ini
[ras.co_limit]
targets = acc.entitlements
trigger = start_water_year(7)
action  = set(min(self.balance, table.co_limit(self.size)))

[ras.event_topup]
targets = acc.entitlements
trigger = node.gauge1.dsflow[-1, 0] > 500
action  = credit(clamp(80, 0, self.size - self.balance))   ; per-account headroom
```

| Field | Reads |
| --- | --- |
| `self.balance` | The account's live balance at this point in the RAS sequence. |
| `self.size` | The account's size. |
| `self.allocation` | The account's [allocation](accounts.md) (balance + use since the last reset). |
| `self.pair.balance` / `.size` / `.allocation` | The same three fields of the account's [`pair`](accounts.md#pair), from either end of the pairing. Requires every target account to be paired. |

Several verbs are `self` sugar — `set_full` is `set(self.size)`,
`credit_fraction(x)` is `credit(x * self.size)`, `reduce_to(x)` is
`set(min(self.balance, x))` — the named verbs stay because they carry the
audit trail. The verb says *what* changes; the expression says *by how much*;
writes never happen inside an expression.

Rules: `self` is rejected everywhere else (node properties, triggers, `[var.*]`
definitions, `[fn]` bodies — write it directly in the action text); it has no
history, so offsets (`self.balance[-1, 0]`) are errors; and `allocate` never
takes it — an announcement is one percentage for the whole group. Without any
`self` reference, arguments keep their evaluate-once-per-firing semantics.

**The carryover recipe.** End-of-water-year carryover is three composable
sections in file order — grant the pools from their paired entitlements,
write off on the conditions the plan names, reset the entitlements:

```ini
[ras.co_grant]
targets = acc.pools                       ; pool size = the carryover cap
trigger = start_water_year(7)
action  = set(fn.grant() * 0.9 * self.pair.balance)   ; fn.grant(): 0 in a denial year

[ras.co_writeoff_spill]
targets = acc.pools
trigger = node.dam1.level[-1, 0] >= const.fsl
action  = set_empty

[ras.ent_reset]
targets = acc.entitlements
trigger = start_water_year(7)
action  = reset_allocation                ; after the grant, in file order
```

A zero grant *sets* the pool to zero — a write-off, not a skip — and `set()`
clamps at the pool's size, so the cap needs no extra clause.

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
