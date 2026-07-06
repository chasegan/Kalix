---
title: "Architecture Decision Records"
---

# Architecture Decision Records

This page is the index for **Architecture Decision Records (ADRs)** for Kalix. ADRs capture *why* the codebase is the way it is — the significant decisions, the options that were considered, and the trade-offs accepted.

## Where ADRs live

ADRs are **canonical in the repository**, not in Notion. They live as markdown files under:

```jsx
docs/adr/
```

in the [Kalix GitHub repo](https://github.com/chasegan/Kalix). This means:

- ADRs are versioned alongside the code they justify.

- New ADRs are proposed and reviewed in pull requests, at the moment the decision is being made.

- The full history survives independently of any external service.

- Anyone who clones the repo has the complete decision log.

This Notion page exists as a **discoverability layer** for non-developer readers (modellers, government collaborators, academic users) and a place to explain the convention.

## Conventions

- **One file per decision.** Filename: `NNNN-short-kebab-case-title.md`, e.g. `0007-use-ini-format-for-model-files.md`.

- **Numbers are sequential and never reused**, even if an ADR is later deprecated or superseded.

- **ADRs are immutable once accepted.** If a decision changes, write a new ADR that supersedes the old one. Update the old ADR's status to `Superseded by ADR-NNNN` but otherwise leave it intact.

- **Status values**: `Proposed` → `Accepted` → (optionally) `Deprecated` or `Superseded by ADR-NNNN`.

- **Scope.** Write an ADR for decisions that are hard to reverse, affect multiple components, set a precedent, or that future contributors are likely to question. Don't write one for routine refactors or cosmetic choices.

## Workflow

1. Open a pull request adding `docs/adr/NNNN-title.md` with status `Proposed`.

2. Discuss in the PR — the PR thread becomes part of the decision's audit trail.

3. On merge, update status to `Accepted`.

4. If a future ADR overrides this one, that future PR also updates this ADR's status to `Superseded by ADR-XXXX`.

## Template

The canonical template is `docs/adr/0000-template.md` in the repo. Copy it, renumber, fill in the blanks. It captures:

- **Title** — short, imperative ("Use INI format for model files")

- **Status** — Proposed / Accepted / Deprecated / Superseded

- **Date**

- **Deciders** — who made the call

- **Tags** — area of the codebase

- **Context and Problem Statement** — what forced the decision

- **Decision Drivers** — the criteria that mattered

- **Considered Options** — at least two, ideally three

- **Decision Outcome** — chosen option with justification, plus positive and negative consequences

- **Pros and Cons of the Options** — detailed comparison

- **Links and References** — supersedes / superseded by / related ADRs, issues, PRs, external sources

- **Notes** — open questions and follow-ups

The full template is below. Copy this into `docs/adr/0000-template.md` to seed the directory.

```jsx
# ADR-NNNN: [Short title in imperative tense]

- **Status**: Proposed | Accepted | Deprecated | Superseded by [ADR-XXXX](XXXX-short-title.md)
- **Date**: YYYY-MM-DD
- **Deciders**: [names or GitHub handles of people who made the call]
- **Tags**: [e.g. engine, io, calibration, cli, ide, python, build]

## Context and Problem Statement

What is the issue that motivates this decision? Describe the forces at play —
technical, organisational, scientific, project — in two or three short
paragraphs. Frame the core question if it helps.

## Decision Drivers

- [driver 1, e.g. simulation performance must not regress]
- [driver 2, e.g. cross-platform support: Linux / macOS / Windows]
- [driver 3, e.g. model files must be hand-editable and diff-friendly]
- [driver 4, e.g. backwards compatibility with existing models]

## Considered Options

1. **[Option 1]** — one-line description
2. **[Option 2]** — one-line description
3. **[Option 3]** — one-line description

## Decision Outcome

Chosen option: **[Option X]**, because [justification — explain how it best
satisfies the decision drivers, and why the trade-offs are acceptable].

### Positive consequences

- [e.g. simpler model files, easier to diff in version control]
- [e.g. lower barrier for new users]

### Negative consequences

- [e.g. limited expressiveness for deeply nested structures]
- [e.g. requires a custom parser rather than an off-the-shelf library]

## Pros and Cons of the Options

### Option 1: [name]

- ✅ [pro]
- ✅ [pro]
- ❌ [con]
- ❌ [con]

### Option 2: [name]

- ✅ [pro]
- ❌ [con]

### Option 3: [name]

- ✅ [pro]
- ❌ [con]

## Links and References

- Supersedes: [ADR-XXXX](XXXX-short-title.md)
- Superseded by: [ADR-XXXX](XXXX-short-title.md)
- Related: [ADR-XXXX](XXXX-short-title.md)
- Issue: [#NNN](https://github.com/chasegan/Kalix/issues/NNN)
- Pull request: [#NNN](https://github.com/chasegan/Kalix/pull/NNN)
- External: [paper, blog post, RFC, standard]

## Notes

Open questions, follow-up actions, or context that didn't fit above.
```

## Tooling (optional)

Lightweight tools that work well with this convention:

- `adr-tools` — shell scripts to create and link ADRs from the commandline.

- `log4brains` — generates a static website from your `docs/adr/` directory, useful if you ever want a browsable web view.

Neither is required — plain markdown files in the repo work fine on their own.

## Index of accepted ADRs

*(Maintain this list manually, or generate it from the repo. Newest at top.)*

- *No ADRs yet. The first one is traditionally an ADR about using ADRs.*
