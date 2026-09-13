---
title: "ADR-0001: Record decisions in ADRs"
---

# ADR-0001: Record decisions in ADRs

- **Status**: Accepted
- **Date**: 2026-09-14
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: process

## Context and problem statement

A project that lasts accumulates decisions faster than it accumulates people
who remember why they were made. Without a record, the same questions come
back — "why is there no `log()`?", "why doesn't the engine just sort the
nodes?" — and each time they are answered from scratch, by whoever is in the
room, from memory and taste. The answer drifts. Contributors and AI agents
working in the repository have no way to find out that a question is already
settled, so they relitigate it in good faith.

Until now Kalix recorded its doctrine as a folder of *manifestos*: per-topic
documents that stated principles, the rules that followed, and the rationale.
That worked, but it blurred two things that want to be separate. A value
("what you read is what runs") changes rarely and belongs to the project as a
whole; a decision ("the engine refuses out-of-order files rather than sorting
them") is one application of that value, made on a date, with alternatives
that were considered and rejected. The manifesto charter said a manifesto was
"not a record of decisions", while every manifesto in the folder recorded
decisions, with dates, because there was nowhere else to put them.

The question is where decisions should live so that they are discoverable,
citable, frozen once made, and traceable to the values that drove them.

## Decision drivers

- Manifesto §2.2 — transparency by default. The reasoning behind the
  codebase should be as inspectable as the codebase.
- Manifesto §2.6 — clarity, and using structure to make things robust to
  future change. A decision that survives without its author in the room is
  a structural property of the project.
- Manifesto §2.7 — grounded, not speculative. The record should be harvested
  from decisions actually made, not written on spec.
- [Governance](https://github.com/chasegan/Kalix/blob/main/GOVERNANCE.md):
  "There is no vote. The lead listens, decides, and explains. … once a
  decision is made, the discussion moves on." The record is how the
  discussion moves on.

## Considered options

1. **Per-topic manifestos** — the previous approach: one document per domain
   holding principles, rules, and rationale together.
2. **Decisions in pull-request threads and commit messages only** — no
   separate record; the history is the record.
3. **A single running decisions log** — one file, one entry per decision,
   appended over time.
4. **Architecture Decision Records** — one file per decision, numbered,
   templated, frozen once accepted, with a separate Manifesto holding the
   values they cite.

## Decision

Chosen option: **Architecture Decision Records**, with the values split out
into the [Manifesto](../manifesto.md). The Manifesto says *why*; ADRs say
*what*. Each layer cites the one above it: code and reviews cite ADRs by
clause, ADRs cite the Manifesto by clause.

1. **Every significant decision gets an ADR.** That means decisions that
   shape the architecture, set a precedent, are hard to reverse, span
   components, or have been deliberately considered by the core team and
   should not be reopened without new information. Ideas that were
   considered and *rejected* qualify on the same terms — they are the
   decisions most likely to be proposed again.
2. **Harvest, don't invent.** Write an ADR when a decision has just been
   made, not in anticipation of one. A record harvested from a real decision
   is battle-tested on arrival; one written speculatively is a guess wearing
   the costume of authority, and rots the first time reality disagrees. A
   decision made before it was recorded may be written up retrospectively,
   labelled as such, with its original date and only the options that were
   actually on the table.
3. **ADRs are frozen, with an amendments log.** Once accepted, an ADR's
   original text is not edited. A change that does not reverse the decision —
   a clarification, a narrowing, a new measurement, a retraction of one
   supporting claim — is a dated entry in its Amendments section. A reversal
   is a new ADR that supersedes the old one. This keeps the history honest
   without spawning an ADR for every refinement.
4. **Decision clauses are numbered and cited by number.** `per ADR-0002
   §2.5` in a code comment, a review, or a commit message points at one
   sentence and ends a conversation. A `§` number always refers to the
   Decision section's clauses. Unnumbered prose cannot be cited precisely,
   so it cannot settle anything.
5. **Every ADR names its enforcement.** Doctrine that cannot be checked
   drifts. Each ADR says how it is held in place — ideally structurally, in
   code or tests, so that violating it is inconvenient rather than merely
   discouraged. A clause held only by review is labelled **Advisory** rather
   than pretending to teeth it doesn't have.
6. **Decision drivers cite the Manifesto.** If a decision cannot be traced to
   a Manifesto clause, either the decision is wrong or the Manifesto is
   missing something. The second case is how the Manifesto grows — by
   proposal to the lead, not by an ADR quietly asserting a new value.
7. **Accepted ADRs are binding on contributors and agents.** They are house
   style, already decided — not suggestions. When a decision isn't covered,
   follow the spirit of the nearest ADR and the Manifesto, and flag the gap
   so it can be recorded if it recurs. Never contradict an ADR silently: if
   one is wrong, say so and propose a superseding ADR. An exception that
   isn't written down is drift.
8. **The lead accepts ADRs.** Anyone may propose one; the core team
   considers it; the lead accepts it, per Governance.
9. **ADRs live in `website/docs/code/design/adrs/`** and are published on
   the website. `website/docs/docs/` is user documentation and stays
   descriptive; `website/docs/code/` is for developers and may be
   prescriptive. An ADR is added to the site navigation and to the register
   index when it is created.

### Consequences

- ✅ A settled question is answered by citation, in one line, by anyone.
- ✅ Rejected ideas stay rejected until someone brings new information.
- ✅ Values and decisions can change at different rates without either
  document contradicting itself.
- ✅ AI agents working in the repository can be pointed at a folder and
  expected to honour it.
- ❌ Ceremony. A short template still takes longer than a commit message.
  Accepted because the alternative — re-arguing — costs more, repeatedly.
- ❌ The register must be maintained by hand (index and nav). Accepted;
  §9 makes it part of creating an ADR.

## Pros and cons of the options

### Option 1: Per-topic manifestos

- ✅ Principles and rules read together, in one place, per domain.
- ✅ Already in use; citations existed across the codebase.
- ❌ Blurred values (rarely changed, lead-owned) with decisions (dated,
  supersedable). The charter forbade recording decisions; the documents did
  it anyway.
- ❌ Amended in place, so the history of a decision was only in git.
- ❌ No natural home for a rejected idea, which is not a rule.

### Option 2: PR threads and commits only

- ✅ Zero ceremony.
- ❌ Not discoverable: you have to already know the decision exists to find
  the thread.
- ❌ Not citable by clause.
- ❌ Reasoning is scattered across review comments and never consolidated.

### Option 3: A single decisions log

- ✅ One place to look; trivial to add to.
- ❌ One file grows without bound and cannot be frozen per decision.
- ❌ Cannot be superseded entry-by-entry without editing the log.
- ❌ Cannot be cited more precisely than "the log, somewhere".

### Option 4: ADRs with a separate Manifesto

- ✅ One decision, one file, one number, one status.
- ✅ Frozen text plus an amendments log gives an honest history in the
  document itself.
- ✅ A clean citation chain: code → ADR clause → Manifesto clause.
- ✅ Widely understood convention; tooling exists if ever wanted.
- ❌ Two documents to keep consistent (Manifesto and ADRs). Mitigated by §6:
  the ADR cites the Manifesto, never restates it.

## Worked example

A contributor proposes adding `log()` as an alias for `ln()` "because
everyone expects it". The reviewer replies: *rejected per ADR-0006 §2.3 —
`log` is ambiguous about its base; the error message suggests `ln`.* The
thread is three lines long and ends there. If the contributor believes the
decision is wrong, the route is a superseding ADR that engages with the
recorded reasoning, not a longer thread.

Contrast the same proposal with no record: the reviewer reconstructs the
argument from memory, the contributor counters, and a decision that was
settled in July 2026 is made again in a worse mood.

## Enforcement

**Advisory.** ADRs are held in place by review and by the citing habit. The
project `CLAUDE.md` directs contributors and agents to the register and makes
§7 binding; the register page repeats these conventions in short form.
Nothing in the build checks that a decision has an ADR, and nothing here
claims otherwise.

## Links and references

- Related: [Manifesto](../manifesto.md), particularly §5 ("How this document
  is used").
- Related: [Governance](https://github.com/chasegan/Kalix/blob/main/GOVERNANCE.md),
  "How decisions are made".
- Supersedes: the `manifestos/` folder and its charter, `on-manifestos.md`,
  removed from the repository in the same change that added this ADR. Their
  content was migrated into the Manifesto (values) and ADR-0002 onwards
  (decisions).
- External: Michael Nygard, [Documenting Architecture Decisions](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions)
  (2011), the origin of the ADR convention.

## Amendments

*None yet.*
