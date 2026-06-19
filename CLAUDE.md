# Kalix

*A hydrologic and river-management modelling platform — for the people.*

Kalix simulates catchments and river systems as networks of **nodes** (active
elements — lumped river processes that modify flow) and **links** (passive
connections that pass water downstream), stepping on a constant, configurable
timestep. It spans a Rust simulation engine, a desktop IDE (Kalix IDE, Java/Swing),
a command-line tool (Kalix CLI), and a Rust-backed Python package.

## The mission

Kalix exists to **lead the open-source hydrological modelling space and show what
is possible** there — to put a fast, transparent, expert-grade modelling platform
in the hands of the people who actually do the work, free and open **forever**,
rather than gated behind expensive, closed, incumbent tools. Everything below
serves that end.

## Ethos

Kalix is focused on **core performance and technical usability**, and it is held
together by several commitments at once. **No single one defines it** — read them
as a balance to keep, not a ranking to optimise:

- **Blazing-fast simulation, pursued obsessively on the Rust side.** Speed is
  measured, not asserted; hot paths get scrutiny most projects never give them.
- **Idiomatic, clean, maintainable design on the Java side.** The IDE earns its
  keep through clear architecture and code that stays workable over years, not
  through cleverness.
- **Transparency by default.** Text-based, human-readable model files and open
  formats — nothing hidden from the modeller. Where bespoke formats exist (CSV; the
  Pixie/Gorilla compressed time-series format), they stay inspectable and
  version-control-friendly.
- **Proven hydrology, taken seriously.** The science and the numerics are correct
  first; design follows how real modelling actually works, not an approximation of it.
- **Built for serious practitioners.** People who care what happens under the hood.
  We optimise for their depth and control — not for hype or the lowest common
  denominator.
- **Forward-thinking, but grounded.** Python bindings, AI-friendly interfaces, and
  new ways of working are welcomed when they serve real modelling needs, not for
  novelty's sake.

Hold these in tension on purpose. The right call in a Rust inner loop (raw speed)
is not the right call in an IDE manager (clarity); good work knows which value
leads where while keeping the others in view. Don't flatten Kalix to a single
slogan — and don't let this short description close your mind to the parts of the
platform you aren't touching today.

## Doctrine: the manifestos

Opinionated, *settled* decisions live as **manifestos** in `manifestos/` — kept
separate from the descriptive reference in `docs/`. They exist to be **cited**, so
that something argued through once is not relitigated every time it comes up.

- **Start with [`manifestos/on-manifestos.md`](manifestos/on-manifestos.md)** —
  what a manifesto is, what it isn't, and how to write one. Read it before adding
  to the folder.
- **Treat active manifestos as binding** for the domain they cover. They are house
  style, already decided — not suggestions.
- **Cite by clause** in code comments, reviews, and commit messages
  (e.g. `per context-menu-style §2.5`) so work is traceable back to its doctrine.
- **Never silently contradict a manifesto.** If you think one is wrong, propose an
  amendment (`on-manifestos §8`); a quiet exception is just drift.

The index lives in [`manifestos/README.md`](manifestos/README.md).

## Repository map

- `src/` — the Rust simulation engine (crate `kalix`).
- `kalixide/` — the Java Swing IDE. Has its own `kalixide/CLAUDE.md` with
  IDE-specific architecture, patterns, and build notes — read it when working there.
- `python/` — the Rust-backed Python package (its own `Cargo.toml` + `pyproject.toml`).
- `manifestos/` — project doctrine (see above).
- `docs/` — descriptive specifications and reference (STDIO protocol, file formats,
  data flow). Descriptive, not prescriptive — contrast with `manifestos/`.
- `benchmarks/` — performance benchmarks.
- `regression_tests/` — the model regression suite.
- `examples/` — example models.

## Building & tests

- **Engine (Rust):** `cargo build` / `cargo test` at the repo root.
- **IDE (Java):** `./gradlew build --no-daemon` in `kalixide/` (details in
  `kalixide/CLAUDE.md`).
- **Python package:** built from `python/` via its own toolchain.
