# Water management in Kalix: recommended components

*The concrete component design that follows from
[`water-allocation-review.md`](water-allocation-review.md). The review's central
finding: Australia's water allocation systems are configurations of a small
shared kernel — accounts, credit/debit policies, limits, events, resource
assessment, transfers. This document maps that kernel onto Kalix.*

## 1. Design stance

Three commitments, applied to management:

- **Small orthogonal primitives.** No `annual_accounting` node, no
  `capacity_sharing` node. Named systems are *configurations*; Kalix ships the
  dials. A monolithic named-system component would be obsolete at the first
  jurisdictional variant (of which the review catalogues dozens) and useless at
  the first novel system.
- **Policy as authored text.** Every threshold, fraction, trigger, and
  assessment that a water plan might vary should accept a DynamicExpression.
  The INI file *is* the water sharing plan, legible and diffable.
- **Accounting at engine speed.** Accounts are flat `f64` state updated by
  compiled expressions and cheap triggers — structurally identical to the
  existing hot path. A century of daily accounting over hundreds of accounts
  should cost a small constant factor over the physical simulation, so option
  ensembles (the real use case — Barma ch. 6) stay interactive.

## 2. Where Kalix stands today

The existing seams are better than expected; the account framework just built is
a correct, minimal foundation.

| Exists now | Role in this design |
|---|---|
| `Account` {name, type, size, wy_month, balance} (`src/hydrology/accounts/account.rs`) | The core state object. `account_type` string is carried but uninterpreted — the natural policy hook. A `carryover()` stub is already sketched. |
| `AccountManager` + `Trigger` (EveryTimestep / StartMonth / StartCalendarYear / StartWaterYear) + `MaintenanceGroup` (SetFull / SetEmpty) | The event system in embryo. Today's hard-coded rule — unreg accounts refill to full each water year — is *already a degenerate annual allocation system* (announce 100%, forfeit at year end). |
| `unregulated_user` node: `flow_threshold`, `pump`, `annual_cap`, `account`, `demand_carryover` | Opportunity-rights access ("dry water") is substantially built: commence-to-pump, rate limit, annual volumetric limit, account debit. |
| DynamicExpressions + `[table.*]` + `sim.*` context + temporal offsets | The policy language. Missing only the ability to *read* account state. |
| Ordering system (backward order pass, zones, lags, loss gross-up) + `storage` node (4 outlets, MOL, target level, backward-Euler) | The physical regulated-river machinery orders and delivers water — account-blind, which is correct: ownership is a ledger over the storage's fluxes, not part of its physics (§3.5). |
| `acc.<name>.balance` / `.size` recorder series | The transparency channel, already wired. |

Gaps, in increasing order of depth: accounts are 1:1 with unreg users and only
credited by refill-to-full; expressions cannot read balances; there is no
resource assessment / announcement machinery; storage fluxes (evaporation,
seepage, total inflow) are not all exposed as readable outputs; orders and
deliveries are not attributed to accounts.

## 3. The component set

### 3.1 First-class accounts — `[account.*]` sections

Promote accounts from an inline node property to standalone sections, mirroring
`[table.*]`:

```ini
[account.smith_gs]
size = 150            # account cap (ML). Expression-valued, so caps can vary.
initial_balance = 0
water_year = 7        # July
```

- Parsed in a pre-pass (like tables) so any node or expression can reference an
  account regardless of file order.
- Multiple nodes may reference one account (a works taking against a shared
  bucket — the FWAF model), and one node may reference several (deemed
  order-of-use, §3.4).
- The existing inline `account = ...` property on `unregulated_user` remains as
  sugar that declares an implicit account, preserving current models.
- Round-trip: add an `[account.*]` arm to both sides of `render_canonical_0_0_1`
  (the lesson of `ec0e803`).

### 3.2 Accounts readable in expressions — the `acc.*` namespace

Extend `DynamicInput` variable resolution so `acc.<name>.balance`,
`acc.<name>.size`, and `acc.<name>.space` (size − balance) resolve to account
state, exactly as `node.<name>.<output>` resolves to node outputs.

This single feature is the biggest unlock in the design. It makes account state
available to *every existing expression-valued parameter*: a storage's
`target_level` can depend on a reserve account's balance; a user's `demand` can
taper as its account empties; an `order_control` can clamp orders to a class's
remaining allocation; a gauge `force_flow` can implement an environmental
release rule conditioned on the contingency-allowance account. Many "allocation
system" behaviours then need no new machinery at all — they are just expressions.

