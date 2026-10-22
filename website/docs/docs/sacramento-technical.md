---
title: "Sacramento"
---

# Sacramento

This page is the technical reference for the Sacramento Soil Moisture Accounting (SAC-SMA) rainfall-runoff model used by the [Sacramento node](sacramento.md). For configuration syntax, properties, and result outputs, see the node page. This page covers the underlying model structure, equations, parameter behaviour, and calibration guidance.

## Model overview

The Sacramento Soil Moisture Accounting model (SAC-SMA, or simply Sacramento) is a lumped, conceptual, continuous rainfall-runoff model developed by Burnash, Ferral, and McGuire at the US National Weather Service River Forecast Center in Sacramento, California (Burnash et al., 1973; Burnash, 1995). It is the operational rainfall-runoff model of the US NWS and one of the most widely used and studied models in operational hydrology.

Sacramento is more complex than parsimonious models like GR4J. It represents soil moisture through five conceptual stores split across two zones, plus impervious-area runoff and loss terms. The added complexity provides flexibility to fit a wide range of hydrological regimes, but at the cost of increased parameter identifiability challenges during calibration.

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

- **Losses** SIDE (the side loss: baseflow lost to groundwater before it reaches the channel) and SSOUT (taken from channel flow).

- **Riparian ET** from the SARVA area.

