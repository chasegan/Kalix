# Rainfall weight parameterisation

*How Kalix re-parameterises the coefficients of linear-combination inputs for
optimisation, the schemes that were considered, and why the design space is
constrained the way it is.*

## Background

Rainfall-runoff nodes accept rainfall as a **linear combination** of data
series:

```ini
rain = 0.3 * data.rain1 + 0.7 * data.rain2
```

The parser detects this pattern and exposes the coefficients to the optimiser.
It does not expose them *directly*, however. The optimisable surface is a
re-parameterisation with two parts:

- **`rf_bias`** — the sum of all weights (total rainfall volume multiplier);
- **`rf_d0 .. rf_d(n-2)`** — distribution parameters, each in `[0, 1]`,
  controlling how that total is shared among the n stations.

Two reasons for the split:

1. **Identifiability.** Total rainfall volume is the dominant sensitivity
   direction in any calibration; the spatial mix between nearby gauges is
   second-order. Optimising raw coefficients entangles the two — moving
   distribution without moving volume requires travelling along the diagonal
   `Σa = const`. Factoring out the bias aligns the parameter axes with the
   natural sensitivity structure of the problem.
2. **Box bounds.** Optimisers in Kalix search a box (each gene in `[0, 1]`,
   mapped through `lin_range`/`log_range`). With bias factored out, the
   distribution lives on a simplex (shares are non-negative and sum to 1),
   which is not a box. The distribution parameterisation exists to map a box
   onto that simplex so the optimiser never sees a constraint.

The distribution therefore has n − 1 degrees of freedom for n stations, and
the whole design question is: *what map from `[0,1]^(n-1)` onto the simplex
should we use?*

## Why no truly symmetric scheme exists (given bias)

"Symmetric" can mean two different things:

- **Coordinate symmetry** — parameters correspond one-to-one with stations,
  and relabelling stations just relabels parameters. The formula treats every
  station identically.
- **Statistical symmetry (exchangeability)** — a uniform search of the
  parameter box induces a distribution over weight vectors in which every
  station is treated identically.

**Coordinate symmetry is impossible with n − 1 parameters.** The intuition is
pigeonhole: n stations cannot be assigned one-to-one to n − 1 dials, so some
station is always treated specially — the only choice is where the asymmetry
lives.

More precisely: a coordinate-symmetric scheme would let the permutation group
of the n stations act on the n − 1 parameters by permuting them, compatibly
with the (bijective) map onto the simplex. But S<sub>n</sub> cannot act
faithfully on n − 1 coordinates by permutation: any homomorphism
S<sub>n</sub> → S<sub>n-1</sub> has a non-trivial kernel (n! > (n−1)!, and
the kernel, being normal, contains the alternating group for n ≥ 3). So some
genuine relabelling of stations — a 3-cycle, say — would have to leave every
parameter fixed while visibly permuting the weights, contradicting the
requirement that parameters determine weights bijectively.

Consequently there are exactly two escape routes:

1. **Keep n − 1 parameters and settle for statistical symmetry.** The map's
   *formula* is ordered, but a uniform box search treats all stations
   identically in distribution. This is the implemented stick-breaking scheme
   (below).
2. **Increase the degrees of freedom to n.** Give every station its own dial
   and renormalise, e.g. `a_i = bias · u_i / Σu_j`. Perfectly
   coordinate-symmetric and very interpretable, but the extra dimension is
   redundant: scaling all dials together changes nothing, so the objective is
   flat along a ridge, optimal dial values are non-unique, and identifiability
   reporting gets muddier. Kalix chose not to pay this cost.

## If bias were not required

The obstruction exists only because the sum is factored out as its own
parameter — that is what turns the distribution into a simplex. Drop that
requirement and the problem dissolves: **optimise the raw weights directly**,
one box-bounded parameter per station:

```
a_i = lin_range(g(i), 0, a_max)      # or log_range for wide dynamic ranges
```

This is perfectly coordinate-symmetric, exactly n degrees of freedom for n
free quantities, trivially invertible, has no parameter interactions at all,
and represents exact zeros. It is what we would use if the weights were
independent quantities. Kalix keeps the bias split deliberately — the
volume/distribution factorisation is worth more to calibration quality than
coordinate symmetry is — but the comparison makes clear that the simplex
machinery is the *price of the bias parameter*, not something intrinsic to
weighted sums.

