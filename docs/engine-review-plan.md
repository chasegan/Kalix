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
| 5 | Criterion benchmark harness + `[profile.release]` tuning | Pending — pick 2–3 representative models |
| 6 | DataCache: preallocate recorder series; name→idx hashmap | Pending |
| 7 | Remove timestamps from cache-resident series | Pending — decide irregular-series future first |
| 8 | DynamicInput: stack-allocated args, infallible evaluate, short-circuit `if`/`&&`/`\|\|` | Pending |
| 9 | Small hot-loop items (Cell-based sim context, hoisted catch_unwind, node config hoists) | Pending |
| 10 | Optimiser: reuse workers across generations; slim Model clone | Pending |
| 11 | Optimiser: precompute per-eval invariants; split reset from topology build | Pending |
| 12 | CSV fast read/write (single date parse, reused record, fast-float, buffered writes) | Pending |
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
