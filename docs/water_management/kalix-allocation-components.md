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

### 3.1 Account groups — `[acc.*]` sections

An `[acc.*]` section declares a *group* of accounts as a headed table, one row
per account — the same headed multi-line grammar the loss node's `table`
property already uses, and the shape a spreadsheet pastes into:

```ini
[acc.gs_annual]
accounts = name,      size, initial, carryover_limit,
           smith_aba, 42,   0,       20,
           jones,     105,  0,       40,
```

**Account groups are pure nouns.** A row states what an account *is* (name and
per-account data); nothing in an `[acc.*]` section ever changes a balance or
schedules behaviour — no `water_year`, no events, no policy. All verbs live in
the RAS (§3.3). A group no RAS targets is well-defined: a continuous-accounting
ledger touched only by node takes.

- **Header contract.** `name` is required; every other column is optional and
  order-free — the header, not position, assigns meaning. Missing columns take
  documented defaults (`initial` → 0). Unknown column names are a **load
  error**, never ignored: forgiving-on-absent plus strict-on-unrecognised is
  the combination that lets the schema grow new columns without ever letting a
  typo hide. `size` keeps its name from the shipped `acc.<name>.size` series;
  cell values are expression-valued where that makes sense (caps can vary).
- **Columns are per-account data, never behaviour.** Any column is readable as
  `acc.<name>.<column>` (§3.2) and usable as a per-account argument in RAS
  actions (`reduce_to(carryover_limit)`, §3.3). A proposed column that *does*
  something rather than *states* something belongs in `[ras.*]` — the test
  that moved `water_year` out of this section.
- **`accounts` is the only key an `[acc.*]` section may contain.** A future
  set-level property request is a signal the thing is policy and belongs on a
  RAS. This also keeps the grammar collision-free forever.
- **Namespace.** Account names are globally unique across all groups (the
  existing `AccountManager` duplicate check polices this — any second
  declaration is a loud load error, never a merge). Group names share the flat
  `acc.` namespace, reserving `acc.<group>.*` for set aggregates later.
- **The group is the policy-targeting unit** (the "account type" a RAS clause
  addresses). Wanting different policy for a subset means splitting the group —
  deliberate pressure: policy boundaries should surface as group boundaries.
- Parsed in a pre-pass (like tables) so any node or expression can reference an
  account regardless of file order. Multiple nodes may reference one account (a
  works taking against a shared bucket — the FWAF model), and one node may
  reference several in priority order (§3.6).
- **Nodes reference; they never declare.** The inline
  `account = name, type, size, wy_month` declaration on `unregulated_user` is
  **removed** — the property no longer exists, so old files fail with the
  standard unexpected-parameter error (the `;`-comment and `[const]`
  precedent: pre-1.0, loud, documented). It embedded policy (`wy_month`) and a
  policy hook (`type`) that now have proper homes, and inline declaration is
  structurally unable to express shared accounts without risking inconsistent
  duplicate specification.
- Round-trip: add an `[acc.*]` arm to both sides of `render_canonical_0_0_1`
  (the lesson of `ec0e803`), including column alignment as the loss-table
  renderer already does.

### 3.2 Accounts readable in expressions — the `acc.*` namespace

Extend `DynamicInput` variable resolution so `acc.<name>.balance`,
`acc.<name>.size`, `acc.<name>.space` (size − balance), and any data column of
the group table (`acc.<name>.carryover_limit`, …) resolve to account state,
exactly as `node.<name>.<output>` resolves to node outputs.

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

### 3.3 The resource allocation system — `[ras.*]`

A `[ras.*]` section is a named operator that does **one thing to the accounts
of one or more groups**. It is the single home of every policy verb — the
review's event primitive (§6.6), **trigger → transform → account set**, made
first-class. Outside node takes (§3.6), *nothing but a RAS ever mutates a
balance*: "what can touch this account?" has a one-place answer, and the RAS
sections read top-to-bottom as the water sharing plan.

The anatomy is three properties — *to whom, when, what*:

```ini
[const]
const.wy_north = 7                       # the valley's water year, stated once

[ras.gs_rollover]
targets = acc.gs_annual
trigger = start_water_year(const.wy_north)
action  = reduce_to(carryover_limit)     # column name → per-account argument
# action = set(0)                        # plain annual accounting
# (no RAS at all)                        # continuous accounting

[ras.gs_spill_forfeit]
targets = acc.gs_annual
trigger = node.dam1.spill[-1, 0] > 0     # expression trigger: spill forfeiture
action  = scale(0.95)
```

- **Exactly one `trigger` and one `action` per section.** Related steps are
  consecutive sections; RAS sections execute in **file order** within their
  timestep slot (`node-definition-order` §1 extended to policy), which is also
  how priority tiers chain (§3.4). No second intra-section ordering rule.