(Evaluation-order note: account reads during the flow phase see start-of-step
balances, matching the existing `node.*` no-lookahead rule. Deterministic, and
documented.)

### 3.3 Generalised account events — triggers and transforms

Grow the existing `Trigger`/`MaintenanceGroup` seam into the review's event
primitive (§6.6 of the review): **trigger → transform → account set**, declared
on the account:

```ini
[account.smith_gs]
size = 150
water_year = 7
on_water_year = reduce_to(50)          # carryover limit: annual + carryover
# on_water_year = set(0)               # plain annual accounting
# on_water_year = none                 # continuous accounting
event_1_when = node.dam1.spill > 0     # expression trigger: spill forfeiture
event_1_do = scale(0.95)
```

**The trigger grammar** (settled; used at every trigger site in the platform —
account events, `announce_on`, `reconcile`, cap resets):

- A trigger is *either* a calendar keyword (`start_water_year`, `start_month`,
  `start_year`, `every_step`) *or* a single DynamicExpression evaluated as a
  pseudo-bool (nonzero = true). There is no parse ambiguity: bare identifiers
  are never valid Kalix expressions (all variables are namespaced), so the
  closed keyword set is tried first and anything else lowers as an expression.
- **Calendar keywords fire once** at the boundary, regardless of timestep
  resolution, and bind to local context (`start_water_year` uses the
  account's/group's own `water_year` month) — that is why they exist as
  keywords rather than expressions.
- **Expression triggers are level-semantic**: the transform applies on *every*
  timestep the expression is nonzero. This matches how water sharing rules are
  written — "whenever the dam is spilling, SWAs lose water" is a per-day debit
  for each day of the spill period, not a once-per-event action. Level,
  spill, and storage-full triggers (Burdekin's 148.1 mAHD; Lachlan's
  zero-and-refill) all come free.
- **Edge behaviour is not engine machinery.** When a modeller genuinely wants
  fire-once-per-crossing, the existing temporal-offset syntax expresses it:
  `node.dam1.spill > 0 && node.dam1.spill[-1, 0] == 0`. No hidden
  previous-state bit, no second semantics to learn.
- **No trigger lists.** Alternatives compose with `||` inside one expression;
  conjunction with `&&`. (Trigger + transform are therefore declared as
  *paired properties* — `event_n_when` / `event_n_do` — rather than a
  comma-separated pair, since expressions legitimately contain commas inside
  function calls. A comma-sugar form can be revisited if list syntax ever
  enters the lexicon.)
- **Per-step-aware arguments.** With level semantics, a transform that fires
  across a period compounds per step: `scale(0.95)` while spilling means ×0.95
  *per timestep*. Modellers write step rates, the same convention as `evap` /
  `seep` inputs today.

**Transforms:** `set(x)`, `set_fraction(x)`, `reduce_to(x)`, `scale(x)`,
`credit(x)`, `debit(x)`, `transfer_to(account, x)`, `expire_fifo(x, years)`
(SA's aged credits) — each argument expression-valued. This subsumes and
replaces the hard-coded refill rule; `MaintenanceType` becomes the transform
enum.

With §3.1–3.3 alone, Kalix can express: annual accounting, every
annual-plus-carryover variant (including evaporation haircuts and spill
forfeitures), continuous accounting with rolling-window use limits (window
state per §3.6), SA-style aged credits, and the two-account ABA/SWA stack
(two accounts + transfer rules + a spill write-down). That is most of the
review's configuration table before any storage or ordering work.

### 3.4 The allocation group — `[allocation.*]`

The one genuinely new *system-level* object: the review's resource assessment +
priority classes, and the WAF's "water allocation group" (entitlements that are
"apples and apples", sharing one set of rules). It is the component that decides
**how much to credit whom**, while accounts decide what happens after.

```ini
[allocation.border_gs]
method = announced                 # announced | inflow_share | event
accounts = smith_gs, jones_gs, env_gs   # members (priority order across groups, below)
assessment = node.dam1.volume + table.min_inflow(sim.month) - acc.hp_reserve.balance - acc.loss_reserve.balance
announce_on = start_month          # reassessment schedule: keyword or expression trigger
```

(`start_month` subsumes the water-year boundary for a monthly schedule; an
expression trigger works here too, and since announcements are monotone within
the year, a level-semantic trigger that stays true simply re-assesses
harmlessly.)

Note there is no `storage =` property: an allocation group has no structural
link to any node. For `announced`, the assessment expression *is* the
resource-assessment definition — whatever the pool physically is enters through
the expression (`node.dam1.volume` here). For `inflow_share`, the group's
algorithms consume four flux inputs, also given as expressions (§3.5). Either
way the group only reads series and writes account state; the storages are
unaware of it.

