---
title: "ADR template"
---

# ADR-NNNN: [Short title in imperative tense]

- **Status**: Proposed | Accepted | Deprecated | Superseded by `[ADR-XXXX](XXXX-short-title.md)`
- **Date**: YYYY-MM-DD *(the date the decision was made; add "recorded YYYY-MM-DD" if retrospective)*
- **Deciders**: [names or GitHub handles of the people who made the call]
- **Tags**: [e.g. engine, io, calibration, cli, ide, python, build, process]

## Context and problem statement

What is the issue that motivates this decision? Describe the forces at play —
technical, organisational, scientific, project — in two or three short
paragraphs. Frame the core question if it helps.

## Decision drivers

Cite the Manifesto clauses that bear on this decision, and any other criteria
that mattered.

- [Manifesto §2.2 — what you read is what runs]
- [Manifesto §2.4 — the fast implementation is the default on the hot path]
- [driver, e.g. backwards compatibility with existing models]

## Considered options

1. **[Option 1]** — one-line description
2. **[Option 2]** — one-line description
3. **[Option 3]** — one-line description

## Decision

Chosen option: **[Option X]**, because [justification — how it best
satisfies the drivers, and why the trade-offs are acceptable].

Number the clauses so they can be cited (`per ADR-NNNN §1`); a `§` number
always refers to this section's clauses. Sub-number (`§2.3`) when a clause
has parts. Each clause of consequence says *why*.

1. **[Rule.]** [Rationale.]
2. **[Rule.]** [Rationale.]
3. **[Rule.]** [Rationale.]

### Consequences

- ✅ [positive consequence]
- ✅ [positive consequence]
- ❌ [negative consequence, accepted because…]

## Pros and cons of the options

### Option 1: [name]

- ✅ [pro]
- ❌ [con]

### Option 2: [name]

- ✅ [pro]
- ❌ [con]

### Option 3: [name]

- ✅ [pro]
- ❌ [con]

## Worked example

At least one concrete application — a before/after, a snippet, a menu, a
file — that shows the decision is operable rather than aspirational.

## Enforcement

How is this decision held in place? Name the code, tests, or structure that
make a violation inconvenient (**Structural**), and be honest about which
clauses are held only by review and the citing habit (**Advisory**).

## Links and references

- Supersedes: `[ADR-XXXX](XXXX-short-title.md)`
- Superseded by: `[ADR-XXXX](XXXX-short-title.md)`
- Related: `[ADR-XXXX](XXXX-short-title.md)`
- Issue: [#NNN](https://github.com/chasegan/Kalix/issues/NNN)
- Pull request: [#NNN](https://github.com/chasegan/Kalix/pull/NNN)
- External: [paper, blog post, RFC, standard]

## Amendments

Dated entries for changes that do not reverse the decision — clarifications,
narrowings, new data points, retractions of a supporting claim. A reversal is
a new ADR. Leave this section empty rather than deleting it.

- *YYYY-MM-DD* — [what changed, and why]
