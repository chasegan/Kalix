---
title: "ADR-0019: distribute shares one signed volume across a group by weight, capped and redistributed"
---

# ADR-0019: `distribute` shares one signed volume across a group by weight, capped and redistributed

- **Status**: Proposed
- **Date**: 2026-11-08
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: engine, language, io

## Context and problem statement

Two needs the stencilled actions could not meet: credit new resource to a
group's accounts in proportion to their sizes, and debit a known loss in
proportion to their balances, in both cases keeping every balance within
`[0, size]` and passing what one account cannot hold to the others. Capacity
sharing schemes work this way (the internal spill of the St George scheme),
and the redistribution is iterative, so it cannot be authored as an expression
over `self`. The question was what the action is, and how much it does.

## Decision drivers

- Manifesto §2.2 — the rule is read from the file: one verb, a signed amount,
  a stated weight.
- Manifesto §2.3 — nothing is lost silently: a volume no account can hold is
  recorded.
- Manifesto §2.8 — one verb for both directions and both weights, not a verb
  per idiom.
- [ADR-0004](0004-performance-on-the-hot-path.md) §3.5 — a model without the
  action pays nothing; a firing allocates nothing.

## Considered options

1. **One verb, `distribute(x, by_size | by_balance)`**, signed amount,
   capped and redistributed.
2. **A verb per idiom**: `share_inflow(x)` and `apportion_loss(x, method)`,
   as the design note sketched.
3. **Stencilled only**: `credit_fraction` and `self` arithmetic, with the
   redistribution left to the modeller.
4. **Uncapped**, as `allocate` is: let balances pass their size.

## Decision

Chosen option: **1**.

1. **One verb.** `distribute(x, by_size)` shares `x` in proportion to the
   targets' sizes; `distribute(x, by_balance)` in proportion to their
   balances as they stood before the action. A positive `x` credits, a
   negative `x` debits. The sign is the direction, so a loss and a credit are
   the same verb read the same way.
2. **Capped and redistributed.** No balance leaves `[0, size]` by this
   action. Each pass hands every account with room its weighted share of
   what remains, capped at that room; an account that reaches its room leaves
   the pool, and the rest is shared among those left by the same weights.
   The loop ends in at most one pass per target and does not depend on
   target order. An account with no weight receives nothing. An account
   already above its size, from an announcement over 100%, has no room to
   credit and is debited by its share, not cut back to its size.
3. **What finds no room is recorded.** `ras.<name>.undistributed` carries
   it, and `ras.<name>.amount` the volume asked for; both read as zero on
   steps the action does not fire. The ledger's mass balance is auditable
   from the series.
4. **One volume for the pool.** `x` is evaluated once per firing and never
   takes `self`, as `allocate` does not. Where `targets` names several
   groups their accounts form one pool, as stencilled actions already
   flatten them.
5. **Policy, not use.** A distributed debit moves the balance and leaves
   `debits` and `use` alone, as `debit(x)` does; only a user's take is use.
6. **No new timing slot.** The action runs in the ras slot like every other,
   reading fluxes at `[-1, 0]`. A post-flow slot for flux-consuming actions
   waits for a case the offset cannot serve (Manifesto §2.7).

Option 2 spells one mechanism two ways and ties the verb to a purpose the
sign already states. Option 3 cannot express the redistribution, which needs
every account's room at once. Option 4 is what was asked against: the cap is
the requirement.

### Consequences

- ✅ Inflow sharing and loss attribution are one section each, and every
  balance they move is a recordable account.
- ✅ Models without the action run the instructions they ran before; a RAS
  on another action pays two `Option` tests per step for the new recorders.
- ❌ A firing costs O(passes × targets), at most O(targets²); accepted, as
  firings are rare or the pool is small, and the weights live in a scratch
  sized at load.

## Worked example

Sizes 100/100/100, balances 90/50/0, `distribute(100, by_size)`. The first
pass offers a third each; the first account takes 10 and fills. The second
pass shares the 23.3 left between the other two by the same weights. Final
balances 100/95/45, nothing undistributed.

## Enforcement

- **Structural**: §1, §3 and §4 by the parser in
  `src/io/ini_model_io_versions/ini_doc_model_io_0_0_1.rs` (the two weight
  keywords, the closed series set, `self` refused); §2 and §5 by
  `RasSystem::distribute` in `src/hydrology/allocation_systems/ras.rs`, tested
  in `src/tests/test_ras.rs` (`test_distribute_*`).
- **Advisory**: that the weights stay at two. A `by_<column>` weight, or a
  reconciliation verb, is proposed against §1 when a use case arrives.

## Links and references

- Design note: `docs/water_management/kalix-allocation-components.md` §3.3, §3.5
- Related: [ADR-0004](0004-performance-on-the-hot-path.md)
- Docs: `website/docs/docs/ras.md`, "Sharing a volume"

## Amendments

*None yet.*
