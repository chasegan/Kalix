# Manifestos

Opinionated doctrine about how this project should be done, and **why** — the
written, citable form of decisions we have already argued through once and do not
wish to argue through again. Cite them by clause (`per context-menu-style §2.5`)
to settle questions rather than relitigate them.

Start with **[On Manifestos](on-manifestos.md)** — what these documents are, what
they are for, and how to write one. Read it before adding to this folder.

## Index

- **[On Manifestos](on-manifestos.md)** — the charter: what a manifesto is, the
  "harvest, don't invent" rule, and the conventions every entry here follows.
- **[Context-menu style](context-menu-style.md)** — how KalixIDE structures and
  names its right-click menus (ordering skeleton, sentence case, sparse icons,
  Delete-vs-Remove), framed as Strunk & White applied to menus.
- **[Identity and labels](identity-and-labels.md)** — identity is a stable, typed
  token; the label is a projection of it, never stored, keyed on, or parsed.
  Applies to runs, nodes, datasets, files, series.
- **[Performance](performance.md)** — fast by default (no waiting for a profiler to
  grant permission); bare-metal on the engine's hot path. No hash maps, no branches,
  no allocation in the inner loop; clarity still leads on the cold path.
