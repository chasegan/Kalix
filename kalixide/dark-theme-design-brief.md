# Design Brief: KalixIDE Dark Theme Redesign

**Deliverable:** One self-contained HTML/CSS mock per theme, with every colour exposed as a named CSS custom property so values can be mechanically extracted back into the application. Details in §4.

---

## 1. Product context

Kalix is an open-source hydrological modelling platform: a fast Rust simulation
engine driven by a desktop IDE (KalixIDE, Java Swing). Users are professional
water-resource modellers — serious practitioners who build river-system models as
text files (an INI-like format), view them as a node-link schematic map, run
simulations, and inspect results as time-series plots. The IDE is text-first,
transparent, and performance-obsessed. The desired feel for its dark themes is a
**professional development environment** — the calibre of VSCode's or JetBrains'
best dark themes: calm, coherent, low-fatigue for all-day use, with colour spent
deliberately on meaning (syntax, node types, data series), not on decoration.
The current dark themes miss this badly (see §3); this brief commissions a
coherent redesign.

The IDE's main window has three primary panes visible at once: the **text editor**
(model source), the **schematic map** (the model drawn as coloured node shapes
connected by links), and standard chrome (menu bar, toolbar, status bar, dialogs,
file trees, tables). A separate **plot window** (FlowViz) shows time-series
results. All four must be designed together — the most common view is editor and
map side by side, so their backgrounds and accents must feel like one room.

A note on scope of assumptions: an unrelated bug currently builds dark themes on a
light widget base. That is being fixed separately — **assume correct dark defaults
underneath** and design the colour system only.

---

## 2. Colour role inventory

Every theme must define a value for every role below. Roles, not widgets, are the
unit of design — one role may paint many widgets.

### 2a. Application chrome

| Role | What it colours | Where you see it |
|---|---|---|
| `--surface-default` | Panels, dialogs, option panes, toolbar, menu bar, status bar, split panes, tab strip | The base "wall colour" of the whole app |
| `--surface-content` | Text areas, tables, lists, trees, combo boxes, selected tab body | Content wells sitting on the base surface — must read as a distinct layer (in a dark theme, typically *darker* or *lighter* than the wall by a deliberate step) |
| `--surface-popup` | Popup menus, dropdown lists | Floating layers; need separation from whatever they open over |
| `--surface-titlebar` | Window title bar | Top edge of every window |
| `--text-primary` | Labels, menu text, button text, status bar text | Everywhere; the workhorse foreground |
| `--text-content` | Text inside content wells (tables, lists, trees, text fields) | May be slightly brighter than `--text-primary` |
| `--selection-bg` / `--selection-fg` | Selected text, selected table/list/tree rows, selected menu items, combo selection | The single most-seen accent surface in daily use |
| `--focus-ring` | Focused component border (text fields, buttons) | Keyboard-focus indicator |
| `--accent` | Default-button background, checkbox checkmark, radio dot, combo/spinner arrow buttons, slider thumb + filled track, progress bar fill | The theme's signature colour — this is what gives each theme its name and personality |
| `--accent-hover` | Hover/pressed states of accent-coloured controls | |
| `--hover-bg` | Menu-item hover, button hover, tab hover | Subtle; must not compete with selection |
| `--border` | Component borders, toolbar border | |
| `--separator` | Menu separators, split-pane dividers, table grid lines | Quieter than `--border` |
| `--scrollbar-track` / `--scrollbar-thumb` / `--scrollbar-thumb-hover` | Scroll bars | Present on every pane |
| `--tooltip-bg` / `--tooltip-fg` | Tooltips | |
| `--tab-selected-bg` | Selected tab | Should connect visually to the content it reveals |

### 2b. Editor and syntax tokens

The editor edits Kalix model files: an INI-like text format. A representative
fragment (use something like this in your editor mock):

```ini
# Upper catchment inflows
[node.river_gauge_01]
type = gauge
loc = 349.5, 214.0
observed = data/gauge01.csv

[node.headwater_rr]
type = sacramento          ; rainfall-runoff model
area = 452.7
params = 0.01, 40.0, 23.0, 0.009

[outputs]
node.river_gauge_01.dsflow
node.headwater_rr.runoff_depth
```

