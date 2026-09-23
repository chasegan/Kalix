---
title: "Architecture Decision Records"
---

# Architecture Decision Records

This is the register of **Architecture Decision Records (ADRs)** for Kalix.
An ADR captures one decision: the problem that forced it, the options that
were considered, the choice made and why, and how that choice is held in
place. Together they explain *why the codebase is the way it is*, so that a
question argued through once is settled by citation, not re-argued each time
it comes up.

ADRs derive from the [Manifesto](../manifesto.md). The Manifesto says what
Kalix values; each ADR names the clauses that drove it. The Manifesto is
deliberately not a rulebook — the rules live here.

The conventions below are themselves a decision, recorded in
[ADR-0001](0001-record-decisions-in-adrs.md). This page is the short form.

## What gets an ADR

Write an ADR for a decision that:

- shapes the architecture of the engine, the IDE, the Python package, or the
  formats and protocols between them;
- sets a precedent that later work will follow;
- is hard to reverse, or affects more than one component;
- has been deliberately considered by the core team and should not be
  reopened without new information — **including ideas that were considered
  and rejected**. A rejected idea is often the most valuable ADR of all: it
  is the one most likely to be proposed again.

Don't write one for routine refactors, cosmetic choices, or anything the pull
request thread explains adequately on its own.

## Where they live

ADRs are canonical in the repository, under
`website/docs/code/design/adrs/`, and are published at
[kalix.org/code/design/adrs/](https://kalix.org/code/design/adrs/). They are
versioned alongside the code they justify, proposed and reviewed in pull
requests at the moment the decision is made, and anyone who clones the
repository has the complete record.

## Conventions

- **One file per decision.** Filename `NNNN-short-kebab-case-title.md`, e.g.
  `0005-node-definition-order.md`.
- **Numbers are sequential and never reused**, even when an ADR is later
  deprecated or superseded.
- **Status values:** `Proposed` → `Accepted` → optionally `Deprecated` or
  `Superseded by ADR-NNNN`. An ADR is accepted by the project lead
  ([Governance](https://github.com/chasegan/Kalix/blob/main/GOVERNANCE.md)).
- **Accepted ADRs are frozen, with one relief valve.** A change that does
  *not* reverse the decision — a clarification, a narrowing, a new data
  point, a retraction of one supporting claim — is added as a dated entry in
  the ADR's **Amendments** section, leaving the original text intact.
  Anything that reverses the decision is a new ADR that supersedes the old
  one; the old ADR's status is updated to point at it and is otherwise left
  alone.
- **Decision clauses are numbered** so they can be cited precisely:
  `per ADR-0002 §2.5`. A `§` number always refers to the numbered clauses
  of the ADR's **Decision** section, never to its other headings. Use that
  form in code comments, reviews, and commit messages.
- **Decision drivers cite the Manifesto** by clause (`Manifesto §2.2`). If a
  decision cannot be traced to a Manifesto value, either the decision is
  wrong or the Manifesto is missing something.
- **Every ADR names its enforcement** — how the decision is held in place.
  *Structural* means code or tests make a violation inconvenient or
  impossible; *Advisory* means it is held only by review and by the citing
  habit. Say which, honestly.
- **Retrospective ADRs are allowed and labelled.** A decision made before it
  was recorded gets an ADR with its original decision date and a note that it
  was recorded after the fact. Record only the options that were actually on
  the table; do not invent alternatives to make the record look thorough.

## Workflow

1. Copy [`0000-template.md`](0000-template.md) to `NNNN-title.md` with the
   next number and status `Proposed`.
2. Add the page to the ADRs section of the site navigation in
   `website/mkdocs.yml`, and a line to the index below.
3. Open a pull request. Discuss there — the thread is part of the audit trail.
4. On merge, the lead sets the status to `Accepted`.
5. If a later ADR supersedes this one, that later pull request also updates
   this ADR's status.

## Index

Newest first.

- [ADR-0008 — State ordering knowledge per node type, in exhaustive matches](0008-ordering-knowledge-in-exhaustive-matches.md)
- [ADR-0007 — File-tree colour](0007-file-tree-colour.md)
- [ADR-0006 — Expression naming](0006-expression-naming.md)
- [ADR-0005 — Node definition order is execution order](0005-node-definition-order.md)
- [ADR-0004 — Performance on the hot path](0004-performance-on-the-hot-path.md)
- [ADR-0003 — Separate identity from labels](0003-identity-and-labels.md)
- [ADR-0002 — Context-menu style](0002-context-menu-style.md)
- [ADR-0001 — Record decisions in ADRs](0001-record-decisions-in-adrs.md)
