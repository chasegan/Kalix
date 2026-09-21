---
title: "ADR-0004: Performance on the hot path"
---

# ADR-0004: Performance on the hot path

- **Status**: Accepted
- **Date**: 2026-06-20 (recorded 2026-09-14, retrospectively, from the
  `performance` manifesto)
- **Deciders**: Chas Egan (@chasegan)
- **Tags**: engine

## Context and problem statement

Kalix simulates every node at every timestep. A long run over a large
network is timesteps × nodes × the work inside each — hundreds of billions
of operations is normal, not exceptional. The simulation inner loop is where
Kalix wins or loses.

The conventional advice — write it naive, measure, optimise the hotspot
later — assumes the hot path is small and unknown, so you shouldn't guess
where it is. Here it is large and known. The question is what rules bind
inside that loop, and how they are kept from leaking into the rest of the
codebase where they would do harm.

## Decision drivers

- Manifesto §2.4 — obsessive about speed: the fast implementation is the
  default implementation; speed is built in from the start, not earned
  after profiling.
- Manifesto §2.3 — correctness first; behaviour on failure never so lean
  that a problem becomes untraceable.
- Manifesto §2.6 and §3 — clarity leads off the hot path; a speed loss taken
  for clarity says what the clarity buys.

## Considered options

1. **Measure-then-optimise everywhere** — the standard discipline: profile
   first, optimise only demonstrated hotspots.
2. **Fast by default on a defined hot path, clarity elsewhere** — name the
   hot path, bind strict rules there, and keep ordinary readable code
   everywhere else.

## Decision

Chosen option: **fast by default on a defined hot path**. The rules below
are mostly facts about the machine, not preferences; the load-bearing
judgement is §2, knowing which path you are on.

### 1. Fast by default — you don't need permission

Write the fast implementation the first time. You do **not** have to observe
a slow runtime, profile it, and "earn" the right to optimise. On the hot
path, the fast version *is* the default version (Manifesto §2.4). The
condition that makes measure-first sensible — a small, unknown hot path —
does not hold here.

### 2. Know the hot path

The **hot path** is everything that runs per-timestep / per-node /
per-element — the simulation inner loop and anything it calls. The rules in
§3 bind there, hard.

Everything else — model loading, setup, IO, the IDE, config parsing — is the
**cold path**, where clarity leads and ordinary, readable code is exactly
right. Most of the codebase is cold. Know which one you're in before you
write a line; §3 is not a licence to mangle cold code (§5).

### 3. Rules for the hot path

1. **A branch costs even when it isn't taken.** An `if` in the inner loop is
   evaluated hundreds of billions of times whether or not it ever fires —
   each one a fetch, a compare, and a chance to mispredict. Hoist invariant
   conditions out of the loop: decide once, at setup, and select the code
   path then, not per iteration. Before adding an `if` to the hot path,
   assume it will run 10^11 times.
2. **No hash maps on the hot path.** Hashing, probing, and the pointer-chase
   to a boxed value are far too slow per element. Resolve names and keys to
   small integer indices **once**, at setup, then index into contiguous
   arrays inside the loop. A hash map is the right tool for the setup code
   that *builds* those indices — that's cold. See also
   [ADR-0003](0003-identity-and-labels.md): resolve identity up front,
   don't carry strings into the loop.
3. **Don't allocate in the loop.** No per-timestep or per-element
   allocation. Pre-size and reuse buffers. Allocation churn bleeds
   performance even when no single line looks hot.
4. **Lay data out for the cache — and measure, don't reason from
   declaration order.** Sequential access over contiguous arrays beats
   chasing pointers through maps, linked structures, and boxed objects; the
   cache miss, not the instruction count, is usually what costs you. But a
   `repr(Rust)` struct's declaration order is not its memory order — rustc
   reorders fields freely — and a struct inside an enum cannot move that
   enum's stride, which is set by its largest variant. If you need
   declaration order to be memory order, say `#[repr(C)]` and accept its
   padding; otherwise you do not control it. Measure instead: `size_of`,
   `offset_of`, and a benchmark model shaped like the workload
   (`regression_tests/speed/`). If a struct on the hot path changes size and
   the benchmark moves, build a third binary: the old code plus dead padding
   to the new size, doing nothing else. Run all three. Only the gap between
   the real change and the padded one is yours; the rest is layout, and
   layout moves benchmarks by several percent on its own. Print `offset_of`
   for the hot fields in each build, because padding a `repr(Rust)` struct
   reorders it. Build every arm in one worktree with the source swapped
   between builds, and check each binary by content - a symbol only the
   change has, a size, a hash - before timing it. The padded build is a
   throwaway for attribution; the register records only real builds. This
   clause claims only what is measured:
   layout matters, and the mechanism is rarely the one you would guess. (See
   Amendments for the history of this clause.)
