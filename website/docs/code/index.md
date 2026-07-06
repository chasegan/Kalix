---
title: Code
---

# Code

Kalix is open source under the MPL 2.0. The engine is Rust, the IDE is Java, and a Python package wraps the Rust core.

[<span class="kx-pill kx-pill--accent">github.com/chasegan/Kalix ↗</span>](https://github.com/chasegan/Kalix)

## Latest stats

<div class="kx-stats-split" markdown>

<div markdown>
<div class="kx-stat">
  <div class="kx-stat-num {% if health.all_passed %}kx-ok{% endif %}">{{ health.models_passed | default(0) }}/{{ health.models_total | default(0) }}</div>
  <div class="kx-stat-label">Regression models verified</div>
</div>

Regression **models** checked in CI against pinned mass-balance baselines every release.
</div>

<figure markdown>
![Simulation time per test across benchmark runs](../assets/speed-plot.png){ .glightbox }
<figcaption>Model network simulation time (ms) as the engine improves — one line per dev machine.</figcaption>
</figure>

</div>

## Ways to get involved

<div class="kx-cards" markdown>
<div class="kx-card-item" markdown>
#### Report a bug
Tracked openly on [GitHub Issues](https://github.com/chasegan/Kalix/issues).
</div>
<div class="kx-card-item" markdown>
#### Request a feature
Email the dev team — details on the [Contact](../contact/) page.
</div>
<div class="kx-card-item" markdown>
#### Collaborate
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
