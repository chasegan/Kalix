---
title: "ADR-0006: Expression naming"
---

# ADR-0006: Expression naming

- **Status**: Accepted
- **Date**: 2026-07-11 (recorded 2026-09-14, retrospectively, from the
  `expression-naming` manifesto)
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: engine, ide, language

## Context and problem statement

The expression language is the part of Kalix a modeller types most often and
reads for decades. Its vocabulary was argued through across two concrete
decisions in July 2026 — rejecting `log` in favour of `ln`/`log10`, and
renaming `avg` to `mean` — and then extended as tables, user-defined
functions, `[var.*]` blocks and RAS per-target arguments were added. Each
addition raised the same questions: familiar spelling or precise one; alias
or single name; bare identifier or namespace; and, for self-reference, what
`this` and `self` mean.

This ADR locks the answers so the next naming question is settled by
citation, not conversation.

## Decision drivers

- Manifesto §2.6 — on language: describe things simply and precisely, with
  minimal jargon; use "mean" (precise) not "average"; one thing has one
  name.
- Manifesto §2.5 — built for expert modellers, in their own vocabulary.
- Forward compatibility: a model written today must not break when a builtin
  is added tomorrow.

## Considered options

The alternatives that were actually on the table, per decision:

1. **Familiar spellings and aliases** — accept `log`, keep `avg`, allow
   synonyms so that what a user guesses first works.
2. **One precise spelling, synonyms redeemed in the diagnostic** — reject
   synonyms in the grammar; have the unknown-name error suggest the real
   spelling.
3. **Bare user-defined names** (`rating(x)`) versus **namespaced ones**
   (`table.rating(x)`).
4. **Reuse `this` for RAS per-target arguments** versus a distinct **`self`**.

## Decision

Chosen: **one spelling, redeemed in the diagnostic; namespaces for
everything user-defined; `this` and `self` kept distinct.**

### 1. Principles

1. **Name the precise concept, in the modeller's language.** Kalix is built
   for serious practitioners; its readers write *mean annual flow*, not *avg
   annual flow*. When a familiar spelling is ambiguous about which concept it
   denotes, the familiar spelling is the wrong one.
2. **One name per thing — no aliases, ever.** A synonym looks like kindness
   and costs forever: two spellings in documentation, two entries in
   autocomplete, two styles drifting apart across models, and a permanent
   "are these the same?" question for every new reader.
3. **The language owns the bare names; the modeller owns the namespaces.**
   Everything a user defines is reached through a namespace prefix
   (`data.*`, `node.*`, `c.*`, `sim.*`, `table.*`, `this.`). Bare
   identifiers are reserved for builtins.

### 2. Rules

1. **Every builtin has exactly one spelling.** Never add an alias — not for
   familiarity, not for migration, not "temporarily".
2. **`mean`, not `avg` or `average`.** "Average" names a *family* of
   statistics — mean, median, mode — while `mean` names the one this
   function computes. (Decided 2026-07; `avg` was renamed as a breaking
   change on these grounds.)
3. **`ln`, `log10`, `log2` — never `log`.** `log` is ambiguous about its
   base; each explicit form is not. (Decided 2026-07; `log` was documented
   but never implemented, and was deliberately not added.)