5. **Do each piece of work at the coldest place it can live.** Resolution,
   validation, allocation, and branch decisions belong at setup /
   `initialise` time, not inside the loop. Work done once is work the loop
   never pays for again.

### 4. Measure to verify, not to permit

Benchmarks (`benchmarks/`) exist to **confirm** you reached bare-metal and to
compare real alternatives — not to grant permission to care about speed
(that permission is standing, §1). Don't claim a speedup you haven't
measured, and re-run the benchmarks when you touch the hot path so a
regression can't slip in unseen.

### 5. Scope

This ADR governs the **Rust engine's hot path** without compromise. It does
not justify unreadable cold code, and it does not override the IDE's
commitment to clean, maintainable design (Manifesto §2.6). Speed leads in
the inner loop; clarity leads almost everywhere else. The skill is knowing
which you're in.

### 6. What speed yields to

When two implementations are both correct, take the faster one — even when
the difference is small, even when it is hard to measure. "The gain is
negligible" is not an argument for the slower version; leaving time on the
table has no compensating virtue, and small losses compound across 10^11
operations and across years of contributions. Do not turn §4's measurement
discipline around: measurement exists to verify speed claims and compare
real alternatives, never to argue that a free speedup is too small to bother
taking.

Speed yields to exactly three things (Manifesto §3):

1. **Numerical correctness, always.** Numerics and mass balance are never
   traded for speed, at any magnitude. A fast wrong answer is worthless.
2. **Error semantics, enough to let the modeller find the issue.** The
   behaviour on failure — what gets detected, what gets rejected, what the
   modeller is told — may be lean, but never so lean that a problem becomes
   untraceable. A fast silence where a signal was needed costs more
   modeller-hours than it saves machine-seconds.
3. **Clarity, on the cold path only (§5)** — and the trade must name what it
   buys ("this is clearer because…"), not merely observe that the cost is
   small.

### Consequences

- ✅ The engine's inner loop is written fast the first time, by everyone,
  without a profiling detour.
- ✅ A clear hot/cold boundary keeps the strict rules from degrading the
  readability of the rest of the codebase.
- ❌ Contributors must learn where the boundary is. Accepted: §2 is short,
  and the boundary (the simulation loop) is stable.

## Pros and cons of the options

### Option 1: Measure-then-optimise everywhere

- ✅ Never optimises the wrong thing.
- ✅ The industry default; needs no explanation.
- ❌ Its premise — an unknown hot path — is false here. The detour is pure
  cost.
- ❌ Invites slow code to land "for now" in the one place slow code is
  fatal.

### Option 2: Fast by default on a defined hot path

- ✅ Matches the actual shape of the problem: one large, known loop.
- ✅ Confines the strict rules to where they pay.
- ❌ Requires the hot/cold judgement of everyone who touches the engine.

## Worked example

**Objective-function masking (2026-07).** Candidate validation during the
masking pass, versus checking the final objective value for NaN. The
end-check was rejected on §6 grounds 1 and 2: `min()`, `if()`, and
comparisons launder NaN into finite values, so a blown-up parameter set
could score a plausible objective (a wrong number) with no trace of why (no
signal). The benchmark — which showed the checked loop no slower — answered
a speed question; it was never the reason to accept a slower option.

## Enforcement

`benchmarks/` and `regression_tests/speed/` for verification, plus review.
The hot/cold distinction (§2) is the load-bearing judgement. §3.4's layout
guidance and §6 are **Advisory**: held by review, by measurement, and by
citing them when a trade is proposed.

