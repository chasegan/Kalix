---
title: Manifesto
---

# Manifesto

*Kalix's philosophy*

This document states the project's mission, the values that underpin project
direction, and the scope. 

This manifesto is here to guide decisions concerning software design and 
project organisation. Decisions concerning software design are documented in 
the [Architecture Decision Records](adrs/index.md) and may cite the manifesto
clauses for support (e.g. `per Manifesto §2.2`). 

The Manifesto changes rarely, and only the project lead changes it
([Governance](https://github.com/chasegan/Kalix/blob/main/GOVERNANCE.md)).

---

## 1. Mission

Kalix exists to **lead the open-source hydrological modelling space and show
what is possible** — to put a fast, transparent, expert-grade modelling
platform in the hands of users, free and open **forever**. It is a tool by 
modellers for modellers.

Everything below serves that end.

## 2. Values

Kalix is held together by several commitments at once. None should be pursued
in disregard of any other. With clever design these values can usually be 
achieved together - that is your ultimate mandate as a participant. In case 
a hard decision must be made, §3 offers some guidance.

### 2.1 Free and open, forever

Kalix is open source and will stay that way. There is no premium tier, no
enterprise edition, no feature held behind a paywall. Financial support buys 
priority among work the project already considers acceptable; it never buys 
a compromise of our values. If an idea is bad without money, it is still bad
with money.

### 2.2 Transparency by default

Nothing is hidden from the modeller. Model files are text, human-readable, and
version-control-friendly; where a bespoke format is unavoidable it stays
inspectable. **What you read is what runs**: the file *is* the model, and the
engine executes it as written rather than silently rearranging it into
something the modeller cannot see. A modeller must always be able to reason
about their model by reading it.

### 2.3 Proven hydrology, correctness first

The science and the numerics are correct before anything else is considered.
Notwithstanding that numerical methods are approximate, the code logic must 
faithfully deliver the method that is represented to the modeller. 
Mass balance correctness is never traded for speed or for convenience. A fast 
wrong answer is worthless, and behaviour on failure is never so lean that a 
problem becomes untraceable.

### 2.4 Obsessive about speed

Speed is a standing commitment. The engine should run as fast as the machine 
allows. 

We respectfully reject Kent Beck's principle "make it work, make it right, 
then make it fast" because the world is filled with software whose writers
gave up after step 2 (or step 1). As a Kalix engine contributor if you haven't 
made it fast, you haven't made it at all. Therefore build speed into your work 
from the start.

On the hot path the fast implementation is the default implementation.
Anything you ask the CPU to do slows down everybody else's models; is your 
fantastic new feature really that good? Should you leave it out?
Adding something that makes another user's model slower is a sin, and 
your penance shall be to spend time looking for ways to make the engine run 
their model faster than ever.

### 2.5 Built for serious practitioners

Kalix is for expert modellers who want flexibility and control. We 
optimise design of the engine, and the IDE, for their needs. This may
present a learning-curve for novices in some areas. Features that are 
simple, well-designed, and predictable are good for everybody.

### 2.6 Clarity, when speed isn't the priority

Much of Kalix - the IDE, model load/save, tooling, IO, the Python API - 
is not the hot path. There, clarity is the dominant value. KalixIDE in 
particular must employ idiomatic architectures, more than clever performance 
optimisations, to deliver features and defend against UI bugs, race 
conditions, memory bloat, and design debt. Where possible we use structure 
and the type system to make features robust in the face of future changes.

On language: Clear thinkers can describe things simply and precisely, with
minimal jargon and pretence. That's what we should strive for in the 
code, software, and web docs - channel your inner Richard Feynman.
Examples: use "mean" (precise) not "average", and one thing has
one name (e.g. "zip" in one context menu can't be "compress" in another). 

### 2.7 Forward-thinking, but grounded

Python bindings, AI-friendly interfaces, and new ways of working are welcomed
when they serve real modelling needs, not for novelty's sake. New features
should be grounded in real use-cases. Features will not be added to cover
invented needs.

## 3. When values collide

With good design, the values in §2 are usually achieved together. This section
is for the times they cannot be.

- **On the engine's hot path, speed leads (§2.4)** and yields to one thing
  only: correctness (§2.3) — the numerics, mass balance, and enough behaviour
  on failure that a problem can still be traced.
- **Off the hot path, clarity leads (§2.6).** Speed is still welcome there,
  but it is not bought with code that is hard to read, reason about, or
  change. 
- **Transparency (§2.2) is never traded for convenience.** A convenience that
  hides something from the modeller is not a convenience Kalix offers. A tool
  that wants to rearrange or rewrite a model does so in the file, visibly.
- **A real need outranks an elegant idea (§2.7).** However good a feature
  looks, it waits until a use-case exists.
- **Values outrank money (§2.1).** Funding orders the queue; it never buys 
  a change that runs counter to the values of the project.

## 4. Scope — what Kalix is and is not

Kalix **is** a platform for simulating catchments and river systems as
networks of nodes and links on a constant timestep, with the calibration,
water-management and analysis tooling needed to undertake river modelling 
in Australia, and with interfaces — CLI, IDE, Python, AI-friendly protocols — 
that let practitioners adopt modern and future workflows.

Kalix **is not**:

- a GIS, a data-management system, or a replacement for the tools that already
  do those things well;
- a tool recognisably designed for any particular river system;
- a postprocessing tool; 
- a black box. A feature whose behaviour cannot be explained from the model
  file and the documentation does not belong.

Scope changes are rare, are made by the lead, and are recorded here.

## 5. How this document is used

- **ADRs derive from it.** Every decision record names the clauses here that
  drove it. If a decision cannot be traced to a value in this document, either
  the decision is wrong or this document is missing something — and the second
  case is how the Manifesto grows.
- **Cite it by clause** in reviews and design discussion (`per Manifesto §2.2`)
  so that arguments are settled by reference rather than by taste.
- **It is not a rulebook.** Rules — what to actually do in a given domain — are
  decisions, and belong in ADRs. This document says why; the ADRs say what.
