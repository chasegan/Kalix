# Performance

*The engine should run as fast as the machine allows. Bare-metal is the target.*

Kalix simulates every node at every timestep. A long run over a large network is
timesteps × nodes × the work inside each — hundreds of billions of operations is
normal, not exceptional. The simulation inner loop is where Kalix wins or loses.
This manifesto is about that loop. It is strict on purpose, and it deliberately
rejects a couple of common habits.

## 1. Fast by default — you don't need permission

Write the fast implementation the first time. You do **not** have to observe a slow
runtime, profile it, and "earn" the right to optimise. On the hot path, the fast
version *is* the default version.

The usual advice — write it naive, measure, optimise the hotspot later — assumes the
hot path is small and unknown, so you shouldn't guess where it is. Here it is large
and known: it's the simulation loop. The condition that makes the usual advice
sensible doesn't hold, so skip the detour and write it fast now.

## 2. Know the hot path

The **hot path** is everything that runs per-timestep / per-node / per-element — the
simulation inner loop and anything it calls. The rules in §3 bind there, hard.

Everything else — model loading, setup, IO, the IDE, config parsing — is the **cold
path**, where clarity leads and ordinary, readable code is exactly right. Most of
the codebase is cold. Know which one you're in before you write a line; the rules
below are not a licence to mangle cold code (§5).

## 3. Rules for the hot path

1. **A branch costs even when it isn't taken.** An `if` in the inner loop is
   evaluated hundreds of billions of times whether or not it ever fires — each one a
   fetch, a compare, and a chance to mispredict. Hoist invariant conditions out of
   the loop: decide once, at setup, and select the code path then, not per
   iteration. Before adding an `if` to the hot path, assume it will run 10^11 times.
2. **No hash maps on the hot path.** Hashing, probing, and the pointer-chase to a
   boxed value are far too slow per element. Resolve names and keys to small integer
   indices **once**, at setup, then index into contiguous arrays inside the loop.
   (A hash map is the right tool for the setup code that *builds* those indices —
   that's cold. See also `identity-and-labels §2`: resolve identity up front, don't
   carry strings into the loop.)
3. **Don't allocate in the loop.** No per-timestep or per-element allocation.
   Pre-size and reuse buffers. Allocation churn bleeds performance even when no
   single line looks hot.
4. **Lay data out for the cache.** Sequential access over contiguous arrays beats
   chasing pointers through maps, linked structures, and boxed objects. The cache
   miss, not the instruction count, is usually what costs you.

   **Do not reason about layout from declaration order.** A `repr(Rust)`
   struct's declaration order is not its memory order — rustc reorders fields
   freely — so putting a field "at the tail" of a struct does not put it at the
   tail in memory. And a struct inside an enum cannot move that enum's stride,
   which is set by its largest variant. If you need declaration order to be
   memory order, say `#[repr(C)]` and accept its padding; otherwise you do not
   control it. Measure instead: `size_of`, `offset_of`, and a benchmark model
   shaped like the workload that showed the effect (`regression_tests/speed/`).

   *Retracted, 2026-08: this clause previously prescribed "cold configuration
   goes at the tail of hot structs", citing two ~3% regressions from field
   layout alone (DataCache registries, July 2026; ConfluenceNode `regulated`
   config, August 2026). That explanation is not plausible. In `ConfluenceNode`,
   `regulated_upstream` — declared last, beneath a comment asserting it was
   deliberately at the tail — sits at offset 248 of 624; and `NodeEnum`'s stride
   is 3880 bytes, set by a far larger variant, so growing `ConfluenceNode` from
   472 to 624 bytes left the array the simulation loop walks byte-identical and
   measured as no change on a 110-node, ten-confluence model. Whether the
   original measurements were noise, a real effect with a different mechanism,
   or a real effect since erased is unresolved, and deliberately left for a
   future performance pass now that `5_ordering_confluences` exists to test
   against. This clause claims only what is measured: layout matters, and the
   mechanism is rarely the one you would guess.*

   *Third data point, 2026-09: padding `RoutingNode` (the stride-setting
   variant) by 4 KB — doubling `NodeEnum`'s stride from 3,880 to ~7,980
   bytes — measured as no change across speed tests 1/2/4/5 (deltas −3.6%
   to +1.0%, interleaved runs, sign inconsistent). Stride sensitivity is
   flat at current model scales (~110 nodes ≈ 430 KB, L2-resident), so a
   RoutingNode diet — its fourteen inline `[f64; 32]` arrays are 3,584 of
   its 3,880 bytes — buys nothing today and stays deferred until a
   cache-spilling benchmark (thousands of nodes) exists to justify it.*

5. **Do each piece of work at the coldest place it can live.** Resolution,
   validation, allocation, and branch decisions belong at setup / `initialise` time,
   not inside the loop. Work done once is work the loop never pays for again.

## 4. Measure to verify, not to permit

Benchmarks (`benchmarks/`) exist to **confirm** you reached bare-metal and to compare
real alternatives — not to grant permission to care about speed (that permission is
standing, §1). Don't claim a speedup you haven't measured, and re-run the benchmarks
when you touch the hot path so a regression can't slip in unseen.

## 5. Scope, and the balance with the rest of Kalix

This manifesto governs the **Rust engine's hot path** without compromise. It does
not justify unreadable cold code, and it does not override the IDE's commitment to
clean, maintainable design (`/CLAUDE.md`, Ethos). Speed leads in the inner loop;
clarity leads almost everywhere else. The skill is knowing which you're in.

## 6. What speed yields to

When two implementations are both correct, take the faster one — even when the
difference is small, even when it is hard to measure. "The gain is negligible" is
not an argument for the slower version; leaving time on the table has no
compensating virtue, and small losses compound across 10^11 operations and across
years of contributions. Do not turn §4's measurement discipline around: measurement
exists to verify speed claims and compare real alternatives, never to argue that a
free speedup is too small to bother taking.

Speed yields to exactly three things:

1. **Numerical correctness, always.** Numerics and mass balance are never traded
   for speed, at any magnitude. A fast wrong answer is worthless.
2. **Error semantics, enough to let the modeller find the issue.** The behaviour on
   failure — what gets detected, what gets rejected, what the modeller is told —
   may be lean, but never so lean that a problem becomes untraceable. A fast
   silence where a signal was needed costs more modeller-hours than it saves
   machine-seconds.
3. **Clarity, on the cold path only (§5)** — and the trade must name what it buys
   ("this is clearer because…"), not merely observe that the cost is small.

*Worked example: objective-function masking (2026-07). Candidate validation during
the masking pass versus checking the final objective value for NaN. The end-check
was rejected on grounds 1 and 2: `min()`, `if()`, and comparisons launder NaN into
finite values, so a blown-up parameter set could score a plausible objective (a
wrong number) with no trace of why (no signal). The benchmark — which showed the
checked loop no slower — answered a speed question; it was never the reason to
accept a slower option.*

---

*Enforcement: `benchmarks/` and `regression_tests/speed/` for verification, plus
review. The hot/cold distinction (§2) is the load-bearing judgement — most rules
here are facts about the machine, not preferences. §3.4's layout guidance and §6
are Advisory: held by review, by measurement, and by citing them when a trade is
proposed.*