- `targets` lists one or more `acc.*` group references. Multiple targets are
  valid for stencilled actions; distributive actions take exactly one target
  group (tiering is chaining, not multi-targeting).
- Shared calendar facts (water-year months) are model constants, referenced by
  triggers — `[const]` already exists for exactly this.

**The trigger grammar** (settled; used at every trigger site in the platform):

- A trigger is *either* a calendar keyword (`start_water_year`, `start_month`,
  `start_year`, `every_step`) *or* a single DynamicExpression evaluated as a
  pseudo-bool (nonzero = true). There is no parse ambiguity: bare identifiers
  are never valid Kalix expressions (all variables are namespaced), so the
  closed keyword set is tried first and anything else lowers as an expression.
- **Calendar keywords fire once** at the boundary, regardless of timestep
  resolution — that is why they exist as keywords rather than expressions.
  `start_water_year(m)` takes its month explicitly (a literal or a `const.*`
  reference); the month is a fact of the plan, not of any account, so it lives
  at the trigger site and shared months live in `[const]`.
- **Expression triggers are level-semantic**: the action applies on *every*
  timestep the expression is nonzero. This matches how water sharing rules are
  written — "whenever the dam is spilling, SWAs lose water" is a per-day debit
  for each day of the spill period, not a once-per-event action. Level,
  spill, and storage-full triggers (Burdekin's 148.1 mAHD; Lachlan's
  zero-and-refill) all come free.
- **Node-output reads take the explicit previous-step offset.** A RAS runs
  before the flow phase, and the engine's no-lookahead rule is enforced, not
  papered over: `node.dam1.spill[-1, 0] > 0`, never a silent shift. Same
  doctrine as everywhere else in the expression language; a bare read of a
  yet-uncomputed series is a loud runtime error pointing at the fix.
- **Edge behaviour is not engine machinery.** When a modeller genuinely wants
  fire-once-per-crossing, the existing temporal-offset syntax expresses it:
  `node.dam1.spill > 0 && node.dam1.spill[-1, 0] == 0`. No hidden
  previous-state bit, no second semantics to learn.
- **No trigger lists.** Alternatives compose with `||` inside one expression;
  conjunction with `&&`. (`trigger` and `action` are separate properties —
  never a comma-joined pair — since expressions legitimately contain commas
  inside function calls.)
- **Per-step-aware arguments.** With level semantics, an action that fires
  across a period compounds per step: `scale(0.95)` while spilling means ×0.95
  *per timestep*. Modellers write step rates, the same convention as `evap` /
  `seep` inputs today.

**The action vocabulary.** Two families, split by scope — could this rule run
for one account knowing nothing of any other account or shared total?

