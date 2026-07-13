---
title: "Optimisable parameters"
---

# Optimisable parameters

This page is the reference for what the optimiser can tune: every parameter address that may appear on the left-hand side of a line in the `[parameters]` section of an optimisation configuration.

```ini
[parameters]
node.my_gr4j.x1 = lin_range(g(1), 10, 2000)
node.my_gr4j.x4 = lin_range(g(2), 0.0001, 4)
c.divert_frac   = lin_range(g(3), 0, 1)
```

Each line maps a **parameter address** to a Kalix expression driven by one or more **genes** `g(n)` — normalised values in `[0, 1]` that the algorithm searches. `lin_range(g(n), min, max)` searches linearly between the bounds; `log_range(g(n), min, max)` searches logarithmically, which suits parameters spanning orders of magnitude. The same gene may drive several targets (tying parameters together), and expressions can be composed freely. For a worked walkthrough see [Tutorial 12 — Optimisation from the commandline](../tutorials/12-optimisation-cli.md).

## Parameter addresses

There are two families of address:

| Address | Meaning |
| --- | --- |
| `c.<name>` | A model constant from the `[constants]` section |
| `node.<name>.<param>` | A parameter of a named node. Supported by `gr4j`, `sacramento` and `routing` nodes |

KalixIDE lists every valid address for the loaded model (via the engine's `get_optimisable_params` command), which is the quickest way to discover what a given model exposes.

### Validation and infeasible candidates

Candidate values are applied to the model as-is; the engine re-validates all parameters at the start of every run. A candidate that fails validation — or that produces non-finite flows inside the assessment window — is treated as **infeasible** and scores an objective of infinity, so it cannot win but also teaches the optimiser nothing. Keep your `lin_range`/`log_range` bounds inside each parameter's valid range so evaluations aren't wasted on infeasible candidates.

## Constants (`c.*`)

Every constant is optimisable by the address `c.<name>`. This is the general-purpose route for optimisation beyond the built-in node parameters: any node property or expression that references a constant becomes calibratable through it.

```ini
# model file
[constants]
c.scale = 1.0

[node.gauged_inflow]
type = inflow
inflow = c.scale * data.flows_csv.by_name.upstream

# optimisation file
[parameters]
c.scale = lin_range(g(1), 0.5, 2.0)
```

## GR4J nodes (`type = gr4j`)

| Parameter | Description | Common search range |
| --- | --- | --- |
| `x1` | Production store capacity (mm) | 10 – 2000, log |
| `x2` | Groundwater exchange coefficient (mm/day) | −8 – 6, linear |
| `x3` | Routing store capacity (mm) | 10 – 500, log |
| `x4` | Unit hydrograph base time (days) | 0.0001 – 4, linear |

The ranges above are the ones used in Kalix's own calibration examples; the [GR4J node page](gr4j.md) discusses the broader literature bounds. GR4J nodes whose rainfall input is a linear combination of stations also expose the [rainfall input parameters](#rainfall-input-parameters-rf_bias-rf_di) below.

## Sacramento nodes (`type = sacramento`)

All seventeen Sacramento parameters are optimisable. The ranges below are typical search bounds (used in Kalix's regression calibrations); see the [Sacramento node page](sacramento.md) for what each parameter means.

| Parameter | Common search range |
| --- | --- |
| `adimp` | 1e−5 – 0.15, log |
| `lzfpm` | 1 – 300, log |
| `lzfsm` | 1 – 350, log |
| `lzpk` | 0.001 – 0.6, log |
| `lzsk` | 0.001 – 0.9, log |
| `lztwm` | 10 – 600, log |
| `pctim` | 1e−5 – 0.11, log |
| `pfree` | 0.01 – 0.5, log |
| `rexp` | 1 – 6, log |
| `sarva` | 1e−5 – 0.11, log |
| `side` | 1e−5 – 0.1, log |
| `ssout` | 1e−5 – 0.1, log |
| `uzfwm` | 5 – 155, log |
| `uzk` | 0.1 – 1, log |
| `uztwm` | 12 – 180, log |
| `zperc` | 1 – 600, log |
| `laguh` | 0 – 3, linear |

Sacramento nodes whose rainfall input is a linear combination of stations also expose the [rainfall input parameters](#rainfall-input-parameters-rf_bias-rf_di) below.

## Routing nodes (`type = routing`)

A routing node exposes whichever parameter family matches how it is configured — never both:

| Node configuration | Optimisable parameters |
| --- | --- |
| Nonlinear Muskingum (`nlm = k, m`), or lag-only | `nlm_k`, `nlm_m` |
| Piecewise linear table (`pwl = ...`) | `pwl_tt_0` … `pwl_tt_<n−1>`, one per table row |

Addressing a parameter from the inactive family is a configuration error.

**Nonlinear Muskingum.** `nlm_k` and `nlm_m` are the k and m of the storage relationship S = k·Qᵐ (see the [routing node page](routing.md) for units and the per-division convention). Give `nlm_k` a strictly positive lower bound — `log_range` does this naturally — because k = 0 turns routing off entirely, leaving a degenerate pass-through candidate in the search space. `nlm_m` must lie in (0, 5]; typical hydrological values are 0.6 – 1.0. A lag-only node (no `pwl` table, no `nlm`) also accepts these two parameters: calibrating a positive k onto it is how you give it Muskingum routing.

```ini
[parameters]
node.reach_4.nlm_k = log_range(g(1), 1, 1000)
node.reach_4.nlm_m = lin_range(g(2), 0.5, 1.1)
```

**Piecewise linear travel times.** The optimiser tunes the travel-time column of the `pwl` table; the index flows stay fixed as the modeller wrote them, so you choose the flow breakpoints and the optimiser shapes the rating between them. A table with n rows exposes `pwl_tt_0` through `pwl_tt_<n−1>`, in row order. Travel times must be non-negative — a candidate with a negative travel time is rejected as infeasible — so give each gene a lower bound of 0 or more.

```ini
# model file:  pwl = 0, 5,  50, 3,  150, 2,  1000, 1   (4 rows)
[parameters]
node.reach_4.pwl_tt_0 = lin_range(g(1), 0, 10)
node.reach_4.pwl_tt_1 = lin_range(g(2), 0, 10)
node.reach_4.pwl_tt_2 = lin_range(g(3), 0, 10)
node.reach_4.pwl_tt_3 = lin_range(g(4), 0, 10)
```

## Rainfall input parameters (`rf_bias`, `rf_d<i>`)

When a GR4J or Sacramento node's `rain` input is a **linear combination of stations** (`rain = w1 * data.a + w2 * data.b + ...`), two derived parameter families become available on the node:

| Parameter | Meaning | Common search range |
| --- | --- | --- |
| `rf_bias` | Overall bias multiplier applied across all stations | 0.7 – 1.3, linear |
| `rf_d0` … `rf_d<n−2>` | Relative distribution between the n stations | 0 – 1, linear |

These calibrate the rainfall weighting itself alongside the model parameters; the optimised weights are written back into the node's `rain` expression when the model is saved. A single-station input exposes only `rf_bias`.
