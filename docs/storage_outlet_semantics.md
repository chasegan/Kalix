# Storage outlet semantics: rating curves

How storage outlets behave around their minimum operating levels (MOLs),
their capacities, and the empty storage — and the solver that implements it.
Adopted 2026-09-02 over an access-based alternative (see "How it differs"
below for the comparison that settled it); the user-facing description lives
on the storage node page (`website/docs/docs/storage.md`).

## Semantics

Each outlet's maximum release is a function of the **end-of-step level**,
exactly as spill already is:

- no `ds_N_outlet`  → unlimited at any level;
- `ds_N_outlet = MOL` → unlimited strictly above the MOL, zero at or below it
  (a step function);
- `ds_N_outlet = MOL, capacity` → `capacity` strictly above, zero at or below
  (capacity is per-timestep volume, and is **enforced** — it was parsed but
  ignored before);
- `ds_N_outlet = level, capacity, level, capacity, ...` (2+ pairs) → a full
  **rating table**: capacity interpolates linearly in level between points and
  holds flat beyond the ends. A repeated level is an explicit step. Levels
  must be non-decreasing and inside the dimensions table's level range;
  capacities finite, non-negative, and **non-decreasing** (decreasing
  capacity, e.g. gate submergence, can create multiple equilibria in the
  implicit solve and is rejected at parse). Kalix-style implied columns:

  ```ini
  ds_2_outlet = 0.0, 0,
                8.0, 0,
                8.5, 100,
                9.0, 100
  ```

Every outlet level — bare MOL and MOL+capacity included — must lie inside
the dimensions table's level range; an out-of-range level is a config error
at initialise (previously a bare MOL outside the table interpolated to NaN
and the constraint silently vanished).

No table is required for the simple forms; internally all three compile to
one canonical capacity-vs-volume curve (the MOL forms are two-point steps),
so the solver has a single mechanism. Rating-table curves also carry
breakpoints at every dimension row their level span crosses, keeping capacity
exactly linear in volume between consecutive curve points.

Outlets couple only *implicitly through the solved level*: heavy joint demand
pulls the level down and shuts (or caps) outlets as it passes their MOLs.
The mental model: **the MOL is where the valve physically stops flowing.**

### Step functions and infinities are fine

- Infinite capacity only ever enters arithmetic as `min(demand, capacity)`,
  which is exact in IEEE — no infinities leak into sums or interpolation.
- A step makes the (monotone) equilibrium error jump upward at the threshold.
  If the root falls inside a jump — outlet on would overshoot below its MOL,
  outlet off would stay above — the generalized (Filippov) solution applies:
  **the volume parks exactly on the threshold and the jumping outlet releases
  the residual** (physically: it flowed while the level was above its sill,
  which ended the day exactly on it). Outlets sharing a threshold split the
  residual in priority order ds_1 > ds_2 > ds_3 > ds_4.

Releases are continuous in demands and volumes, and mass balance closes by
construction (`v_final = W − spill − Σ releases`; TwoWayStorage 137-y run:
worst residual 3.6e-12 ML).

## How it differs from the rejected access-based alternative

An access-based semantics ("an outlet may be supplied from any water that
stood above its MOL today") was implemented first and both fixed the
starvation and mass-balance bugs of the old active-set solver. The two
diverge exactly when *sibling demand or evap drags the end-of-step level
below an outlet's MOL*:

| Scenario (no other fluxes) | access (rejected) | rating (adopted) |
|---|---|---|
| v=80; ds_2 MOL 50 orders 30; ds_3 no MOL orders 40 | ds_2=30, ds_3=40, v=10 | ds_2=0, ds_3=40, v=40 |
| TwoWayStorage wet day (+30; ds_1 orders 8, ds_2 MOL at FSL orders 1e6) | ds_1=8, ds_2=30, v=6992 | ds_1=8, ds_2=22, v=7000 (parked) |
| v=80; ds_2 MOL 50 orders 60 (alone) | ds_2=30, v=50 | ds_2=30, v=50 (same) |

Access reads the MOL as an *entitlement* ("order fits above your MOL ⇒ order
met"); rating reads it as *hydraulics* ("the level trace never contradicts
'no flow below the MOL'"). Notably, rating reproduces what the old solver did
on its well-behaved days (regression test 17 keeps its original totals),
without the old solver's leaks and flips. Rating was adopted because a
modeller writing `ds_2_outlet = 600` most often means "this valve does not
flow below 600" — and because it generalises cleanly to capacity curves.

## Solver

Same row bracketing as the plain solver, with a step-aware within-segment
walk over the curve breakpoints (merged, sorted, and deduped once at
initialise):

1. slice the breakpoints inside the segment from the precomputed list;
2. on each threshold-free piece the outflow has at most three linear branches
   (spill covers ds_1's due / the outlet tops up to the due / the outlet is
   capacity-bound) — solve each branch exactly and keep the self-consistent
   one (this keeps 1e9-ML/ML spill kinks closed-form);
3. at each threshold, if the error jump swallows the root, park there and
   hand the residual to the jumping outlets;
4. Illinois false position remains as the fallback for degenerate pieces.

Storages without MOL outlets take the legacy path unchanged (bit-identical
with pre-redesign results; all reproducibility baselines pass).

## Performance (MOL-heavy variant of speed test 4, 11 storages, interleaved)

| engine | sim time |
|---|---|
| old active-set solver | 42.5 ms |
| access-based (rejected) | 45.7 ms |
| rating, step MOLs | 47.5 ms |
| rating, continuous rating tables | 42.4 ms |

Essentially parity on step MOLs (the naive first cut was 2.7× slower —
64-iteration bisection per day; the closed-form piece branches recovered it),
and a smooth rating taper costs nothing at all: indistinguishable from
unconstrained storages (42.7 ms), because continuous curves are the solver's
happy path — no jumps, no parking, clean linear pieces. No-MOL models are
identical in cost and results on all three engines.
