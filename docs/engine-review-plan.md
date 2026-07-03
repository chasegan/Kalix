# Engine review — findings and work plan (July 2026)

A thorough review of the Rust engine (simulation core, DataCache, DynamicInput,
nodes, optimiser, IO, STDIO protocol) was carried out on 2026-07-02. This
document records the findings that matter and the agreed plan for acting on
them, one step at a time. Update the status column as steps land.

Working rhythm per step: design note in prose → agreement → implementation on a
branch with tests → verification → merge to main. No PRs; merge directly.

## Status

| # | Step | Status |
|---|------|--------|
| 1 | Gorilla codec fixes (Rust + Java, in lockstep) | **Done** (fix/gorilla-codec) |
| 2 | Sacramento `evapuzfw` fix + revalidation | **Done** (fix/sacramento-evapuzfw) |
| 3 | STDIO interrupt: run commands on a worker thread | **Done** (fix/stdio-interrupt) |
| 4 | Optimiser robustness (NaN mask, `total_cmp`, KGE guard, SCE callback) + version string | **Done** (fix/optimiser-robustness) |
| 5 | Speed test suite + `[profile.release]` tuning | **Done** (feature/speed-suite) |
| 6 | DataCache: capacity prealloc, single-write recording, first-step read validation | **Done** (perf/datacache) |
| 7 | Remove per-point timestamps (regular grids assumed platform-wide) | **Done** (perf/remove-timestamps) |
| 8 | DynamicInput: allocation-free, infallible evaluate, short-circuit `if`/`&&`/`\|\|` | **Done** (perf/dynamic-input) |
| 9 | Small hot-loop items (Cell context, hoisted catch_unwind, dead-flag deletion) | **Done** (perf/hot-loop-small) — measured flat; kept only the simplifications |
| 10 | Optimiser: reuse workers across generations; slim Model clone | Pending |
| 11 | Optimiser: precompute per-eval invariants; split reset from topology build | Pending |
| 12 | Fast CSV + pixie IO (hand-rolled date parse/format, reused record, no per-cell alloc, byte-chunked Gorilla) | **Done** (perf/io-fast) |
| 13 | Node boilerplate: dispatch macro, recorder helper, storage ds arrays, dead-code sweep | Pending |
| 14 | Data-driven INI model IO (design discussion first) | Pending |
| 15 | Topological sort at configure time (or manifesto the file-order rule) | Pending |

## Step 1 record — Gorilla codec (done 2026-07-02)

Three defects, present identically in `src/io/compression/gorilla.rs` and
`kalixide/.../io/compression/gorilla/GorillaCompressor.java` (the two are
bit-compatible mirrors and must stay so):

1. **Value corruption**: `leading_zeros` (up to 63 for a 64-bit XOR) was written
   unclamped into the 5-bit field. Values ≥ 32 truncated → wrong reconstruction.
   Demonstrated with 1-ulp-apart doubles. Fix: clamp to 31, widen the meaningful
   window accordingly.
2. **No compression**: the compact branch was gated on `meaningful_bits <= 6`,
   a confusion of the 6-bit *field width* with a value threshold. Nearly all real
   transitions fell to the raw 64-bit fallback (66 bits/value — worse than raw).
   Fix: use the compact form whenever it is no larger than raw
   (`meaningful_bits <= 53` for doubles, `<= 21` for floats).
3. **Timestamp corruption**: the 32-bit delta-of-deltas fallback wrote the low
   32 bits of a signed value but the decoder zero-extended. Fix: sign-extend on
   read (both sides).

Verification: new property/adversarial test suites in both languages, including
a cross-language fixture (Rust-encoded bytes decoded by Java, and Java re-encodes
to the identical stream). End-to-end on the Condamine Upper model: `.pxb` output
8,420,306 → 7,864,175 bytes; all 4.9M values match the CSV output within f32
epsilon (pixie output defaults to 32-bit precision — see note).

Notes for later steps:
- Old `.pxb` files written by the buggy encoder may contain corrupted values
  (accepted; no recovery planned).
- `pixie_io::write_series` defaults to f32 storage. The float path's compact
  window (≤ 21 meaningful bits) rarely helps full-precision f32 data, which caps
  the file-size win on smooth series; round-number/operational series benefit
  most. A future option is making output precision configurable per run.

## Step 2 record — Sacramento evapuzfw (done 2026-07-03)