Surface runoff, interflow and impervious-area runoff are routed through a unit hydrograph (parameterised in Kalix as LagUH — see [LagUH](sacramento.md#laguh) and the implementation notes below). Baseflow is added unrouted to give the final streamflow.

## Mathematical formulation

This section gives the standard SAC-SMA equations. State variables are written in lower case (uztw, uzfw, lztw, lzfp, lzfs) and parameter capacities in upper case (UZTWM, UZFWM, etc.) for clarity, even though Kalix uses lower case for parameter names in the configuration file.

Kalix runs the two lower-zone free water stores at enlarged capacities LZFPM′ = LZFPM · (1 + SIDE) and LZFSM′ = LZFSM · (1 + SIDE); the extra room holds the side loss, water that leaves as groundwater without reaching the channel. The contents of these effective stores are written lzfp′ and lzfs′ below. The stores the node reports are lzfp = lzfp′ / (1 + SIDE) and lzfs = lzfs′ / (1 + SIDE), which range from 0 to LZFPM and LZFSM. With SIDE = 0 the two forms are the same.

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

- PBASE = LZFPM′ · LZPK + LZFSM′ · LZSK

- LZdef = (LZTWM − lztw) + (LZFPM′ − lzfp′) + (LZFSM′ − lzfs′)

- LZmax = LZTWM + LZFPM′ + LZFSM′

Actual percolation is the least of demand, available supply and the room left in the lower zone:

```jsx
PERC = min( DEMAND · uzfw / UZFWM,  uzfw,  LZdef )
```

A fraction PFREE goes directly to the lower-zone free-water stores; the remainder (1 − PFREE) goes to LZTW first, with overflow distributed to LZFP and LZFS.

### Lower-zone baseflow

- QBp = lzfp′ · LZPK (primary, slow)

- QBs = lzfs′ · LZSK (supplemental, fast)

- QB = QBp + QBs

Of QB, the fraction 1 / (1 + SIDE) reaches the channel. The rest, SIDE / (1 + SIDE), is the side loss. In terms of the reported stores, the baseflow that reaches the channel is lzfp · LZPK + lzfs · LZSK in the model's equations; the engine drains the stores in sub-steps, so the recorded series reproduce this only approximately.

### Losses

SIDE is the ratio of baseflow that leaves the catchment as groundwater to baseflow that reaches the channel; this side loss is taken before the channel (see Lower-zone baseflow). SSOUT is a fixed depth in mm taken from channel flow each step, or all of the flow if there is less than that. Riparian ET, up to SARVA · E, is then taken from what remains. All three reduce the streamflow at the gauge.

### Impervious-area runoff

- Fixed impervious: ROimp = P · PCTIM

- Variable impervious: ROvar = P · ADIMP · (uztw / UZTWM)

### Routing and channel flow

Surface runoff, interflow and baseflow are generated on the pervious area, so each is scaled by the pervious fraction 1 − PCTIM − ADIMP. Surface runoff, interflow and impervious-area runoff are routed through the unit hydrograph (UH, see Implementation notes); baseflow is not:

```jsx
Qquick = UH[ (1 − PCTIM − ADIMP) · (ROsurf + QI) + ROimp + ROvar ]
Qbase  = (1 − PCTIM − ADIMP) · QB / (1 + SIDE)
Qch    = max( 0,  Qquick + Qbase − SSOUT )
```

Riparian ET, up to SARVA · E, is then taken from Qch. What remains is `runoff_depth` [mm], converted to `runoff_volume` [ML] using the catchment area.

## Parameters

Sacramento has 17 parameters in Kalix (16 SAC-SMA parameters plus the LagUH routing parameter). They are passed to the node in alphabetical order:

```ini
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

With SIDE > 0 the engine runs the lower-zone free water stores at (1 + SIDE) times LZFPM and LZFSM but reports them divided by the same factor, so `lzfp` and `lzfs` fill to LZFPM and LZFSM; see Mathematical formulation.

### Depletion rates

| Parameter | Description | Typical bounds | Units |
| --- | --- | --- | --- |
| UZK | Upper zone free water depletion rate | 0.1 – 0.5 | day⁻¹ |
| LZSK | Lower zone supplemental depletion | 0.01 – 0.25 | day⁻¹ |
| LZPK | Lower zone primary depletion | 0.0001 – 0.025 | day⁻¹ |

LZSK > LZPK by convention: the supplemental store is the one that drains faster. The engine does not enforce the ordering; calibrating `lzpkonlzsk` with an upper bound of 1 keeps it (see [optimisable parameters](optimisable-parameters.md#the-ratios-lzpkonlzsk-and-sarvaonpctim)).

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
| SIDE | Ratio of baseflow lost to groundwater to baseflow reaching the channel | 0 – 0.5 | — |
| SSOUT | Depth taken from channel flow each step | 0 – 0.1 | mm |

### Routing

| Parameter | Description | Typical bounds | Units |
| --- | --- | --- | --- |
| LagUH | Reparameterised unit-hydrograph shape | 0 – 5 | days |

LagUH replaces the five original UH1–UH5 unit-hydrograph ordinates with a single shape-controlling parameter. See [LagUH](sacramento.md#laguh) for the mapping. The reparameterisation reduces dimensionality during calibration and constrains the unit hydrograph to physically plausible shapes.

## Internal flux outputs

The Sacramento node exposes the internal flux components separately from total runoff. These are useful for diagnostic plotting and for understanding model behaviour during calibration:

| Output | Component |
| --- | --- |
| flosf | Surface flow (from UZFW overflow) |
| floin | Interflow (from UZFW depletion) |
| flobf | Baseflow (sum of primary and supplemental) |
| roimp | Impervious-area runoff (fixed plus variable) |

## Soil moisture store outputs

The node reports the contents of each of the five soil moisture stores at the end of every step:

| Output | Store | Capacity |
| --- | --- | --- |
| uztw | Upper zone tension water | UZTWM |
| uzfw | Upper zone free water | UZFWM |
| lztw | Lower zone tension water | LZTWM |
| lzfp | Lower zone primary free water | LZFPM |
| lzfs | Lower zone supplemental free water | LZFSM |

Things to keep in mind when using them:

- **Depths over the pervious area.** Each output is a depth in mm over the pervious part of the catchment, which is the fraction 1 − PCTIM − ADIMP. Multiply by that fraction for a depth over the whole catchment, and by the catchment area for a volume in ML.

- **Lower-zone free water has the side loss divided out.** `lzfp` and `lzfs` are the effective stores defined under Mathematical formulation divided by (1 + SIDE), so they fill to LZFPM and LZFSM. A water balance over the five reported stores does not close on its own when SIDE > 0: the side loss is removed from `lzfp` and `lzfs` as water enters them, and returned when free water resupplies lower-zone tension water. To close a balance, use the effective stores: the change in uztw + uzfw + lztw + (1 + SIDE) · (lzfp + lzfs), scaled by the pervious fraction and the catchment area, accounts for `flosf`, `floin` and `flobf` exactly, where `flobf` is baseflow before the side loss.

- **The impervious areas are not included.** The fixed impervious area (PCTIM) holds no water. The variable impervious area (ADIMP) holds tension water of its own, which is not reported.

- **Stores start empty.** All stores are empty at the start of the simulation, so the early part of each series reflects the warm-up (see Calibration guidance).

## Calibration guidance

- **Warm-up.** Sacramento has substantial soil-moisture memory; use a warm-up of at least 2 years (5+ for arid catchments) to remove the influence of initial states.

- **Identifiability.** With 17 parameters Sacramento can be over-parameterised relative to short streamflow data records.

- **Multi-objective.** Calibration to compound objective functions (e.g. SDEB) helps distinguish between high-flow and low-flow process parameters.

- **Sensitivity ordering.** UZTWM, LZTWM, UZK, LZSK, LZPK, ZPERC, and REXP are typically the most sensitive parameters; SARVA, SIDE, SSOUT, and PFREE are usually low-sensitivity.

- **Constraint between LZSK and LZPK.** Keep LZPK at or below LZSK during calibration, so that the two stores keep their roles. Searching the ratio `lzpkonlzsk` with an upper bound of 1 does this for you.

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

- LagUH is a single-parameter reparameterisation of the original 5-ordinate unit hydrograph; see [LagUH](sacramento.md#laguh).

- All internal calculations are in mm; conversion to ML uses the configured catchment area.

- Model states are carried between timesteps and initialised at the start of the simulation.

## References

Burnash, R. J. C., Ferral, R. L., and McGuire, R. A. (1973). *A generalized streamflow simulation system: Conceptual modeling for digital computers*. US Department of Commerce, National Weather Service, and State of California Department of Water Resources, Sacramento.

Burnash, R. J. C. (1995). "The NWS river forecast system — catchment modeling." In Singh, V. P. (ed.), *Computer Models of Watershed Hydrology*, Water Resources Publications, pp. 311–366.

Anderson, E. A. (2002). *Calibration of conceptual hydrologic models for use in river forecasting*. NOAA Technical Report, US National Weather Service.
