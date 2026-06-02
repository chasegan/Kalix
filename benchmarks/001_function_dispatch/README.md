# Function-dispatch benchmark

A synthetic Kalix model built to make expression-engine function dispatch
a meaningful fraction of total simulation cost. Useful for measuring the
impact of changes to the expression engine — e.g. parse-time function
resolution, AST optimisations, or new function additions.

## What's in the model

- **200 years × daily timestep** ≈ 73,000 simulation steps.
- **8 inflow nodes**, each with a hand-crafted `inflow` expression containing
  roughly 15 built-in function calls (`sin`, `cos`, `sqrt`, `abs`, `pow`,
  `exp`, `min`, `max`). Expressions reference both `sim.*` fields (timestep
  varying) and `c.*` constants (constant per run) so the AST traversal
  isn't trivially constant-folded.

Per-run work, very roughly:

| | per timestep | total |
|---|---:|---:|
| function-dispatch events | ~120 | ~8.7 M |
| AST evaluate calls (all node types) | thousands | ~tens of M |

The model is *not* physically meaningful; expressions are arbitrary math.
The only goal is to load the expression engine.

## Measuring

The standalone `kalix` binary has a built-in profile mode:

```bash
cargo build --release
target/release/kalix simulate benchmarks/001_function_dispatch/model.ini --profile
```

This prints a breakdown like:

```
=== Execution Profile ===
  Loading time:         0.8 ms
  Simulation time:    353.5 ms
  Output time:          0.0 ms
  Misc:                 0.0 ms
  ─────────────────────────────
  Total time:         354.3 ms
```

The number you want to watch is **Simulation time**. Loading/Output are
constant and tiny.

## Comparing two builds

Recommended workflow when assessing an expression-engine change:

1. Note the current branch / commit.
2. `git stash` any uncommitted work.
3. `git checkout <baseline-commit>` (typically `main` or the commit before
   the change).
4. `cargo build --release` and run the simulate command 3–5 times.
   Discard the first run (cold-cache effects), median the rest.
5. `git checkout <change-commit>` (or back to the working branch).
6. `cargo build --release` and re-run 3–5 times. Median again.
7. Compare medians. A real effect should be > 5% to be worth claiming.

A few sanity-check tips:
- Make sure nothing else on the machine is competing for CPU.
- `--release` is essential — debug builds have wildly different perf
  characteristics for this kind of dispatch code.
- If the difference is in the noise, the change has no measurable impact
  on this workload — which may itself be informative.

## Scaling the workload

If 350 ms is too short to measure reliably, lengthen the `end =` date in
the `[kalix]` section. Each additional century roughly doubles the
simulation time. Conversely, if it's annoyingly long, shorten the date
range or remove some nodes.
