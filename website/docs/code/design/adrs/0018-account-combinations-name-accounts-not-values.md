---
title: "ADR-0018: A user's accounts line combines accounts with | and &, and holds no values"
---

# ADR-0018: A user's accounts line combines accounts with `|` and `&`, and holds no values

- **Status**: Proposed
- **Date**: 2026-11-08
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: engine, language, io, ide

## Context and problem statement

A user node's `accounts` property was a list: draw on `a`, then `b`. Some
licences need a take debited from both of two accounts and limited by the
shorter ([#507](https://github.com/chasegan/Kalix/issues/507)). The question
was how the line says so, and whether it may also say how much.

## Decision drivers

- Manifesto §2.2 — what you read is what runs: the policy must be visible in
  the file, not in a function the engine applies.
- Manifesto §2.8 — less is more: one small grammar, no second way to write
  what `[ras.*]` already writes.
- [ADR-0004](0004-performance-on-the-hot-path.md) §3.5 and
  [ADR-0009](0009-options-faster-or-clearer.md) §4 — resolved at load; no cost
  to models that do not use it.

## Considered options

1. **`|`, `&` and parentheses on the line**, naming accounts only.
2. **Named combinations** in a `[wallet]`-style section.
3. **Expressions on the line** (a debit factor, a conditional order of use).
4. **`||` and `&&`** as the operators.

## Decision

Chosen option: **1**.

1. **`a | b` is `a` then `b`: the sum is available and the debit fills left to
   right. `a & b` is both: the minimum is available and the whole take is
   debited from each side.** The plain list is `|`, and a combination without
   `&` is lowered to it. A `&` beneath a `|` absorbs the least of what it is
   handed and what its sides can cover, and the `|` carries the rest on. A
   regulated user's order beyond what the line can cover, authorised by
   `order_accounts`, is committed to the first account of each side of the
   outermost `&` that fell short, or of the whole: the plain list's rule, per
   side.
2. **Parentheses are required to mix `|` and `&`.** There is no precedence to
   remember, so there is none to get wrong.
3. **The line holds no values.** It names accounts and says how they combine.
   Anything dynamic is an account whose balance a `[ras.*]` rule controls: a
   gate, a daily limit, a cap. The line says who pays; expressions say how
   much, and they stay in the sections that can be read for it.
4. **An account appears once in a node's combination**, so every walk is an
   exact tree walk and no solver is needed.
5. **The plain list keeps its own field and code.** A combination is a second
   representation chosen at load, and the phases are instantiated on a const
   for it, so a model on the plain list runs the instructions it ran before,
   plus one test per phase to choose. A node result for the combined
   availability waits for a use case (Manifesto §2.7).

Option 2 names something each node uses once. Option 3 moves policy out of
`[ras.*]` into a second place. Option 4 reads as boolean, which the operators
are not, and lets one spelling mean two things in one file.

### Consequences

- ✅ A take limited by two ledgers is one line, and every balance that limits
  it is a recordable account.
- ✅ Models on the plain list are unchanged in text and in speed.
- ❌ A combination costs about 13 ns per user-step over the plain list of
  two accounts it replaces, of which about 3 ns is the third account it
  touches and the rest the tree walk and the cap committed and debited
  every step (`regression_tests/speed/` 11 against 10, 16 users, 36,525
  steps, 40.3 to 47.9 ms by minimum, 20 interleaved reps, Apple M5, one
  session; it was 19 ns before the two tree levels were made to inline, by
  disassembly). Accepted: paid only by the user that writes one. Models on
  the plain list measured at the noise floor against `main` across five
  sessions (tests 2, 4, 5 and 10, by minimum −1.5% to +1.8%, sign
  inconsistent), with the plain-list instantiations of the regulated phases
  instruction-identical to `main`'s; what moves between builds whose
  plain-list code is identical is placement, as the 2026-10-06 entry of
  ADR-0004 describes.

## Worked example

```ini
accounts = (smith_co | smith_annual) & valley_cap
```

Draw on the carryover, then the annual account, and debit every megalitre
from the valley cap as well. `a | b & c` is refused: `'|' and '&' cannot be
mixed without parentheses`.

## Enforcement

- **Structural**: §1, §2 and §4 by the parser in
  `src/hydrology/accounts/account_tree.rs`, tested in
  `src/tests/test_account_combinations.rs`; §3 by the grammar, which has no
  numbers or operators but these; §5 by the `COMBINED` const on both user
  nodes' phases and by speed test 10 against `main`.
- **Advisory**: that the vocabulary stays at two operators, and that a debit
  factor or a conditional order of use, if ever wanted, is proposed against
  §3.

## Links and references

- Issue: [#507](https://github.com/chasegan/Kalix/issues/507)
- Related: [ADR-0004](0004-performance-on-the-hot-path.md),
  [ADR-0009](0009-options-faster-or-clearer.md),
  [ADR-0017](0017-resolve-references-at-load.md)
- Docs: `website/docs/docs/accounts.md`, "Combining accounts"

## Amendments

*None yet.*