## The implemented scheme: Beta-corrected stick-breaking

Since July 2026 (branch `opt/stick-breaking-weights`), Kalix uses route 1:
statistical symmetry with exactly n − 1 distribution parameters.

### Forward map

Stations are walked in file order. Station i (0-based, i < n−1) takes a
fraction of the weight *remaining* at its turn, and the last station takes
whatever is left:

```
v_i     = 1 - (1 - u_i)^(1 / (n - 1 - i))
share_i = v_i × remaining_i
a_i     = rf_bias × share_i
```

Plain stick-breaking (`share_i = u_i × remaining`) would be badly asymmetric —
early stations reach large shares far more easily than late ones. The
exponent `1/(n-1-i)` is the Beta(1, n−1−i) quantile correction, chosen so
that **a uniform search of the u-box is an exactly uniform (Dirichlet(1),
exchangeable) search of the weight simplex**: no station is statistically
favoured, however the modeller happened to order them.

### Inverse map

The map is bijective, and Kalix inverts it at load time
(`invert_stick_breaking_weights`): the coefficients written in the model file
are converted back to `(rf_bias, u)` exactly, so

- an optimisation **warm-starts from the modeller's weights**, and
- setting a *subset* of the `rf_*` parameters (e.g. `rf_bias` alone) scales
  or adjusts the written weights instead of silently resetting the
  distribution — a defect of the previous scheme.

Weight vectors containing a negative entry, or summing to zero across several
stations, are not representable; they fall back to equal-weight defaults
(`equal_weight_u_params`), with `rf_bias` still the coefficient sum.

### Properties and practical notes

- `rf_d*` bounds are `[0, 1]`, independent of n. The full range is
  well-behaved: **exact zero weights are reachable at the endpoints** (the
  optimiser can switch an uninformative gauge off completely), and there are
  no saturation plateaus. `lin_range(g(#), 0, 1)` is the natural mapping.
- For n = 2, `u_0` is exactly station 0's fractional share.
- For n > 2, the equal-weights point is **not** the box centre; it is
  `u_i = 1 - (m/(m+1))^m` with `m = n-1-i` (e.g. `(5/9, 1/2)` for n = 3),
  available as `equal_weight_u_params(n)`.
- Later parameters are conditioned on earlier ones (each divides what
  remains), so individual `u_i` values are not station-labelled knobs. This
  is the residual, structural asymmetry that the impossibility argument says
  must live *somewhere*; here it is confined to the formula and absent from
  the search statistics.

### Historical scheme (pre-July 2026)

The previous parameterisation fixed station 0 as a reference and set
`a_i = bias × softmax(w)` with `w_0 = 0`, `w_i = logit(u_i)` clamped at
`1e-10`. It shared the bias/distribution split and the `[0,1]` bounds, but
was symmetric in neither sense: the reference station was structurally
privileged *and* the induced search density differed between stations. It
also could not represent exact zero weights (the clamp bottomed out near
`e^-23`), had flat plateaus at the box edges, and — because no inverse was
implemented — initialised `u = 0.5` regardless of the written coefficients,
which is what made partial `rf_*` updates destructive. For n = 2 its `u` was
station **1**'s share; optimised `rf_d` values therefore do not carry over
across the scheme change, though `[parameters]` bounds do.

## Scheme comparison

| | Reference softmax (old) | Normalised dials (route 2) | Stick-breaking (implemented) | Raw weights (no bias) |
|---|---|---|---|---|
| Parameters | n−1 + bias | n + bias | n−1 + bias | n |
| Coordinate symmetry | no | yes | no | yes |
| Statistical symmetry | no | yes (centre-favouring) | yes (exactly uniform) | yes |
| Exact zero weights | no | yes | yes | yes |
| Bijective / warm-startable | for a > 0 (never implemented) | up to ridge | yes (implemented) | yes |
| Search pathology | edge plateaus | neutral ridge (+1 dim) | sequential conditioning | volume/mix entanglement |
