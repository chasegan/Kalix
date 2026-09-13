---
title: "ADR-0003: Separate identity from labels"
---

# ADR-0003: Separate identity from labels

- **Status**: Accepted
- **Date**: 2026-06-20 (recorded 2026-09-14, retrospectively, from the
  `identity-and-labels` manifesto)
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: ide, engine

## Context and problem statement

Many things in Kalix have both an **identity** (which thing it is) and a
**label** (the text shown for it): runs, nodes, datasets, loaded files,
series. The IDE's run-manager and flowviz subsystems originally conflated
the two — a series was keyed by its display string, and a rename meant
propagating the new string, in lockstep, into every map, set, undo frame and
cache that had keyed by the old one. Miss one and you get stale or orphaned
state. Code also grew habits like `endsWith(" [Last]")` to recover structure
from a label, which is identity being reconstructed after it was thrown away.

The question is how to represent identity so that a whole class of
rename / stale-key / duplicate-key defects becomes structurally impossible
rather than merely avoided by care.

## Decision drivers

- Manifesto §2.6 — clarity off the hot path; use structure and the type
  system to make features robust in the face of future change.
- The IDE's commitment to idiomatic architecture over cleverness (Manifesto
  §2.6): a rename should be a no-op for storage, not a coordinated update.

## Considered options

1. **Label as identity** — key collections by the display string; on rename,
   update every keyed structure. The status quo at the time.
2. **Typed identity token, label derived** — key everything by an opaque,
   stable, typed token; produce labels on demand from a single resolver.

## Decision

Chosen option: **typed identity token, label derived**.

### 1. Principle

**Identity is separate from label.** Identity is an opaque, stable, *typed*
token. The human-readable string is a *projection* of that token, computed
on demand by a resolver — never stored, never used as a key, never parsed
back into identity. A label can change without the identity changing; an
identity must survive every cosmetic change to its label. So the label
cannot *be* the identity, and identity cannot be recovered by reading a
label.

### 2. Rules

1. **Never key a collection by a display string.** A `Map<String, …>` or
   `Set<String>` that holds the identity of a domain object is a bug. Key by
   the typed identity token instead.
2. **Never parse identity out of a label.** No `endsWith(" [Last]")`, no
   `lastIndexOf(" [")`, no `substring` to recover a suffix. If you are
   reading structure out of a display string, the identity was thrown away
   too early.
3. **Never hand-build a label.** No `name + " [" + run + "]"` at a call
   site. Display strings come only from the resolver that owns that
   projection.
4. **Identity tokens are opaque and typed.** Prefer a sealed/closed set of
   identity types over a bare string or int, so the compiler — not a
   convention — rejects the illegal cases.

### 3. Scope

The principle binds wherever a domain object has both an identity and a
display name — not only series. New identity types follow the same shape: a
typed, stable token; storage keyed by it; the label derived, never stored or
parsed.

### Consequences

- ✅ Rename is a no-op for storage: nothing keyed by identity changes when
  the label changes.
- ✅ Stale-key, orphaned-state and duplicate-key defects from renames become
  impossible by construction, not by vigilance.
- ✅ "Last run" can be a pure alias resolved at the boundary and can never go
  stale.
- ❌ One more type per identity kind, and a resolver to maintain. Accepted:
  the type is the enforcement.

## Pros and cons of the options

### Option 1: Label as identity

- ✅ Nothing to design; strings are already everywhere.
- ❌ Every rename is a distributed update across every keyed structure.
- ❌ Structure gets parsed back out of strings, which is fragile and
  invisible to the compiler.
- ❌ Two things with the same label are indistinguishable.

### Option 2: Typed token, label derived

- ✅ The compiler rejects most violations.
- ✅ One resolver owns every display string, so labelling is consistent.
- ❌ Requires a deliberate identity type per domain, and a migration of
  existing string-keyed code.

## Worked example

The run-manager / flowviz subsystem is the reference implementation (full
dataflow in `kalixide/docs/SERIES_IDENTITY_DATAFLOW.md`):

- Identity is a sealed `SeriesRef` — `RunSeries(runId, baseName)`,
  `LastSeries(baseName)`, `DatasetSeries(datasetId, baseName)`. Every store
  keys by `SeriesRef`, never by a string.
- `TimeSeriesData` carries no name — identity is supplied externally when
  data is inserted, so the data object cannot smuggle in a second source of
  truth.
- Labels are produced solely by `LabelResolver.labelFor(ref)` at render
  time. A run's `runId` is stable across `withName`, so renaming re-renders
  without touching storage.
- `LastSeries` is a pure alias resolved to the underlying `RunSeries` at the
  pool boundary — never a stored key — so "last run" data can never go
  stale.

## Enforcement

**Structural** where the type system allows: the sealed identity types and
the ref-only `DataSet` API mean a violation usually won't compile. The audit
checklist in `SERIES_IDENTITY_DATAFLOW.md` §"Invariants" catches the rest.
Rule 3 (never hand-build a label) and the extension of the principle to new
identity kinds (§3) are **Advisory**, held by review.

## Links and references

- Related: `kalixide/docs/SERIES_IDENTITY_DATAFLOW.md`.
- Related: [ADR-0004](0004-performance-on-the-hot-path.md) §3.2 — the same
  discipline on the engine side: resolve identity to indices at setup, never
  carry strings into the loop.

## Amendments

*None.*
