# Run Manager / Flowviz Data Flow — Post-SeriesRef Refactor

A primer for understanding how series identity, data, and labels flow through the Run Manager and the flowviz subsystem. Written for an auditor coming in cold; companion docs are `SERIES_IDENTITY_REFACTOR_PLAN.md` (the migration plan), `SERIES_IDENTITY_PHASE0_SURVEY.md` (the original site inventory), and `RUN_MANAGER_QUALITY_ISSUES.md` (the issue tracker that drove this work).

## The core idea

> The general principle behind this subsystem — *identity is separate from label* —
> is codified as project doctrine in [`manifestos/identity-and-labels.md`](../../manifestos/identity-and-labels.md).
> This document is the descriptive companion: how that principle is wired into the
> Run Manager and flowviz. The manifesto is the rule; this is the implementation.

**Identity is separate from label.** Internally every series is identified by a `SeriesRef` (a sealed type — `RunSeries(runId, baseName)`, `LastSeries(baseName)`, `DatasetSeries(datasetId, baseName)`). The user-visible string ("`node.x.ds_1 [Run_3]`", "`flow [mydata.csv]`") is a *projection* of the ref produced on demand by `LabelResolver`. Renaming a run does not change any ref; it changes how the resolver renders it.

This replaces the previous design in which the string `"node.x.ds_1 [Run_3]"` was the key in every map, set, and undo state. That design coupled identity to label and required propagating renames into ~7 different stores in lockstep.

## Where refs are constructed

Refs are minted at the *producer* boundary:

- `OutputsTreeBuilder` — refs are minted when the outputs tree is built: each `SeriesLeafNode` carries a `public final SeriesRef ref` computed at construction (`RunSeries` / `LastSeries` / `DatasetSeries`). `RunManager.seriesRefForLeaf(SeriesLeafNode)` is now a trivial accessor (`return leaf.ref`) used by `onOutputsTreeSelectionChanged`, `searchAndCollectPaths`, and `getVisibleSeriesKeys` so all read the one ref the leaf was built with.
- `RunInfoImpl` carries a stable `runId` (final, `AtomicLong`-assigned at construction). `RunInfoImpl.withName` preserves the runId across rename — this is the load-bearing fact that makes the whole scheme work.
- `OptimisationPlotManager` uses two static `DatasetSeries("(optimisation)", ...)` constants for its synthetic series.
- `FlowVizDataManager` (the file-based viewer's loader) mints `DatasetSeries(file.absolutePath, "filename: column")` refs via `uniqueRefFor` when a CSV/Pixie file is loaded, and inserts them into the window's `DataSet` through the ref API. `FlowVizWindow` receives the refs via the `DataSetListener` callbacks — it never constructs refs itself.
- File importers (`TimeSeriesCsvImporter`, `PixieReader`) return `NamedSeries(name, data)` pairs — the raw column/series name read from the file, plus nameless data. The name is file *content*, not identity; the consumer (`FlowVizDataManager`, `DatasetLoaderManager`) builds the `SeriesRef` from `(file, name)`.

## Where refs flow (storage and consumers)

After construction, refs flow through these collections:

| Storage | Type | Owner |
|---|---|---|
| `plotDataSet` (the shared pool) | `Map<SeriesRef, TimeSeriesData>` (via `DataSet.seriesByRef`) — `LastSeries` keys are redirected to `RunSeries` by the pool's `LastSeriesResolver`, so storage only ever holds `RunSeries` / `DatasetSeries` keys | `RunManager` |
| `tab.selectedSeries` (per tab) | `Set<SeriesRef>` | `VisualizationTabManager.TabInfo` |
| `sharedColorMap` | `Map<SeriesRef, Color>` | `SeriesColorManager` (reference shared with `VisualizationTabManager`, `PlotPanel`, `TimeSeriesRenderer`, `CoordinateDisplayManager`) |
| `PlotPanel.visibleSeries` | `List<SeriesRef>` | `PlotPanel` (reference shared with `TimeSeriesRenderer`, `CoordinateDisplayManager`, `PlotInteractionManager` via supplier) |
| `PlotState.visibleSeries` | `List<SeriesRef>` | `PlotStateHistory` (undo/redo) |
| `StatsTableModel.seriesData` (`SeriesStats.ref`) | row identity by ref | `StatsTableModel` |
| `StatsTableModel.originalSeriesCache` | `Map<SeriesRef, TimeSeriesData>` | `StatsTableModel` |
| `TimeSeriesRenderer.seriesRenderModes` | `Map<SeriesRef, SeriesRenderMode>` | `TimeSeriesRenderer` |
| `LODManager.lodCache` | string key built from `ref.toString() + viewport` | `LODManager` |

`TimeSeriesData` itself has no identity — it carries timestamps + values only. There is no `name` field and no named constructor; identity is supplied externally as a `SeriesRef` when the data is inserted into a `DataSet`.

## Label projection — `LabelResolver`

A single resolver, constructed in `RunManager` (`new DefaultLabelResolver(this::runNameForId)`), is wired down to:

- `VisualizationTabManager.setLabelResolver` (called once during RunManager init)
- → each `PlotPanel.setLabelResolver`, which forwards to `PlotLegendManager.setLabelResolver` (the legend is the only label-rendering surface on a PlotPanel)
- → each `StatsTableModel.setLabelResolver`
- `PlotInteractionManager` gets the resolver via a supplier (`setLabelResolverSupplier`) — used by the CSV-export path to produce column headers.

`CoordinateDisplayManager` does **not** receive a resolver: the hover overlay renders time/value only, no series label. (Earlier revisions carried a dead `labelResolver` field there; it has been removed.)

`OptimisationPlotManager` and `FlowVizWindow` construct their `PlotPanel`s outside the `RunManager` → `VisualizationTabManager` wiring, so each installs its own small lambda resolver directly (both project `DatasetSeries` → `baseName`, since their synthetic/file series carry the display name in the baseName already).

All UI surfaces that need a string call `labelResolver.labelFor(ref)` at render time. Run name lookup is `O(runs)` linear over `sessionToTreeNode.values()`; the run count is small.

Rename consequences: a run's `runId` is stable, so the resolver returns a new display string after `RunInfoImpl.withName` swaps the tree node's user object. No collection mutation required.

## `[Last]` resolution

`LastSeries(baseName)` is a *true alias*, not an identity. The pool (`DataSet`) **never stores data under a `LastSeries` key**: a `LastSeriesResolver` installed on `plotDataSet` (in `RunManager.initializeManagers`) redirects every `LastSeries` access — `addSeries`, `getSeries`, `hasSeries`, `removeSeries` — to `RunSeries(lastRunInfo.runId, baseName)`. So "Last" data lives under the underlying run's stable identity, indistinguishable from that run being selected directly.

Consequences:

- **Stale Last data is structurally impossible.** There is no `LastSeries` entry to go stale. When the Last run changes, a `LastSeries` ref simply resolves to a different `RunSeries`; old data remains valid under its own `RunSeries` key.
- `RunManager.refreshLastSeries` still runs on each `updateLastRun` — its job is now only to *populate* the new `RunSeries(newLastId, baseName)` target if it isn't in the pool yet (fetch from `lastRunInfo`'s session). It no longer evicts or overwrites anything.
- The resolver returns `null` when no Last is set; the access then falls through to the bare `LastSeries` key (absent from storage — reads yield `null`, the intended "no Last" signal).

