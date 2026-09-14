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
   reorders it. The padded build is a throwaway for attribution; the register
   records only real builds. This clause claims only what is measured:
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
