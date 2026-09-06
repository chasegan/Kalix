# Multi-Document Workspace Architecture

Status: **Complete** — all five phases delivered.
Amended September 2026: the contextual view moved *inside* each tab (see the
Addendum at the end); the diagram and decision notes below are updated to match.

Map zoom/pan is intentionally not persisted per file (auto-fit-on-load is the
chosen default); everything else in the plan below is implemented.

## Goal

Move KalixIDE from a single-file application to a VSCode-style, project-oriented
experience: open a modelling project *folder*, see all its files in a tree on the
left, and open, run, and switch between multiple models seamlessly.

The main window has two regions, with the contextual view living inside each tab:

```
+-----------+-------------------------------------------------+
|           |  tab  tab  tab  tab  tab  tab                   |
|  project  +--------------------------------+----------------+
|   tree    |                                |   contextual   |
| (JTree)   |   text editor (active tab)     |     view       |
|           |                                |  (map for a    |
|           |                                |   model tab)   |
+-----------+--------------------------------+----------------+
```

- **Left** — project file tree (collapsible, resizable).
- **Centre** — one tab per open file, the tab strip running the full remaining
  width. This is the always-present anchor. Each tab's content is its document's
  editor plus — for documents with a contextual view — an editor|context split
  (`DocumentSplitView`) whose divider width and collapsed state are shared across
  all tabs (`ContextSplitCoordinator`).

## Settled design decisions

These were worked through deliberately; the rationale matters for future changes.

### 1. The right panel is the "contextual view of the active tab", not "the map"

There is exactly **one** notion of active: the active tab. The right panel is a
*pure projection* of it:

- active tab is a model (`.ini`) → right panel shows the **map**
- active tab is data (CSV/pixie, *future*) → right panel shows a **plot**
- active tab is plain text / log → right panel is **empty / collapsed**

This deliberately avoids a duality between an "active tab" and a separate "active
model". "Which model the map shows" is not stored state — it is `f(activeTab)`.
The user is never confused about what is active. It also makes polymorphic open
(plots for data files) a clean extension rather than a special case.

We considered a "pinned map" (map stays on the last model even when a non-model
tab is focused). Rejected for Phase 1 because it reintroduces the duality. If we
ever want it, the clean form is an *opt-in* lock (à la VSCode's locked Markdown
preview), where the duality exists only when the user explicitly asks for it.

> **Amended (September 2026):** the contextual view now lives *inside* each tab
> rather than in a shared right-hand panel — see the Addendum. This strengthens
> the invariant above: the view is no longer even a projection that must track
> the active tab, it is *part of* the tab. The pinned-map idea is thereby off the
> table entirely (a non-model tab simply has no map region), a trade-off accepted
> knowingly.

### 2. Per-document ownership, symmetric across views

Each open document owns **its own** editor instance and, if it is a model, **its
own** map + parsed model, with text↔map sync wired once at construction.

Consequences:
- **Undo/redo is per-document for free** via RSyntaxTextArea's native `RTextArea`
  undo stack. No custom `UndoManager`.
- **View state (zoom/pan/selection/caret) is per-document for free.**
- Switching tabs is "show a different component", not "re-point a shared
  component" — which eliminates an entire class of rebind-on-switch sync bugs.

Shared services (`SchemaManager`, theme system, run/session infra, FS watcher)
are **injected** into documents, never owned by them.

### 3. Open = show text (uniformly), for now

Every file opens as text in a tab. `.ini` tabs additionally drive the map. CSV /
pixie files open as text too for now; richer behaviour (add-to-plot, plot view)
is a deliberate later layer enabled by the document-subtype design.

### 4. The docking system is removed

`DockingArea` / `DockableMapPanel` / `DockableTextEditor` are unused and not
needed by the three-region layout. They are removed in Phase 2 in favour of a
purpose-built nested-split layout. Net code reduction.

> **Amended (September 2026):** the split layout itself was later reshaped —
> `[ tree | tabs ]` outside, `[ editor | context ]` inside each tab (see the
> Addendum). Docking never returned.

### 5. Tree component: `JTree` + a thin custom layer (not a from-scratch widget)

`JTree` under FlatLaf gives modern look, keyboard nav, selection, expand/collapse,
lazy loading, and accessibility for free. The "super-nice" comes from a thin layer
on top: custom `TreeCellRenderer` (Ikonli file-type icons + theme colours via
FlatLaf `Tree.*` keys), row hover highlight, tooltips, right-click context menu,
and a filesystem-backed model. No indent guides (out of scope by choice).

**File-system watching:** use `io.methvin:directory-watcher` (native FSEvents on
macOS) rather than `java.nio.WatchService`, which falls back to laggy polling on
macOS.

## Core abstractions

- **`KalixDocument`** — one open file. Owns: `File` ref (nullable = untitled), its
  `EnhancedTextEditor` (→ native per-document undo), dirty state, display name.
  Exposes `getContextView()` returning the right-panel component, or `null`.

- **`ModelDocument extends KalixDocument`** (for `.ini`) — additionally owns its
  `HydrologicalModel` + `MapPanel`, wires text↔map sync internally at
  construction, and returns the map from `getContextView()`. Future `DataDocument`
  would return a plot view — that is why this is a type hierarchy.

- **`DocumentManager`** — owns the set of open documents and the active-document
  concept. API: `open(File)` (create or focus), `close(doc)` (with dirty check),
  `newUntitled()`, `getActiveDocument()`, listeners
  `onActiveDocumentChanged / onOpened / onClosed`. The spine everything listens to.

- **Document typing** — `DocumentKind.forFile` maps file extension → kind, and a
  factory function (`KalixIDE.createDocument`, injected into
  `FileOperationsManager`) builds the right bundle: the single place where "what
  does opening this file mean" is decided. (The originally planned
  `DocumentFactory` class + subtype hierarchy is deferred until a second rich
  kind — CSV — actually exists.)