- **`announced`**: allocation fraction = clamp(assessment / Σ member sizes,
  0, 1), *monotone non-decreasing within the water year* (announcements only
  rise — Barma pp. 9–10); each announcement credits the increment to each
  member pro-rata by size. The assessment is an arbitrary expression: NSW's
  storage-plus-minimum-inflows, Goulburn's %-of-99%-reliable-inflows, or
  Ord-style counter-cyclical rules are all just different expressions.
- **`inflow_share`**: each member holds a share fraction; per step, credit =
  min(share × storage inflow, member's remaining space); surplus from full
  accounts redistributes iteratively pro-rata to non-full members (**internal
  spill**), overflowing to physical spill only when all are full. This is
  continuous sharing / capacity sharing — detailed in §3.5.
- **`event`**: credit when an expression condition holds (unregulated
  announcements, supplementary access, conductivity-triggered groundwater).
  Largely sugar over §3.3's `credit` transform, but grouped so a flow event is
  shared across members under a rule rather than raced for.
- **Priority tiers** are expressed by chaining groups: `[allocation.hp]` runs
  before `[allocation.gs]` and its assessment subtracts what the earlier tier
  reserved (visible in the expression, per the transparency commitment).
  Reserves are ordinary accounts owned by the system — credited by their own
  allocation group, debited by loss/priority draws — exactly the review's
  "reserves are accounts" observation.
- Every announcement and credit is recordable:
  `alloc.border_gs.fraction`, `alloc.border_gs.assessment`, per-account credit
  series. The modeller can audit the whole chain, St-George-spreadsheet style.

Timing: `announced` and `event` groups run in the existing account-maintenance
slot at the top of `run_timestep` (they read start-of-step state, and today's
orders can see today's announcements), after which account events (§3.3) fire.
`inflow_share` groups necessarily run *after* the flow phase, since they consume
the step's solved fluxes (§3.5); their credits become visible the next step.

### 3.5 Storage ownership — a ledger over storage fluxes, not storage physics

For capacity sharing the *storage* is partitioned — but the partition is pure
accounting, and it stays out of `storage_node.rs`. Everything ownership needs
from the dam is a flux the backward-Euler step already computes: total inflow,
evaporation/seepage volumes, per-outlet releases, spill, end-of-step volume.
The dam's water balance is identical whether zero or fifty accounts are
watching it. This mirrors reality: SunWater operates one physical pond at
St George and the "capacity sharing" is a ledger fed by gauged inflows and
estimated losses, trued up monthly precisely *because* ledger and physics are
separate artifacts (H&G pp. 17–20); on the Murray, one operator runs the
storages while the MDBA maintains the state accounts. Ownership is FWAF's
"account", the dam is FWAF's "works".

So ownership lives in the `inflow_share` allocation group, whose algorithms
consume four flux inputs — each an expression, because the group needs flux
*series*, not a storage:

```ini
[allocation.stgeorge]
method = inflow_share
accounts = acc.town, acc.irrigators, acc.env
shares = 0.025, 0.955, 0.02            # capacity fractions (caps derive from these)
inflow = node.beardmore.inflow + node.jack_taylor.inflow    # flux to share
losses = node.beardmore.evap_vol + node.jack_taylor.evap_vol # flux to attribute
volume = node.beardmore.volume + node.jack_taylor.volume    # reconciliation target
spill  = node.beardmore.spill + node.jack_taylor.spill      # external-spill signal
loss_attribution = by_balance          # by_balance | socialised(account) | factor(expr)
share_spill = internal                 # internal spill redistribution on full shares
reconcile = start_month                # true accounts up to the volume expression
```

- The **storage node's only change** is exposing all its fluxes as
  recordable/readable outputs (total inflow, evaporation volume, seepage
  volume, spill — some already exist, the rest are trivial and independently
  useful). No ownership state, no per-share solver changes, no cost to models
  that don't use accounts.
- Per step (post-flow-phase, alongside `record_results`): credit each account
  min(share × inflow, remaining space) with iterative **internal spill**
  redistribution; debit evaporation per the chosen attribution; debit
  withdrawals via the user-node linkage (§3.6). Accounts read during the flow
  phase therefore show start-of-step balances — the same no-lookahead rule as
  `node.*` reads, and faithful to real schemes, where orders precede the day's
  accounting.
- **Reconciliation** applies the St George asymmetry: surplus (physical >
  account sum) credited pro-rata by *capacity share*; deficit debited pro-rata
  by *balance* (H&G pp. 19–20). Physical and accounted water only diverge where
  the modeller's rules use factors that differ from the storage's physics —
  which is exactly how a real scheme should be reproduced: model the *rules*
  (TEF debits, factor-based evaporation) *and* the *physics*, and let
  reconciliation absorb the gap, as the real scheme does.
- The multi-storage "conceptual storage" (H&G p. 17) falls out for free — the
  flux expressions simply sum across storages — where a shares-on-the-node
  design would have handled it awkwardly. The same generality covers sharing a
  tributary or a reach: point `inflow` at a gauge instead of a dam.
- **The feedback direction needs no coupling either.** "Don't release water the
  user doesn't own" is enforced where orders originate — a regulated user caps
  its order by its balance (§3.6) — not inside the storage, which meets the
  summed order as it does today. Conditional access (e.g. a class that loses
  access below a lake level) is an expression on the user side
  (`... && node.dam.level > c.cutoff`), not stratified ownership in the pond:
  water in storage is fungible, and every real scheme treats it that way.
- The honest caveat: internal-spill redistribution and reconciliation are
  iterative multi-account algorithms, so they are built-in policies of the
  allocation group rather than modeller expressions — engine code in the
  accounting layer, not in the storage node.

### 3.6 User-node integration

- **`regulated_user`** gains `account` (or an ordered account list): orders are
  capped by available balance, and deliveries debit it. Two dials from the
  review: `debit = use | order` (metered take vs debit-at-release), and
  `loss_factor` (expression; the TEF — debit = order / loss_factor). An ordered
  list gives deemed order-of-use across sub-accounts (carryover first — SA,
  NSW Macquarie).
- **`unregulated_user`** is nearly complete. Additions: reference standalone
  accounts (incl. shared ones — several works on one bucket, FWAF-style);
  optional `daily_cap` distinct from pump rate (Queensland's DVL is a volume,
  `pump` is physical capacity); rolling-window use limits
  (`use_limit = 3, 3y` — a small ring buffer of annual takes, giving Gwydir's
  3-in-3 and ACT's 3×/3yr rules).
- **Supplementary/off-allocation access** falls out of composition: an
  `unregulated_user` (or a flow-conditioned account credit) attached to a
  regulated reach, gated on uncontrolled-flow conditions — expressible today as
  `usflow` in excess of order-driven flow, refined later if the ordering system
  learns to tag uncontrolled water explicitly.

### 3.7 Transfers and trade

A transfer is `debit A, credit B` — the machinery is §3.3's `transfer_to`
transform with expression triggers (rule-based transfers: SWA→ABA on
declared-safe; Murray cessions). Market *behaviour* (who trades with whom, at
what price) is emergent modeller logic, not engine machinery: expose scripted
transfers through the Python API and defer any in-engine market abstraction
until real use demands it.

### 3.8 Ordering-phase integration (deliberately last)

Today orders are physical volumes; the ordering system never sees accounts.
Phase 1–3 functionality works without touching this: users cap their own orders
by balance at order time (§3.6), which handles the common cases. True
account-aware operation — shortfall sharing by priority when a storage cannot
meet summed orders, per-owner release attribution, channel-capacity sharing —
requires threading account state into `run_order_phase`. Do it once the
accounting layer is proven, not before; it is the deepest cut and the easiest
to get wrong.

## 4. What *not* to build

- **Named-system monoliths.** Ship `examples/` models and documentation recipes
  ("Gwydir GS continuous accounting", "St George continuous sharing",
  "Victorian SWA") instead — configurations of the kernel, doubling as
  regression tests.
- **Ownership inside storage physics.** The storage node computes fluxes;
  accounts consume them (§3.5). Per-share solver state, stratified ownership,
  or per-owner outlets would complicate the hot path for no case the real
  systems exhibit — every scheme reviewed treats stored water as fungible and
  runs its ledger beside the physics, not inside it.
- **Groundwater capacity sharing / continuous accounting.** Physically
  meaningless (unquantifiable storage — Barma p. 225). Groundwater needs only
  annual/carryover accounts plus level-triggered events, which §3.1–3.3 provide.
- **A market engine.** Premature; see §3.7.
- **Hard-coded jurisdictional presets in the engine.** The review shows rules
  are renegotiated constantly; presets belong in examples and (later) IDE
  templates, never in Rust.

## 5. Phasing

Each phase ships something a modeller can use, with fixtures from the
literature.

1. **Accounts as first-class citizens.** `[account.*]` sections; `acc.*`
   readable in expressions; generalised triggers/transforms (§3.1–3.3).
   *Unlocks:* annual, annual+carryover (all variants), continuous accounting,
   ABA/SWA stacks, unregulated opportunity accounting with carryover.
   *Fixtures:* Victorian SWA worked examples — Barma Tables 28–29 give exact
   date-by-date balances for both spill and no-spill scenarios; NSW unregulated
   200%/300% window rules.
2. **Allocation groups — announced method.** Resource assessment, monotone
   announcements, priority tiers, reserves-as-accounts (§3.4). *Unlocks:*
   regulated announced-allocation systems, i.e. the bulk of NSW/Vic/Qld
   practice. *Fixtures:* Gwydir GS parameters (1.5 / 1.25 / 3-in-3, reserves,
   allocatable-storage envelope — Barma pp. 109, 183); a legacy St George
   announced-allocation model reproducing the conservative-announcement
   behaviour (H&G Tables 22–23).
3. **Storage sharing — inflow_share method.** Expose the storage node's
   remaining fluxes as outputs (small, self-contained change); then internal
   spills, evaporation attribution, and reconciliation as allocation-group
   policies over those fluxes (§3.4–3.5), plus regulated-user account debits
   with loss factors (§3.6). The storage node itself carries no ownership
   state. *Unlocks:* continuous sharing, capacity sharing, interstate bulk
   sharing. *Fixtures:* St George — H&G publishes four years of aggregate
   account balances, inflows, loss debits, reconciliations, overflows, and
   internal-spill volumes (Tables 12–24); the Murray clause-116 internal spill
   and 47:53 Border Rivers rules.
4. **Account-aware ordering and delivery** (§3.8), and transfer/trade scripting
   via the Python API (§3.7). *Unlocks:* shortfall sharing, per-owner delivery,
   behavioural trade studies.

Each phase in the established house style: engine + INI grammar (both arms of
the canonical render) + schema/templates for the IDE + `acc.*`/`alloc.*`
recorder series + example models.

## 6. Performance notes

- Account state is a flat SoA vector of `f64` balances in `AccountManager` —
  cache-friendly, trivially `Clone` for optimiser runs (the existing
  requirement, `model.rs:83`).
- All policies compile to `DynamicInput` trees at load: zero allocation, no
  string lookups, no branching beyond the expression itself (per
  `manifestos/performance.md`).
- Triggers are one compiled-expression evaluation per account-event per step;
  calendar triggers stay the current cheap timestamp checks. Allocation groups
  do O(members) work only when their trigger fires (announcements are rare);
  inflow-share crediting is O(members) per step with the iterative internal
  spill converging in ≤ members passes (in practice 1–2; St George data shows
  spill events are rare and short).
- Everything recordable is opt-in via `[outputs]`, as now — no cost when
  unobserved.

## 7. Validation targets

The corpus supplies numerical ground truth; encode it as regression tests:

1. **SWA scenarios** — Barma Tables 28–29: 100 HR + 50 LR shares, 40 ML
   carryover, 5% haircut, exact ABA/SWA balances through both scenarios.
2. **St George aggregate accounting** — H&G Tables 12/18: reproduce four years
   of monthly balances given inflows/withdrawals, incl. reconciliation credits
   and overflow (environment-spills-first) credits.
3. **Internal spill anatomy** — H&G fig. q (July 2005): accounts filling over
   6 days with surviving accounts receiving up to +200% of pro-rata share.
4. **Gwydir continuous accounting** — cap/use-limit behaviour and the 78%
   allocatable-storage envelope check (Barma p. 183).
5. **Murray bulk rules** — clause 116 internal spill, evaporation pro-rata to
   volume, SA annual tier (Barma pp. 170–173).

## 8. Open questions for the owner

1. **Naming.** `[account.*]` and `[allocation.*]` are proposed; `[share.*]`,
   `[pool.*]`, `[wag.*]` are alternatives. Also whether the FWAF vocabulary
   (entitlement / account / works) should surface in Kalix terms or stay
   implicit.
2. **Where entitlements live.** This design folds entitlement (share size,
   priority) into account + allocation-group membership. A separate entitlement
   object would ease future trade/registry modelling but adds a concept; the
   fold-in is recommended until trade studies demand more.
3. **Sub-account stacks.** Modelled here as separate accounts + transfer rules
   (maximally primitive). If ABA/SWA-style stacks become common, a sugar
   syntax on one account may be worth adding later.
4. **IDE surface.** Accounts and allocation groups want visualisation (balance
   plots come free via `acc.*` series; a dedicated accounts panel is a later
   IDE conversation).