4. **Reject synonyms in the grammar; redeem them in the error message.** The
   unknown-function error suggests the real spelling (`avg` → "did you mean
   'mean'?", `log` → `ln`). The kindness lives in the diagnostic, not in the
   language — a suggestion teaches once, an alias confuses forever.
5. **New user-defined callables and values get a namespace, never a bare
   name.** A bare user name would share one flat namespace with the
   builtins, so any *future* builtin could silently collide with an existing
   model — a forward-compatibility trap. This is why tables are called as
   `table.rating(x)`, not `rating(x)`.
6. **Names are lowercase; matching is case-insensitive.** `MEAN(x)` and
   `mean(x)` are the same call; write lowercase in documentation and
   examples. For *definitions* this is enforced, not advisory: every
   definition name in a model file — `[fn]` names and parameters, `[var.*]`
   blocks and keys — must satisfy the strict bare rule (lowercase letter
   first, then lowercase letters, digits, underscores; decided 2026-07,
   closing a drift where `[fn]` case-folded while everything else was
   strict). Call-site matching stays case-insensitive.
7. **Reserved names span tiers, and nothing user-named may shadow any
   tier.** The language's names come in three tiers — builtin functions,
   stateful functions (resolved at lowering, not the builtin enum), and
   grammar keywords (`assert`, `this`, `self`). Locals, `[fn]` names, and
   parameters are checked against *all* tiers through one registry, so a
   tier added later extends every guard automatically (decided 2026-07,
   closing a drift where stateful names were reserved for `[fn]` but
   shadowable as locals).
8. **`this` names the enclosing definition; `self` names the element being
   operated on. Neither may take the other's role.** `this` is a static,
   lexical alias — expandable to the full reference of the definition it
   appears in (`this.dsflow` on a node is `node.<name>.dsflow`; `this[-1, 0]`
   in a var is that var's own series) — and it keeps full series semantics,
   offsets included. `self` is a per-element, live-state binding: inside a
   `[ras.*]` action argument it ranges over the target accounts at
   evaluation time, has no longhand expansion, reads live mid-sequence
   state, and therefore takes no offsets. The spelling tells the reader
   which evaluation model they are in; merging the two would make one word
   flip meaning by context. Corollary: in a `[ras.*]` section, `this` is
   reserved for the section itself (`this.fired`-style self-reference, if
   ever wanted) — never for the current target account. (Decided 2026-08,
   when RAS per-target arguments were added.)

### Consequences

- ✅ One spelling per concept, in docs, autocomplete and every model file.
- ✅ Adding a builtin can never break an existing model.
- ✅ A reader of someone else's model never has to guess a base or a
  statistic.
- ❌ `avg` → `mean` was a breaking rename. Accepted: it cost nothing (no
  example, regression test or tutorial had used `avg()`), which is also the
  standing argument for settling names *early*, while renames are cheap.
- ❌ Users who type the familiar spelling hit an error. Mitigated by §2.4:
  the error tells them the real one.

## Pros and cons of the options

### Option 1: Familiar spellings and aliases

- ✅ Whatever the user guesses first works.
- ❌ Ambiguity is absorbed into the language forever (`log` of which base?).
- ❌ Two spellings in every doc, autocomplete list and codebase.

### Option 2: One spelling, redeemed in the diagnostic

- ✅ Precise, single vocabulary; the suggestion teaches once.
- ❌ Occasional friction on first use.

### Option 3: Bare versus namespaced user names

- ✅ Bare names read beautifully (`rating(x)`).
- ❌ A model that named a table `clamp` would break the day `clamp` becomes
  a builtin. Namespaces make the collision structurally impossible and tell
  the reader what kind of thing they are looking at.

### Option 4: `this` reused for RAS targets versus distinct `self`

- ✅ Reusing `this` is one fewer keyword.
- ❌ `this.balance` in a RAS would behave nothing like `this.dsflow` on a
  node while looking identical. A stencilled action is an implicit "for
  each target account" loop, and `self` is that loop's parameter — a
  different account per evaluation, live state, no history. Keeping them
  distinct also leaves `this` inside a `[ras.*]` section free to mean the
  section itself.

## Worked example

**`avg` → `mean` (2026-07).** The owner's framing, verbatim in spirit: the
"average" is a group of statistics that includes means, medians and modes;
the function computes the arithmetic mean, so it is called `mean`.

**`log` → nothing (2026-07).** The documentation promised `log` as a
natural logarithm; the engine never implemented it. Rather than absorb the
ambiguity, the docs were corrected and `log` stays rejected: a modeller
reading `log(x)` in someone else's file should never have to guess the base.

**`table.rating(x)`, not `rating(x)` (2026-07).** Bare table names read
beautifully and were rejected anyway, per §2.5.

**`self`, not `this`, for RAS per-target arguments (2026-08).** See option 4
above; per §2.8.

## Enforcement

- **The engine is the single source of truth.** Builtin names resolve only
  through `BuiltinFunction::from_name` in `src/functions/functions.rs`,
  which is also the home of the full reserved-name registry
  (`STATEFUL_FUNCTIONS`, `RESERVED_WORDS`, `reserved_name_kind`) and the
  add-a-builtin checklist. The strict bare-name rule lives once, in
  `misc_functions::is_valid_bare_name`. **Structural.**
- **The IDE linter mirrors the engine, never leads it.** The IDE holds ONE
  Java copy of the language definition (`ExpressionLanguage.java` —
  builtins, arities, sim variables, reserved tiers), consumed by the
  validator, the section validators, and autocomplete alike, with a
  cross-sync test asserting the consumers agree.
  `FunctionExpressionValidatorTest.testEngineDriftFunctions` pins the
  rejected spellings (`log`, `avg`, `running_*`, `days_since`, and the
  never-implemented `sinh`/`cosh`/`tanh`) as rejected — the tests exist
  because the linter *did* drift, more than once, in both directions.
  **Held by test.**
- **Docs list the absences explicitly.** `FUNCTIONS_DOCUMENTATION.md` names
  the deliberately-missing spellings and why, so a future contributor finds
  the decision before re-proposing it. **Advisory** — held by review.

## Links and references

- Related: `docs/functions/FUNCTIONS_DOCUMENTATION.md`,
  `docs/functions/structured_expressions_design.md`.
- Related: [ADR-0002](0002-context-menu-style.md) — the same one-name-per-
  thing discipline applied to menu labels.

## Amendments

- *2026-07-13* — §2.6 (strict bare names for definitions) and §2.7
  (reserved names span tiers) ratified, closing two drifts found in the
  structured-expressions p5 review.
- *2026-08-05* — §2.8 (`this` versus `self`) added when RAS per-target
  arguments were introduced; `this[-1, 0]` self-reference in var
  definitions confirmed as the series-semantics case of `this`.
