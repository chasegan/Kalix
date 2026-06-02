# Multi-Document Workspace Architecture

Status: **Complete** — all five phases delivered.

Map zoom/pan is intentionally not persisted per file (auto-fit-on-load is the
chosen default); everything else in the plan below is implemented.

## Goal

Move KalixIDE from a single-file application to a VSCode-style, project-oriented
experience: open a modelling project *folder*, see all its files in a tree on the
left, and open, run, and switch between multiple models seamlessly.

The end-state main window has three regions, left to right:

```
+-----------+------------------------------+------------------+
|           |  tab  tab  tab               |                  |
|  project  +------------------------------+   contextual     |
|   tree    |                              |      view        |
| (JTree)   |   text editor (active tab)   |  (map for the    |
|           |                              |   active model)  |
|           |                              |                  |
+-----------+------------------------------+------------------+
```

- **Left** — project file tree (collapsible, resizable).
- **Centre** — tabbed text editors, one tab per open file. This is the always-present anchor.
- **Right** — *contextual view of the active tab* (collapsible, resizable).

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

- **`DocumentFactory`** — maps file extension → which `KalixDocument` subtype to
  build. The single place where "what does opening this file mean" is decided.

- **`WorkspacePanel`** — the three-region layout: nested `JSplitPane`s
  `[ tree | editor-tabs | context-view ]`; tree and context regions independently
  collapsible with persisted sizes. Editor area is the always-present anchor.

- **`ContextViewPanel`** — right region. Listens to `onActiveDocumentChanged`;
  shows `activeDoc.getContextView()` or collapses if `null`. No independent state.

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