## Links and references

- Related: [ADR-0003](0003-identity-and-labels.md) — identity resolved up
  front on the IDE side, for the same reason §3.2 forbids strings in the
  loop.
- Related: [ADR-0005](0005-node-definition-order.md) — an engine feature
  deliberately *not* added; the order check is cold-path work.

## Amendments

- *2026-07-03* — §6 "What speed yields to" added, harvested from the
  objective-function masking decision above. The original text bound speed
  without saying what it yielded to.
- *2026-08-23* — §3.4 retracted its prescription that "cold configuration
  goes at the tail of hot structs", which had cited two ~3% regressions
  from field layout alone (DataCache registries, July 2026; `ConfluenceNode`
  `regulated` config, August 2026). That explanation is not plausible. In
  `ConfluenceNode`, `regulated_upstream` — declared last, beneath a comment
  asserting it was deliberately at the tail — sits at offset 248 of 624;
  and `NodeEnum`'s stride is 3880 bytes, set by a far larger variant, so
  growing `ConfluenceNode` from 472 to 624 bytes left the array the
  simulation loop walks byte-identical and measured as no change on a
  110-node, ten-confluence model. Whether the original measurements were
  noise, a real effect with a different mechanism, or a real effect since
  erased is unresolved, and deliberately left for a future performance pass
  now that `5_ordering_confluences` exists to test against. The clause now
  reads as above: layout matters; measure, don't reason from declaration
  order.
- *2026-09-03* — third data point for §3.4: padding `RoutingNode` (the
  stride-setting variant) by 4 KB — doubling `NodeEnum`'s stride from 3,880
  to ~7,980 bytes — measured as no change across speed tests 1/2/4/5
  (deltas −3.6% to +1.0%, interleaved runs, sign inconsistent). Stride
  sensitivity is flat at current model scales (~110 nodes ≈ 430 KB,
  L2-resident), so a `RoutingNode` diet — its fourteen inline `[f64; 32]`
  arrays are 3,584 of its 3,880 bytes — buys nothing today and stays
  deferred until a cache-spilling benchmark (thousands of nodes) exists to
  justify it.
- *2026-09-10* — fourth data point for §3.4, and the padded-build
  rule above: an intra-struct size change is NOT flat, even where
  stride is. Adding the forced-level fields grew `StorageNode` from 2,616 to
  2,800 bytes with `NodeEnum`'s stride untouched at 3,880 (`RoutingNode`
  still sets it), and both storage-bearing speed tests (4, ten storages; 5,
  five) moved −6.1% against a flat storage-free control (2), by interleaved
  A/B, 12 reps. A third binary — the same baseline plus `[u64; 23]` of dead
  padding, reaching 2,800 bytes with no behaviour change — split the effect
  in opposite directions: test 4 took +1.1% (noise) from the padding and
  −4.5% from the code; test 5 took −4.2% from the padding and −0.9% (noise)
  from the code. The storage-free control was flat throughout. (The two legs
  do not compose to −6.1% — they imply ≈ −3.4% and ≈ −5.1% — so the split
  and the headline are recorded as measured, not reconciled.) Two things
  follow. A multi-percent swing in `regression_tests/speed/` can be produced
  by bytes that do nothing, so a delta, in either direction, is not a result
  without the padded build beside it; and a speedup whose attribution flips
  between models is layout luck rather than an improvement, and must not be
  banked as a property of whatever change happened to carry it. One machine,
  one CPU, one session for the split — like the notes above, and not yet
  checked on another. Source: `0f45ef5` on `feat/SID`.
