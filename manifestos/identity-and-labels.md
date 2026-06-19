# Identity and Labels

*How Kalix represents the identity of a thing versus the text shown for it.*

Many things in Kalix have both an **identity** (which thing it is) and a **label**
(what we display for it): runs, nodes, datasets, loaded files, series. These are
not the same, and conflating them is a recurring source of bugs. This manifesto
states how we keep them apart.

## 1. The principle

**Identity is separate from label.** Identity is an opaque, stable, *typed* token.
The human-readable string is a *projection* of that token, computed on demand by a
resolver — never stored, never used as a key, never parsed back into identity.

A label can change (a run is renamed) without the identity changing. An identity
must survive every cosmetic change to its label. So the label cannot *be* the
identity, and identity cannot be recovered by reading a label.

## 2. The rules

1. **Never key a collection by a display string.** A `Map<String, …>` or
   `Set<String>` that holds the identity of a domain object is a bug. Key by the
   typed identity token instead.
2. **Never parse identity out of a label.** No `endsWith(" [Last]")`, no
   `lastIndexOf(" [")`, no `substring` to recover a suffix. If you are reading
   structure out of a display string, the identity was thrown away too early.
3. **Never hand-build a label.** No `name + " [" + run + "]"` at a call site.
   Display strings come only from the resolver that owns that projection.
4. **Identity tokens are opaque and typed.** Prefer a sealed/closed set of identity
   types over a bare string or int, so the compiler — not a convention — rejects
   the illegal cases.

## 3. Why

The label-as-identity design couples *what a thing is* to *what it's called*. Every
rename then has to be propagated, in lockstep, into every map, set, undo frame and
cache that keyed by the old string — miss one and you get stale or orphaned state.
Keying by a stable token instead makes rename a no-op for storage: nothing keyed by
identity changes when the label changes. An entire class of rename / stale-key /
duplicate-key defects becomes *structurally impossible* rather than merely avoided.

## 4. Enforcement and worked example — `SeriesRef`

The run-manager / flowviz subsystem is the reference implementation (see
`kalixide/docs/SERIES_IDENTITY_DATAFLOW.md` for the full dataflow):

- Identity is a sealed `SeriesRef` (`RunSeries(runId, baseName)`,
  `LastSeries(baseName)`, `DatasetSeries(datasetId, baseName)`). Every store keys by
  `SeriesRef`, never by a string.
- `TimeSeriesData` carries no name — identity is supplied externally when data is
  inserted, so the data object cannot smuggle in a second source of truth.
- Labels are produced solely by `LabelResolver.labelFor(ref)` at render time. A run's
  `runId` is stable across `withName`, so renaming re-renders without touching storage.
- `LastSeries` is a pure alias resolved to the underlying `RunSeries` at the pool
  boundary — never a stored key — so "last run" data can never go stale.

This is **structural enforcement**: the sealed types and the ref-only `DataSet` API
mean a violation usually won't compile, and the audit checklist in
`SERIES_IDENTITY_DATAFLOW.md` §"Invariants" catches the rest.

## 5. Scope

The principle binds wherever a domain object has both an identity and a display
name — not only series. New identity types should follow the same shape: a typed,
stable token; storage keyed by it; the label derived, never stored or parsed.

---

*Enforcement: Structural where the type system allows (sealed identity types,
identity-keyed APIs), plus review. When you introduce a new identity/label pair,
apply §2 by construction rather than by vigilance.*
