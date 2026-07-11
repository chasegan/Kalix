# Water allocation systems: a review and synthesis

*A review of Australian water allocation systems — their history, mechanics, and
underlying structure — undertaken to ground the design of Kalix's water
management functionality. The companion document,
[`kalix-allocation-components.md`](kalix-allocation-components.md), turns this
review into a concrete component design.*

**Primary sources**

- Barma Water Resources et al. (2011), *Water allocation systems: exploring
  opportunities for reform*, Waterlines Report No 65, National Water Commission.
  Cited as **(Barma p. n)**.
- Hughes, N & Goesch, T (2009), *Capacity sharing in the St George and MacIntyre
  Brook irrigation schemes in southern Queensland*, ABARE research report 09.12.
  Cited as **(H&G p. n)**.
- D. Barma, *MDB Surface Water Allocation Systems* (slide deck).
- Queensland DRDMW internal material: *Future Water Accounting Framework* deck
  (Nov 2021), *Water Authorisation Framework* note (Sep 2023), and *Pocket
  Primer: how the NCP changed water* deck. Cited as **(FWAF)**, **(WAF)**,
  **(Primer)**.

---

## 1. Why this review

Kalix is adding management: rules that decide *who* may take water, *how much*,
and *when* — layered over the physical simulation of storages, links, and users.
The obvious path is to implement the named allocation systems of Australian
practice (annual accounting, continuous accounting, continuous sharing,
unregulated access rules) as discrete features, one per system. That is broadly
what incumbent platforms did.

This review reaches a different conclusion. The named systems are not discrete
things. They are recurring *configurations* of a small shared kernel of
accounting primitives, and the literature — especially Barma's national review —
demonstrates this both explicitly (the systems "operate along a continuum",
Barma p. 7) and implicitly (every real system it catalogues is already a hybrid
of the named types). A platform that implements the kernel gets every named
system, every jurisdictional variant, and — critically — every *future* system
that policy-makers have not yet invented, for roughly the cost of implementing
two named systems as monoliths.

## 2. What a water allocation system is

The definitions matter, because they fix the vocabulary of the design.

- A **water access entitlement** is a perpetual right to *a share* of a
  specified consumptive pool (NWI definition, Barma p. 1). It is not water; it
  is a claim on whatever water becomes available.
- A **water allocation** is the specific volume credited against an entitlement
  in a given season (Barma p. 1).
- A **water allocation system** is "the set of policies and rules used to
  determine and distribute the available water to the accounts of water access
  entitlement holders. Water accounts are generally established for each water
  access entitlement. Water is credited to the account when an allocation is
  made, and debited when water is extracted" (Barma p. 1).

So at its core, every allocation system in the country — however baroque — is a
set of rules for **crediting and debiting accounts**. This is the single most
load-bearing fact in the whole corpus.

## 3. How Australia got here

The history explains why the systems look the way they do, and why they will
keep changing — which is the strongest argument for building configurable
machinery rather than fixed replicas.

1. **Riparian rights → land-tied licences (1910–1989).** English common law
   riparian doctrine was progressively extinguished by state licensing Acts.
   Rights were issued case-by-case, tied to land, revocable, and constructed to
   drive "beneficial use" — a licence might oblige the holder to install a pump
   and use the water (Primer).
2. **The NCP reforms (1994–2004).** The CoAG Water Reform Framework and then
   the National Water Initiative separated water from land title and required
   entitlements to be specified as secure, tradeable, mortgageable *shares* of
   a capped consumptive pool, recorded in public registers, compensable if
   diminished (Primer; Barma p. 1). Water became property.
3. **Formal volumetric sharing (1960s onward).** Annual allocation systems
   emerged from drought-era competition; carryover appeared in the variable
   northern systems in the 1980s; NSW converted several valleys to continuous
   accounting in the late 1990s; Queensland's continuous sharing went live at
   St George in 2000; Victoria bolted spillable water accounts onto its annual
   system in 2010–11 (Barma pp. 92–93). Each step was a *renegotiation of the
   same dials* under drought pressure, not the invention of new physics.
4. **The environment becomes a user (2005 onward).** Government purchase
   programs made environmental water holders the largest entitlement holders in
   many valleys (>20% of general-security shares in the Gwydir; Barma pp. 39–41).
   Their demand pattern — bank in wet years, spend in dry, occasionally flood a
   wetland — is close to the *inverse* of an irrigator's, and the systems
   designed around irrigation demand fit them poorly (Barma pp. 41–45). This is
   the canonical driver of future reform: new user types with new demand
   patterns (Barma p. 39).
