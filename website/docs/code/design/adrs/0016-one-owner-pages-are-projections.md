---
title: "ADR-0016: A view's state has one owner; its pages are projections"
---

# ADR-0016: A view's state has one owner; its pages are projections

- **Status**: Proposed
- **Date**: 2026-11-04
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: ide

## Context and problem statement

A Run Manager plot tab has three pages, Plot, Stats and Data, sharing one set
of controls: which series are selected, the aggregation, the masks, the plot
type. Before [#489](https://github.com/chasegan/Kalix/pull/489) the plot
panel held the tab's state and its undo history, the tab manager held a
second copy in a settings object, the stats page computed its own aggregates
from the pool, and dirty flags and sync code kept the copies in step. Adding
a third page meant a third copy and a third set of flags, and the pages could
disagree about what they were showing.

The question is where a view's state lives when more than one page shows it.

## Decision drivers

- Manifesto §2.6 — clarity wherever speed isn't the point. The IDE is off
  the hot path; its architecture is judged on being idiomatic and easy to
  reason about.
- Manifesto §2.8 — less is more. Sync code and dirty flags are code that
  exists only to repair a design.
- ADR-0003 §1 — series are identified by typed refs, so derived data can be
  cached by identity rather than invalidated by hand.

## Considered options

1. **A copy per page, kept in step by a manager** — the shape that existed:
   each page holds what it shows; the manager pushes changes to each and
   flags what is stale.
2. **One owner, pages as projections** — one object holds the state, its
   history and the data derived from it; each page listens and redraws
   itself from the owner.

## Decision

Chosen option: **2**. Agreement by construction beats agreement by
synchronisation.

1. **One owner per view.** A model object holds the view's current state,
   its undo history, and the data derived from the state: the aggregated
   series and the display set. Nothing else holds a copy of the state as its
   own truth. For plot tabs this is `FlowVizModel`.
2. **The state is a value.** It is immutable, changed by with-ers, and
   replaced whole. A listener receives the old and the new state and works
   out for itself what changed, so the owner carries no per-page flags.
3. **Every change goes through the owner.** One update is one history entry
   and one notification, which says why: a user gesture, a replacement, a
   restore, or new data under an unchanged state. The toolbar and the pages
   follow the model;
   none of them owns a setting.
4. **Pages are projections.** A page holds no state beyond what it needs to
   draw. Shown, it redraws from the owner; hidden, it notes that it is out of
   date and catches up when shown. A hidden page holds no data the owner has
   let go.
5. **Derived data is computed on the owner, by identity.** The display set
   the plot draws, Save Data writes and the Data page shows is one object,
   reused while its inputs are the same objects. New data is noticed by
   identity, not by an invalidation call.

### Consequences

- ✅ Pages cannot disagree: they read the same object.
- ✅ No sync code, no dirty flags on the owner, no settings copies.
- ✅ Undo returns to what the user saw, because a listener's result (the
  plot's fitted viewport) is folded into the history entry it came from.
- ❌ A page cannot be used without a model. Accepted: a page without the
  tab's controls has nothing to show.

## Worked example

A modeller ticks a series. Before: the manager updated its settings copy,
set two dirty flags, resynced the toolbar, and the plot and the stats page
each rebuilt from their own copy of the selection. After: one `update` on
the model records one history entry; the plot, the stats page and the data
page each receive the old and new state. The plot rebuilds from the model's
display set and fits; the stats page, hidden, marks itself out of date and
rebuilds once when shown, from the same aggregates the plot used.

## Enforcement

- **Structural**: `FlowVizState` is immutable, and a tab's current state is
  held and reachable only through `FlowVizModel`; the pages have no setters
  for it, so a second copy has nowhere to live. `FlowVizModelReplaceTest` and
  `FlowVizModelReleaseTest` pin the history and the release behaviour of §3
  and §4.
- **Advisory**: §4's "holds no data the owner has let go" and the choice to
  apply this shape to a new view with more than one page are held by review.
  The Run Manager's own data side is not yet in this shape.

## Links and references

- Related: [ADR-0003](0003-identity-and-labels.md) — the identity that §5
  keys on.
- Related: [ADR-0015](0015-view-state-is-not-persisted.md) — the owner is
  live state and is not written anywhere.
- Issue: [#456](https://github.com/chasegan/Kalix/issues/456)
- Pull request: [#489](https://github.com/chasegan/Kalix/pull/489)

## Amendments
