---
title: "ADR-0015: Do not persist a model's view state"
---

# ADR-0015: Do not persist a model's view state

- **Status**: Proposed
- **Date**: 2026-10-30
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: ide

## Context and problem statement

Twice in one week a feature raised the question of remembering how a model
was being looked at: which text regions were folded ([#494](https://github.com/chasegan/Kalix/issues/494))
and, days earlier, another piece of per-model view state. Each time the
answer was no. This records the answer so the next feature can cite it.

Three kinds of remembered state are easy to confuse. Application
preferences say how the IDE presents every model: theme, font, node-label
mode, gridlines. The session says which documents were open, and where the
caret was, when the IDE last closed. View state is how one model was being
looked at: its folds, map viewport, selection, panel layout. The question is
only about the third.

## Decision drivers

- Manifesto §2.2 — transparency by default: what you read is what runs. A
  model file that also records how it was last viewed is text that does not
  run, and changes without the model changing.
- Manifesto §2.8 — less is more: nothing to version, migrate or invalidate.
- ADR-0010 §1 — an annotation is a comment the IDE reads. One that records a
  passing view would turn every glance into a diff.

## Considered options

1. **In the model file**, as annotations.
2. **Outside the model file**, in the preference file or a sidecar keyed by
   the model's path.
3. **Not at all.** Live without it, and make the view quick and meaningful
   to reach instead.

## Decision

Chosen option: **3**. A view is cheap to re-establish and costly to carry.

1. **A model file holds the model.** Nothing about how it is viewed is
   written to it, in annotations or otherwise. A diff of a model file shows
   changes to the model.
2. **Application preferences and the session are not view state**, and this
   ADR does not touch them. The preference file keeps how the IDE presents
   every model, and machine-local preferences keep which documents reopen,
   at which caret. That is the IDE remembering where you were, and it is the
   extent of it.
3. **Per-model view state is not persisted.** First live without it. Then
   make the view quick to reach: a Fold All, a region, a Zoom to fit, so
   nobody feels the need to save it.
4. **Persistence needs a strong reason, and a new ADR.** If one arrives, the
   state goes outside the model file, by option 2. Clause 1 is not reopened.

### Consequences

- ✅ A model file changes only when the model does, so version control and
  the eye both see the real change.
- ✅ No per-model store to keep in step with renames, moves and deletions.
- ❌ Folds, the map viewport and the selection are lost between sessions.
  Accepted: each is a few keystrokes to restore, and §3 keeps shortening
  them.

## Worked example

A modeller folds a two-hundred-node model to its regions, closes the IDE, and
reopens the model the next day. It opens unfolded. Fold All, then Unfold on
the region they are working in, and they are back: two keystrokes, nothing
written to the file, no `#@folded = ...` line for a colleague to wonder
about.

## Enforcement

- **Advisory**: review asks of any feature that remembers something, "is
  this the model, a preference, the session, or the view?", and the fourth
  answer is held by this ADR. `kalixide/CLAUDE.md` cites it where folding is
  described.

## Links and references

- Related: [ADR-0010](0010-annotations.md) — annotations are the one place
  the IDE writes its own information into a model file, and §1 bounds it.
- Issue: [#494](https://github.com/chasegan/Kalix/issues/494)

## Amendments