When a new run becomes Last:

1. `updateLastRun` increments `lastRunGeneration` (volatile long).
2. `refreshLastSeries` iterates `tabManager.getAllSelectedSeriesAcrossTabs()`, filters for `instanceof LastSeries`, captures `lastRunGeneration`, and fetches data for each into the pool (which lands under the new `RunSeries` via the resolver).
3. Async responses check `capturedGeneration == lastRunGeneration` before writing — a stale response would otherwise be written under the *new* Last's `RunSeries` key, so the guard is still required.
4. `onOutputsTreeSelectionChanged`'s async callback applies the same guard, but only to `instanceof LastSeries` entries in the captured batch.

## Run rename — what now happens

`RunManager.renameRun(RunInfo, String)`:

1. Validate (non-empty, no duplicate; `"Last"` is no longer reserved — it's a typed variant and can't collide).
2. `newRunInfo = oldRunInfo.withName(newName)` — same `runId`.
3. Swap tree node user object, fire `treeModel.nodeChanged`.
4. Update `sessionToRunName` and (if applicable) `lastRunInfo`.
5. Rebuild outputs tree under `isUpdatingSelection` guard, restore selection.
6. `tabManager.updateAllTabs(false)` for a repaint.

That's the whole operation. No propagation. The pool, color map, undo history, stats rows, and tab selections all key by ref — they don't change at all.

## Design notes & boundaries

There is no longer any legacy string-keyed code path — the file-import bridge was retired. A few design points worth knowing:

- **`DataSet` is purely ref-keyed.** A single `Map<SeriesRef, TimeSeriesData> seriesByRef` is the only storage. There is no string-keyed API and no second list. `addSeries`/`getSeries`/`hasSeries`/`removeSeries` all take a `SeriesRef`.
- **File-import boundary — `NamedSeries`.** Importers cannot know a series' `SeriesRef` (that depends on which file/run the consumer is loading into), so they return `NamedSeries(name, data)` — the raw file column name plus nameless data. This is a clean boundary, not a bridge: the name is genuine file content; the consumer constructs the ref. Likewise `PixieWriter.writeToFile` takes `List<NamedSeries>` because the `.pxt` metadata format stores a per-series name.
- **`DataSetListener` is ref-typed** — `onSeriesAdded(SeriesRef, TimeSeriesData)`, `onSeriesRemoved(SeriesRef)`, `onDataChanged()`. The ref-keyed `addSeries`/`removeSeries` fire it. Only `FlowVizWindow` subscribes.
- **`OutputsTreeBuilder.SeriesLeafNode`** carries a `public final SeriesRef ref` computed at construction. It also keeps a `source` object (`RunInfoImpl | LoadedDatasetInfo`) for callers that need session lookup. `RunManager.seriesRefForLeaf` is `return leaf.ref`.
- **`RunInfoImpl.withName`** is the only `withName` pattern in the codebase; it's load-bearing for rename semantics — it preserves `runId`.