5. **The accounting frontier (Queensland, 2021–).** The FWAF work separates
   three concepts that older frameworks conflate: the **entitlement** (a share
   of the resource, ownable by anyone, "like ASX shares"), the **water bank
   account** (a bucket attached to the point of take, into which announced
   allocations are credited and against which metered take is debited), and the
   **works** (the pump/meter, held under an operating authority by the person
   who actually takes water) (FWAF). Compliance follows the *taker*, not the
   *owner*. The companion authorisation-framework note distils the doctrine:
   manage **at the point of take**; entitlements should be "apples and apples"
   within a **water allocation group**; and — verbatim — *"hydrologic modelling
   needs to be supportive of, and not the determinant of, water resource
   policy"* (WAF).

That last line is a design mandate. If the model can only express the systems
that exist today, the model becomes the constraint on policy. Kalix should be
built so that it never is.

## 4. The named systems

The recognised taxonomy (Barma ch. 2), with defining mechanics.

### 4.1 Non-volumetric systems
No volumes are allocated. Use is controlled indirectly: pump or bore size
limits, maximum irrigated area, extraction-rate limits, flow or groundwater
level conditions, seasonal windows, purpose restrictions (Barma p. 8). Still
common in undeveloped sources; the floor of the continuum. Note that a
volumetric system without metering degrades to this — "without monitoring and
compliance arrangements in place there is little value to be gained from
implementing any form of volumetric allocation system" (Barma p. 57).

### 4.2 Annual allocation
Water available for the year is assessed and **announced** as a percentage of
entitlement, credited to accounts, and topped up progressively if further
inflows arrive; at the end of the water year all unused credits are **forfeited**
and the pool is redistributed (Barma pp. 8–10). Usually paired with **reserves**:
water held back in storage so that high-priority users get 100% through a repeat
of the worst drought on record (NSW rule, Barma pp. 25, 108). Applied almost
universally to high-priority/high-security classes, whose demand is inflexible
anyway.

### 4.3 Annual allocation with carryover
As above, but at year end the account is reduced to a **carryover limit** rather
than zero. The recurring dials (Barma pp. 11–12):

- carryover limit (e.g. 50% of entitlement; 0.3 ML/share Murrumbidgee; 0.5 ML/ha
  Murray),
- maximum account volume (e.g. 120% of entitlement),
- an evaporation deduction on carried-over water (~5%, Victoria),
- treatment of carryover on storage spill (see §7.3),
- sometimes a **separate carryover account** with its own rules: debited first,
  evaporates, spills first (NSW Macquarie; Barma p. 43, p. 110).

The **spillable water account** (Victoria, 2010–11) is a variant with a
two-account stack per holder: a capped allocation bank account (ABA) that can be
used and traded, and an uncapped SWA holding the excess, which cannot be used or
traded, loses water pro-rata whenever the dam physically spills, and transfers
back to the ABA when the manager declares spill risk passed (Barma pp. 14, 154–155;
worked examples in Tables 28–29, pp. 155–156 — these make excellent test
fixtures).

### 4.4 Continuous accounting
Delete the end-of-year event. Account limits and all other rules apply *at all
times*; old and new water are indistinguishable (Barma p. 15). Example (Gwydir
general security): account capped at 1.5 ML/share — allocation "spills" from the
account at the cap — with use limits of 1.25 ML/share/year and 3 ML/share over
any 3 years (Barma pp. 15, 109). Variants zero-and-refill all accounts when the
system storage fills (Lachlan, p. 111). The 3-parameter pattern (carryover limit /
account limit / annual-use limit) also describes every NSW groundwater source in
tabular form (Barma p. 116).

### 4.5 Capacity sharing and continuous sharing
The conceptual jump (Dudley & Musgrave 1988): stop sharing *releases* and share
the *storage itself* — each user holds a percentage of storage capacity and a
percentage of inflows, bears their share of losses, and decides for themselves
when to store and when to release (Barma pp. 17–18; H&G p. 6). The manager stops
announcing allocations altogether; there is nothing to announce. Distinctive
mechanics:

