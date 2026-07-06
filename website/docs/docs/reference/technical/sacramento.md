---
title: "Sacramento"
---

# Sacramento

This page is the technical reference for the Sacramento Soil Moisture Accounting (SAC-SMA) rainfall-runoff model used by the [Sacramento node](https://www.notion.so/2883cd7417a2800b84d6e72bc5b39bf5). For configuration syntax, properties, and result outputs, see the node page. This page covers the underlying model structure, equations, parameter behaviour, and calibration guidance.

## Model overview

The Sacramento Soil Moisture Accounting model (SAC-SMA, or simply Sacramento) is a lumped, conceptual, continuous rainfall-runoff model developed by Burnash, Ferral, and McGuire at the US National Weather Service River Forecast Center in Sacramento, California (Burnash et al., 1973; Burnash, 1995). It is the operational rainfall-runoff model of the US NWS and one of the most widely used and studied models in operational hydrology.

Sacramento is more complex than parsimonious models like GR4J. It represents soil moisture through five conceptual stores split across two zones, plus impervious-area runoff and channel-loss components. The added complexity provides flexibility to fit a wide range of hydrological regimes, but at the cost of increased parameter identifiability challenges during calibration.

## Conceptual structure

Sacramento divides the catchment soil column into an **upper zone** and a **lower zone**, each with **tension water** (water held against gravity) and **free water** (water available for gravity drainage):

| Zone | Tension water | Free water |
| --- | --- | --- |
| Upper | UZTW | UZFW |
| Lower | LZTW | LZFP (primary), LZFS (supplemental) |

The runoff-generation processes are:

- **Direct runoff** from the fixed impervious fraction PCTIM.

- **Variable impervious runoff** from the dynamic ADIMP area, which expands as upper-zone tension water fills.

- **Surface runoff** when UZFW exceeds UZFWM.

- **Interflow** from UZFW at depletion rate UZK.

- **Percolation** from UZFW to the lower zone, controlled by lower-zone deficit and upper-zone supply.

- **Primary baseflow** from LZFP at depletion rate LZPK (slow).

- **Supplemental baseflow** from LZFS at depletion rate LZSK (fast).

- **Channel losses** SIDE (subsurface side losses) and SSOUT (subsurface out).

- **Riparian ET** from the SARVA area.

Generated runoff is routed through a unit hydrograph (parameterised in Kalix as LagUH — see the [Reparameterisations](https://www.notion.so/13e3cd7417a2807da8a9d1a5d1d303b7) page and the implementation notes below) to produce the final streamflow.

## Mathematical formulation

This section gives the standard SAC-SMA equations. State variables are written in lower case (uztw, uzfw, lztw, lzfp, lzfs) and parameter capacities in upper case (UZTWM, UZFWM, etc.) for clarity, even though Kalix uses lower case for parameter names in the configuration file.

### Tension-water filling order

Within each zone, tension water fills before free water. After rainfall is applied to the upper zone:

- If uztw < UZTWM, fill uztw first.

- Excess flows to uzfw.

- If uzfw exceeds UZFWM, the surplus becomes surface runoff.

A similar ordering applies to the lower zone, with the additional split between LZFP and LZFS controlled by the relative storage levels.

### Evapotranspiration

ET demand is drawn preferentially from upper-zone tension water:

- E1 = E · (uztw / UZTWM), drawn from uztw.

- Residual demand (E − E1) is drawn from uzfw if available.

- Remaining demand draws from lower-zone tension water proportional to its fill ratio.

Riparian-area ET is drawn additionally from the SARVA area.

### Surface runoff

When uzfw exceeds UZFWM, the excess becomes surface runoff:

```jsx
ROsurf = max(0, uzfw − UZFWM)
```

### Interflow

Interflow drains uzfw exponentially:

```jsx
QI = uzfw · UZK
```

### Percolation

Percolation demand is set by the lower-zone deficit and grows non-linearly as the lower zone empties:

```jsx
DEMAND = PBASE · [ 1 + ZPERC · (LZdef / LZmax)^REXP ]
```

where:

- PBASE = LZFPM · LZPK + LZFSM · LZSK

- LZdef = (LZTWM − lztw) + (LZFPM − lzfp) + (LZFSM − lzfs)

- LZmax = LZTWM + LZFPM + LZFSM

Actual percolation is the lesser of demand and available supply:

```jsx
PERC = min( DEMAND · uzfw / UZFWM,  uzfw )
```

A fraction PFREE goes directly to the lower-zone free-water stores; the remainder (1 − PFREE) goes to LZTW first, with overflow distributed to LZFP and LZFS.

### Lower-zone baseflow

- QBp = lzfp · LZPK (primary, slow)

- QBs = lzfs · LZSK (supplemental, fast)

- QB = QBp + QBs

### Channel losses

SIDE represents losses from the channel system; SSOUT represents subsurface flow that bypasses the gauge. These reduce the effective baseflow contribution to streamflow.

### Impervious-area runoff

- Fixed impervious: ROimp = P · PCTIM

- Variable impervious: ROvar = P · ADIMP · (uztw / UZTWM)

### Total channel inflow before routing

```jsx
Qch = ROsurf + QI + QB + ROimp + ROvar − channel losses
```

In Kalix, Qch is then routed through the unit hydrograph (see Implementation notes) to produce `runoff_depth` [mm], converted to `runoff_volume` [ML] using the catchment area.

## Parameters

Sacramento has 17 parameters in Kalix (16 SAC-SMA parameters plus the LagUH routing parameter). They are passed to the node in alphabetical order:

```toml
params = adimp, lzfpm, lzfsm, lzpk, lzsk, lztwm, pctim, pfree, rexp, sarva, side, ssout, uzfwm, uzk, uztwm, zperc, laguh
```

### Soil-moisture store capacities

| Parameter | Description | Typical bounds | Units |
| --- | --- | --- | --- |
| UZTWM | Upper zone tension water max | 1 – 150 | mm |
| UZFWM | Upper zone free water max | 1 – 150 | mm |
| LZTWM | Lower zone tension water max | 1 – 500 | mm |
| LZFSM | Lower zone supplemental free water max | 1 – 1000 | mm |
| LZFPM | Lower zone primary free water max | 1 – 1000 | mm |

### Depletion rates

| Parameter | Description | Typical bounds | Units |
| --- | --- | --- | --- |
| UZK | Upper zone free water depletion rate | 0.1 – 0.5 | day⁻¹ |
| LZSK | Lower zone supplemental depletion | 0.01 – 0.25 | day⁻¹ |
| LZPK | Lower zone primary depletion | 0.0001 – 0.025 | day⁻¹ |

LZSK > LZPK by definition: supplemental baseflow always drains faster than primary baseflow.

### Percolation

| Parameter | Description | Typical bounds | Units |
| --- | --- | --- | --- |
| ZPERC | Maximum percolation multiplier | 1 – 250 | — |
| REXP | Percolation exponent | 1 – 5 | — |
| PFREE | Fraction of percolation directly to LZ free water | 0 – 0.6 | — |

### Impervious-area fractions

| Parameter | Description | Typical bounds | Units |
| --- | --- | --- | --- |
| PCTIM | Permanent impervious fraction | 0 – 0.05 | — |
| ADIMP | Maximum variable impervious fraction | 0 – 0.4 | — |

### Losses and ET

| Parameter | Description | Typical bounds | Units |
| --- | --- | --- | --- |
| SARVA | Riparian-vegetation ET fraction | 0 – 0.1 | — |
| SIDE | Side-channel loss fraction | 0 – 0.5 | — |
| SSOUT | Subsurface outflow fraction | 0 – 0.1 | — |

### Routing

| Parameter | Description | Typical bounds | Units |
| --- | --- | --- | --- |
| LagUH | Reparameterised unit-hydrograph shape | 0 – 5 | days |

LagUH replaces the five original UH1–UH5 unit-hydrograph ordinates with a single shape-controlling parameter. See the [Reparameterisations](https://www.notion.so/13e3cd7417a2807da8a9d1a5d1d303b7) page for the mapping. The reparameterisation reduces dimensionality during calibration and constrains the unit hydrograph to physically plausible shapes.

## Internal flux outputs

The Sacramento node exposes the internal flux components separately from total runoff. These are useful for diagnostic plotting and for understanding model behaviour during calibration:

| Output | Component |
| --- | --- |
| flosf | Surface flow (from UZFW overflow) |
| floin | Interflow (from UZFW depletion) |
| flobf | Baseflow (sum of primary and supplemental) |
| roimp | Impervious-area runoff (fixed plus variable) |

## Calibration guidance

- **Warm-up.** Sacramento has substantial soil-moisture memory; use a warm-up of at least 2 years (5+ for arid catchments) to remove the influence of initial states.

- **Identifiability.** With 17 parameters Sacramento can be over-parameterised relative to short streamflow data records.

- **Multi-objective.** Calibration to compound objective functions (e.g. SDEB) helps distinguish between high-flow and low-flow process parameters.

- **Sensitivity ordering.** UZTWM, LZTWM, UZK, LZSK, LZPK, ZPERC, and REXP are typically the most sensitive parameters; SARVA, SIDE, SSOUT, and PFREE are usually low-sensitivity.

- **Constraint between LZSK and LZPK.** Always ensure LZSK > LZPK during calibration; this is a structural requirement, not a recommendation.

- **Constraint between LZFSM and LZFPM.** No fixed ordering, but extreme differences between the two are physically unusual.

## Strengths and limitations

### Strengths

- Detailed soil-moisture accounting captures a wide range of hydrological behaviours.

- Operational pedigree — the model of choice for the US NWS for decades.

- Separates fast and slow baseflow, useful in catchments with stratified groundwater contributions.

- Internal flux outputs aid diagnostic analysis.

### Limitations

- Many parameters are weakly identifiable without auxiliary data or strong priors.

- Lumped — no spatial representation.

- No explicit snow processes; couple with a snow model upstream of inputs for snow-affected catchments.

- A poorly calibrated Sacramento can perform substantially worse than a well-calibrated parsimonious model.

## Implementation notes

- The Kalix Sacramento node uses 17 parameters in alphabetical order (see Parameters above).

- LagUH is a single-parameter reparameterisation of the original 5-ordinate unit hydrograph; see [Reparameterisations](https://www.notion.so/13e3cd7417a2807da8a9d1a5d1d303b7).

- All internal calculations are in mm; conversion to ML uses the configured catchment area.

- Model states are carried between timesteps and initialised at the start of the simulation.

## References

Burnash, R. J. C., Ferral, R. L., and McGuire, R. A. (1973). *A generalized streamflow simulation system: Conceptual modeling for digital computers*. US Department of Commerce, National Weather Service, and State of California Department of Water Resources, Sacramento.

Burnash, R. J. C. (1995). "The NWS river forecast system — catchment modeling." In Singh, V. P. (ed.), *Computer Models of Watershed Hydrology*, Water Resources Publications, pp. 311–366.

Anderson, E. A. (2002). *Calibration of conceptual hydrologic models for use in river forecasting*. NOAA Technical Report, US National Weather Service.
