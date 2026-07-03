# KalixIDE Full Review — July 2026 (Fable 5)

A whole-of-IDE review: core wiring plus nine subsystem deep-dives (editor, linter,
FlowViz, run manager/CLI, map/schematic, themes/preferences, IO, optimisation,
workspace/shell). Every finding below was verified by reading the code; the two
IO corruption bugs were additionally **reproduced by executing the code**.

Grades are health assessments of each subsystem as found:

| Subsystem | Grade | One-line assessment |
|---|---|---|
| Workspace/file tree & new utils | B+ | Newest, strongest code; lives up to the ethos |
| FlowViz plotting | B | Strong bones; interaction hot paths leave speed on the table |
| Map/schematic | B− | Interaction math solid; regex text-sync fragile at edges |
| Core wiring (KalixIDE) | B− | Decent shape; toolbar-rebuild bug, EDT gaps, comment drift |
| Editor stack | C+ | Good architecture; duplication bred real bugs; lifecycle leaks |
| Linter | C+ | Sound pipeline; ~600 lines dead weight; quiet correctness defects |
| Run manager / CLI sessions | C+ | Excellent identity model atop buggy process lifecycle layer |
| Optimisation | C | Happy path fine; every unhappy path broken; over-decomposed |
| IO (CSV/Pixie/Gorilla) | C | res.csv code excellent; two confirmed data-corruption bugs below it |
| Themes & preferences | D | Two architectures fused mid-migration; rescue plan below |

---

## P0 — Data corruption / data loss (fix first, in any order)

1. **Gorilla decoder corrupts all values after a non-canonical NaN** —
   `GorillaCompressor.java:430` (double), `:536` (float); also `:182–226`.
   Decoder re-derives its XOR base via `Double.doubleToLongBits(value)`, which
   canonicalizes NaN payloads; Rust uses raw bits. A Rust-encoded stream with a
   `0xFFF8…` NaN (x86 `0.0/0.0`) desyncs the chain — **reproduced: 1.5 decodes
   as −1.5**, cascading to end of series. The cross-language fixture misses it
   because its NaN is canonical. Fix: `doubleToRawLongBits`/`floatToRawIntBits`
   everywhere; carry `long` bit-state end-to-end; add a payload-NaN case to the
   shared fixture **on both sides**. (Same bug class as the July 2026 Rust
   Gorilla findings.)

2. **Every Pixie file saved from the plot's "Save Data" is corrupt** —
   `PixieWriter.java:106–173`, read side `PixieReader.java:243`.
   Writer feeds epoch-*millisecond* timestamps into the epoch-*seconds*
   convention; `formatTimestamp` multiplies ms by 1000 again. **Reproduced:
   2020-01-01 daily data reloads as year +51969.** Side effects: regular-timestep
   fast path never triggers (worse compression); ms-scale deltas overflow the
   32-bit delta-of-deltas after a 24.8-day gap. `PixieRoundTripTest` masks it by
   never asserting timestamps. Fix: convert to seconds at the writer boundary;
   make `detectTimestep` consistent; extend the round-trip test to assert
   timestamps against a Rust-written fixture file.

3. **32-bit delta-of-deltas silently truncates — shared with Rust** —
   `GorillaCompressor.java:259–261`, mirrored at `gorilla.rs:249–250`.
   |dod| ≥ 2³¹ drops top bits, decodes sign-extended → silently wrong
   timestamps. Fix (same commit both sides): range-check and throw, or add a
   64-bit escape code.

4. **Locale-sensitive coordinate writes corrupt the INI** —
   `TextCoordinateUpdater.java:101–102`, `MapClipboardManager.java:351`,
   `MapContextMenuManager.java:188`. `String.format("%.2f")` under comma-decimal
   locales writes `loc = 123,45, 678,90`, which re-parses as x=123, y=45 — the
   node silently jumps. Fix: `Locale.ROOT` at all three sites; audit all numeric
   `format` calls that feed file content.

5. **Preference file corruption paths** — `PreferenceManager.java:263–277,
   398–444`. (a) Non-atomic `Files.writeString` — crash mid-write loses all
   file preferences (load swallows the error, starts empty). (b) Hand-rolled
   JSON writer does **no escaping** — a quote/newline/backslash in a free-text
   preference (external editor command; Windows paths) silently destroys the
   file on next load. `PaletteCodec.java:14–18` already documents contorting
   itself around this. (c) Save-per-keystroke from PreferencesDialog free-text
   fields maximizes the corruption window. Fix: temp-file + `ATOMIC_MOVE`,
   proper escaping (still dependency-free), save on focus-lost/close.

