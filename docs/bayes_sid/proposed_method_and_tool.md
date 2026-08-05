# BayeSID for Kalix — proposed method and tool

**Status:** Proposal (draft for discussion)

This document proposes a Kalix-native implementation of the Bayesian Storage Inflow
Derivation (BayeSID) method, based on:

> Egan, C. A., *"Bayesian Approach to Estimation of Daily Reservoir Inflows"*,
> HWRS 2024, Melbourne.
> ([PDF](https://drive.google.com/file/d/1nh71dDXjSZXYJFKQRtRQIRNgf2kk0eRp/view?usp=sharing))

It also draws on the reference C# implementation (`~/github/bayesid`) and on design
discussion following a review of that code. The proposal keeps the published method's
character — a Hidden Markov Model over storage volumes, solved exactly — while making
deliberate changes to the transition model, missing-data handling, robustness, numerics,
and the tool's input surface. Departures from the paper are marked **[change]** and
justified in place.

---

## 1. Problem

Historical reservoir inflows are derived by mass balance from operational records
(paper, Eq. 1):

```
q = Vf − Vs − p + d + e + s
```

Errors in the instantaneous storage volume — wind setup, seiche, sensor noise on the
lake level — dominate the small daily volume differences, so the derived inflow series
is noisy and frequently negative. Heuristic cleanups (NEGFLO) and signal-processing
filters treat the symptoms; BayeSID treats the cause by modelling the storage-volume
error explicitly and inferring the most probable true volume trajectory.

## 2. Model

### 2.1 States and observations

As in the paper (Eqs. 2–3), the hidden state on day *i* is the pair
`X_i = [V_i, q_i]` (true volume, true inflow); the observation is the level-derived
volume `V̂_i`. In implementation terms the state is the pair of consecutive volumes
`(V_{i−1}, V_i)` with the inflow implicit via mass balance — the pair is required
because the transition prior depends on consecutive *inflows*, making the volume
process second-order Markov.

### 2.2 Emission model

Gaussian observation error on volume (paper, Eq. 4):

```
V̂_i ~ Normal(V_i, σ_V),   σ_V = A(L_i) · σ_L
```

with `σ_L` defaulting to 6 mm per the seiche/sensor literature cited in the paper.
`A(L)` comes from the storage's level–volume–area rating (see §5), not from a
user-supplied area series.

### 2.3 Transition model **[change]**

The paper (Eqs. 6–8) scores a transition by how *marginal* the relative flow change
`ρ = (q_{i+1} − q_i)/q_i` is under an empirical CDF: `P(ρ) = 2·min[CDF(ρ), 1−CDF(ρ)]`.
That construction is a p-value-like quantity, not a normalized transition probability,
and its row sums vary with the previous inflow — silently reweighting previous states
in favour of those with more plausible successors. (The reference implementation
acknowledges this: it relies on per-day renormalization and hopes the effect washes
out.)

Proposed replacement: a **normalized empirical transition density** on the log-flow
ratio

```
δ = log(q_{i+1} / q_i) = log(1 + ρ)
```

estimated from the donor record by kernel density, with three explicit point masses:

- `P0→0` — flow stays zero (spell persistence),
- `P+→0` — cessation (ρ = −1),
- `P0→+` — start-up from zero (ρ = +∞), with a start-up magnitude distribution.

This is the same prior information the paper uses (natural hydrographs rise fast,
recede slowly, change smoothly), expressed as a proper conditional density
`p(q_{i+1} | q_i)` that normalizes over the discrete successor grid. It removes the
hidden reweighting, and log-ratio space handles the scale-free character of recessions
naturally. Negative inflows retain zero probability, as in the paper.

The density is precomputed once into a flat lookup table (see §4); runtime cost is one
array index per transition, identical to the tuned reference implementation.

### 2.4 Robustness: no dead days **[change]**

In the paper (Step 5) and the reference implementation, a day on which every state has
zero probability — typically a storage drop larger than the level-error budget can
absorb — is handled by resetting probabilities to the emission likelihoods. This works
but is a hard, binary event, and it is the model *asserting certainty it doesn't have*:
only storage-level error is represented, so any error in outflows, rain, evaporation,
or an unrecorded operation has nowhere to go.

Two standard mechanisms, both proposed:

1. **Contamination floor on transitions.** Replace `p(q'|q)` with
   `(1−ε)·p(q'|q) + ε·u(q')` for small ε (default 10⁻⁶) and a broad floor
   distribution `u`. Interpretation: with small probability, something happened that
   the recession model doesn't cover (unrecorded gate operation, datum shift, spike).
   No day can then score exactly zero; an "inexplicable" transition pays a large but
   finite penalty and inference recovers gracefully and locally.

2. **Optional mass-balance slack (process noise).** Relax the hard constraint of
   paper Eq. 5 to `q_i = ΔV − p + d + e + s + η_i` with `η_i` tightly distributed.
   This represents, in aggregate, the flux uncertainties the paper's conclusion lists
   as future work (rain, evaporation, tailwater, seepage, metering), without modelling
   each flux separately. Off by default in v1 (it widens the state space); the
   contamination floor alone eliminates dead days.

These mechanisms upgrade the paper's binary quality code into a **continuous
diagnostic**: the realized per-day transition penalty ("how hard the model worked to
explain this day"), which doubles as a data-quality audit of the operational record.

### 2.5 Missing data: segmentation **[change]**

Gaps in the storage record are often months or years long; the post-gap storage level
is genuinely new information and **no state information is carried across a gap**.

- A day is **valid** iff every mandatory input (§5) is present.
- The record is split into contiguous segments of valid days; each segment is solved
  independently (fresh start-of-record initialization at its first day, end-of-record
  termination at its last).
- The first day of each segment yields no inflow (unknown previous volume) — one NaN
  per segment boundary, exactly as the paper already accepts for the start of record.
- A configurable bridge threshold (default **0 days**) may allow very short telemetry
  dropouts to be bridged by dropping the emission term for the missing day(s). This is
  a data-preparation convenience, not a modelling feature; the default performs no
  bridging.
- Missing rain/evaporation may be infilled (zero or climatology) as an explicit user
  choice; missing outflows are never infilled silently — they invalidate the day.

Segments are independent, which also parallelizes the record trivially.

### 2.6 Decoding **[change, discussed]**

The paper decodes by per-day argmax of the marginal posterior volume (Eqs. 12–13),
then re-derives inflows by mass balance (Eq. 14). This guarantees mass balance — but
adjacent marginal winners are chosen independently, so the pair can imply an inflow
transition the model itself considers wildly improbable; residual jumpiness
concentrates exactly where the posterior is flat or bimodal.

Proposed default: **Viterbi decoding** — the jointly most probable volume path.
Because transitions only exist between pair-states that agree on the shared volume,
the Viterbi path is self-consistent by construction and its derived inflows satisfy
mass balance exactly, while additionally guaranteeing every consecutive transition is
prior-plausible. It is also cheaper: one max-product pass plus backpointers (one small
integer per state) versus two sum-product passes storing dense probability matrices.

The forward–backward marginals remain available as an option (`--marginals`) for
posterior uncertainty bands per day, which Viterbi cannot provide. Acceptance test
before switching the default: run both decoders on the Teemburra and Hinze case
studies from the paper and inspect the days on which they disagree.

## 3. Estimation outputs

| Output | Default | Notes |
|---|---|---|
| Inflow series | ✔ | The headline result (paper Eq. 14, from decoded volumes) |
| Posterior volume series | ✔ | Overlay against the observed record to *see* the corrections |
| Diagnostic series | ✔ | Per-day realized transition penalty (§2.4) + segment/quality flags |
| Posterior volume quantiles | opt | Requires `--marginals` (forward–backward) |

## 4. Numerics and performance

The reference C# implementation is exact but slow: the O(T·N³) transition sums
(N = 91 grid points, T ≈ 48 000 days ⇒ ~7×10¹⁰ inner iterations per direction pair)
run a virtual call and a binary search per iteration, and ~16 GB of per-day matrices
are persisted, most of it redundant. The Rust implementation should:

1. **Work in log-space** (max-plus for Viterbi); no per-day normalization passes,
   no underflow, finite scores under the contamination floor.
2. **Store nothing redundant.** The emission likelihood is a rank-1 N-vector per day;
   the inflow for a state pair is one subtraction (`V_i − V'_j + c_day`); Viterbi
   persists only backpointers (u8/u16 per state ⇒ ~8–16 KB/day vs ~330 KB/day).
3. **Flat lookup table** for the transition density (precomputed on a uniform δ grid
   with the point masses handled explicitly); one index op per transition, no
   dispatch, no search.
4. **Beam pruning.** Posteriors are sharply peaked; skip predecessor states below
   `ε_beam · rowmax`. Expected 10–50× with no visible change in results; must be
   validated against the unpruned result on the case studies.
5. **Parallelism at two levels:** across segments, and across the state grid within a
   day (rayon); flat arrays for SIMD-friendly inner loops.
6. **Grid:** uniform in V, `±3σ_V`, N configurable (default 91 per the paper; the
   cubic cost makes N = 61 a 3.3× saving where accuracy tolerates it). Optional
   centre-dense spacing as in the reference implementation's method 2.

Target: the full 131-year daily record in seconds on a laptop, per Kalix's
performance ethos.

### 4.1 Alternative formulation (future work)

The grid can be eliminated entirely by posing the MAP problem in continuous state
space: maximize `Σ log N(V̂_i | V_i, σ_V) + Σ log p(q_i → q_{i+1})` over the volume
sequence directly. Each transition couples three consecutive volumes, so the Hessian
is banded and Newton/L-BFGS solves a full record in milliseconds with no
discretization error. The non-smooth pieces (q ≥ 0, the cessation point mass) need
interior-point or smoothing treatment, and penalties should be heavy-tailed for
robustness. Deferred: v1 implements the discrete HMM, which matches the published
method and is easier to validate; the continuous formulation is the natural v2 if
still-faster or grid-free behaviour is wanted.

## 5. Tool design

### 5.1 Inputs **[change]**

The reference tool requires eight daily series via a positional 10-line control file.
Three inputs collapse or disappear:

- Spills, releases, and extractions are only ever used summed → one **outflows**
  input accepting 1..n series.
- The area series is derived internally from the **rating table** (level–volume–area),
  which the user has anyway — supplying pre-derived, mutually consistent volume *and*
  area series is duplicated effort and a silent-inconsistency hazard (σ_V and the
  rain/evap conversions depend on area).
- The donor gauge is optional: the paper reports results are insensitive to donor
  choice, so a built-in default ρ/δ distribution ships with the tool.

**Mandatory:**

| Input | Form |
|---|---|
| Storage record | level series (preferred; σ_L is a statement about the level sensor) or volume series |
| Rating table | level–volume–area; from file, or from a Kalix storage node |
| Outflows | one or more daily series, summed internally |

**Optional (with defaults):**

| Input | Default |
|---|---|
| Rainfall depth | 0, with a visible warning |
| Evaporation depth | 0, with a visible warning; a 12-value monthly pattern is accepted |
| Donor flow series (or δ-distribution table) | built-in default distribution |
| σ_L | 6 mm |
| Grid: N, ±kσ | 91, 3 |
| Contamination ε, beam ε | 10⁻⁶, tuned default |
| Date window | valid overlap of the inputs (no hardcoded epoch) |
| Gap bridge threshold | 0 days |
| Decoder | Viterbi (`--marginals` for forward–backward) |

Units follow Kalix conventions and are declared, not assumed (the reference tool
hardcodes ML/ha/mm conversions). The timestep follows the model/context; only the
transition distribution is timestep-dependent (a donor CDF built from daily data
describes daily changes), so non-daily use requires a matching donor record.

### 5.2 Configuration

Named configuration (TOML) or named CLI arguments — no positional control file, no
output paths mixed into inputs. Consistent with Kalix's text-based, transparent,
version-control-friendly formats.

### 5.3 Surfaces

- **CLI:** a `kalix`-family subcommand taking the TOML config; writes the three
  output series (§3) as CSV.
- **IDE:** select a storage node from the open model (rating comes from the node's
  dimension table), attach level and outflow series from the data pane, adjust σ_L if
  desired, run. Results view overlays: (a) posterior vs observed volume; (b) BayeSID
  vs raw mass-balance inflow, linear and log scale — i.e. Figures 7–10 of the paper
  as a live view — plus the diagnostic series for data-quality auditing.
- **Python:** thin binding over the Rust core, for scripted/batch use.

## 6. Validation plan

1. **Reference parity:** reproduce the Teemburra and Hinze case studies; compare
   against the paper's figures and the C# implementation's output (allowing for the
   deliberate changes in §2.3–§2.6, each of which can be disabled to isolate its
   effect).
2. **Decoder comparison:** Viterbi vs marginal-argmax on both case studies (§2.6).
3. **Pruning safety:** beam-pruned vs exact on both case studies.
4. **Synthetic truth:** generate synthetic inflows, route through a storage model,
   corrupt the level record with realistic noise (incl. seiche-scale error, gaps, and
   injected "inexplicable" days), and score recovery — the only setting where the true
   inflows are known.
5. **Mass-balance audit:** decoded inflows must reproduce the decoded volume series
   exactly through Eq. 1 on every valid day.

## 7. Open questions

- Default decoder: confirm Viterbi after the §6.2 comparison, or keep marginal-argmax
  for continuity with the paper?
- Mass-balance slack (§2.4 item 2) in v1, or defer with the contamination floor as
  the sole robustness mechanism?
- Where does the built-in default δ-distribution come from — ship the
  Teemburra-derived table from the reference implementation, or refit from a small
  panel of Queensland headwater gauges?
- Engine placement: standalone tool in the Rust crate vs a callable engine feature
  exposed through the STDIO protocol for the IDE.
