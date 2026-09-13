# Kalix

*A hydrologic and river-management modelling platform — for the people.*

Kalix simulates catchments and river systems as networks of **nodes** (active
elements — lumped river processes that modify flow) and **links** (passive
connections that pass water downstream), stepping on a constant, configurable
timestep. It spans a Rust simulation engine, a desktop IDE (Kalix IDE, Java/Swing),
a command-line tool (Kalix CLI), and a Rust-backed Python package.

## The Manifesto

The project's mission, values and scope live in one document:
[`website/docs/code/design/manifesto.md`](website/docs/code/design/manifesto.md)
(published at kalix.org/code/design/manifesto/). **Read it before doing
design work here.** It is the thing every decision answers to, and it is cited
by clause (`per Manifesto §2.2`).

In one breath: Kalix exists to lead the open-source hydrological modelling
space and show what is possible — free and open forever, by modellers for
modellers. Its values, held in balance and not as a ranking: free and open
(§2.1); transparency by default — what you read is what runs (§2.2); proven
hydrology, correctness first (§2.3); obsessive about speed on the engine's hot
path (§2.4); built for serious practitioners (§2.5); clarity wherever speed
isn't the point, including the language we use (§2.6); forward-thinking but
grounded in real use (§2.7). §3 says which value leads when they collide —
speed on the hot path, yielding only to correctness; clarity everywhere else;
transparency never traded for convenience.

Do not flatten Kalix to a single slogan, and do not let the part of the
platform you are working on today close your mind to the rest.

## Decisions: the ADRs

Settled decisions live as **Architecture Decision Records** in
[`website/docs/code/design/adrs/`](website/docs/code/design/adrs/index.md)
(published at kalix.org/code/design/adrs/). An ADR records one decision — the
problem, the options considered, the choice and why, and how it is enforced —
so that a question argued through once is settled by citation, not re-argued.
Ideas that were considered and *rejected* get ADRs too; they are the ones most
likely to be proposed again.

- **Start with [ADR-0001](website/docs/code/design/adrs/0001-record-decisions-in-adrs.md)** —
  the conventions, and why. The register page is the short form.
- **Treat accepted ADRs as binding** for the domain they cover. They are house
  style, already decided — not suggestions.
- **Cite by clause** in code comments, reviews, and commit messages
  (e.g. `per ADR-0002 §2.5`). A `§` number always refers to the ADR's Decision
  section.
- **When a decision isn't covered**, follow the spirit of the nearest ADR and
  the Manifesto, and flag the gap so it can be recorded if it recurs.
- **Never silently contradict an ADR.** If you think one is wrong, say so and
  propose a superseding ADR (`ADR-0001 §7`); a quiet exception is just drift.
- **Harvest, don't invent** (`ADR-0001 §2`): write an ADR when a decision has
  just been made, not in anticipation of one. Add it to the register index and
  to the site nav in `website/mkdocs.yml`.

## Repository map

- `src/` — the Rust simulation engine (crate `kalix`).
- `kalixide/` — the Java Swing IDE. Has its own `kalixide/CLAUDE.md` with
  IDE-specific architecture, patterns, and build notes — read it when working there.
- `python/` — the Rust-backed Python package (its own `Cargo.toml` + `pyproject.toml`).
- `website/` — the kalix.org site (MkDocs). Two kinds of content, deliberately
  separated:
  - `website/docs/docs/` and `website/docs/tutorials/` — **user documentation**.
    Descriptive: it explains what Kalix does and how to use it.
  - `website/docs/code/` — **for developers**. May be prescriptive. The
    Manifesto and the ADRs live here, under `code/design/`.
- `docs/` — engine-side design notes and reference (STDIO protocol, file
  formats, data flow, function docs). Descriptive; decisions belong in ADRs.
- `benchmarks/` — performance benchmarks.
- `regression_tests/` — the model regression suite.
- `examples/` — example models.
- `GOVERNANCE.md` — who decides what, and how the Manifesto and ADRs fit in.

## Building & tests

- **Engine (Rust):** `cargo build` / `cargo test` at the repo root.
- **IDE (Java):** `./gradlew build --no-daemon` in `kalixide/` (details in
  `kalixide/CLAUDE.md`).
- **Python package:** built from `python/` via its own toolchain.
- **Website:** `mkdocs build --strict` in `website/` (CI runs it strict; run
  `scripts/gen_corpus.py` first if you changed pages the help assistant reads —
  everything under `docs/`, `tutorials/` and `code/`).