6. **Decimal-comma CSV values silently ×10** — `TimeSeriesCsvImporter.java:578`.
   Importer auto-detects `;` delimiters (European dialect), then strips commas
   from values: `1,5` → `15.0`, no warning. Fix: strip only thousands-grouping
   patterns; when delimiter is `;`, treat `,` as decimal separator.

7. **Tree rename can silently overwrite a sibling** —
   `TreeFileOperations.java:107–121`. POSIX `renameTo` replaces an existing
   target: renaming `a.ini` → `b.ini` destroys an existing `b.ini` (Windows
   fails instead — inconsistent). Fix: `target.exists()` check (as `duplicate`
   already does) + `Files.move` for real error messages.

---

## P1 — High-impact correctness bugs

### Process & session lifecycle (run manager / CLI)
8. **Run startup rests on `Thread.sleep(500)`** — `StdioTaskManager.java:116`,
   `RunModelProgram.java:89–197`. The program treats *any* `rdy` as
   "model loaded" and *any* `res` as "simulation complete" without checking
   `cmd`. On a slow CLI start the run is marked COMPLETED with no outputs.
   `OptimisationProgram` already solved this (`WAITING_FOR_INITIAL_READY` +
   per-command result matching) — mirror it, then delete the sleep (also −500ms
   latency per run).
9. **stderr heuristic wedges and orphans kalixcli** — `SessionManager.java:330–425`.
   Any stderr line matching `error:`/`failed to`/`exception` marks the session
   ERROR, which stops draining pipes **without killing the process** — CLI
   blocks forever on a full pipe, invisible. Fix: on ERROR keep draining to EOF
   but kill the process; decide failure from exit code / protocol `err`, not
   stderr substrings.
10. **Cancel doesn't exist — on either side** — `JsonStdioProtocol.java:96–136`
    (`createStopMessage`/`createTerminateMessage`: zero callers), IDE-side
    mirror of the backend dead-interrupt finding. Force-kill discards
    best-so-far optimisation results. Fix: Stop action sending `stp`, handle
    `rdy(rc=2)`; `terminateSession` tries `term` before force-kill. Both program
    state machines already have `STOPPED` cases waiting for messages that can
    never come.
11. **Backend error result breaks all optimisation completion handling** —
    `OptimisationProgram.java:376` / `OptimisationEventHandlers.java:142`.
    `"ERROR: …"` fed to `mapper.readTree()` throws; end-time, status,
    progress-bar cleanup all skipped; elapsed timer ticks forever; real error
    text lost. Fix: structured success/failure result callback.
12. **Stopped/crashed optimisations show "Optimising" forever** —
    `OptimisationInfo.java:44–64`, `OptimisationWindowInitializer.java:284–287`.
    `getStatus()` consults the still-OPTIMISING program first; STOPPED branch
    unreachable; no SessionEvent listener registered (unlike RunManager), so
    crashes are invisible. Fix: transition program state on terminate and on
    TERMINATED/ERROR events.
13. **Removing a never-run optimisation leaks a live kalixcli** —
    `OptimisationWindowInitializer.java:273–276`. "New" spawns a session
    immediately; remove only terminates when status == RUNNING. Fix: always
    terminate unless already TERMINATED/ERROR.
14. **Removing a run leaks its data everywhere except the tree** —
    `RunContextMenuManager.java:484–510`, `TimeSeriesRequestManager.java:79/199`.
    Plot pool, completed-fetch cache, series slots all retain the run's
    multi-decade `double[]`s; terminated sessions stay in `activeSessions` with
    full model text. Fix: a `removeRunData(...)` counterpart to the (already
    correct) `removeLoadedDataset`.
15. **Pending time-series futures never fail on session death** —
    `TimeSeriesRequestManager.java:119–156, 249–281`. Permanent "Loading…" that
    also blocks any retry (stuck cache entry); plus a staleness race writing a
    previous run's data into the cache after `clearCacheForSession`. Fix: fail
    pending futures on TERMINATED/ERROR; add request timeout; drop responses
    whose future was cancelled.
16. **kalixcli orphaned on exit paths bypassing `exitApplication`** —
    `KalixIDE.java:1538` (`clearAppData` → `System.exit`), JVM crash/kill.
    Fix: `Runtime.addShutdownHook(sessionManager::shutdown)`.
