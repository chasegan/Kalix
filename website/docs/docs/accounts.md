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

An account holds a name, a `size`, and a running balance — nothing else.
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

Columns are addressed by the header, not by position, so `name, size, initial`
and `name, initial, size` are equivalent. An **unknown column name is a load
error** — a typo like `intial` fails loudly rather than silently leaving every
account at zero. `accounts` is the only property an `[acc.*]` section may
contain; anything that *does* something belongs in a [`[ras.*]`](ras.md)
section.

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

## Recordable series

Account state is published as ordinary series — readable in any
[dynamic expression](dynamic-expressions.md) and recordable in
[`[outputs]`](model-outputs.md):

| Series | Meaning |
| --- | --- |
| `acc.<name>.opening_balance` | Balance after the RAS step, before any take — a stable snapshot every expression reader sees regardless of node order. |
| `acc.<name>.closing_balance` | Balance at the end of the step. |
| `acc.<name>.debits` | Water taken by users this step (not policy changes). |
| `acc.<name>.allocation` | Allocation to date: balance plus use since the last reset (see [Allocation systems](allocation-systems.md)). |
| `acc.<name>.size` | Account size. |

<a id="groups"></a>Every field except `size` is also published for the **group
aggregate**, summed over its members: `acc.<group>.closing_balance`,
`acc.<group>.allocation`, and so on.

`opening_balance` is written before ordering and flow, so it reads cleanly
mid-step. The others are written at end of step; reading them earlier in the
same step needs the previous-step offset, e.g. `acc.smith.closing_balance[-1, 0]`.

## Rules

- Account and group names are bare lowercase identifiers and share one flat
  namespace — every name, account or group, must be unique.
- A user drawing its balance down does **not** reduce its `allocation`; the
  water moves from the balance into the use tally.
- Accounts carry no behaviour. To reset, refill, limit, or announce, use a
  [`[ras.*]`](ras.md) section.