- *2026-09-14* — fifth data point for §3.4, and a rule for building the
  arms. Adding an optional `loss_rate` to `RoutingNode` grew it from 3,880
  to 4,056 bytes and moved every hot array 160 bytes down the struct;
  `4_regulated_system` went +7.4% and `5_ordering_confluences` +2.5%. Four
  builds in one worktree - the baseline, the baseline padded to 4,056 bytes
  with its offsets unchanged, the baseline carrying the new fields unused so
  that only its offsets moved, and the feature - split it: size −0.6%,
  layout +0.6%, code +8.1% (and −1.2%, +0.4%, +2.7% on test 5). The cost was
  runtime `if configured` selects carried through the PWL segment loop on
  the path every unconfigured reach runs: about 10% more instructions, more
  branches, and more stack reloads. Monomorphising the division routing on
  `const LOSS: bool` - one dispatch per step, the `false` instantiation
  identical to the pre-feature loop instruction for instruction - brought
  it to +0.6% and −0.2%, at the 0.9% noise floor, with a routing-free model
  moving by the same amount. Two things follow. A branch nobody takes is not
  free inside a hot loop; §3.5 says to decide at the coldest place, and for
  a per-node option that place is compile time. And a build-hygiene rule
  learned the hard way: a shared target directory silently reused one binary
  for all four arms and produced a flat decomposition that was fiction,
  twice, before a content check caught it - hence the wording in §3.4 above.
  Separately, the same source at three checkout paths gave three different
  binaries with the hot function the same size: a fact about bytes, not yet
  shown to move a benchmark. One machine, one CPU (Apple M5). Source:
  `c55325d1` on `feat/routing-loss-rate`.
- *2026-09-15* — sixth data point for §3.4. Adding `dead_storage` to
  `RoutingNode` behind a second const generic, `route_divisions::<LOSS,
  DEAD>`, left the unconfigured arithmetic byte-identical (35 fixtures) and
  still cost +8.0% on `4_regulated_system`, +6.2% on `5_ordering_confluences`
  and +2.6% to +4.4% on tests 2 and 3, six of six rounds. Five arms in one
  worktree split it. The three new fields alone (16 bytes, stride 4,056 to
  4,072, no code change) cost +2.4% to +3.7% on tests 3-5; head with only
  the two `DEAD = false` instantiations emitted was flat on test 4; head
  with `route_divisions` marked `#[inline(never)]` was +1.2% on test 4. So
  test 4 paid about +5% for four copies of the routing loop inlined into
  the flow phase, and every routing model paid about +3% for 16 bytes of
  layout - the opposite of the 2026-09-14 finding, where 176 bytes were
  flat. Keeping one field (`dead_storage`, the share derived where used;
  4,064 bytes) and the routine out of line gave +0.5%, +0.8%, +0.9%, −0.2%
  on tests 2-5, at the noise floor. The two flags were then folded into
  one, `route_divisions::<LOSS_OR_DEAD>`: losses and dead storage are used
  together, the arithmetic with a zero loss or a zero dead level is the
  plain arithmetic, and one configured copy leaves no branch to get right
  for one property and wrong for the other. Two things follow. A const
  generic keeps the callee's arithmetic but not the caller's size: each
  instantiation the dispatch names is inlined, and a hot function that
  grows with every option should be pinned out of line and measured. And
  the layout sensitivity of a struct is a fact about this struct at this
  size, not a rule to reason from: measure the fields-only arm every time.