- **Stencilled actions** (yes — applied to each target account independently):
  `set_full`, `set_empty`, `set(x)`, `set_fraction(x)`, `reduce_to(x)`,
  `scale(x)`, `credit(x)`, `debit(x)`, `transfer_to(account, x)`,
  `expire_fifo(x, years)` (SA's aged credits). Each argument is
  expression-valued, and a bare data-column name resolves per-account from the
  group table (`reduce_to(carryover_limit)`). `set_full`/`set_empty` exist
  today as `MaintenanceType`; this vocabulary subsumes and replaces the
  hard-coded refill rule.
- **Distributive actions** (no — one shared quantity computed once, then
  divided among the target group's accounts by a stated method; this is what
  makes a RAS a RAS): `allocate(amount, method)`,
  `apportion_loss(amount, method)`, and the storage-sharing pair
  `share_inflow` / `reconcile` (§3.5). Methods to start: `prorata_by_size`,
  `prorata_by_balance`, `fill_in_order` (group-table row order). Pretending a
  distributive rule is stencilled would force every account to duplicate the
  shared assessment and hope they agree — the same inconsistent-specification
  failure the account table eliminates for data, reappearing for behaviour.

**Timing within the step.** RAS sections run in the account-maintenance slot at
the top of `run_timestep`, in file order — so today's orders and takes see
today's announcements — then the flow phase (node debits), then recorders.
The exception is flux-consuming actions (`share_inflow`, loss apportionment of
solved losses, `reconcile`), which necessarily run *after* the flow phase,
since they consume the step's solved fluxes; their credits become visible next
step (§3.5). Expression reads keep start-of-step semantics throughout (§3.2).

With §3.1–3.3 alone, Kalix can express: annual accounting, every
annual-plus-carryover variant (including evaporation haircuts and spill
forfeitures), continuous accounting with rolling-window use limits (window
state per §3.6), SA-style aged credits, and the two-account ABA/SWA stack
(two accounts + transfer rules + a spill write-down). That is most of the
review's configuration table before any storage or ordering work.

### 3.4 Allocation methods in practice

The review's three credit methods are not section *types* — they are the
distributive and stencilled actions of §3.3, applied through the same
target/trigger/action anatomy. The account group is the "water allocation
group" (WAF's entitlements that are "apples and apples"); the RAS sections
targeting it are its rules.

**Announced allocation** is `allocate` on a schedule:

```ini
[ras.border_gs_alloc]
targets = acc.border_gs
trigger = start_month
action  = allocate(node.dam1.volume + table.min_inflow(sim.month)
                   - acc.hp_reserve.balance - acc.loss_reserve.balance,
                   prorata_by_size)
```

- Allocation fraction = clamp(assessment / Σ target sizes, 0, 1), *monotone
  non-decreasing within the water year* (announcements only rise — Barma
  pp. 9–10); each firing credits the increment pro-rata. Since announcements
  are monotone, a level-semantic expression trigger that stays true simply
  re-assesses harmlessly.
- The first argument *is* the resource-assessment definition: NSW's
  storage-plus-minimum-inflows, Goulburn's %-of-99%-reliable-inflows, or
  Ord-style counter-cyclical rules are all just different expressions. Note
  there is no `storage =` property — a RAS has no structural link to any node;
  whatever the pool physically is enters through the expression. The RAS only
  reads series and writes account state; the storages are unaware of it.

**Event credits** (unregulated announcements, supplementary access,
conductivity-triggered groundwater) are the stencilled `credit(x)` under an
expression trigger — targeting the group means a flow event is shared across
its accounts under a rule rather than raced for.

**Inflow sharing** (continuous sharing / capacity sharing) is the `share_inflow`
/ `apportion_loss` / `reconcile` action set, detailed in §3.5.

**Priority tiers** are consecutive RAS sections: `[ras.hp_alloc]` appears
before `[ras.gs_alloc]`, and the later tier's assessment subtracts what the
earlier tier reserved (visible in the expression, per the transparency
commitment). Reserves are ordinary accounts — credited by their own RAS,
debited by loss/priority draws — exactly the review's "reserves are accounts"
observation.

Every announcement and credit is recordable: `ras.border_gs_alloc.fraction`,
`ras.border_gs_alloc.assessment`, per-account credit series. The modeller can
audit the whole chain, St-George-spreadsheet style.

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

So ownership lives in RAS sections whose actions consume fluxes — each an
expression, because the ledger needs flux *series*, not a storage. Capacity
fractions are per-account data, so they are a column of the account group:

```ini
[acc.stgeorge]
accounts = name,       size,  share,
           town,       2600,  0.025,
           irrigators, 99300, 0.955,
           env,        2100,  0.02,

[ras.stg_credit]
targets = acc.stgeorge
trigger = every_step
action  = share_inflow(node.beardmore.inflow + node.jack_taylor.inflow,
                       by = share, spill = internal)

[ras.stg_losses]
targets = acc.stgeorge
trigger = every_step
action  = apportion_loss(node.beardmore.evap_vol + node.jack_taylor.evap_vol,
                         prorata_by_balance)

[ras.stg_reconcile]
targets = acc.stgeorge
trigger = start_month
action  = reconcile(node.beardmore.volume + node.jack_taylor.volume)
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
  (`... && node.dam.level > const.cutoff`), not stratified ownership in the pond:
  water in storage is fungible, and every real scheme treats it that way.
- The honest caveat: internal-spill redistribution and reconciliation are
  iterative multi-account algorithms, so they are built-in distributive
  actions rather than modeller expressions — engine code in the accounting
  layer, not in the storage node.

### 3.6 User-node integration

Both user nodes carry the same linkage property — **reference-only, never
declaration** (§3.1):

```ini
accounts = smith_swa, smith_aba      # ordered: deemed order-of-use
```

One key, plural even for a single account. List order is order-of-use: each
step the take draws the first account down before touching the second, so
available volume is the sum and the debit cascades (SWA-before-ABA; carryover
first — SA, NSW Macquarie).

- **`regulated_user`** gains `accounts`: orders are capped by available
  balance, and deliveries debit it. Two dials from the review:
  `debit = use | order` (metered take vs debit-at-release), and `loss_factor`
  (expression; the TEF — debit = order / loss_factor).
- **`unregulated_user`** replaces its inline `account` declaration with
  `accounts` references (hard break, §3.1). Other additions: optional
  `daily_cap` distinct from pump rate (Queensland's DVL is a volume, `pump` is
  physical capacity); rolling-window use limits (`use_limit = 3, 3y` — a small
  ring buffer of annual takes, giving Gwydir's 3-in-3 and ACT's 3×/3yr rules).
- **Supplementary/off-allocation access** falls out of composition: an
  `unregulated_user` (or a flow-conditioned account credit) attached to a
  regulated reach, gated on uncontrolled-flow conditions — expressible today as
  `usflow` in excess of order-driven flow, refined later if the ordering system
  learns to tag uncontrolled water explicitly.

### 3.7 Transfers and trade

A transfer is `debit A, credit B` — the machinery is §3.3's `transfer_to`
action with expression triggers (rule-based transfers: SWA→ABA on
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

1. **Account groups and the RAS kernel.** `[acc.*]` group tables; `acc.*`
   readable in expressions; `[ras.*]` target/trigger/action with the
   stencilled action set; node `accounts` references (§3.1–3.3, §3.6).
   *Unlocks:* annual, annual+carryover (all variants), continuous accounting,
   ABA/SWA stacks, unregulated opportunity accounting with carryover.
   *Fixtures:* Victorian SWA worked examples — Barma Tables 28–29 give exact
   date-by-date balances for both spill and no-spill scenarios; NSW unregulated
   200%/300% window rules.
2. **Distributive actions — `allocate`.** Resource assessment, monotone
   announcements, priority tiers via chained sections, reserves-as-accounts
   (§3.4). *Unlocks:* regulated announced-allocation systems, i.e. the bulk of
   NSW/Vic/Qld practice. *Fixtures:* Gwydir GS parameters (1.5 / 1.25 /
   3-in-3, reserves, allocatable-storage envelope — Barma pp. 109, 183); a
   legacy St George announced-allocation model reproducing the
   conservative-announcement behaviour (H&G Tables 22–23).
3. **Storage sharing — `share_inflow` / `apportion_loss` / `reconcile`.**
   Expose the storage node's remaining fluxes as outputs (small,
   self-contained change); then internal spills, evaporation attribution, and
   reconciliation as post-flow-phase distributive actions over those fluxes
   (§3.4–3.5), plus regulated-user account debits with loss factors (§3.6).
   The storage node itself carries no ownership state. *Unlocks:* continuous sharing, capacity sharing, interstate bulk
   sharing. *Fixtures:* St George — H&G publishes four years of aggregate
   account balances, inflows, loss debits, reconciliations, overflows, and
   internal-spill volumes (Tables 12–24); the Murray clause-116 internal spill
   and 47:53 Border Rivers rules.
4. **Account-aware ordering and delivery** (§3.8), and transfer/trade scripting
   via the Python API (§3.7). *Unlocks:* shortfall sharing, per-owner delivery,
   behavioural trade studies.

Each phase in the established house style: engine + INI grammar (both arms of
the canonical render) + schema/templates for the IDE + `acc.*`/`ras.*`
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

1. **Naming.** *Settled (July 2026), both halves.* Sections equal their
   reference prefix, per the platform convention (`node`, `var`, `table`,
   `fn`, and `const` as of the `[constants]` → `[const]` rename).
   - **Accounts: `[acc.*]`** with expression/recorder prefix `acc.`. The
     prefix side was already fixed — `acc.<name>.balance`/`.size` recorder
     series shipped in 0.3.6 — so `[acc.*]` is the only spelling that
     achieves consistency without a breaking change.
   - **Allocation groups: `[ras.*]`** ("Resource Allocation System") with
     series prefix `ras.` (`ras.<name>.fraction`, `.assessment`, …).
     Chosen over `[alloc.*]` because the section names an *actor* whose
     remit is wider than allocating — assessment, announcements, loss
     apportionment, forfeiture, write-offs, reconciliation are all "what a
     RAS does" — and because the term is live practitioner vocabulary for
     exactly this concept (cf. eWater Source's Resource Assessment System).
     Known costs, accepted: the HEC-RAS association at first glance, and
     acronym opacity outside Australia (mitigated by one line of docs).

   Still open here: whether the FWAF vocabulary (entitlement / account /
   works) should surface in Kalix terms or stay implicit.
2. **Where entitlements live.** This design folds entitlement (share size,
   priority) into account + allocation-group membership. A separate entitlement
   object would ease future trade/registry modelling but adds a concept; the
   fold-in is recommended until trade studies demand more.
3. **Sub-account stacks.** Substantially resolved by two settled pieces:
   ordered `accounts` lists on user nodes (deemed order-of-use, §3.6) plus
   `transfer_to` rules give the ABA/SWA stack pattern with no new concept. If
   stacks become very common, a sugar syntax on one account may still be worth
   adding later.
4. **IDE surface.** Accounts and allocation groups want visualisation (balance
   plots come free via `acc.*` series; a dedicated accounts panel is a later
   IDE conversation).