Editor-level roles:

| Role | What it colours |
|---|---|
| `--editor-bg` | Editor background (also used by log/output text panes) |
| `--editor-fg` | Default text |
| `--editor-selection` | Selected-text background |
| `--editor-current-line` | Current-line highlight (subtle) |

Syntax token roles (the tokenizer has exactly these six — design within them):

| Role | Token | What it represents in a model file |
|---|---|---|
| `--syntax-section` | Reserved word | Section headers: `[node.river_gauge_01]`, `[outputs]` — the structural skeleton; node names live inside these headers. Should be the strongest token |
| `--syntax-identifier` | Identifier | Property keys (`type`, `area`, `loc`) and bare output lines — the bulk of non-value text; close to `--editor-fg` |
| `--syntax-string` | Value literal | Property values: numbers, coordinate pairs, file paths, parameter lists, expressions. One colour covers all values, so pick one that stays readable in long runs of digits |
| `--syntax-operator` | Operator | The `=` signs; small marks, can afford a vivid accent |
| `--syntax-comment` | Comment | `#` / `;` comments — recede, but must still pass contrast (modellers document assumptions in comments; they are read, not skimmed) |
| `--syntax-whitespace` | Whitespace glyphs | Rendered whitespace indicators; near-invisible |

### 2c. Schematic map

The map draws the model as a network: coloured shapes (nodes) joined by links,
over a plain background with optional gridlines. **Nodes are hydrological
elements** — each *type* gets one colour, and the colour is the primary way a
modeller distinguishes type at a glance across a network of dozens to hundreds of
nodes. Shapes also vary by type, and each shape carries a 2-letter bold label
(e.g. "St", "Ga") whose colour is auto-picked black/white by background
brightness — so avoid node colours near the mid-brightness flip point (~50%
luma), where the auto-contrast label becomes ambiguous.

| Role | What it colours |
|---|---|
| `--map-bg` | Map canvas background. Sits beside the editor all day — coordinate it with `--editor-bg` (same family, but the two panes should still read as distinct) |
| `--map-gridline` | Optional alignment gridlines; must be barely-there, never compete with links |
| `--map-node-label-text` | Node-name captions drawn under each node |
| `--map-node-label-halo` | Semi-opaque pill behind captions so they survive crossing links (design the colour; it renders at ~78% opacity) |

Node type colours — **10 mutually distinguishable colours on `--map-bg`**. Some
types intentionally share a colour (they are functional siblings):

| Role | Node type(s) | What it is |
|---|---|---|
| `--node-storage` | storage | Reservoir / dam |
| `--node-inflow` | inflow | Water entering the model |
| `--node-gauge` | gauge | Flow measurement point |
| `--node-rr` | sacramento, gr4j | Rainfall-runoff models (catchment → streamflow) |
| `--node-routing` | routing, loss | Channel routing / transmission loss |
| `--node-splitter` | splitter, confluence | Flow dividing / joining |
| `--node-user-regulated` | regulated_user | Licensed water user (e.g. irrigator on a regulated river) |
| `--node-user-unregulated` | unregulated_user | Unlicensed / opportunistic user |
| `--node-blackhole` | blackhole | Terminal sink (water leaves the model) |
| `--node-order-control` | order_control | Water-ordering / operations control point |

Where sensible, let semantics guide hue (water-ish for storage/inflow, earthy or
neutral for losses/sinks) — but distinguishability beats metaphor.

### 2d. Time-series plots (dark mode — this is new design territory)