17. **Terminate usually reports a spurious ERROR** — `SessionManager.java:247–259`
    vs `:590`. Monitor threads' `IOException` on reader close overwrites
    TERMINATED with ERROR. Fix: `handleSessionError` no-ops when already
    TERMINATED/closed.

### Editor & sync
18. **Find/Replace dialogs clobber each other's fields** —
    `TextSearchManager.java:220–257` vs `:317`. After opening Replace once, Find
    searches text from the hidden Replace dialog's field. Fix: per-dialog fields
    or one shared physical panel. (Also: both dialogs are modal — the editor
    can't be clicked to reposition the search origin; make non-modal.)
19. **Alias sanitisation contradicts the Rust engine** —
    `CommandExecutor.java:666`: replaces before lowercasing, so `MyData.csv` →
    `__ata_csv` (engine: `mydata_csv`). Three divergent Java copies of this
    engine-owned rule (also `DataSourceHeaderReader.cleanseName`). Fix:
    `toLowerCase()` first; **one shared utility** matching
    `src/misc/misc_functions.rs:126`.
20. **Node rename corrupts lines via unanchored `String.replace`** —
    `EnhancedTextEditor.java:1105` fed by `CommandExecutor.findNodeReferences`.
    Renaming node `s` on `ds_1 = s` yields `dx_1 = x`. Fix: carry match offsets
    in `TextReplacement`, replace ranges.
21. **Every closed tab leaks its editor graph** — `EnhancedTextEditor.java:159–166`
    (global Toolkit `AWTEventListener` never removed), `KalixIDE.java:700–709`
    (close never calls `editor.dispose()`). Same shape in the linter:
    `ValidationEventManager`/tooltip managers' `dispose()` don't remove their
    listeners → orphan fires per keystroke after re-init. Fix: a disposal chain
    from `DocumentManager.closeDocument` down; every `dispose()` must detach
    what it attached.
22. **Map→text sync regex disagrees with the parser grammar** —
    `TextCoordinateUpdater.java:94`, `MapClipboardManager.java:236`.
    `[` in a section comment silently breaks drag write-back; `# loc =` or keys
    ending in "loc" get rewritten; indented headers parse but can't be dragged;
    duplicate node names: parser honours last section, updater rewrites first.
    Fix: one shared `NodeSectionLocator` (line-anchored, same trimming rules as
    `ModelParser`) used by updater, clipboard, and editor scroll-to-node.
23. **Deleting a node leaves dangling `ds_N` references** —
    `TextCoordinateUpdater.deleteSelectedElements`, `MapClipboardManager.cut`.
    Invisible in IDE; engine error on load. Fix: collect `ds_\d+ = <name>` lines
    in other sections when deleting nodes.
24. **`endDrag` final-position update is dead code** —
    `MapInteractionManager.java:186–189` (`isDragging=false` before
    `updateDrag`). Reorder.
25. **`toggleComment` mis-restores selections spanning 3+ lines** —
    `EnhancedTextEditor.java:1001–1006` (stale coordinates vs edited document).
26. **Diff view corrupts text containing `~`** — `DiffWindow.java:41–42` (in-band
    marker). Use positions or an improbable sentinel.
27. **Copy-pasted wrong dialogs in alias commands** —
    `RenameInputFileAliasCommand.java:88–98` ("Rename Node" for a file alias),
    `AddInputFileAliasCommand.java:87–97` (pre-fills the *path* as alias, which
    #19 then mangles).

### Shell, themes, preferences
28. **Dark themes are built on the light FlatLaf base** —
    `ThemeCompatibilityAdapter.java:21–23`: sets `@dark`; FlatLaf only reads
    `@baseTheme`. Dracula/One Dark/Obsidian inherit light defaults everywhere
    they don't override, and `isDark()` reports light. One-line fix.
29. **Slider/spinner/radio colors silently discarded for every theme** —
    `ExactColorTheme.java:66–82`: no component builder copies them through.
    Fix: emit unmatched keys verbatim (builders supply fallbacks only).
30. **Opening Preferences permanently breaks tooltips app-wide** —
    `PreferencesDialog.java:98–100` clobbers the global `ToolTipManager` dismiss
    delay (4000 → 500ms) forever. Remove.
31. **Theme-switch animation runs before the UI updates** —
    `managers/ThemeManager.java:105–106`. FlatLaf order: snapshot → set LaF →
    `FlatLaf.updateUI()` → hide snapshot. Also `configureFlatLafProperties()`
    (arcs, resolved tab colors) runs only at startup — stale after a switch
    (`ThemeManager.java:191–193`); and a failed switch leaves the snapshot
    frozen (`:95–102`, missing `finally`).
32. **`updateToolBar()` forgets `fileTreeToggleButton`** — `KalixIDE.java:1935`.
    After one theme change the visible toggle is orphaned; sync updates the old
    detached button.
33. **Save All shows Save-As for the active document instead of each untitled
    one** — `FileOperationsManager.java:302, 186–190`. Fix:
    `saveAsModel(document)`.
34. **"Clear App Data" asks twice and doesn't clear most app data** —
    `PreferencesDialog.java:994–1018` + `KalixIDE.java:1489–1509` (clears only
    one of two OS nodes, never the JSON file). Also sleeps 1s on the EDT.
35. **`updateStatus` mutates Swing off the EDT** — `KalixIDE.java:899`; called
    from monitor threads (`SessionManager.java:397`), programs, `VersionChecker`
    (`VersionChecker.java:69–99`). Fix once: marshal inside `updateStatus`.
36. **External editor command splits on whitespace after substitution** —
    `KalixIDE.openExternalEditor` (~:1884). Spaced paths break. Tokenize
    template first, substitute per-token.
37. **Watcher/tree path identity never canonicalized** —
    `ProjectTree.java:461–486`, `FileWatcherManager.java:143–145`. Symlinked
    project roots (macOS `/tmp` → `/private/tmp`) silently stop live-updating.
    Fix: `toRealPath()` at watch start; canonicalize event paths.
38. **"Expand children" recurses forever on symlink cycles** —
    `ProjectTree.java:346–354`. Skip symlinked dirs / track canonical paths.
39. **OVERFLOW recovery syncs only root's immediate children** —
    `ProjectTree.java:400–403`: comment promises `resyncLoadedSubtree(root)`
    (exists at :146); code calls `resyncDirectory(root)`. One-word fix.
40. **`JsonUtils.flattenJson` corrupts string values** — `JsonUtils.java:26–31`
    (whitespace collapsed inside literals). Flatten via Jackson.
41. **Editor rename mechanism duplicated in map path** + **global undo/redo uses
    CTRL on macOS** — `KalixIDE.java:830–860` (use
    `getMenuShortcutKeyMaskEx()`), `cmdF4` built with CTRL mask (`:878`);
    `EnhancedTextEditor.java:719–797` binds both META and CTRL on all platforms
    (Cmd+H collides with macOS Hide); map panel hand-rolls a `KeyListener`
    (`MapPanel.java:814–902`) instead of InputMap/ActionMap.

### Linter & validation
42. **Next-error navigation skips/backtracks** — `ErrorNavigationManager.java:30–49`
    (list not line-sorted). Sort before scanning.
43. **Unclosed `[` in a reference validates clean** —
    `FunctionExpressionValidator.java:463–479`. Throw on missing `]`.
44. **CRLF files: blank lines don't terminate continuation chains** —
    `INIModelParser.java:106–110, 258`. Strip trailing `\r` per line.
45. **Rule toggles that do nothing; toggling never revalidates** —
    `LinterPreferencesPanel` + `NodeValidator.java:154–161` +
    `SchemaManager.java:110–130`. Honor the schema or trim it; `validateNow()`
    after preference changes.
46. **Hover tooltips hold a stale schema after reload** —
    `PropertyHoverTooltipManager.java:58–64`. Inject a `Supplier<LinterSchema>`.
47. **`issuesByLine` keeps one issue per line** — `LinterManager.java:127–132`.
    `Map<Integer, List<ValidationIssue>>`.
48. **`currentValidationResult` not volatile** — `LinterOrchestrator.java:24,70,97`
    (JMM visibility). Same class of bug in `RunModelProgram`/`OptimisationProgram`
    state fields and `OptimisationSessionManager`'s five plain HashMaps mutated
    off-EDT.

### FlowViz & data
49. **`setVisibleSeries` doesn't rebuild the display dataset** —
    `PlotPanel.java:254`. Re-checked series can silently not render in the
    standalone window. Make mutators self-invalidating.
50. **POINTS render mode ignored above the LOD threshold** —
    `TimeSeriesRenderer.java:122–130`. Scatter clouds (optimisation
    populations) render as connected envelope lines.
51. **Daily aggregation treats a partial final day as complete** —
    `TimeSeriesAggregator.java:130–133`. Under-reports the last day.
52. **Exact-match viewport end drops the last point (irregular series)** —
    `TimeSeriesData.java:293–319`. Return `mid+1` on exact match, `!findFirst`.
53. **Zoom-rect minimum uses `&&` for "at least 5x5"** —
    `PlotInteractionManager.java:511`. A 200×1px sliver zooms Y to a broken
    range. Use `||`.
54. **Exceedance plots with NaNs zoom past 101%** — `PlotTypeTransformer.java:343`.
    Drop invalid points from exceedance output.
55. **Dataset sanitisation collapses distinct columns** —
    `DatasetLoaderManager.java:625–670`: `Flow (ML)` and `Flow [ML]` both →
    `Flow__ML_`; second silently overwrites. Disambiguate or warn.
56. **Optimisation params arriving resets the whole form** —
    `OptimisationWindow.java:425–448` reloads all three panels, reverting
    in-flight edits. Update only the parameters panel.
57. **Parameter Sheet: filter keystroke discards pending edits** —
    `ParameterSheetTableModel.java:45–49`. Prompt on `hasDirtyValues()` or key
    dirty state by (node, propertyKey).
58. **Start button bypasses all GUI validation** — `OptimisationWindow.java:556–570`;
    run-path validator is `contains("[optimisation]")` substring matching.
59. **Optimisation startup race** — `OptimisationSessionManager.java:128–150`:
    program installed after monitoring starts; early `rdy` wedges at "Waiting
    for CLI". Install program before monitoring.
60. **`main()` About/Shortcuts dialogs show literal `\n`** —
    `FlowVizActionManager.java:196–227` (`\\n` escapes); popup menus shown only
    on `mousePressed` via `isRightMouseButton` (`PlotInteractionManager.java:192`)
    — use `isPopupTrigger()` in press+release.

---

## P2 — Performance (ordered by user-felt impact)

61. **Auto-Y pan scans every point of every series per mouse event** —
    `PlotInteractionManager.java:595` ignores `getIndexRange`. ~15M compares
    per drag tick at 3×5M points, with auto-Y on by default. *The* fix for the
    "millions of points" promise.
62. **LOD cache misses every pan frame and disables itself at 100 entries** —
    `LODManager.java:97–114`. Quantise the key + LRU/clear-on-full; for
    contiguous series compute bands in O(plotWidth) arithmetic.
63. **Monitor loops add 16ms latency per protocol line** —
    `SessionManager.java:341, 360`. Reads already block; delete both sleeps.
    Caps `get_result` streams at ~60 msgs/s and taxes optimisation progress.
64. **20MB+ results parsed → re-serialized (new `ObjectMapper` per call) →
    re-parsed** — `SessionManager.java:430–452`, `TimeSeriesRequestManager.java:287`.
    Pass the parsed message (or raw line) through.
65. **Convergence plot rebuilt + re-zoomed per progress tick** —
    `OptimisationPlotManager.java:86–116` (O(n²) per run; also prevents
    inspecting the plot mid-run). Append-only + throttle; preserve zoom.
    *Directly relevant to perf/optimiser throughput work.*
66. **CSV exporter O(rows²×series)** — `TimeSeriesCsvExporter.java:291–302`
    linear scan per timestamp; `SourceResCsvExporter.java:175` already fixed
    this. Share a k-way merge.
67. **CSV importer: exception-per-row date parsing + whole-file + boxed Doubles**
    — `TimeSeriesCsvImporter.java:298–382, 550–567`. ~1M thrown exceptions on a
    date-only million-row file. Adopt the `SourceResCsvImporter` structure
    (streaming, primitives, resolve date-only once).
68. **Gorilla bit I/O bit-at-a-time with boxed Boolean** —
    `GorillaCompressor.java:109–165`. Rust twin already chunked
    (`gorilla.rs:67–87`); port is mechanical and fixture-protected.
69. **Session Manager scans all system processes on the EDT every 2s forever
    once opened** — `SessionManagerWindow.java:124, 304–345` (`HIDE_ON_CLOSE`
    means `windowClosed` never fires). Tie the timer to visibility; scan off-EDT.
    Same dead-`windowClosed` pattern in `RunManager.java:573–580`.
70. **Map: full-panel repaint per idle mouseMoved** — `MapPanel.java:269–283`
    (coordinate overlay). Repaint the overlay clip only. Also per-node
    Stroke/Font/`Color.decode` allocation per frame (`MapRenderer.java:342–452`,
    `NodeTheme.java:347–362`); `SpatialIndex` maintained but only queried by
    dead test drivers — use it for culling/hit-tests or delete it.
71. **Drag commit queues 2×N full re-parses** — `KalixDocument.java:81–96`.
    Coalesce with a dirty flag; same for per-keystroke parse.
72. **Editor: full `getText()` per keystroke for dirty-tracking; model re-parsed
    ~3× per right-click; recursive dir scan on EDT per autocomplete popup** —
    `EnhancedTextEditor.java:1173`, `KalixDocument.java:143–151`,
    `KalixCompletionProvider.java:351–393`. Length-check first; memoize parse on
    modification count; cache the file listing off-EDT (pattern exists in
    `InputDataRegistry`).
73. **Hover tooltips copy+split the whole document per mouse move** —
    `PropertyHoverTooltipManager.java:173–175`. Cheap line check first; full
    analyze only inside the dwell timer.
74. **Linter hot-path waste** — `NodeTypeDefinition.java:20–26` (merged HashSet
    per property per pass — precompute), `String.matches` recompilation
    (`FunctionExpressionValidator.java:226–255`), defensive map copies per call
    (`LinterSchema.java:269–272`), new `javax.swing.Timer` per keystroke
    (`ValidationEventManager.java:57–73` — use `restart()`).
75. **Plot transforms round-trip long[]→LocalDateTime[]→long[]** —
    `PlotTypeTransformer.java:511–520` (6 call sites; one-line fixes — the
    primitive ctor exists). Boxed HashMap merges → two-pointer primitive merge
    (pattern in `TimeSeriesMasker`). Boxed aggregation pipeline
    (`TimeSeriesAggregator.java:104–118`): daily bucketing is an integer
    division.
76. **Multi-series file load: full rebuild + re-zoom per series** —
    `FlowVizWindow.java:342–360`. Batch; one `setDataSet` per file.
77. **Undo restores the plot via 5 full pipeline rebuilds** —
    `PlotPanel.java:930–956`. Set fields directly, rebuild once.
78. **Tree file ops on the EDT** — `TreeFileOperations.java:229–356` (recursive
    delete/copy/move), `WindowsShellReveal.java:59` (3s join), model I/O
    (`FileOperationsManager.java:127–272`). `TerminalActions.launchAsync` is the
    house pattern — apply it. `FILE_ORDER` comparator stats disk per comparison
    (`FileTreeNode.java:24–31`).

---

## P3 — Architecture & design

### Theme system (grade D → rescue plan)
The ten palettes are the protected asset; the machinery is the mess. Verified
diagnosis: a semantic-palette generation layer (`ColorPalette`,
`ApplicationThemeSpec`, `NodeThemeSpec`) runs on every theme build and is 100%
overwritten by the exact-color maps; five parallel per-theme registries with
three keying conventions ("Vibrant" default names a nonexistent theme;
`Botanical` is light-flagged in `DarkThemeDefinitions`); propagation
hand-enumerates concrete classes; one "theme" is four unlinked color systems
(FlatLaf/node/syntax/plot) coordinated by three combo boxes.

Phased rescue (each independently shippable):
- **Phase 0** — bugs #28–31 (+ tooltip #30). ~A day, visible payoff.
- **Phase 1** — delete the dead layer (`NodeThemeSpec`, `ColorPalette`,
  `ApplicationThemeSpec`, 10 component builders; ~400 lines), snapshot-test the
  generated properties byte-identical before/after.
- **Phase 2** — one `KalixTheme` record + `ThemeRegistry` (stable ids, not
  display names; one-time pref migration); node/syntax default to "Follow
  application theme" with optional override.
- **Phase 3** — re-express themes as sparse overrides on real
  `FlatLightLaf`/`FlatDarkLaf` bases (90-key tables → ~25 keys).
- **Phase 4** — listener-based propagation (`FlatLaf.updateUI()` + weak
  `ThemeListener`s); **Phase 5 (optional)** — externalize to
  `resources/themes/*.properties` (transparency ethos: themes as inspectable
  text).

### Consolidations that eliminate bug classes
- **One INI-section grammar** (`NodeSectionLocator`) shared by `ModelParser`,
  `TextCoordinateUpdater`, `MapClipboardManager`, `EnhancedTextEditor` — kills
  findings #22, #3-map, #13-map at the root.
- **One engine-matching sanitize utility** (vs `sanitize_name` in Rust) — kills
  #19 and collation drift; check linter/run-manager/plotting against it.
- **One session-tree tracker** shared by RunManager and the optimisation stack —
  they parallel-implement the same bookkeeping and each has bugs the other
  doesn't (#12 exists only because the optimisation side lacks the session-event
  listener RunManager has).
- **Cleanup symmetry**: run teardown centralized like `removeLoadedDataset`
  already is (#14).
- **Preference keys as typed objects** (`Pref.osInt("ui.treeWidth", 250)`) —
  fixes the file/OS-tier confusion, duplicate defaults, and type gaps
  (`PreferenceKeys.java:62–80`).
- **Timestamp units by name/type at every io boundary** (`epochSeconds` vs
  `epochMillis`) — #2 could not have been written with honest names.

### Decomposition
- **RunManager (1,764 lines) → `RunTreeController` + `LastRunTracker` +
  `SeriesFetchCoordinator`** + ~400-line window shell. VisualizationTabManager
  mostly just needs its two toolbar builders extracted (~450 lines).
- **Optimisation stack is over-decomposed**: 11 managers wired via a
  10-positional-Consumer call and a 13-field positional struct; merge
  EventHandlers+UpdateCoordinator; `OptimisationInfo` as single source of truth.
- **Linter: fold `events/`, `managers/`, `factories/`, `utils/` into the root**
  (four single-class packages), delete `performance/`, merge
  LinterOrchestrator into LinterManager.
- **Dissolve `models/`** (vs `model/`): `RunInfoImpl` → windows/run-manager;
  `models/optimisation` → the optimisation packages.
- **Extract from `EnhancedTextEditor`** (1,303 lines): context-menu builder
  (190-line anonymous class), route map-rename through `RenameNodeCommand`;
  collapse `CommandExecutor`'s 4× rename copy-paste into one
  `applyRename(validation, finder, log)` skeleton.
- **PreferencesDialog** (1,121 lines): promote the nine inner panels to
  `preferences/ui` with a `PreferencePage` interface; build the tree from the
  page list (the hand-maintained switch at :196–224 silently drops drifted
  labels).
- **MapPanel**: extract ~330 lines of pure geometry (`MapGeometry`), one
  `MapViewTransform` (screen↔world derived in 5 places), constructor-inject the
  document's collaborators (removes the `setModel` ordering trap + listener
  leak).

### Contracts to write down (decisions needed)
- **STDIO busy-reject**: spec §6.2 says a busy CLI rejects commands; the IDE
  freely pipelines and it works only because the CLI reads stdin sequentially.
  Decide which contract is true; record it in the spec, cited from both sides.
  (Spec also lacks `log`, progress `d` field, `get_optimisable_params`,
  `run_optimisation`.)
- **Plot theming**: FlowViz is hard-coded paper-white in a 10-theme IDE
  (`PlotPanel.java:142`, `AxisRenderer`, legend, `StatusProgressBar`,
  `ParameterSheetWindow` renderers, SessionManagerWindow tree renderer all
  hard-code light colors). Deliberate "plots are paper" or theme-aware —
  either way, centralize the constants and state the decision.
- **EDT policy manifesto clause**: what may run on the EDT; how background
  results come back; `updateStatus` marshals internally. Three subsystems each
  violate it differently today.
- **Prompt-on-close preference** (`FILE_PROMPT_SAVE_ON_EXIT=false` silently
  discards unsaved work — confirm intended).

---

## P4 — Cleanup inventory (all verified by grep)

- **Test drivers in `src/main`**: `ModelIntegrationTest`, `NodeRenderingTest`,
  `StatusReportingTest`, `ZoomToFitTest`, `model/ModelParserTest`,
  `ModelPerformanceTest`, `IncrementalUpdateTest`; empty `com/kalix/ide/test`
  dir; 146-line `main()` harness in `GorillaCompressor`. Convert the valuable
  ones to JUnit under `src/test`, delete the rest.
- **`ai/claude/` package**: three markdown docs + tracked `.DS_Store`, no code,
  unreferenced, not packaged. Move to `docs/`; gitignore `.DS_Store`.
- **Dead classes**: `ApiDiscovery` (336 lines), `ApiModel` (218),
  `StreamMonitor`'s regex progress heuristics (~180 lines), linter
  `performance/` (602), `NodeThemeSpec`, `IconManager`'s commented-out half,
  `ErrorHandler`.
- **Dead members/state** (per-subsystem lists in the agent reports):
  `updatingFromModel` guard (map), write-only `removedNodes/Links`,
  `deleteNodesFromText`, `MapPanel.clearModel`; optimisation's write-only maps
  (two of which leak model text), never-fired `onSessionCompleted`,
  null-returning `getStopButton()`; `NavigationHistory.recordPosition`,
  `CommandRegistry.findByKeyStroke`, `KalixIniTokenMaker` dead handlers;
  FlowViz `ViewPort.pan`, `loadMultipleCsvFiles`, `printCacheStats`;
  `PreferencesDialog.showDialog()` hardcoded `return false` + dead caller
  branch; deprecated `AppConstants.PREF_*`; empty `UIConstants` nested classes;
  `StatusBar.*` theme keys nothing reads.
- **Idiom sweep**: `System.err`/`System.out` → SLF4J (ProcessExecutor,
  StreamMonitor, HydrologicalModel, DataSet, ToolBarBuilder);
  `e.printStackTrace()` alongside logger calls; log the throwable, not
  `getMessage()`; `@author Claude Code Assistant` tags; fully-qualified type
  spam in `EnhancedTextEditor`/`KalixIDE`; wildcard imports in builders;
  platform mask via `getMenuShortcutKeyMaskEx()` everywhere; context-menu label
  casing per `manifestos/context-menu-style.md`; three copies of the
  384-luminance dark check while `UIConstants.Theme.LIGHT_THEME_RGB_THRESHOLD`
  sits unused; `Platform` enum instead of raw `os.name` checks
  (`FileManagerLauncher`, `KeyboardShortcutManager`).
- **Stale docs**: kalixide/CLAUDE.md lists nonexistent `JsonSessionManager`,
  mislabels Botanical; `RunInfoImpl` javadoc contradicts `withName`;
  `VisualizationTabManager` javadoc documents removed params; "Phase 1 exactly
  one document" comment in KalixIDE; truncated javadoc in
  `FileOperationsManager:296` ("Implemented as a w").

---

## Cross-cutting themes (why these bugs, not just which)

1. **Duplication is where the bugs breed.** Four INI-grammar encodings, three
   sanitize rules, two session trackers, two CSV exporters (one fixed, one
   not), 4× rename copy-paste (with copy-pasted wrong dialogs). Nearly every
   consolidation in P3 pays for itself in eliminated bug classes.
2. **Lifecycle asymmetry**: things attach (listeners, processes, caches,
   timers) and nothing detaches them — editor Toolkit hook, linter dispose,
   HIDE_ON_CLOSE windows whose `windowClosed` cleanup never fires, run-data
   retention, kalixcli orphans. House rule: every attach names its detach;
   hidden singletons tie lifecycle to visibility.
3. **Dead "safety/performance" machinery documents protection that doesn't
   exist** — unused feedback-loop guard, dead linter performance package,
   never-consulted SpatialIndex, misleading thread-safety claims
   (ConcurrentHashMap on EDT-confined data next to *actually* racy plain
   HashMaps). Delete or make true; the comments actively mislead.
4. **Units and identity by convention**: ms-vs-s timestamps, display-name vs
   enum-name theme keys, raw `File.equals` path identity, string-keyed theme
   maps. Names/types at boundaries would have prevented the worst P0.
5. **Two strata of code quality.** The newer work (workspace tree, FsWatcher,
   SeriesRef identity, res.csv, TimeSeriesMasker, TerminalLauncher) is
   excellent and documents *why*. The older stratum is a generation behind.
   The highest-leverage general instruction: bring old code up to the standard
   the new code already sets — the templates all exist in-repo.
6. **Tests are thinnest exactly where logic is purest** — Gorilla edge cases,
   binary search, aggregation completeness, cadence detection, the expression
   parser (whose existing test pins dead cache behavior + flaky timing).
   The IO reviewer's reproduction harnesses (session scratchpad:
   `gorilla/NanPayloadHarness.java`, `gorilla/PixieUnitsHarness.java`) are
   ready to convert to JUnit regressions.

---

## Suggested sequencing

1. **Wave 1 — data safety** (P0 #1–7) + convert the two reproduction harnesses
   into regression tests; fixture extensions on both Rust/Java sides.
2. **Wave 2 — session lifecycle** (P1 #8–17): RunModelProgram state machine,
   stderr policy, stop/term wiring (coordinate with backend interrupt fix on
   perf/optimiser), teardown symmetry, shutdown hook.
3. **Wave 3 — interaction performance** (P2 #61–65): auto-Y, LOD, monitor
   sleeps, result-path JSON, convergence plot. Measured before/after, per the
   performance manifesto.
4. **Wave 4 — theme Phase 0–2** + preference hardening (P0 #5 done in wave 1;
   typed keys here).
5. **Wave 5 — consolidations & decomposition** (P3), interleaved with the P4
   cleanup sweep (much of it mechanical).
6. **Ongoing** — editor/linter/map bug list (P1 #18–48) can proceed in parallel
   waves; most are local fixes.
