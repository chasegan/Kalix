# On Manifestos

*The first manifesto: what these documents are, what they are for, and how to write one.*

A manifesto is an opinionated doctrine about how some part of this project should
be done, and **why**. It is the written, citable form of a decision we have
already argued through once and do not wish to argue through again.

Its purpose is almost theological: to settle arguments by being *citable*. When a
question has a manifesto, you do not re-derive the answer from first principles or
from someone's personal taste — you cite the clause. "Sentence case, per
[context-menu-style §2.1]." The discussion is over, not because someone pulled
rank, but because the reasoning was recorded the first time and survives without
its author in the room.

---

## 1. What a manifesto is

A manifesto states **principles**, the **rules** that follow from them, and the
**rationale** that makes both persuasive. It is *prescriptive* — it tells you what
to do — and it earns the right to be prescriptive by showing its reasoning. A rule
without a reason is an edict; nobody can apply it to a case it didn't anticipate.
A reason without a rule is an essay; nobody can act on it. A manifesto is both.

The test: could a competent stranger — or an AI agent — read it and make the same
decision you would have made, *including* in a case the document never explicitly
mentions, because they absorbed the principle behind the rules? If yes, it's a
manifesto.

## 2. What a manifesto is not

- **Not reference or architecture docs.** "How the session manager works" is
  descriptive — it explains what *is*. A manifesto is prescriptive — it argues
  what *should be*. Keep descriptive docs in `docs/`; keep doctrine here. The
  moment a how-it-works section creeps into this folder, the folder loses the
  identity that makes it citable.
- **Not a changelog or a record of decisions.** A manifesto is the *living rule*,
  not the history of how we got it. Past debate belongs in PRs and commits.
- **Not API documentation.** If it would go stale when a method signature changes,
  it is not a manifesto.

## 3. The shape

Hold to a predictable skeleton so manifestos are fast to read and fast to write:

1. **A one-line statement** of what the manifesto governs.
2. **Principle(s)** — the few load-bearing ideas, stated plainly.
3. **Rules** — what to actually do, numbered (see §6).
4. **Rationale** — interleaved or grouped; every rule of consequence says *why*.
5. **Worked examples** — at least one before/after or concrete application.
   Examples are where a manifesto proves it is operable rather than aspirational.

This document follows that skeleton. So should the next one.

## 4. Harvest, don't invent

**Write a manifesto when you have just made a class of decision** and want to lock
the rationale so you never relitigate it — not in advance of having the opinion.
A manifesto harvested from real, just-resolved decisions is battle-tested on
arrival. A manifesto invented speculatively is a guess wearing the costume of
authority, and it will rot the first time reality disagrees with it.

A folder of twelve aspirational manifestos nobody applies is worse than no folder:
it teaches readers that the manifestos here are decoration. One manifesto that
accretes from a real decision is worth more than ten written on spec. Let this
folder grow slowly and only from things we have actually done.

## 5. Every manifesto names its enforcement

Doctrine that cannot be checked drifts. Each manifesto must say how it is held in
place — ideally structurally, in code, so violating it is *inconvenient* rather
than merely *discouraged*.

> The context-menu manifesto reserves icons for four actions and routes all of them
> through a single `MenuIcons` helper that exposes only those four. There is no easy
> way to icon a fifth item, so the rule enforces itself.

When a rule can only be enforced by vigilance, say so — label it **Advisory** —
rather than pretend it has teeth it doesn't.

## 6. Make clauses citable

Number the sections. A manifesto's power is that you can point at "§2.3" and end a
conversation. Unnumbered prose cannot be cited precisely, so it cannot settle
anything. Cross-reference freely, within a manifesto and between manifestos —
citability is the whole mechanism.

## 7. Conventions

- **Location:** repo root `manifestos/`. Manifestos are project-level doctrine and
  may span the Rust engine, the Java IDE, or both; they live in one discoverable
  home rather than scattered by module.
- **File naming:** `kebab-case-topic.md`, named for the domain it governs
  (`context-menu-style.md`), not for a module path.
- **Index:** add a one-line entry to `README.md` when you add a manifesto.
- **Voice:** plain, direct, and willing to have an opinion. A hedge ("you might
  consider…") cannot settle an argument. State the rule; then earn it with the why.
- **Length:** short enough to be read in full. A manifesto nobody finishes governs
  nothing.
- **Status:** if a manifesto is provisional, mark it **Draft** at the top; if
  retired, mark it **Superseded by [x]** rather than deleting it, so old citations
  still resolve.

## 8. For collaborators and AI agents

These documents exist precisely so that the author does not have to re-explain
their judgement — their whole life-story and the consequential process by which
they choose, say, a menu name — every time work is delegated. They are the durable,
externalised form of taste.

If you are a contributor or an agent working in this repository:

- **Treat active manifestos as binding** for the domain they cover. They are not
  suggestions; they are the house style, already decided.
- **Cite by clause** in code comments, reviews, and commit messages
  (`per context-menu-style §2.5`) so the reasoning is traceable from the work back
  to the doctrine.
- **When a decision isn't covered**, follow the *spirit* of the nearest manifesto —
  apply its principle to the new case — and flag the gap so it can be harvested
  into the manifesto if it recurs.
- **Do not silently contradict a manifesto.** If you believe one is wrong, say so
  and propose an amendment; a manifesto changes by argument and revision, never by
  quiet exception. An exception that isn't written down is just drift.

---

*Enforcement: Advisory — held by review and by the citing habit. The discipline
that keeps this folder honest is §4 (harvest, don't invent) and §5 (name your
enforcement); apply them to every manifesto added here, including this one.*
