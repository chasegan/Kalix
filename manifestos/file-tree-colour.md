# File-tree colour

*How the project tree encodes importance: full-strength text for what Kalix
recognises, stepped-back greys for the rest, accent hues on icons only.*

## 1. Principles

- **1.1 Prominence is contrast, not hue.** Accent colours are almost always
  lighter than a theme's foreground, so colouring important text with an accent
  visually *demotes* it. Important text keeps the full-strength
  `Tree.foreground`; importance is manufactured by muting everything else.
- **1.2 Recognised vs unrecognised is the axis.** Rows Kalix has an opinion
  about — model files, data files, folders holding models — read at full
  strength. Rows it doesn't recognise step back. There is no third semantic.
- **1.3 Colours are theme data.** Every tree colour is a `Kalix.tree.*` key in
  `resources/themes/*.properties`, tuned per theme — with one deliberate
  exception, the shared icon grey (§2.4).

## 2. Rules

- **2.1 Text has exactly three strengths.** Full `Tree.foreground` for model
  files, data files, and model folders; `Kalix.tree.mutedForeground` for
  model-less folders; `Kalix.tree.faintForeground` for unrecognised files.
  Never an accent hue on text (§1.1).
- **2.2 Accents live on icons.** `Kalix.tree.modelFileColor` on the model
  file's node-link glyph, `Kalix.tree.dataFileColor` on data-file glyphs. An
  unrecognised file's icon matches its text tier, so an icon never out-pops
  its own label.
- **2.3 Derive the grey tiers; don't pick them.** `mutedForeground` sits ~60%
  of the way from `Tree.background` to `Tree.foreground`, keeping the theme's
  tint (green-grey for Botanical, slate for Lapland); `faintForeground` is
  muted moved a further 10% toward the background. On dark themes "fainter"
  therefore correctly comes out dimmer, not lighter.
- **2.4 Folder glyphs use the shared icon grey** (`ThemeUtils.iconColor`) —
  the same colour as toolbar and menu icons, so glyphs read as one family:
  full strength for model folders, muted for the rest.
- **2.5 A model folder is direct containment only.** A folder whose own
  listing holds a `*.ini`. Ancestors do not light up: a recursive rule would
  run the signal up every chain to the root and drown it.
- **2.6 Selection wins.** Selected rows always keep the selection foreground;
  de-emphasis never fights selection contrast.
- **2.7 Position follows convention; colour carries importance.** Hidden
  entries sort first within their group — deliberately, not as the ASCII
  accident most trees inherit ("." happening to sort before letters) — because
  the top is where every tool users know puts them. Their de-emphasis is
  already done by the grey tiers (§2.1); ordering is never bent to re-encode
  importance.

## 3. Worked example

Adding a newly recognised type (say, results files): give it an icon-accent
key and full-strength text, per §2.1–2.2. The wrong move — colouring its
*text* with the new accent — is exactly the mistake §1.1 exists to prevent:
it would render the new type *less* prominent than an unrecognised file on
most light themes.

## 4. Enforcement

Partly structural: all tiers resolve through `Kalix.tree.*` theme keys in one
renderer (`FileTreeCellRenderer`), and `ThemePropertiesSnapshotTest` pins every
theme's generated keys, so a missing or altered value surfaces as a baseline
diff. The text-never-accented rule (§2.1) is **Advisory** — held by review and
by citing this document.
