---
title: Code
---

# Code

Kalix is open source under the MPL 2.0. The engine is Rust, the IDE is Java, and the Python package wraps the Rust core.

<span class="kx-pill">MPL 2.0</span> <span class="kx-pill">Rust · Java · Python</span> [<span class="kx-pill kx-pill--accent">github.com/chasegan/Kalix ↗</span>](https://github.com/chasegan/Kalix)

## Ways to get involved

<div class="kx-cards" markdown>
<div class="kx-card-item" markdown>
#### Report a bug or feature
Tracked openly on [GitHub Issues](https://github.com/chasegan/Kalix/issues).
</div>
<div class="kx-card-item" markdown>
#### Build from source
Clone the repo and build the engine, IDE, or Python package (below).
</div>
<div class="kx-card-item" markdown>
#### Collaborate on development
Run a remote team on a fork, or join the core team — see [Contact](../contact/).
</div>
</div>

## Build from source

```bash
git clone https://github.com/you/Kalix.git
cd Kalix
cargo build --release        # the Rust simulation engine + CLI
./gradlew :kalixide:run      # the Java IDE
```

<div class="kx-cards" markdown>
<div class="kx-card-item" markdown>
#### Rust
Install via [rustup](https://rustup.rs/); build with `cargo`.
</div>
<div class="kx-card-item" markdown>
#### JDK 23
Temurin or equivalent; the IDE builds with the bundled `gradlew`.
</div>
<div class="kx-card-item" markdown>
#### Python 3.12+
The package is built with `maturin` from `python/`.
</div>
</div>

CI runs the full suite on Linux, macOS and Windows via GitHub Actions.

## Project health

Published automatically from CI. Regression **models** (not unit tests) are verified against pinned mass-balance baselines every release.

<div class="kx-health" markdown>
<div class="kx-stat">
  <div class="kx-stat-num {% if health.all_passed %}kx-ok{% endif %}">{{ health.models_passed | default(0) }}/{{ health.models_total | default(0) }}</div>
  <div class="kx-stat-label">Models verified</div>
</div>
<div class="kx-stat">
  <div class="kx-stat-num {% if health.models_failed == 0 %}kx-ok{% endif %}">{{ health.models_failed | default(0) }}</div>
  <div class="kx-stat-label">Failing</div>
</div>
</div>

Simulation time per regression test, across machines and releases (wired from the CI benchmark database):

<figure markdown>
![Simulation time per test across benchmark runs](assets/speed-plot.png){ .glightbox }
<figcaption>Simulation time (min of repeats, ms) per test — one line per machine, per benchmark run.</figcaption>
</figure>

## Design & brand

The site's visual system is documented alongside the design assets in the repository:

<div class="kx-cards" markdown>
<div class="kx-card-item" markdown>
#### Component & pattern sheet
Buttons, callouts, code blocks, tables, image framing — [view on GitHub ↗](https://github.com/chasegan/Kalix/tree/main/docs/web/style-guide/demo-pages).
</div>
<div class="kx-card-item" markdown>
#### Voice & tone
Plain, honest, state-don't-sell writing guidance.
</div>
<div class="kx-card-item" markdown>
#### Design tokens
`tokens.css` — the single styling source of truth.
</div>
</div>