- **`WorkspacePanel`** — the outer layout: one `JSplitPane`
  `[ tree | document tabs ]`; the tree is collapsible with a persisted size. The
  tab area is the always-present anchor.

- **`DocumentSplitView` + `ContextSplitCoordinator`** — per-tab
  `[ editor | contextual view ]` split (built only for documents whose
  `getContextView()` is non-null), sharing one remembered divider width and
  collapsed state across all tabs. Replaced the original shared
  `ContextViewPanel` (September 2026 — see the Addendum).

## Phases (each is independently shippable)

### Phase 1 — `KalixDocument` + `DocumentManager` behind the current UI (no visible change)
The load-bearing refactor, de-risked by changing nothing the user sees. Extract
per-document state (editor, model, map, file ref, dirty, sync wiring) out of
`KalixIDE` into a `KalixDocument`. `DocumentManager` holds exactly one. Refactor
`FileOperationsManager` (drop its `currentFile` field → onto the document),
`TitleBarManager`, `FileWatcherManager`, and the root-pane Ctrl+Z/S/R bindings to
route through the active document. Existing docking layout untouched.
**Acceptance:** open / save / save-as / new / run / undo-redo / map↔text sync all
behave exactly as before. Pure refactor.

### Phase 2 — Three-region collapsible layout (still single document)
Remove docking. Build `WorkspacePanel` + `ContextViewPanel`. Map moves to the
right; left region appears (empty stub); collapse/restore + divider persistence.
**Acceptance:** identical single-document functionality, new layout, map on right,
regions collapsible/resizable, sizes survive restart.

### Phase 3 — True multi-document (tabs)
FlatLaf-styled tabbed centre over open documents; `DocumentManager` → N documents.
`ContextViewPanel` switches to the active doc's map (or collapses). Per-tab dirty
dot, close button, middle-click close, unsaved-close prompts. Runs/sessions
associated with their source document.
**Acceptance:** open many models, switch instantly, each retains its own undo,
zoom/pan, selection, caret; runs independent; closing prompts on unsaved.

### Phase 4 — Project tree
"Open Folder" → `WorkspaceModel` (root, persisted). `FileSystemTreeModel` backed
by `io.methvin:directory-watcher`. Custom renderer (Ikonli icons, theme colours),
hover highlight, tooltips, context menu (Open, Reveal, New File/Folder, Rename,
Delete), lazy loading. Click opens via `DocumentManager.open`.
**Acceptance:** open a folder, browse, open files into tabs, live FS updates,
themed across all themes, smooth scrolling.

### Phase 5 — Workspace persistence & tree polish
Persist/restore full session: open folder, open tabs + order + active, per-file
view state, panel sizes/collapsed state. Inline rename (F2), keyboard nav, final
polish.
**Acceptance:** reopen the app → project, tabs, and view state restored exactly.

## Cross-cutting concerns

- **Two distinct watchers, distinct jobs:** per-open-document *content* watcher
  (external-edit reload — existing `FileWatcherManager`) vs. workspace *structure*
  watcher (methvin, Phase 4). Kept separate; unification is possible future work.
- **`EnhancedTextEditor`'s `modelSupplier` / linter / autocomplete** currently
  close over the single model — become per-document wiring (each editor points at
  its own document's model). Shared *schema* data stays shared.
- **Memory:** a handful of managers + one map per open document, bounded by tab
  count. Watch at Phase 3.
- **Tests:** `DocumentManager` / `DocumentFactory` are plain logic — unit-tested.
  Phase 1 acceptance is "no behaviour change", verified against existing flows.

## Addendum (September 2026): the contextual view lives inside each tab

The shared right-hand region (`ContextViewPanel`) was replaced: each tab's content
is now the document's editor plus, for documents that have one, its contextual
view, composed by a per-tab `DocumentSplitView`. What this buys:

- **A full-width tab strip.** The tab bar runs to the window's right edge, so many
  more tabs are visible before scrolling.
- **Typed tab content.** A non-model file's tab is just its editor today; a future
  CSV tab can carry a table/plot as *its* context view, a text tab file details —
  per tab, without a shared panel having to dispatch on the active document's type.
- **A stronger form of decision 1.** "The contextual view is a pure projection of
  the active tab" becomes structural: the view is part of the tab, so it cannot
  even transiently disagree with it. `ContextViewPanel`'s swap-on-activation logic
  is deleted rather than maintained.

What stays deliberately unchanged: there is still **one** remembered region width
and collapsed state for the whole application (`ContextSplitCoordinator`, persisted
under the same preference keys as before) — dragging the divider in one tab moves
it for every tab, and View → Toggle Map collapses the region everywhere. The
visible split applies changes immediately; hidden ones catch up when shown.

Consequences worked through at the time:

- **Tab identity** resolves through a per-document root map in `DocumentTabPane`
  (never through `getEditor()`), so the composite root changes no tab bookkeeping.
- **First-layout ordering**: `DocumentSplitView` lands the shared divider in
  `doLayout()` *before* the split's children get bounds, so `MapPanel`'s deferred
  zoom-to-fit completes at the real width and a collapsed region never flashes open.
- **Theme switches** simplify: with every map mounted, `updateComponentTreeUI`
  reaches them all — `MapPanel.updateUI()` re-resolves its theme colours, and the
  old per-activation catch-up (and ThemeManager's map registration) is gone.
- **Focus**: selecting a tab explicitly focuses its editor (the composite would
  otherwise leave focus on the tab header).
- **Losing "map visible while a non-model tab is active"** is accepted; if ever
  wanted, the clean form remains the opt-in lock described under decision 1.
