# Kalix IDE themes

These `.properties` files ARE the application themes: every colour the IDE's
look and feel uses comes from here, as inspectable, diffable text. They are
loaded by `UnifiedThemeLoader`, registered in `ThemeRegistry` (one entry per
file, keyed by the file's basename — the theme's stable id), and handed to
FlatLaf via `FlatPropertiesLaf`.

## Format

Each `<id>.properties` file contains:

- `displayName=` — the name shown in the UI. Meta key; stripped before the map
  reaches FlatLaf.
- `@baseTheme=dark|light` — which FlatLaf base defaults underpin the theme
  (FlatDarkLaf or FlatLightLaf) for every key neither this file nor
  `defaults.properties` sets. Also decides `isDark()`. This is the FlatLaf
  properties convention; a theme that doesn't declare `dark` gets LIGHT base
  defaults.
- Everything else — FlatLaf UI keys (`Panel.background`, `Button.hoverBackground`,
  `Slider.thumb`, ...) plus Kalix custom keys (`MapPanel.background`,
  `MapPanel.gridlineColor`). Every key set here reaches FlatLaf verbatim.

## Defaults

`defaults.properties` supplies fallback values for keys a theme file omits.
These are light-coloured legacy defaults (they descend from the original Light
theme), so dark themes should override every key they care about rather than
rely on them. One conditional exception lives in `UnifiedThemeLoader`:
`TitlePane.unifiedBackground=false` is only defaulted for themes that opt into
a custom title bar by setting `TitlePane.background`.

Resolution order for any key: theme file, then `defaults.properties`, then
FlatLaf's `@baseTheme` base defaults.

## Provenance of the dark palettes

The dark themes (Dracula, One Dark, Obsidian, Sanne, Kalix Dark) are gleaned
from commissioned designer mocks in `kalixide/docs/design_guides/` — one
self-contained HTML per theme defining every colour as a role-named CSS custom
property. Those files are the source of truth for the dark values here and for
the matching `NodeTheme` / `SyntaxTheme` palettes; change them in step.

## Editing and adding themes

- Edit a colour: change the value here; no Java required.
- Add a theme: create `<new-id>.properties` (id is kebab-case, permanent), then
  register it in `ThemeRegistry` with its linked node and syntax palettes
  (`NodeTheme.Theme` / `SyntaxTheme.Theme`).
- `ThemePropertiesSnapshotTest` pins the exact generated properties for every
  registered theme (baselines in `src/test/resources/themes/snapshots/`). Any
  edit here shows up as a baseline diff — intentional changes are accepted by
  copying the dump the test writes to `build/theme-snapshots/` over the
  baseline.
