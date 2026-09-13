---
title: "ADR-0002: Context-menu style"
---

# ADR-0002: Context-menu style

- **Status**: Accepted
- **Date**: 2026-06-20 (recorded 2026-09-14, retrospectively, from the
  `context-menu-style` manifesto)
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: ide

## Context and problem statement

KalixIDE has a right-click menu on nearly every panel — file tree, schematic
map, editor, run tree, outputs, optimisation, table view, plots. They grew
independently and had drifted: Title Case here and sentence case there,
`Delete Selection` on the map but `Delete` in the tree, Expand/Collapse
mid-menu in one panel and at the bottom in another, shortcuts baked into
some labels and not others.

A menu item is an imperative sentence with everything inessential
amputated — "Open *[this file]*", "*[I want to]* Delete *[the selection]*".
The question was what single design to hold to, so that the panels rhyme
and a user who learns one has learned them all.

## Decision drivers

- Manifesto §2.6 — on language: describe things simply and precisely; one
  thing has one name ("zip" in one menu can't be "compress" in another).
- Manifesto §2.5 — simple, well-designed, predictable features are good for
  everybody.
- E.B. White's governing instruction: *choose a suitable design and hold to
  it.* Much of what follows is Strunk & White's *The Elements of Style*
  applied to the act of amputation.

## Considered options

1. **Leave each panel's menu to its own author** — the status quo.
2. **One skeleton, one case, one set of naming rules, applied to every
   menu** — and re-cut the existing menus to match.

## Decision

Chosen option: **one design, held to everywhere.**

### 1. Ordering skeleton

Every menu is built from the same top-to-bottom blocks, separated by
dividers. Omit any block that has no items; never reorder them.

| # | Block | Contains |
|---|-------|----------|
| ① | **Primary** | the default action (what double-click / Enter does) — usually one item |
| ② | **Context-specific** | actions unique to this thing (Compare…, external handoff like Reveal / Launch Terminal) |
| ③ | **Clipboard** | Cut, Copy, Paste — and copy-derivatives (Copy path) |
| ④ | **Create** | New file…, New folder… |
| ⑤ | **Modify** | Rename…, Duplicate… |
| ⑥ | **Destructive** | Delete — *always isolated in its own block* |
| ⑦ | **View / state** | Expand, Collapse, Show hidden files, Zoom to fit, Refresh — the things that change presentation, never data |

Rationale: the eye enters at the top, so the most-wanted action is first;
the most-dangerous action sits alone where it can't be hit by momentum;
everything that merely changes *how you look* at the thing is quarantined
at the bottom.

### 2. Naming

#### 2.1 Case — sentence case, always

Capitalize the first word and proper nouns only.

> *Good:* `Compare with active editor`, `Show hidden files`, `Reveal in Finder`
> *Wrong:* `Compare With Active Editor`, `Show Hidden Files`

Prose is sentence case; Title Case is typographic ornament, and it forces an
endless minor-word judgement call (`with`/`to`/`in`) that is itself a source
of drift. Proper nouns stay capitalized: **Finder**, **Explorer**,
**Terminal**, **KalixCLI**.

#### 2.2 Verb first

Items are imperative commands, so they begin with a verb: `Open`, `Reveal`,
`Rename…`, `Delete`, `Launch Terminal`. Use **one verb per concept** — do
not mix Reveal / Show / Open for the same idea within a context (but see
the platform exception in §2.6).

**Idiomatic exception — `New …`.** "New file", "New folder", "New tab" are
universal and verbless across every editor. Keep them. "Create file" reads
as precious; S&W warned against affecting a manner as much as against
wordiness.

#### 2.3 Omit needless words (the core rule)

> *"A sentence should contain no unnecessary words… for the same reason
> that a drawing should contain no unnecessary lines and a machine no
> unnecessary parts… every word tell."* — Rule 17

Cut every word the **context already supplies** — the panel is the subject
of the sentence and is already established:

| Before | After | Why |
|--------|-------|-----|
| `Find on Map…` | `Find…` | it's the map's own menu |
| `Delete Selection` | `Delete` | the menu only acts on the selection |
| `Reveal in File Manager` | `Reveal in Finder` *(etc.)* | name the real destination instead (§2.6) |

**The governor:** a word is needless *only if cutting it costs no clarity.*
Terseness that costs clarity has stopped following Rule 17 and started
mangling. This is why we keep the destination in "Reveal in Finder" (where
else?) but drop "on Map" from "Find" (nowhere else).

#### 2.4 Ellipsis (`…`)

Use a true ellipsis character only when the action **needs more input
before it can happen** — i.e. it opens a dialog or prompt.

- `Rename…` (file tree — opens a dialog) → ellipsis
- `Rename "node"` (map — edits inline) → **no** ellipsis
- `Delete` → **no** ellipsis (a yes/no confirmation is not "more input")

#### 2.5 Delete vs Remove

A deliberate, load-bearing distinction:

- **Delete** — destroys the underlying thing (a file on disk, a node in the
  model).
- **Remove** — takes something out of a list or view; the source is
  untouched (a run, a tab, a loaded dataset).

#### 2.6 Platform-native destinations

For actions that hand off to an OS app, use that platform's own idiom
rather than a generic phrase — even though it breaks cross-platform verb
parallelism. Native feel outranks parallelism here; this is the one
sanctioned verb-mixing exception.

| Action | macOS | Windows | Linux |
|--------|-------|---------|-------|
| Reveal file | `Reveal in Finder` | `Show in Explorer` | `Show in File Manager` |
| Open a shell | `Launch Terminal` | `Launch Terminal` | `Launch Terminal` |

#### 2.7 Keyboard shortcuts live in the accelerator slot, not the label

`Show suggestions`, **not** `Show suggestions (Ctrl+Space)`. The shortcut is
set via `JMenuItem.setAccelerator(...)` so Swing right-aligns it. Baking it
into the label is needless words *and* non-parallel (only some items would
carry one).

### 3. Icons — load-bearing only

> *"A drawing should contain no unnecessary lines."*

An icon is a line in the drawing; every icon must earn its place. An item
gets an icon only when **both** hold:

1. the action has a **near-universal glyph** the user recognizes faster than
   the word, and
2. it is a **high-frequency** action.

In practice this is the clipboard + destructive landmark set: **Cut, Copy,
Paste, Delete** (and optionally **Open** / **New**, which have canonical
glyphs). Do **not** invent glyphs for abstract items ("Copy trailhead
path", "Show on map") — those are unnecessary lines.

Sparse icons work *with* Swing, not against it: `JMenuItem` reserves the
left gutter regardless, so iconed and text-only items stay vertically
aligned. The icons then read as landmarks for scanning, exactly as intended.

### 4. Conditional items

- Prefer **hiding** an item that cannot apply to the current selection over
  showing it disabled — a shorter menu is easier to scan. (The File tree's
  predicate-driven visibility is the model.)
- Show an item **disabled (greyed)** only when its presence is itself
  informative — i.e. the user should know the action *exists* but isn't
  available yet (e.g. `Paste` with an empty clipboard).
- Never leave an empty separator block when a whole group is hidden.
- **Empty space is not subject-less.** A right-click that lands on no item
  acts on the **containing folder** being viewed (a tree's open root, a
  dialog column's directory) — the same grammar, with the container as
  subject, and the usual predicates hiding what doesn't apply. Two verbs
  never apply to the root container itself: the identity-changing
  (`Rename…`, `Duplicate…`) and the destructive (`Delete`) — an empty-space
  click must never be able to destroy or rename the thing the user is
  standing in.

### 5. Dynamic labels

When an item names its target, quote the target and keep the verb in
sentence case: `Rename "Inflow_1"`. When no single target exists, fall back
to the bare verb: `Rename`.

### 6. Submenus & value lists are a different grammar

Submenu titles are category **nouns** (`Y-axis scale`, `Missing data`) and
radio/checkbox options are **values** (`Linear`, `Log`, `Sqrt`).
Parallelism applies *within* each class — do not try to verb them. Sentence
case still applies.

Where the children are instead the **objects of a verb**, the title is an
item, not a category, and §2.2 applies — it begins with a verb. Read title
and child aloud: "Y-axis scale: log" is a setting; "Insert node: GR4J" is a
command.

### Consequences

- ✅ Every panel's menu is built from the same skeleton; learning one is
  learning all.
- ✅ Naming questions ("ellipsis or not?", "Delete or Remove?") are answered
  by a rule, not by taste.
- ❌ Existing menus had to be re-cut (see Worked example), and every new
  panel must be cut to the same pattern. Accepted: that is the point.

## Pros and cons of the options

### Option 1: Per-panel menus

- ✅ No coordination cost.
- ❌ Drift is the steady state; users relearn each panel.

### Option 2: One design, held to everywhere

- ✅ Consistency compounds: the rules are short and the panels rhyme.
- ❌ Requires re-cutting existing menus and a rulebook to cite.

## Worked example

The three main panels as re-cut when this was decided.

**File tree**

```
Open
────────────────
Compare with active editor
Compare files
────────────────
Reveal in Finder        (macOS — platform-aware, §2.6)
Launch Terminal
────────────────
Copy relative path
Copy full path
Copy trailhead path
────────────────
New file…
New folder…
────────────────
Rename…
Duplicate…
────────────────
🗑  Delete
────────────────
Expand children
Collapse children
Collapse tree
☑ Show hidden files
Refresh
```

Changes from before: `Reveal in File Manager` → platform string;
`Terminal` → `Launch Terminal`; Title-Case items → sentence case (`New
file…`, `Show hidden files`); **Expand/Collapse moved from mid-menu down to
the view block** so the panel matches the skeleton and the map.

**Schematic map**

```
✂  Cut
⧉  Copy
📋 Paste
────────────────
Rename "node"
🗑  Delete
────────────────
Copy location
Find…
Zoom to fit
```

Changes: `Delete Selection` → `Delete`; `Find on Map…` → `Find…`;
Title-Case → sentence case. Already skeleton-compliant; ordering unchanged.

**Editor (text area)**

```
Undo
Redo
────────────────
✂  Cut
⧉  Copy
📋 Paste
🗑  Delete
────────────────
Select all
────────────────
Show suggestions          (accelerator Ctrl+Space in the slot, §2.7)
────────────────
Go to node definition
Show on map
────────────────
Rename "node"   …context commands…
```

Changes: `Select All` → `Select all`; `Go to Node Definition` → `Go to node
definition`; `Show on Map` → `Show on map`; `Show Suggestions (Ctrl+Space)`
→ `Show suggestions` with the shortcut moved to the accelerator slot.

The same rules apply to the remaining context menus (Run tree, Outputs
tree, Optimisation tree, Table view, Plot, Visualization tabs, Preferences
tree). The high-frequency fixes there: sentence-case every label, apply
Delete-vs-Remove (§2.5) deliberately, and isolate any destructive item per
the skeleton.

## Enforcement

§3 is **Structural**: icons are reserved for the landmark set and routed
through a single `MenuIcons` helper that exposes only those, so there is no
easy way to icon a fifth kind of item. Everything else — the skeleton,
naming, conditional items — is **Advisory**, held by review and by citing
this document.

## Links and references

- Related: [ADR-0007](0007-file-tree-colour.md) — the same tree's colour
  language.
- Related: [ADR-0006](0006-expression-naming.md) — one-name-per-thing in
  the expression language.
- External: Strunk & White, *The Elements of Style*, Rule 17.

## Amendments

- *2026-07-10* — §6 scoped: it governs value-selection submenus only. Where
  a submenu's children are the objects of a verb ("Insert node: GR4J"), the
  title is an item and §2.2 applies.
- *2026-07-23* — §4 gained the empty-space clause: a right-click on no item
  acts on the containing folder, and can never rename or delete it.