## Invariants an audit should verify

1. **No identity-as-string anywhere internal.** Any `Map<String, ...>` or `Set<String>` that holds series identity is a bug. (The `name` in `NamedSeries` is *not* identity — it is a raw file column header at the import boundary, consumed immediately to build a ref.)
2. **No string parsing of series keys.** No `endsWith(" [Last]")`, no `lastIndexOf(" [")`, no `substring` to extract a suffix. None exist; none should be reintroduced.
3. **Labels only come from `LabelResolver`.** UI string construction like `seriesName + " [" + runName + "]"` is a bug. Greps for `" [" +` are useful.
4. **`runId` is preserved by rename.** `RunInfoImpl.withName` returns a new instance with the same `runId`. The constructor that accepts an explicit runId is `private`; the public `(String, KalixSession)` constructor allocates a fresh id.
5. **Pool reads and writes go through the ref API.** `DataSet` has no string-keyed API — `addSeries(ref, data)` / `getSeries(ref)` only.
6. **`LastSeries` is a pool-level alias, never a stored key.** `DataSet`'s `LastSeriesResolver` must redirect every `LastSeries` access to the current `RunSeries`. Any code path that lets a `LastSeries` ref reach `seriesByRef` as a literal key is a bug — it reintroduces the stale-Last class of defect.

## Known foot-guns for the auditor

- `LabelResolver` wiring is set-up-time only — plumbed from RunManager → VisualizationTabManager → PlotPanel/StatsTableModel during init. `OptimisationPlotManager` and `FlowVizWindow` build their own `PlotPanel`s outside that path and install their own lambda resolvers. If some *other* code path constructs a `PlotPanel`/`StatsTableModel` and installs no resolver, the legend / stats column 0 falls through to `String.valueOf(ref)` and shows `"RunSeries[runId=…, baseName=…]"`. That's a wiring bug, not a label bug.
- The `LODManager` cache key uses `ref.toString()`. Record `toString()` is stable and value-equal so this is fine, but anyone introducing a new `SeriesRef` variant must ensure the toString format is unambiguous.
- `OptimisationPlotManager` uses `"(optimisation)"` as a sentinel datasetId. Treat as a known intentional fiction.
- `FlowVizDataManager.uniqueRefFor` appends `" (2)"`, `" (3)"`… to the baseName when a `DatasetSeries` ref is already in the pool (same file loaded twice, or colliding display labels). The disambiguation lives in the baseName, so the ref stays unique.

## Files at a glance

| Area | Files |
|---|---|
| Types | `flowviz/data/SeriesRef.java`, `RunSeries.java`, `LastSeries.java`, `DatasetSeries.java`, `LabelResolver.java`, `DefaultLabelResolver.java` |
| Data | `flowviz/data/DataSet.java` (ref-keyed), `TimeSeriesData.java` (nameless) |
| Identity | `models/RunInfoImpl.java` (runId, withName) |
| Producer | `windows/RunManager.java` (onOutputsTreeSelectionChanged, seriesRefForLeaf, refreshLastSeries, renameRun) |
| Tabs | `windows/VisualizationTabManager.java` (TabInfo, tab.selectedSeries, sharedColorMap wiring) |
| Plot pipeline | `flowviz/PlotPanel.java`, `flowviz/rendering/TimeSeriesRenderer.java`, `LODManager.java` |
| Legend | `flowviz/PlotLegendManager.java` (renders via LabelResolver) |
| Stats | `flowviz/models/StatsTableModel.java` (ref-keyed; column 0 via LabelResolver) |
| Undo | `flowviz/PlotState.java` (List<SeriesRef>) |
| Color | `managers/SeriesColorManager.java` (Map<SeriesRef, Color>) |
| Transforms | `flowviz/transform/TimeSeriesAggregator.java`, `PlotTypeTransformer.java`, `flowviz/stats/TimeSeriesMasker.java` (all return nameless data) |
| Ref minting | `managers/OutputsTreeBuilder.java` (`SeriesLeafNode.ref`); `FlowVizDataManager.uniqueRefFor` |
| File-import boundary | `io/NamedSeries.java`, `io/TimeSeriesCsvImporter.java`, `io/PixieReader.java`, `io/PixieWriter.java` |
| FlowViz viewer | `flowviz/FlowVizWindow.java`, `flowviz/FlowVizDataManager.java`, `flowviz/DataPanel.java` (all ref-keyed) |