`sacramento/mod.rs` run_step read the *previous* timestep's free-water
evaporation (stale, already area-scaled) as this step's residual demand:
`evapuzfw = (evapt - self.evapuzfw).min(uzfwc)`. Correct form, matching the
Fors reference (`Sacramento.cs:458`): `evapuzfw = (evapt - evapuztw).min(uzfwc)`
— free water supplies demand left after tension water (E2 = min(UZFWC, EDMND − E1)).
The Fors reference does NOT have the bug; it was introduced in the C#→Rust port.

Blast radius: the branch only executes when evapt > uztwm (tension store
exhausted by one day's PET). None of the 28 regression models nor the Fors
validation dataset (uztwm 12.7–47 vs max PET 9.7) ever fires it, so all
regression baselines are unchanged and pass as-is. Real impact is limited to
catchments calibrated with small uztwm — including optimiser candidate
evaluations, which explore small uztwm freely.

Verification: two new unit tests drive the branch directly via test-only state
accessors — one pins E2 semantics with hand-derived values, one asserts the
result is independent of the previous step's evapuzfw. Mutation-checked: both
fail on the pre-fix line, pass on the fix.

## Step 3 record — STDIO interrupt (done 2026-07-03)

Commands used to execute synchronously on the session-loop thread, so `stp`
messages were only read after the command finished: the entire interrupt
apparatus was dead, and long simulations/optimisations could not be cancelled
from the IDE (demonstrated live during the review).

Changes:
- `Command::execute` now runs on a worker thread that takes ownership of the
  `Session` (model included) and returns it with the result via a channel.
  The session loop keeps reading stdin while busy (20 ms poll), so `stp`,
  `query`, and `term` are serviced mid-command. New `SessionControl` handle
  carries the shared state/interrupt Arcs.
- Worker wraps execute in `catch_unwind`: a panicking command reports an
  error instead of killing the session.
- New `Optimizer::set_interrupt_flag` (default no-op); DE polls per
  generation, SCE per shuffle, both returning best-so-far with
  `success=false`. `run_optimisation` wires in the session's flag.
- Protocol semantics pinned (spec updated): interrupted command sends `stp`
  only (previously `err`+`stp` overlapped), then `rdy rc=2`; `stp` while
  ready gets a meaningful error instead of "Unknown message type"; stdin
  closing mid-command interrupts and exits instead of computing for nobody.

Verification: unit tests for SessionControl (interrupt across a moved
session, invalid-interrupt rejection) and DE early-stop; live end-to-end
tests — `test_progress` stopped 22 ms after the stop request, and a real
Sacramento DE optimisation (50 pop, 8 threads, 40k-eval budget) stopped
0.65 s after the request (one generation, as designed) with `rdy rc=2`.
The IDE already has a full `stp`/STOPPED pipeline (JsonStdioTypes.java), so
no Java changes were needed.

Note for later: model files that pin `version = 0.0.1` are rejected by
kalix 0.3.3 ("Wrong version!") — the regression *optimisation* configs hit
this over STDIO. Worth deciding a version-compat policy in step 14.

## Step 4 record — optimiser robustness (done 2026-07-03)

Five independent correctness fixes:

1. **Candidate validation against the assessment window** (`objectives.rs`).
   The window (validity mask) is seeded once from the first evaluation
   (observed AND first candidate finite) — this is intentional design: all
   candidates are scored over the same fixed window and the window is never
   re-derived. What was missing was validation: later candidates producing
   non-finite values *inside* the window passed NaN straight into the sums
   (NaN objective → sort panic). Now such candidates are rejected with a
   clear "treated as infeasible" error, which the optimisers map to an
   infinite objective. The eight per-objective `apply_mask` copies collapsed
   into two shared helpers while touching them.
2. **`total_cmp` everywhere** objectives are sorted/compared (4 sites in
   sce.rs, 2 in commands.rs) — NaN can no longer panic the optimiser.
3. **KGE guards**: zero observed mean (beta undefined) now errors clearly,
   alongside the existing zero-variance guard.
4. **SCE honours the trait-level `progress_callback` parameter** (was
   silently ignored; SceConfig gained a manual Clone mirroring DEConfig).
5. **`get_version` over STDIO** reports the real version from the VERSION
   file (was hardcoded "0.1.0"); stale `build_date` field dropped.

Verification: 310 unit tests (7 new — window seeding/validation across all
8 objectives, KGE zero-mean, SCE callback), 28/28 regression models, and a
live STDIO optimisation run.

## Step 5 record — speed test suite + release tuning (done 2026-07-03)

New `regression_tests/speed/` suite, structured like the simulation regression
suite (numbered folders auto-detected by a python runner). Three synthetic
models, each emphasising a hot-path subsystem, generated deterministically by
`generate_models.py` (models + data committed; regenerate, don't hand-edit):

1. `1_sacramento_long` — one Sacramento node, 300 years daily (rainfall-runoff
   arithmetic; also the largest CSV load).
2. `2_unregulated_users` — 6 reaches, 72 unregulated users on scalar demands:
   the expression-free CONTROL for model 3.
3. `3_unregulated_users_with_functions` — same network with pump/threshold
   expressions patterned on the upper Condamine (DynamicInput evaluation).
   The 2-vs-3 gap isolates expression cost: 35 vs 183 ms sim (5.2x, ~71 ns
   per expression evaluation) — step 8's instrument.
4. `4_regulated_system` — 3 valleys of storages in series+parallel, lag+PWL
   routing, seasonal regulated users, common trunk (ordering + storage solver).

`run_speed_tests.py` runs each N times (bench.json) with `kalix sim -p`,
reports min/median/sd per phase, and appends history (commit, machine) to
`speed_log.txt`. Compare MIN values, same machine only. Run it whenever the
hot path changes (performance §4).

First customer: `[profile.release] lto codegen-units=1`. Measured fat vs thin
LTO on the suite; **thin won or tied everywhere** and was adopted. Sim time vs
untuned baseline (min of repeats, Apple M-series): Sacramento −20%,
unregulated −5%, regulated −4%. Build time 8s → 28s. `panic = "abort"` is
deliberately NOT set (catch_unwind error reporting); noted in Cargo.toml.
All 310 unit tests and 28/28 regression models pass on the LTO build.

## Step 8 record — DynamicInput evaluator (done 2026-07-03)

The `Function` hot path was a Box-tree walk with a `Result` branch at every
node and a heap-allocated `Vec<f64>` for every function call's arguments.
Reworked by lowering `FunctionCall` at construction time
(`dynamic_input.rs::lower_function_call`):

- Single/two-argument built-ins become `Func1`/`Func2` holding plain function
  pointers (`f64::abs`, `f64::powf`, ...) — no dispatch, no buffer.
- `if(c,a,b)` becomes a first-class `If` variant that short-circuits (only the
  taken branch evaluates); `&&`/`||` short-circuit inside the BinaryOp arm.
  Expressions are pure, so short-circuiting cannot change results; NaN
  truthiness (non-zero = true) is preserved exactly.
- Variadic `min`/`max`/`sum`/`avg` become a `Fold` variant evaluated with an
  accumulator — the argument buffer is gone entirely, no smallvec needed.
- Unknown function names and wrong argument counts are rejected at model load
  with clear messages (previously: runtime eprintln + silent 0.0). With errors
  impossible by construction, `evaluate` returns bare `f64` — the `Result`
  plumbing is gone from the hot path (`evaluate_binary_op`/`evaluate_unary_op`
  are now infallible too; all operators are total over f64 per the IEEE
  policy).

Measured on the speed suite (sim min, thin-LTO):
- 3_unregulated_users_with_functions: 183.4 -> 91-95 ms (-49%). Expression
  overhead over the expression-free control fell 148 -> ~57 ms: ~71 -> ~28 ns
  per evaluation.
- 4_regulated_system: 82.3 -> 63.6 ms (-23%) — its seasonal `if(sim.month...)`
  orders and harmony fractions benefit from the same machinery.
- Models 1 and 2 (no expressions): unchanged, as expected.

316 unit tests (6 new pinning load-time validation, fold semantics, and
short-circuit truthiness) and 28/28 regression models pass.

## Step 6 record — DataCache recording + fail-fast reads (done 2026-07-03)

Design constraint (Chas, settled): an expression referencing a value not yet
computed at that point in the timestep MUST fail clearly — never read
silently. A series' length is the watermark of how far it has been computed;
the bounds check on reads carries that contract and stays.

Changes:
- Recorder series get capacity reserved once (`DataCache::reserve_all`, called
  from configure when sim length is known); lengths still grow step by step,
  preserving the watermark. `add_value_at_index` common path is now a single
  push (was: push NaN, then overwrite). The blanket 64,000-element
  `Timeseries::new` preallocation (~1 MB/series regardless of need) is gone.
- `get_current_value` panics with a diagnostic naming the series and
  suggesting `[-1, default]`, instead of a raw index-out-of-bounds. Cost:
  identical (the check replaces the implicit bounds check; panic path compiled
  cold).
- First-timestep validation walk: on step 0 (condition is simply
  `current_step == 0` — no flag, so every fresh run re-validates), each
  Function expression walks its AST and checked-reads every zero-offset
  reference, covering branches that short-circuit evaluation would skip. An
  illegal reference now fails deterministically on step 0 of every run, even
  hidden in an `if` branch that data never selects. Empirical at point-of-use:
  no assumptions about phases or about who writes which series.
- Pinned by tests: same-step forward reference fails naming the series;
  illegal reference in an UNTAKEN branch fails on step 0 (mutation-checked:
  fails without the walk); `[-1, default]` form runs and produces the
  expected lagged values.

Performance notes (measured, interleaved A/B on the speed suite):
- First attempt regressed model 3 by ~9%: LLVM inlined the recursive
  validator into `get_value`, bloating the hot function. `#[cold]
  #[inline(never)]` on `validate_reads` fully recovered it. Lesson recorded:
  cold-path helpers called from hot functions need explicit inline barriers.
- Final numbers at parity with step 8 everywhere (7.5/36.7/94.2/64.8 ms);
  recording's former double-write was cheap — the remaining recorder cost is
  the timestamps vector (step 7).
- Half B (dropping the read bounds check after validation): measured moot.
  The checked read benchmarks at parity with plain indexing (len shares the
  Vec header cache line; branch never taken). Nothing to buy; the check
  stays, carrying the fail-fast contract. Revisit only if a profiler ever
  fingers it post-step-7.

## Step 7 record — timestamps removed from Timeseries (done 2026-07-03)

Decision (Chas, settled): timesteps are regular by design, platform-wide; no
plans for irregular series ever. The per-point `timestamps: Vec<u64>` field is
gone from `Timeseries`; the timestamp of point i is `start_timestamp +
i * step_size` (`timestamp_at(i)`).

Consequences through the codebase:
- Recording is a single value push per series per step (the timestamp push was
  the surviving half of the recorder cost identified in the review).
- CSV read still parses/validates every date (regular-spacing check kept) but
  stores timestamps only in one transient local vec instead of duplicating
  them into every column's series.
- CSV/pixie writers and `get_result` derive timestamps on the fly.
- `align_timeseries` (optimiser, per evaluation) went from building a
  HashMap of the whole simulated series to pure index arithmetic — step 11
  work forced forward by the field's removal.
- Python bindings simplified (no more prebuilt timestamp vec).

Measured (sim min vs step-6 baseline, thin-LTO):
- 1_sacramento_long: 7.5 -> 6.3-6.4 ms (**-15%**)
- 2_unregulated_users: 36.7 -> 33.3 ms (**-9%**)
- 3_unregulated_users_with_functions: 94.2 -> 85.2 ms (**-10%**)
- 4_regulated_system: 64.8 -> 59.6 ms (**-8%**)
- Peak memory: model 1 30 -> 23 MB (-25%), model 3 57 -> 35 MB (-39%).
- Load times down ~1-2 ms (CSV read no longer duplicates timestamps per column).

Outputs verified bit-identical to the pre-change binary on three speed
models; 319 unit tests and 28/28 regression models pass. The step-6
complexity back-out criterion (if flat, simplify) was not triggered: the
numbers are decisively non-flat.

## Step 9 record — small hot-loop items (done 2026-07-03)

Measured outcome: FLAT — all four speed tests within noise of the step-7
baseline. Per the standing rule (complexity must pay), only the changes that
*reduce* complexity were kept:

- Simulation context: `RefCell<SimulationContext>` replaced by two plain
  `Cell`s (phase, node index). A Cell store is a branchless write with
  nothing to poison; the struct and its borrow machinery are deleted
  (net -20 lines). Speed-neutral in practice — kept as a simplification.
- `catch_unwind` hoisted from per-timestep to around the whole loop: one
  landing pad instead of one per step; error reporting identical (context is
  thread-local, current_timestamp survives the unwind). Line-neutral,
  semantically simpler.
- SacramentoNode's `recording_obscure_things` micro-optimisation deleted —
  its own TODO suspected it was unmeasurable; it was.
- BACKED OUT: UnregulatedUserNode feature-flag bools (has_flow_threshold
  etc.) — added state that must stay in sync with the DynamicInputs, for no
  measured gain. Reverted per the back-out rule.

Conclusion recorded for future readers: at current model scales the per-node
per-step scaffolding (context writes, panic machinery, enum-discriminant
branches) is not where time lives. The remaining big-ticket items are the
optimiser loop (steps 10-11) and IO (step 12).

## Step 12 record — fast CSV and pixie IO (done 2026-07-03)

Stage-by-stage measurement first (110k-row file): chrono date parsing was 51%
of read time (try_parse_datetime always attempts a doomed NaiveDateTime parse
before NaiveDate for date-only formats, so every row paid two chrono parses);
csv-crate per-row StringRecord allocation 24%; f64 parsing only 2% — the
review's fast-float recommendation was measured moot and dropped. Write side:
format!(",{value}") per cell was 51%; unreserved String regrowth much of the
rest.

Changes (all behaviour-preserving by construction):
- Hand-rolled digit parser for the twelve known date formats (validating:
  Feb 30 rejected, 1-2 digit day/month accepted, trailing garbage rejected)
  with chrono kept for detection and as fallback — accepted inputs and error
  behaviour identical, pinned by equivalence tests against chrono.
- Read loop reuses one StringRecord (csv crate's zero-alloc pattern).
- write_ts: capacity reserved up front; values written with write! into the
  buffer (no per-cell temporary); dates via a fast civil-date formatter
  (chrono fallback outside years 0-9999). Output bytes identical.
- Gorilla BitWriter/BitReader now work byte-at-a-time instead of bit-at-a-
  time (~7x fewer loop iterations); the bitstream is identical and now
  pinned on BOTH sides by the cross-language fixture (new Rust test asserts
  the same base64 the Java test asserts).

Measured (suite, min):
- Load: model 1 54.6 -> 11.6 ms (-79%); models 2-4 ~16 -> ~8.3 ms (-48%).
- Output: model 1 58 -> 22.6 ms (-61%); others -53 to -56%.
- Model 1 total wall-clock 119 -> 41 ms (-66%). Sim times unchanged.
Verified: CSV and .pxb outputs byte-identical to the pre-change binary;
323 tests (7 new); 28/28 regression models.

## Review findings not yet scheduled (context for steps)

Full detail lives in the conversation record of 2026-07-02; the essentials:

- **Hot path** (steps 6–9): `add_value_at_index` does a length-check loop and a
  redundant NaN push per value; every cached series stores a derivable
  `timestamps` vec (doubles memory and clone cost); `DynamicInput` function
  calls heap-allocate an args Vec per evaluation; expression evaluate threads
  `Result` through every node for errors that are only detectable at parse
  time; `&&`/`||`/`if()` don't short-circuit; per-node thread-local
  `RefCell` context updates; `initialize_network()` re-runs on every
  `run()`.
- **Optimiser** (steps 10–11): rayon workers re-clone the full Model (including
  both IniDocuments and all input series) every generation; per-eval re-parsing
  of parameter targets, linear-scan series lookup, and a fresh alignment
  HashMap per term per eval; SCE double-clones simplex members and full-sorts
  after single-element replacement; DE uses `Box<dyn RngCore>`; SCE has no
  convergence-based termination; empty `cmaes.rs`/`sp_uci.rs`.
- **IO** (step 12): CSV date parsing attempts a guaranteed-to-fail datetime
  parse per row; `records()` allocates per row; std float parsing; write path
  allocates a `format!` String per cell.
- **Leanness** (steps 13–14): `node_enum.rs` is 6 hand-written 13-arm matches;
  ~45 recorder fields in StorageNode with ds_1..ds_4 fully unrolled;
  `ini_doc_model_io_0_0_1.rs` duplicates node field knowledge in two 13-arm
  matches (parse + render); dead code (commented RoutingNode optimiser block,
  legacy Optimiser trait, unused `storage` fields).
- **Open decisions**: F1 — is definition-order-as-topology doctrine or should
  configure() topo-sort? (step 15). F2 — do irregular series have a future that
  needs per-point timestamps in file-side Timeseries? (step 7).
