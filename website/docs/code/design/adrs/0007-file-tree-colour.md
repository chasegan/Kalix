---
title: "ADR-0007: File-tree colour"
---

# ADR-0007: File-tree colour

- **Status**: Accepted
- **Date**: 2026-07-22 (recorded 2026-09-14, retrospectively, from the
  `file-tree-colour` manifesto)
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: ide

## Context and problem statement

The IDE's project tree shows everything in a folder: model files, data
files, folders that hold models, and a long tail of files Kalix has no
opinion about. The tree needed to make the rows Kalix recognises read as
more important than the rest, across several colour themes, without turning
into a rainbow.

The obvious move — colour the important rows with the theme's accent — was
tried in thought and rejected: accent colours are almost always *lighter*
than a theme's foreground, so colouring important text with an accent
visually demotes it. The question was how to encode importance so that it
survives every theme.

## Decision drivers

- Manifesto §2.6 — clarity and idiomatic design in the IDE; use structure so
  the feature is robust to future change (new themes, new file types).
- Manifesto §2.5 — simple, predictable features are good for everybody.

## Considered options

1. **Accent hue on important text** — colour model and data file names with
   the theme accent.
2. **Contrast tiers, accents on icons only** — important text keeps the full
   theme foreground; everything else steps back through derived greys;
   accent colour is carried by the icon.

## Decision

Chosen option: **contrast tiers, accents on icons only**.

### 1. Principles

1. **Prominence is contrast, not hue.** Important text keeps the
   full-strength `Tree.foreground`; importance is manufactured by muting
   everything else.
2. **Recognised vs unrecognised is the axis.** Rows Kalix has an opinion
   about — model files, data files, folders holding models — read at full
   strength. Rows it doesn't recognise step back. There is no third
   semantic.
3. **Colours are theme data.** Every tree colour is a `Kalix.tree.*` key in
   `resources/themes/*.properties`, tuned per theme — with one deliberate
   exception, the shared icon grey (§2.4).

### 2. Rules

1. **Text has exactly three strengths.** Full `Tree.foreground` for model
   files, data files, and model folders; `Kalix.tree.mutedForeground` for
   model-less folders; `Kalix.tree.faintForeground` for unrecognised files.
   Never an accent hue on text (§1.1).
2. **Accents live on icons.** `Kalix.tree.modelFileColor` on the model
   file's node-link glyph, `Kalix.tree.dataFileColor` on data-file glyphs.
   An unrecognised file's icon matches its text tier, so an icon never
   out-pops its own label.
3. **Derive the grey tiers; don't pick them.** `mutedForeground` sits ~60%
   of the way from `Tree.background` to `Tree.foreground`, keeping the
   theme's tint (green-grey for Botanical, slate for Lapland);
   `faintForeground` is muted moved a further 10% toward the background. On
   dark themes "fainter" therefore correctly comes out dimmer, not lighter.
4. **Folder glyphs use the shared icon grey** (`ThemeUtils.iconColor`) —
   the same colour as toolbar and menu icons, so glyphs read as one family:
   full strength for model folders, muted for the rest.
5. **A model folder is direct containment only.** A folder whose own
   listing holds a `*.ini`. Ancestors do not light up: a recursive rule
   would run the signal up every chain to the root and drown it.
6. **Selection wins.** Selected rows always keep the selection foreground;
   de-emphasis never fights selection contrast.
7. **Position follows convention; colour carries importance.** Hidden
   entries sort first within their group — deliberately, not as the ASCII
   accident most trees inherit ("." happening to sort before letters) —
   because the top is where every tool users know puts them. Their
   de-emphasis is already done by the grey tiers (§2.1); ordering is never
   bent to re-encode importance.

### Consequences

- ✅ Importance reads the same on every theme, light or dark, because it is
  derived from each theme's own foreground/background.
- ✅ A new recognised file type needs one icon-accent key and nothing else.
- ❌ Three tiers is a hard cap; a fourth semantic would need a new decision.
  Accepted: §1.2 says there isn't one.

## Pros and cons of the options

### Option 1: Accent hue on text

- ✅ Looks "designed" at first glance.
- ❌ Demotes the very rows it means to promote on most light themes.
- ❌ Every theme needs its own hand-tuned text colours.

### Option 2: Contrast tiers, accents on icons

- ✅ Works from the theme's existing foreground/background by derivation.
- ✅ Accent stays available for the icon, where it reads as a landmark.
- ❌ Subtler; relies on the muted tiers being tuned well enough to notice.

## Worked example

Adding a newly recognised type (say, results files): give it an icon-accent
key and full-strength text, per §2.1–2.2. The wrong move — colouring its
*text* with the new accent — is exactly the mistake §1.1 exists to prevent:
it would render the new type *less* prominent than an unrecognised file on
most light themes.

## Enforcement

Partly **Structural**: all glyph and colour selection lives in one shared
mapping (`FileVisuals`), used by every place files are rendered (the project
tree, the file dialogs), and all tiers resolve through `Kalix.tree.*` theme
keys pinned by `ThemePropertiesSnapshotTest` — so a missing or altered value
surfaces as a baseline diff, and a new file-rendering surface gets the
language by using the mapping rather than re-deriving it. The
text-never-accented rule (§2.1) is **Advisory** — held by review and by
citing this document.

## Links and references

- Related: [ADR-0002](0002-context-menu-style.md) — the tree's context menu.
- Related: `kalixide/src/main/resources/themes/README.md`.

## Amendments

- *2026-07-22* — §2.7 (hidden entries sort first; ordering never re-encodes
  importance) added the same day, with the tree polish that moved Zip to the
  Modify group.
- *2026-07-23* — Enforcement extended: the custom file dialogs
  (`KalixFileDialog`) render through the same `FileVisuals` mapping as the
  project tree.