- **credit** = min(share × inflow, remaining space in the user's share)
  (Barma Fig. 9, p. 18);
- **internal spill**: inflows exceeding a full account are credited to the
  other, non-full accounts pro-rata by share — iteratively, until either
  absorbed or the dam physically spills (Barma p. 19; H&G pp. 20, 45–47, with a
  documented event anatomy: in July 2005 St George reallocated 8,132 ML in four
  days, and accounts that filled on day one forfeited 100% of their inflow
  share by day two);
- **individualised losses**: evaporation debited in proportion to each
  account's stored volume (H&G p. 19).

**Continuous sharing** (St George 2000, MacIntyre Brook 2008) is the pragmatic
Queensland implementation: inflow and storage shares are locked together and not
separately tradeable; multiple physical storages are collapsed into one
"conceptual storage" with the operator retaining discretion; delivery losses are
embedded in static per-zone **transmission efficiency factors** (an account is
debited order/TEF, so a zone-C MacIntyre Brook user loses 35% of every order);
and a **monthly reconciliation** trues the sum of accounts up to the gauged
physical storage — surpluses credited pro-rata by *capacity share* (like
inflows), deficits debited pro-rata by *balance* (like losses), with loss
factors deliberately set conservative so reconciliations are small credits
rather than clawbacks (H&G pp. 17–20). Entitlement conversion preserved every
user's historical on-farm yield: reliability-normalise (1 ML high priority ≡
1.75 ML medium priority), gross up by 1/TEF, then pro-rata into capacity
(H&G pp. 17–18). A separate **annual use cap** equal to the old nominal
entitlement rides alongside the account for Murray-Darling Basin Cap compliance
(H&G pp. 28–29).

The scheme works. Uptake reached >99% of entitlement volume; administrative
burden *fell* (several water engineers → one accounting manager); allocation
disputes largely disappeared; and one irrigator replicated his account exactly
in a homemade spreadsheet — the accounting is deterministic and auditable
(H&G pp. 22, 32–33, 48).

### 4.6 Unregulated systems: opportunity rights
In unregulated rivers the allocation system distributes **"opportunity rights"
— the right to take volumes of water if and when flows occur** (Barma p. 7).
Access is governed by commence/cease-to-pump flow thresholds, daily volumetric
limits, annual volumetric limits, rostering or announcements where sharing is
contested, and total-pool extraction limits with multi-year compliance tests
(Barma pp. 26, 114–115, 130). Queensland's FWAF frames this as **"dry water"** —
conditional, event-driven, primary accounting at the *daily* timescale (daily
account credited when the flow condition is met; annual accounts secondary) —
versus the **"wet water"** of announced storage allocations (FWAF). Carryover
here means carrying the *opportunity*, not water; there is nothing stored
(Barma p. 12). Since storage cannot be defined, capacity sharing and continuous
accounting are physically impossible in these sources (Barma p. 56) — the same
argument excludes them from groundwater, where storage exists but cannot be
quantified (Barma p. 225).

### 4.7 Hybrid and tiered systems
"A hybrid system is one where several allocation systems are used for sharing
within the one water source", typically **tiered**: one system at the bulk
level, others at licence level — e.g. capacity sharing between states, annual
allocation for high-reliability licences within a state's share, carryover for
general-reliability (Barma p. 20). This is not an exotic case; as §5 shows, it
is the *normal* case.

## 5. The continuum — and why it is really a configuration space

Barma's explicit claim is that volumetric systems "operate along a continuum" on
two axes: **when allocated water can be used** (must-use-this-year → no time
constraint) and **how completely individual storage access rights are defined**
(pooled and manager-mediated → fully individualised) (Barma p. 7, Fig. 1:
annual → annual+carryover → continuous accounting → capacity sharing).

The evidence in the corpus supports something stronger: the named systems are
points in a small configuration space, and real systems freely mix coordinates.

**Exhibit A — the Murray is all of them at once.** The NSW–Victoria interstate
arrangement, "generally referred to as continuous accounting", simultaneously
exhibits: capacity sharing (50:50 shares of inflows and storage; internal spill
to the other state when one share fills — MDB Agreement clause 116; evaporation
debited pro-rata to stored volume), continuous accounting (running accounts of
use, reconciled only when a share hits capacity), and annual accounting (South
Australia's fixed annual entitlement in monthly instalments; the annual loss
reserve, continuously adjusted within-year) (Barma pp. 170–173). The Border
Rivers arrangement is a simplified capacity share (47:53 of Glenlyon Dam) with
order-debited fixed loss allowances and *no* reconciliation (Barma p. 173).
Nobody designed these as "a named system"; they were assembled from mechanisms.

