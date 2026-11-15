---
title: "[acc.*]"
---

# [acc.*]

An `[acc.*]` section declares a **group of accounts** — the ledgers that a
[resource allocation system](ras.md) credits and debits, and that water users
draw against. Accounts are declared as a headed table, one row per account, in
the same style as a [loss table](loss.md):

```ini
[acc.entitlements]
accounts = name,     size,
           smith,    150,
           jones,    420,
           town_hp,  90,
```

The section name is the group; each row is one account. `acc.entitlements` is
an [account group](#groups) you can target as a whole; `smith` is an account
you can reference and record individually.

## Accounts are pure state

An account holds a name, a `size`, a running balance and a
[debit factor](#debit-factor). It holds nothing else.
It has **no behaviour and no calendar**: a row never resets, never refills, and
never schedules anything. Every change to a balance is either a [`[ras.*]`
action](ras.md) or a water user's take. A group that no RAS targets is
perfectly valid — it is simply a ledger that only users touch.

This split is deliberate: accounts are the *nouns*, resource allocation systems
are the *verbs*. See [Allocation systems](allocation-systems.md) for the why.

## The accounts table

| Column | Required | Meaning |
| --- | --- | --- |
| `name` | yes, first | Account name — a bare lowercase identifier, unique across every group. |
| `size` | yes | Account size [ML] — the entitlement volume that percentages are taken of. May be an expression. |
| `initial` | no (defaults to 0) | Opening balance [ML] at the start of the run, within `[0, size]`. |
| `pair` | no | <a name="pair"></a><a name="co_acc"></a>Paired account — a reference to an account declared elsewhere, not a declaration. The pairing is **symmetric**: declare it on either account's row, and `[ras.*]` action arguments read the other end as [`self.pair.<field>`](ras.md#self) from both sides. An account can be in at most one pair. Nothing else about a paired account is special — it is drawn on via a user's `accounts` list like any account. |
| `debit_factor` | no (defaults to 1) | What a user's take of 1 ML debits from the account. A finite number greater than 0. See [Debit factor](#debit-factor). |

Columns are addressed by the header, not by position, so `name, size, initial`
and `name, initial, size` are equivalent. An **unknown column name is a load
error** — a typo like `intial` fails loudly rather than silently leaving every
account at zero. `accounts` is the only property an `[acc.*]` section may
contain; anything that *does* something belongs in a [`[ras.*]`](ras.md)
section.

A carryover pairing in full — the pool is an ordinary account, first in the
user's order of use, and the grant is an authored rule targeting the pool
(see the [carryover recipe](ras.md#self)):

```ini
[acc.entitlements]
accounts = name,  size, initial, pair,
           smith, 1000, 0,       smith_co,

[acc.pools]
accounts = name,     size,
           smith_co, 250,       # size = the carryover cap

[ras.carryover]
targets = acc.pools
trigger = start_water_year(7)
action  = set(0.9 * self.pair.balance)   # pool = 0.9 x smith's remaining balance
```

## Debit factor

In some schemes an account's balance is set from the water available at the
source. Each megalitre a user diverts then costs the account more than a
megalitre, to cover the loss in delivering it. The `debit_factor` column says
how much more.

```ini
[acc.entitlements]
accounts = name,  size, debit_factor,
           smith, 150,  1.2,
           jones, 420,  1.05,
```

A user node that takes a volume from an account debits the account by the
take times the factor.

```
debit = debit_factor × take
```

A take is limited to what can be debited without the balance going below
zero. The take available from an account is its balance over the factor.

```
take available = balance / debit_factor
```

With a balance of 120 ML and a factor of 1.2, the take available from `smith`
is 100 ML. That take debits 120 ML and leaves the account empty.

The factor applies to everything a user node does with the account. An
[unregulated user](unregulated-user.md#accounts) and a
[regulated user](regulated-user.md#accounts) are both limited and debited this
way.

A [regulated user](regulated-user.md#accounts) also commits each order it
places to the account until the order falls due. The order available from an
account allows for what is already committed, and is never below zero.

```
order available = max(0, balance − committed) / debit_factor
```

The accepted order is committed as the order times the factor. So `committed`
stays in the units of the balance.

An account in `order_accounts` authorises an order up to the take available
from it. When the order is placed, the account is debited the part of the
order it authorises, times the factor.

In a list or a [combination](#combining-accounts) each account uses its own
factor. In `a | b` the take available is the sum of what is available from
each. In `a & b` it is the smaller of the two, and each side is debited the
take times its own factor.

The factor applies to nothing else. A [`[ras.*]`](ras.md) action works in the
units of the balance. `debit(5)` takes 5 ML from the balance, whatever the
factor is.

The `debits`, `use` and `committed` series are in the units of the balance.
They record what was debited and committed, not what was diverted. The
diversion is the user node's `diversion` result.

A factor of 1 is the same as no factor. A model whose factors are all 1 gives
the results it gives without the column.

The factor belongs to the account. Two users who draw on one account are
debited at the same factor.

## Referencing accounts from nodes

A water user references accounts it draws on with the `accounts` property — a
comma-separated list, **never a declaration**:

```ini
[node.smith_pump]
type = regulated_user
accounts = smith
order = 12
```

List order is order of use: `accounts = smith_carryover, smith_annual` draws
the first account down before touching the second. The available volume is the
sum, and debits cascade in order. See
[regulated_user](regulated-user.md) / [unregulated_user](unregulated-user.md).

## Combining accounts

Some licences need more than a list: a take that must be debited from **both**
of two accounts, and is limited by whichever is shorter. The `accounts` line
can say so with `|`, `&` and parentheses:

```ini
[node.smith_pump]
type = regulated_user
accounts = (smith_co | smith_annual) & valley_cap
```

Draw on the carryover, then the annual account, and debit every megalitre from
the valley cap as well.

| Form | Meaning | Available volume | Debit |
| --- | --- | --- | --- |
| `a \| b` | `a` first, then `b`, filling partially | sum | left to right |
| `a & b` | both, each in full | minimum | the whole take from each side |
| `a, b, c` | the plain list; the same as `a \| b \| c` | sum | left to right |

An `&` inside a `|` takes the least of what reaches it and what its sides can
cover, and the `|` carries the rest on: `a | (b & cap) | big` draws on `a`,
then as much from both `b` and `cap` as the shorter allows, then `big`.
Because `&` debits the whole take from every side, a group that holds both
sides of an `&` counts that take once per side in its `debits` and `use`
aggregates.

Rules:

- **Parentheses are required to mix `|` and `&`.** `a | b & c` is a load
  error; there is no precedence to remember.
- **A line is either a plain list or a combination.** A comma mixed with `|`,
  `&` or parentheses is a load error. Existing models are unchanged.
- **`&&` and `||` are a load error.** They are the likely typo, and they mean
  something else in [dynamic expressions](dynamic-expressions.md).
- **An account appears at most once** in a node's `accounts`.
- **No values.** The line names accounts and says how they combine. It holds
  no numbers, no functions and no expressions, and is resolved when the model
  loads.
- **A combination without `&` is the plain list.** `a | b` is `a, b`, and
  the canonical form writes it so. The canonical form has single spaces and
  parentheses only where the operator changes; a save that preserves
  formatting keeps the line as you wrote it.

Where the user is a [regulated user](regulated-user.md#accounts), its order
cap, the commitment of the accepted order and the debit of the delivery all
follow the combination. Where it is an
[unregulated user](unregulated-user.md#accounts), the availability cap and the
debit do. `order_accounts` stays a plain list.

### Why no expressions in the line

The account line says *who pays*; expressions say *how much*. Anything dynamic
is expressed as an account whose balance a [`[ras.*]`](ras.md) rule controls,
so the policy stays in the RAS sections where it can be read:

- **A gate.** `& oa_open`, an account set full while access is announced and
  empty otherwise:

    ```ini
    [acc.gates]
    accounts = name,    size,
               oa_open, 1e9,

    [ras.oa_opens]
    targets = acc.gates
    trigger = node.gauge1.dsflow[-1, 0] > 500
    action  = set_full

    [ras.oa_closes]
    targets = acc.gates
    trigger = node.gauge1.dsflow[-1, 0] <= 500
    action  = set_empty
    ```

- **A daily limit.** `& daily_limit`, refilled every step. A limit on what is
  taken at flow time: an unregulated user, or a regulated user's opportunistic
  take. A regulated user's *order* against it is committed until the order
  falls due, and a refill does not release the commitment, so with travel
  time the limit would also hold the user's orders to one every few days:

    ```ini
    [acc.limits]
    accounts = name,        size,
               daily_limit, 25,

    [ras.daily_limit]
    targets = acc.limits
    trigger = every_step
    action  = set_full
    ```

- **An annual or rolling cap.** `& cap`, run by `roll_cap` at the water-year
  boundary (see [`[ras.*]`](ras.md#actions)).

## Recordable series

Account state is published as ordinary series — readable in any
[dynamic expression](dynamic-expressions.md) and recordable in
[`[outputs]`](model-outputs.md):

| Series | Meaning |
| --- | --- |
| `acc.<name>.opening_balance` | Balance after the RAS step, before any take — a stable snapshot every expression reader sees regardless of node order. |
| `acc.<name>.closing_balance` | Balance at the end of the step. |
| `acc.<name>.debits` | What users' takes debited this step (not policy changes). With a [debit factor](#debit-factor) this is the take times the factor. |
| `acc.<name>.allocation` | Allocation to date: balance plus use since the last reset (see [Allocation systems](allocation-systems.md)). |
| `acc.<name>.allocation_pct` | Allocation as a percentage (nominally 0-100) of the account size. `NaN` for a zero-size account (and NaN counts as true in a [condition](dynamic-expressions.md), so a bare `acc.<name>.allocation_pct` trigger on such an account fires every step). The allocation percentage may be >100% — the allocations are not clamped to the account `size`. |
| `acc.<name>.use` | What users' takes have debited since the last `reset_allocation` — the use term of the allocation. Fed only by user takes, like `debits`. |
| `acc.<name>.committed` | Orders accepted by [regulated users](regulated-user.md#accounts) against this account and not yet due, as the step's ordering begins — their best guess of the diversions to come. A forecast, not a debit: the balance does not move until the water is diverted, and `opening_balance − committed` is the headroom the step's orders are capped by. Each order leaves the tally on the step after it falls due, delivered or not. Written before ordering, like `opening_balance`. |
| `acc.<name>.size` | Account size, as declared. |
| `acc.<name>.initial` | Opening balance at the start of the run, as declared (defaults to 0 if omitted). |
| `acc.<name>.debit_factor` | The [debit factor](#debit-factor), as declared (1 if omitted). Fixed for the run, like `size`. An account group has no `debit_factor`. |

<a id="groups"></a>`opening_balance`, `closing_balance`, `debits`, `allocation`,
`use` and `committed` are also published for the **group aggregate**, summed over its
members: `acc.<group>.use`, `acc.<group>.closing_balance`, and so on — so a
resource assessment can write `/ acc.gs.size` instead of a magic total that
silently goes stale when an entitlement changes.

`allocation_pct` is also made available as a group aggregate: the group
allocation over the group size. That is a mean of the members' own
`allocation_pct` weighted by account size, so it can differ from any one
member's value. Members drift apart when:

- they start at different percentages (`initial` as a proportion of `size`);
- a RAS action works in absolute amounts — `credit(20)` is a larger share of a
  small account than of a large one;
- an action sets the balance without regard to water already used —
  `set_full` after a take leaves that member above 100%;
- a balance-setting action such as `credit`, `set` or `scale` caps the balance at
  the account size, so one member stops rising at its cap while another keeps
  going (`allocate` and `set_full` are not capped).

Note that this is the allocation *held*, which can differ from the percentage
an `allocate` action announces (see [`ras.<name>.pct`](ras.md)) — `allocate`
never reduces an account's allocation, and other actions can increase it.

An account's `debit_factor` is fixed for the whole run, and reads the same
way as `size` does below.

`size` and `initial` are also group aggregates — the sum of member sizes and
opening balances — but like their per-account counterparts above, they're
fixed for the whole run rather than computed, so they read cleanly
*everywhere*, from the very first line of the model, with no write-timing
caveat and (like [`const.*`](constants.md)) no
[offset syntax](referencing-model-results.md#temporal-offset-for-node-outputs).
`opening_balance` and `committed` are written before ordering and flow, so
they read cleanly mid-step. The others are written at end of step; reading them earlier in the
same step needs the previous-step offset, e.g. `acc.smith.closing_balance[-1,
0]` — including `use`, whose `[-1, 0]` read on a reset morning is still the
*old* period's total (the reset fires later that same step).

## Rules

- Account and group names are bare lowercase identifiers and share one flat
  namespace — every name, account or group, must be unique.
- A user drawing its balance down does **not** reduce its `allocation`; the
  water moves from the balance into the use tally.
- Accounts carry no behaviour. To reset, refill, limit, or announce, use a
  [`[ras.*]`](ras.md) section.