- *2026-09-21* — seventh data point for §3.4: a field that changed the
  code the compiler emitted, not only where the data sits. Adding
  `zero_loss_limit` (8 bytes) to `LossNode`, read in the flow phase
  alongside `usflow`, left `NodeEnum`'s stride untouched at 4,064. The
  change lets the flow phase skip its table lookup beneath the inflow where
  the table starts to lose water. Measured on three models of 60 loss nodes
  in series, 200 years daily: one with no table, one running beneath its
  table's threshold, and one with a 1% table that never takes the skip. Arms
  built in one worktree and checked by hash, 11 interleaved runs, median
  simulation time. Declared between `mbal` and `usflow`, the field landed
  next to `usflow` and the compiler fetched the two with one paired load
  (`ldp`): the skipping models went −48% and −60%, and the never-skips model
  went +24.0% (41.4 to 51.3 ms, sd under 0.5). A fields-only arm - the field
  present and set at setup, the flow phase unchanged - measured +0.6%, so it
  was not the size. Declared after `usorders`, or last in the struct, the
  field sits at one and the same offset, the two values load separately (two
  `ldr`), the never-skips model is flat (−1.1% to −1.5%), and the skipping
  models gain −24% and −42% to −44%. So the same ~10 ms, about 2.3 ns per
  node per step, left the never-skips path and appeared on the skip path.
  The two builds' flow phases are instruction-for-instruction the same apart
  from field offsets and the one paired load. The unchanged baseline was
  itself bimodal on the beneath-threshold model (41 to 56 ms across runs,
  sd 3.5 to 7.0) where every other arm held under about 1 ms. Speed tests 4
  and 5, with one loss node between them, were flat throughout. The
  placement kept is the second, because it slows nothing; the larger
  figures of the first are recorded here and not claimed. The mechanism is
  not established. A store-forwarding stall on the paired load was the
  first guess, and the second measurement does not support it on its own.
  Open questions, none yet investigated, for whenever this is taken up in
  earnest. Whether the paired load is the cause or a bystander: a throwaway
  `#[repr(C)]` build could hold the two fields adjacent while forcing
  separate loads, or the reverse. Whether the stride matters: 4,064 is 32
  bytes short of 4,096, so a field at offset X in one node shares its low
  twelve address bits with offset X − 32 in the node before it, and the flow
  phase writes the next node's `usflow` just after reading its own fields;
  a build padded to a 4,096-byte stride would show whether that aliasing is
  in play. Whether the bimodal baseline follows the address the node array
  happens to be allocated at from run to run. Whether any of it appears on
  another CPU. And how much of it is the shape of the benchmark: 60
  identical nodes in series hand each value to the next through memory,
  which is a longer store-then-load chain than a real network offers. One
  machine, one CPU (Apple M5), one session. Source: `9bdf76cf` on
  `perf/skip-table-lookup-beneath-threshold`.
- *2026-09-21* — eighth data point for §3.4: code that is never executed
  moved a benchmark, twice, in one module. Both cases came from work on the
  ordering system (`src/ordering/simple_nodewise_ordering.rs`); in both the
  variants compared gave byte-identical results over all 43 regression and
  speed models with every recorder on, so none of this is work done
  differently. Arms built in one worktree with the source swapped and
  checked by hash, interleaved runs, median simulation time, against main.
  First: recording a confluence's `harmony_fraction` on the step it is
  used. The version that reads best moved the evaluation of the fraction
  from the ordering loop into `ConfluenceNode::run_order_phase`. On
  `2_unregulated_users` - a model with no confluence, which returns from
  `run_ordering_phase` at its first line, so that not one changed
  instruction runs - it measured +3.5%, +3.0% and +2.4% in three sessions of
  15 to 20 reps, against a previous commit within +0.5% of main. A version
  that leaves the evaluation in the loop and has it call a small recording
  method on the node measured +0.4%, −0.4% and +0.5% on tests 2, 4 and 5 in
  the same session, and was kept. Second: restructuring
  `initialize`, which runs once per run, with `run_ordering_phase` untouched
  ([ADR-0008](0008-ordering-knowledge-in-exhaustive-matches.md)). A version
  that gathered each node's incoming links into a `Vec` of `Vec`s measured
  +1.8%, 0.0% and +2.6% on tests 2, 4 and 5, and +2.3% to +2.5% on test 5 in
  two earlier sessions. A version keeping the longest travel time per node
  in one flat `Vec`, and gathering the links for confluences alone,
  measured 0.0%, −0.3% and −0.2% in the same session of 25 reps, and was
  kept. Along the way, pinning `run_ordering_phase` out of line with
  `#[inline(never)]`, in the change and in main, moved none of four arms
  beyond 0.3% of each other, so whether that function is inlined into
  `run_timestep` is not the lever. Two things follow. A change confined to
  cold code is not thereby free: time it, as §4 says of the hot path,
  whenever it lands in a module the hot path lives in. And when a variant
  measures slow for no reason the source explains, the productive move has
  been to write the same thing another way and measure again, which cost
  minutes each time; reasoning about why did not predict either result. The
  mechanism is not established. Where the compiler places the hot functions
  relative to one another is the obvious suspect and was not examined: no
  disassembly was compared and no symbol addresses were read. One machine,
  one CPU (Apple M5). Sources: `a5c1795a` on `refine/ordering-at-junctions`
  and `41dd2e84` on `refactor/ordering-setup`; the rejected variants are
  described in those commit messages and were not committed.