**Exhibit B — one construct, three physical meanings.** "Carryover" is stored
water in a regulated river, accrued *opportunity* in an unregulated river, and
deferred aquifer drawdown in groundwater (Barma pp. 12, 213, 225–227). The
accounting construct is identical; only the physical backing differs. A model
that implements carryover as an accounting primitive gets all three.

**Exhibit C — entitlements mutate into account features.** In the Goulburn,
low-reliability water shares are now held by some users "more to enhance their
ability to carry over water than to make use of the allocations" — an
entitlement class being repurposed as account headroom (Barma pp. 193, 199).
Entitlement class, account cap, and reliability are interchangeable levers in
one design space, and users arbitrage between them.

**Exhibit D — every system turns the same four dials.** Across every
jurisdiction catalogued in Barma's Appendix B, volumetric systems reduce to:
a **crediting rule**, an **account cap**, a **use cap**, and a **forfeiture
rule**. Gwydir general security: credit by announcement / 1.5 ML/share /
1.25 & 3-in-3 / spill-at-cap. Victorian SWA: credit by announcement / 100% ABA /
— / spill-forfeit pro-rata. Burdekin-Haughton: announcement / min(94.6% unused,
25% of scheme) carryover / nominal volume / forfeit at 6 months, dam spill, or
level ≤ 148.1 mAHD. St George: inflow share / capacity share / nominal volume
(cap, 120% with carryover) / internal spill. South Australia's prescribed areas
parameterise the identical dials as carryover fraction (10–30%), total-use cap
(110–140%), credit lifetime (1–3 years, FIFO), and a deemed order-of-use of
account components (Barma pp. 138–143). Barma's own template for describing
*any* groundwater system asks exactly: how allocations are credited; carryover
rules; account limits; annual take limits; forfeiture rules (Barma p. 221).

**Exhibit E — feasibility is ordered by one physical variable.** Which systems
are *possible* in a source is determined by how well its storage can be known:
no storage (unregulated) → annual/carryover of opportunity only; unquantifiable
storage (groundwater) → annual/carryover, scaled by the storage-to-recharge
ratio; quantifiable storage (regulated) → everything, with spill frequency
selecting the machinery (rarely-spilling Gwydir runs bare continuous accounting;
frequently-spilling Goulburn needed the SWA write-down apparatus) (Barma
pp. 56, 218, 225; ch. 7 *passim*).

The conclusion writes itself: **model the dials, not the named systems.** The
names remain useful as presets and as vocabulary — modellers think in them — but
they should be configurations of shared machinery, not separate code paths.

## 6. The kernel: a decomposition into primitives

Distilling every mechanism encountered across the corpus, the whole space is
spanned by eight primitives.

1. **Owners and entitlements.** An entitlement is a *share* with a priority
   class. Priority is ordinal, and classes fill "bottom-up" (reserves and
   high-priority first, then general classes; supplementary last — NSW s.58,
   Barma p. 106). Owners include irrigators, towns, environmental holders,
   *and other tiers of the system itself*: states in an interstate agreement,
   the loss reserve, the environmental contingency allowance. This recursion is
   what makes tiered systems ordinary rather than special.

2. **Accounts.** A balance with a cap, attached to an owner (per FWAF, attached
   to a *point of take*, with entitlements linked by standing arrangement).
   Accounts may stack into ordered sub-accounts with distinct properties —
   current-year vs carryover (Macquarie), ABA vs SWA (Victoria), daily vs annual
   (Queensland unsupplemented) — with a **deemed order of use** for debits
   (carryover first in SA and NSW; against low-reliability shares first in
   Victoria) and a distinct order for forfeiture (carryover spills first).

3. **Credit policies.** Four families cover everything observed:
   - **Announced allocation**: a resource assessment produces an allocation
     fraction per class; credits are progressive and monotone within a year
     (each announcement credits the increment).
   - **Inflow share**: credit = min(share × inflow, remaining account space) —
     capacity sharing.
   - **Opportunity/event**: credit when a flow (or salinity, or bore-level)
     condition is met — unregulated access, supplementary events, Queensland's
     four-weekly conductivity-triggered groundwater announcements (Barma p. 130).
   - **Retrospective/rule-based oddities**: Glenmaggie's spill entitlement
     (releases retrospectively re-badged once the dam spills, Barma p. 160) —
     a reminder the kernel needs an expression-level escape hatch.

4. **Debit policies.** Metered use vs **order-debit** (debit at release, grossed
   up by a loss factor — order/TEF at St George; fixed proportional allowance in
   the Border Rivers); evaporation attribution (socialised into a reserve;
   individualised pro-rata to balance; a fixed ~5% haircut on carryover);
   delivery-loss attribution (static zone factors vs actual).

