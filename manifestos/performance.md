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

---

*Enforcement: `benchmarks/` for verification, plus review. The hot/cold distinction
(§2) is the load-bearing judgement — most rules here are facts about the machine,
not preferences.*