The plot window currently hard-codes a **white background, black axes and tick
labels, very light grey grid** (#f0f0f0), and a 10-colour "tab10" series palette
(#1f77b4, #ff7f0e, #2ca02c, #d62728, #9467bd, #8c564b, #e377c2, #7f7f7f,
#bcbd22, #17becf). The owner has decided **light themes keep white plots**; dark
themes need a designed answer for every plot role:

| Role | What it colours |
|---|---|
| `--plot-bg` | Plot canvas background |
| `--plot-grid` | Grid lines aligned with axis ticks (thin, ~0.5px; must recede) |
| `--plot-axis` | Axis lines and tick marks |
| `--plot-label` | Tick labels and axis titles |
| `--plot-series-1` … `--plot-series-10` | Series line colours, applied in order as series are added. **At least 10**; more welcome. Each must be clearly visible on `--plot-bg` at 1–1.5px line weight |
| `--plot-legend-bg` / `--plot-legend-border` / `--plot-legend-fg` | The legend is a floating semi-opaque panel drawn over the plot (bg renders at ~92% opacity) |
| `--plot-hover-bg` / `--plot-hover-fg` | Hover/crosshair readout: a small semi-opaque box near the cursor showing series values, plus a marker dot per series (dot uses the series colour) |
| `--plot-empty-fg` | "Loading…" / no-data placeholder text |

Series-palette constraints (hard requirements):

- **Mutually distinguishable on `--plot-bg`** — 10 thin lines on one chart is a
  normal working session, not an edge case.
- **Colour-blind consideration:** must remain tellable-apart under the common
  dichromacies (deuteranopia/protanopia) — vary lightness and saturation, not just
  hue; avoid relying on red-vs-green pairs at similar lightness.
- **Printable:** modellers screenshot plots into reports; colours should survive
  reproduction on white paper (i.e. not so dark they vanish off the dark
  background context, not so pale they vanish on white).

---

## 3. Current state — where we're starting from

Two of the four dark themes, key values as shipped:

| Role | Dracula | One Dark |
|---|---|---|
| Surface (panels/toolbar/menus) | `#414450` | `#21252b` |
| Content wells / editor bg | `#3a3d4c` | `#282c34` |
| Title bar | `#414450` | `#21252b` |
| Text primary | `#bbbbbb` | `#abb2bf` |
| Selection bg | `#6272a4` | `#4d78cc` |
| Focus ring | `#6272a4` | `#568af2` |
| Accent | `#bd93f9` | `#568af2` |
| Border | `#6272a4` | `#333841` |
| Table grid / map gridline | `#5d5e66` / `#6272a4` | `#5c6370` / `#5c6370` |
| Scrollbar track/thumb | `#3e4244` / `#565c5f` | `#3e4244` / `#565c5f` |
| Tooltip bg/fg | `#6272a4` / `#ffffff` | `#3d424b` / `#abb2bf` |
| Map background | `#414450` | `#282c34` |
| Syntax: section / value / comment | `#bd93f9` / `#f1fa8c` / `#6272a4` | `#c678dd` / `#98c379` / `#5c6370` |
| Node palette (first 5) | `#ff79c6 #bd93f9 #f1fa8c #8be9fd #ffb86c` | `#56b6c2 #c678dd #98c379 #e06c75 #d19a66` |

Frank diagnosis, per theme:

- **Dracula** — washed-out and hierarchy-inverted: the wall colour `#414450` is a
  pale, milky grey-blue (real Dracula sits near `#282a36`), the "content" layer
  `#3a3d4c` differs from it by almost nothing, and body text `#bbbbbb` is a flat
  mid-grey that makes the whole app look low-contrast and dusty. `#6272a4` is
  overloaded — border, focus, selection, tooltip, *and* map gridline — so a
  comment-blue utility colour ends up shouting from every edge of the UI.
- **One Dark** — the closest to respectable, but flat and borrowed-parts:
  chrome and content are near-identical so nothing layers; selection `#4d78cc` is
  a fully-saturated opaque blue used for text, tables, lists *and* menus, which
  is loud everywhere; hover states (`#55585a`) and the scrollbar stack are
  neutral warm greys pasted in from elsewhere (they are byte-identical to
  Dracula's) and sit off-family against the cool blue-grey base.

Cross-cutting problems to fix in the redesign: (1) no deliberate surface
hierarchy — wall, content, popup and title bar collapse into one or two values;
(2) shared utility colours doing too many jobs; (3) copy-pasted neutrals that
ignore each theme's temperature; (4) gridlines and table grids far too prominent;
(5) pure `#ffffff`/`#000000` used for selection foregrounds and progress text —
harsh on dark surfaces.

For the level of coherence expected, look at the project's better **light**
themes: *Nemo* builds an entire ocean system (surfaces `#e6f3ff`/`#cce7ff`/
`#b3e5fc`, sandy editor `#fff8e1`, clownfish-orange accent `#ff6f00`, an
all-blue-turquoise node palette) and *Sunset Warmth* does the same in cream and
sunset orange (`#fef7f0`, accent `#ff6b35`, warm brown text `#8b4513`). Every
role in those themes is clearly chosen from one palette with one temperature.
That is the bar — inverted for dark.

---

## 4. Constraints and deliverable format

**Accessibility and legibility**

- All text roles meet **WCAG AA** against their backgrounds: 4.5:1 for body-size
  text (labels, editor text, tick labels, comments), 3:1 for large text and
  meaningful UI graphics (focus rings, icons, axis lines).
- Selection and focus must be unmistakable but not neon — prefer a
  mid-saturation tint that keeps selected text at AA contrast.
- The 10 node colours must be mutually distinguishable **on `--map-bg`**, and the
  10+ plot series colours mutually distinguishable **on `--plot-bg`** (these are
  two separate checks; the backgrounds may differ).
- Avoid node colours near 50% luma (see §2c label-contrast note).

**Family coherence**

- The dark themes must feel like siblings of the existing light themes: same
  design language, same restraint, inverted values — a user switching Light →
  Kalix Dark should feel they changed the lighting, not the product.
- Within each theme, every role comes from one deliberate palette: one base
  temperature, one accent family, neutrals mixed to match (no copy-pasted greys).

**Deliverable — one HTML file per theme**

- Fully self-contained: inline CSS, no external assets, fonts, or scripts.
- All colours defined once as **CSS custom properties on `:root`, named exactly
  after the roles in §2** (e.g. `--surface-default`, `--syntax-section`,
  `--node-storage`, `--plot-series-1`). This is a hard requirement: values will
  be extracted mechanically by property name.
- The page must show, top to bottom:
  1. **Swatch grid** — every custom property as a labelled swatch (role name +
     hex), grouped by the four §2 categories.
  2. **Editor mock** — the §2b INI fragment rendered with the syntax tokens,
     including a selected line and a current-line highlight.
  3. **Map mock** — a small node-link sketch (simple CSS/SVG shapes are fine):
     8–10 nodes using the node colours on `--map-bg` with gridlines, link lines,
     and captioned labels with halos.
  4. **Plot mock** — a chart area on `--plot-bg` with grid, axes, tick labels,
     all 10 series drawn as thin lines (SVG polylines are fine), the floating
     legend, and a hover readout box.
  5. A thin strip of **chrome**: menu bar with one open menu (hover +
     selected item), a toolbar, buttons (normal / default / hover / focused),
     a table with a selected row, a scrollbar, a tooltip.
- Note alpha where a role renders translucently (legend bg, label halo): give the
  base colour as the custom property and state the opacity in the swatch label.

---

## 5. Scope

Refresh the **four true dark themes, keeping their names and personalities**
(a fifth listed dark theme, "Botanical", is actually a light theme and is out of
scope):

1. **Dracula** — purple-accented, faithful to the well-known Dracula palette's
   spirit (deep blue-charcoal base, purple/pink accents) but tuned to this app's
   role system rather than transplanted verbatim.
2. **One Dark** — Atom-inspired: cool blue-grey base, blue accent, the classic
   multicolour syntax feel — done with proper layering this time.
3. **Obsidian** — purple-and-stone: near-black neutral base with violet accents.
   Note its current node palette is ten shades of purple — unusable for
   type-at-a-glance; it needs a genuinely varied node/series palette while keeping
   purple as the *chrome* accent.
4. **Sanne** — pink-accented. Keep the bold pink personality but rescue it from
   hot-pink-everywhere: pink as accent and selection tint, not as tooltip
   backgrounds and scrollbar thumbs.

**Plus (encouraged, optional): one new flagship "Kalix Dark"** — no legacy
identity to honour; your best answer to "what should the definitive dark theme of
a serious open-source hydrological modelling IDE look like?" Water is the obvious
motif; restraint is the obvious risk-control.

Five HTML files (or four if the flagship is dropped), one per theme, each
following the §4 format. No follow-up questions should be needed — where this
brief is silent, exercise professional judgement and note the decision in an HTML
comment at the top of the file.