5. **Limits.** Account caps; annual use caps; **rolling-window caps** (3 ML/share
   per 3 years, Gwydir; 300%/3 years NSW unregulated; ACT's 2×/year within
   3×/3 years); and the physical envelope — the sum of all account caps cannot
   exceed allocatable storage (the Gwydir sits at 78% and is "approaching the
   maximum possible", Barma p. 183). Loss allowances and dead storage are part
   of the envelope arithmetic.

6. **Events.** Trigger → transform, applied to sets of accounts:
   - calendar triggers: end of water year (reset to zero; reduce to carryover
     limit; expire aged credits FIFO);
   - state triggers: physical spill (SWA pro-rata write-down; carryover-first
     forfeiture; zero-and-refill on system full — Lachlan); level/volume
     triggers (Burdekin's 148.1 mAHD; Boondooma's medium-priority cut-off;
     critical supply overrides that switch crediting from proportional to
     priority, H&G p. 21);
   - **internal spill**: iterative pro-rata redistribution of a full account's
     inflow share;
   - **reconciliation**: periodic truing of account sums against physical
     storage, with St George's principled asymmetry (surplus → by share,
     deficit → by balance).

7. **Resource assessment and reserves.** The function computing "available
   water": storage + minimum expected inflows − reserves − losses, evaluated
   continuously or annually, conservatively or aggressively (this
   conservatism was itself the grievance that drove St George to capacity
   sharing, and Bundaberg's current reform pressure — Barma p. 48). Reserves
   are *accounts owned by the system*: the worst-drought high-priority reserve
   (NSW), the annual loss reserve continuously adjusted within-year (Murray),
   the environmental contingency allowance (Gwydir, 90 GL).

8. **Transfers.** Debit one account, credit another: allocation trade, cap-credit
   trade (brisk at St George year-end), inter-state cession (Murray clauses
   106–107), category conversion at an exchange rate (Goulburn's 1.06, now
   distrusted under climate change — Barma p. 193). Mechanically trivial;
   policy-rich.

### The named systems as configurations

| System | Credit | Year-end event | Account cap | Use limits | Losses | On physical spill |
|---|---|---|---|---|---|---|
| Annual | announced allocation | reset to zero | 100% of entitlement | — | socialised (reserve) | — |
| Annual + carryover | announced allocation | reduce to carryover limit | e.g. 120% | sometimes | socialised ± evap haircut on carryover | varies: nothing / carryover spills first |
| Carryover + SWA (Vic) | announced allocation | excess above ABA cap → SWA | ABA = 100%; SWA uncapped | SWA unusable/untradeable | socialised + 5% carryover haircut | SWAs written down pro-rata; zeroed when cumulative spill exceeds SWA total |
| Continuous accounting (Gwydir GS) | announced allocation | **none** | 1.5 ML/share (credit spills at cap) | 1.25/yr; 3 per 3 yrs | socialised | account cap does the work |
| Continuous sharing (St George) | share × inflow, capped by share space | none (cap carryover ≤ 20%) | capacity share | annual cap = nominal entitlement | individual: evap pro-rata balance; delivery via TEF; monthly reconciliation | internal spill → others pro-rata by share |
| Capacity sharing (full, unimplemented) | as above, rights separately tradeable | none | capacity share | — | fully individualised incl. separate loss shares | internal spill |
| Unregulated annual + carryover (NSW) | 1 ML/share opportunity | reduce to 100% carryover | 200% | 300% per 3 yrs | n/a | n/a (no storage) |
| Murray interstate | 50% of inflows above Doctors Pt + own tributaries | none (SA tier: annual) | 50% of each storage | — | evap pro-rata volume; regulated-period conveyance split equally | internal spill to other state (cl. 116) |

Every row is the same machine with different settings. That is the review's
central finding.

## 7. What the case studies teach

Beyond the taxonomy, the corpus carries hard-won operational lessons that should
shape the design.

**7.1 Decentralisation replaces one big announcement with many small accounts —
and gets *simpler*.** St George shows the endpoint: no allocation announcements,
no announcement disputes, less staff effort, and users running strategies as
divergent as "spend every drop each season, hedge with planted area" (cotton)
and "bank three years of reliability" (grapes) on the same infrastructure
(H&G pp. 22, 42–44, 49–55). The account dispersion data (some accounts 0–20%
full, others 90–100%, re-diverging immediately after a drought compressed
everyone) is direct evidence that users *value* the individual choice the
accounting gives them.

**7.2 The conservation law.** Wherever total use is capped, "any increase in
flexibility will result in a redistribution of allocations" — winners are
under-users in regulated systems, and (invertedly) heavy users in capped
unregulated systems, whose take forces cuts on everyone (Barma pp. 91, 217).
Allocation policy is zero-sum at the envelope; assessing a rule change is
therefore inherently a *whole-of-system, long-sequence modelling problem*. This
is why allocation machinery belongs in a fast simulator rather than a
spreadsheet.

**7.3 Spill treatment is the political heart of carryover.** The two poles —
"carried-over water occupies dam space, so old water should spill first" versus
"allocated water is property and must not be re-socialised" — generated the SWA
apparatus, the Macquarie zero-on-flood-zone rule, and most of the equity debate
(Barma pp. 174–175). A model must be able to express *both* poles and everything
between, because this dial is renegotiated in every reform.

**7.4 Losses are where theory meets mud.** Static loss factors do not reflect
reality — Queensland's monthly reconciliation exists precisely because its TEFs
are wrong, deliberately conservatively so (Barma pp. 175–176; H&G p. 20).
Under-estimating loss shares exposes the government in drought;
over-estimating suppresses productive use. Expect any allocation model to need:
socialised reserves, pro-rata attribution, fixed factors, *and* reconciliation —
often simultaneously.

**7.5 Accounting must be auditable.** The St George irrigator who reproduced his
account in a spreadsheet (H&G p. 48), and the stakeholder insistence on codified,
plain-language rules (Barma pp. 53–54), point the same way: deterministic,
transparent, replayable accounting is a feature users demand of the *real*
systems. A modelling platform should hold itself to the same standard — every
credit, debit, and forfeiture visible as a recordable series.

**7.6 Metering is the precondition.** Volumetric systems without measurement are
unenforceable and effectively fictitious (Barma pp. 57, 90, 218, 225). For
modelling, the analogue is honesty about what is observable: the platform will
be used to *design* systems for sources that lack data, so it must run on
modelled proxies while making clear what a real implementation would need to
measure.

**7.7 Initialisation is half the reform.** Converting entitlements to shares
(reliability normalisation, TEF gross-up, preserving every user's historical
yield) is where St George won stakeholder consent (H&G pp. 17–18, 29), and where
Barma locates the hardest technical risk of capacity sharing (disaggregation,
Barma p. 176). Burdekin adds the forward-looking version: decide whether shares
are defined against current or *full* development **before** issuing new
entitlements (Barma p. 212). A modelling platform that can simulate both the
before and after states of a conversion — and demonstrate yield preservation —
is directly supporting the consent process.

## 8. Implications for Kalix

1. **Build the kernel, not the named systems.** Accounts, credit/debit policies,
   limits, events, resource assessment, transfers. Ship the named systems as
   documented configurations (and eventually templates) of that kernel. This is
   Kalix's founding pattern — simple components breathing complex systems into
   life — applied to management.
2. **Let policy be authored, not selected.** The WAF doctrine ("modelling should
   support, not determine, policy") demands an expression-level escape hatch
   wherever a rule might vary: thresholds, fractions, triggers, assessments.
   Kalix's DynamicExpressions and lookup tables are exactly the right substrate;
   the design should extend them to read account state.
3. **Speed is a policy tool here, not a luxury.** Every reform assessment in
   Barma's framework requires simulating option ensembles over century-scale
   sequences to characterise reliability and third-party impacts (Barma
   pp. 61–64), and notes that "existing hydrologic models may need to be
   modified" to disaggregate users by behaviour rather than geography (Barma
   p. 64). A platform that runs a century of daily accounting for hundreds of
   accounts in seconds turns option assessment from a project into a loop.
4. **Transparency is a first-class requirement.** Text-format rules, recordable
   account series, deterministic replay. The modeller should be able to do what
   the St George irrigator did.
5. **The literature is a test suite.** The Victorian SWA worked examples
   (Tables 28–29), the St George aggregate account tables (H&G Tables 12–24),
   the Murray clause rules, and the Gwydir continuous accounting parameters are
   concrete, numerical fixtures against which Kalix's components can be
   validated.

The companion document,
[`kalix-allocation-components.md`](kalix-allocation-components.md), translates
these implications into a concrete, phased component design against the current
codebase.
