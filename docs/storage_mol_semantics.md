# Storage outlets, minimum operating levels, and the empty storage

This note specifies how a storage node's outlets behave around their minimum
operating levels (MOLs) and as the storage empties, and describes the solver
that implements it. It replaces the earlier active-set formulation (pre-2026-08),
which suffered from outlet starvation, discontinuous allocations, and a
mass-balance leak when MOL thresholds interacted.

## The semantics: MOL bounds access to stored water

Each outlet `ds_N` may carry a MOL, specified as a level and converted to a
volume `m_i` against the dimensions table at initialisation. The governing
principle is:

> **An outlet may only be supplied from water stored above its own MOL.**

The MOL is a bound on *which water an outlet can access*, not a gate condition
on the end-of-step level. Formally, for a timestep let

- `W` = start-of-step volume (after inflow and pond diversion) plus net
  rain volume `net_rain · A(v_final)` — everything available this step,
- `S` = spill at the solved end-of-step volume (uncontrolled, drawn off the top),
- `c_i` = controlled release through outlet `i`.

The releases must satisfy the **nested access constraints**: for every
threshold `t` in `{m_1..m_4} ∪ {0}`,

```
sum of c_i over all outlets with m_i >= t   <=   max(0, W − S − t)
```

together with `c_i <= demand_i`, where `demand_i` is the order due (or forced
release), and ds_1's controlled demand is `max(0, d_1 − S)` because spill
counts toward ds_1's order.

When outlets with overlapping access compete for the same band of water,
contention resolves in fixed priority order **ds_1 > ds_2 > ds_3 > ds_4**
(the platform's existing outlet convention).

### Consequences

- **No starvation.** An outlet is never denied water by *another* outlet's
  MOL. A small ds_1 order is served from deep storage even while a huge ds_2
  order is pinned at a high MOL (the "TwoWayStorage" case); an unrestricted
  outlet is never zeroed by a clamp at a sibling's threshold.
- **Continuity.** Each release is a continuous, piecewise-linear function of
  demands and volumes. There is no binary activation, so no discontinuous
  flips when an order crosses a threshold-related magnitude.
- **Mass balance closes by construction.** The final volume is defined as
  `v_final = W − S − Σ c_i`; no allocation remainder can vanish.
- **Empty storage is the `t = 0` constraint.** An outlet without a MOL can
  draw the storage exactly to zero and never below; when total demand exceeds
  `W`, the storage lands on the table floor and releases exactly what was
  available.
- **The end-of-step level can sit below an outlet's MOL** when evaporation or
  *other* outlets drew the volume down. The outlet itself only received water
  that stood above its sill; within a daily step the transient ordering of
  draws is not resolved, and orders placed in good faith (the order phase saw
  the water) are honoured rather than shorted retroactively.
- A **forced release** follows the same access constraints as an order: it
  cannot pull from below its outlet's MOL.
- `exists = 0` behaviour is unchanged: a storage that does not exist passes
  everything through ds_1, ignoring outlets and MOLs.

## The solver: one monotone equilibrium

The backward Euler step solves a single scalar equation instead of iterating
over active outlet sets. Define the total outflow at a candidate volume `v`:

```
O(v) = S(v) + Σ c_i(v)
```

where the `c_i(v)` come from the nested-cap allocation evaluated with
`W(v) = v_work + net_rain · A(v)` and `S(v)`. `O` is continuous in `v`, so the
equilibrium error

```
e(v) = v − [W(v) − O(v)]
```

is continuous, and increasing for any non-degenerate dimensions table. The
solver:

1. **Brackets the table segment** containing the root with the existing
   exponential expansion + bisection over rows, evaluating `e` at row points
   (the allocation is a few dozen flops).
2. **Historical candidate first** — within the segment, compute the
   pre-redesign unconstrained solution (straight linear interpolation of the
   error, including the exact `max(spill, order)` kink handling on ds_1),
   with its expressions kept verbatim. If no MOL cap binds *at the candidate
   point*, accept it: every day a MOL never touches is bit-identical with
   pre-redesign results. (The check must be at the candidate, not the segment
   endpoints — a row endpoint deep in a steep spill curve can look capped
   even though the solution point is not.)
3. **Capped bisection otherwise** — a MOL cap binds, so bisect `e(v)` inside
   the segment. `e` is continuous with a sign change, so this is
   unconditionally robust; it runs only on MOL-binding days.
4. **Close the balance**: on MOL-binding (and floor/ceiling-clamped) days,
   the allocation at the solved point is authoritative and
   `v_final = W − S − Σ c_i` exactly — flows and volume satisfy mass balance
   to machine precision by construction. On historical-candidate days the
   pre-redesign flow attribution is likewise kept verbatim (order-limited
   days release the dues exactly; spill-dominant days attribute from mass
   balance, which avoids FP noise from steep spill curves).

Because the allocation caps outflow at available water, `e(row 0) <= 0`
always: the "demand exceeds everything, no sign change anywhere" degenerate
family (the Talgai floor blowup) cannot arise, though the floor guards remain
as defence in depth.

### Allocation algorithm

Greedy in priority order, exact for the nested constraint structure:

```
for i in ds_1..ds_4 (priority order):
    cap = min over thresholds t <= m_i of:
              max(0, W − S − t) − (releases already granted to outlets with m_j >= t)
    c_i = clamp(demand_i, 0, cap)
```

Granting in priority order is safe because every grant is checked against all
of its bands, and later grants subtract earlier ones; the nested (interval)
structure of the constraints makes the greedy exact.

## Not covered here (known open items)

- **Outlet capacity** (`ds_N_outlet = MOL, capacity`) is parsed but not yet
  enforced; under this design it becomes a simple extra cap `c_i <= capacity`.
- **The order phase is MOL-blind**: orders are placed and target-level
  forecasts computed as if outlets can always release. A storage below an
  outlet's MOL still propagates that outlet's orders upstream.
