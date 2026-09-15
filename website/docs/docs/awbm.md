---
title: "AWBM"
---

# AWBM

## At a glance…

This node uses the Australian Water Balance Model (AWBM) to represent catchment inflows from a catchment of a fixed area. The model takes rainfall and potential evapotranspiration data and determines inflows. AWBM has 8 parameters representing catchment characteristics.

```ini
[node.my_awbm_node]
type = awbm
loc = 20, 30
area = 165
rain = data.rex_rain_csv.by_name.value
evap = data.rex_mpot_csv.by_name.value
params = 0.134, 0.433, 7, 70, 150, 0.35, 0.95, 0.35
ds_1 = my_other_node
```

## Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Example: `[node.my_awbm_node]` |
| type (compulsory) | The node type, which is “awbm” in this case. `type = awbm` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| area (compulsory) | The catchment area [km2].  Example `area = 165` |
| rain (compulsory) | Rainfall data [mm]. Example: `rain = data.rex_rain_csv.by_name.value` |
| evap (compulsory) | Potential evapotranspiration data [mm]. Example: `evap = data.rex_mpot_csv.by_name.value` |
| params (compulsory) | The eight AWBM model parameters, in this order: a1, a2, c1, c2, c3, bfi, k_base, k_surf. Example: `params = 0.134, 0.433, 7, 70, 150, 0.35, 0.95, 0.35`. With `variant = two_tap` there are eleven: see [The two-tap variant](#the-two-tap-variant). |
| variant (optional) | Selects the model formulation: `awbm` (Boughton's daily model, used by default if omitted) or `two_tap` (Hydro Tasmania's two-tap groundwater store). Example: `variant = two_tap` |
| cap\_ave (optional) | An expression evaluated every timestep that multiplies the three store capacities, so they can follow a seasonal profile. Absent means 1. Example: `cap_ave = table.capave(sim.day_of_year)` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. AWBM nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

The eight model parameters — and the rainfall-weighting terms, when `rain` is a linear combination of stations — can be calibrated with the built-in optimiser: see [Optimisable parameters](optimisable-parameters.md).

## Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML] |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Order on link ds\_1 [ML] |
| runoff\_volume | Catchment runoff volume from the AWBM model [ML] |
| runoff\_depth | Catchment runoff depth from the AWBM model [mm] |
| rain | Input rainfall [mm] |
| evap | Input evapotranspiration [mm] |
| area | Catchment area [km2] — the declared `area` value, static for the whole run. See [Static Node Properties](referencing-model-results.md#static-node-properties). |

## How the node works

The AWBM node adds inflows from an AWBM model (Boughton, 2004) to the system. The downstream flow is

`dsflow = usflow + runoff_volume`

where `runoff_volume = runoff_depth × area`.

AWBM is a daily saturation-overflow model. The catchment is divided into three partial areas, each with its own surface store, and the runoff they generate is split between a surface-runoff store and a baseflow store, each of which drains at a constant fraction per day.

### Parameters

| Parameter | Description | Units | Valid range | Default |
| --- | --- | --- | --- | --- |
| a1 | Partial area of surface store 1 | – | 0 – 1 | 0.134 |
| a2 | Partial area of surface store 2 | – | 0 – 1 | 0.433 |
| c1 | Capacity of surface store 1 | mm | ≥ 0 | 7 |
| c2 | Capacity of surface store 2 | mm | ≥ 0 | 70 |
| c3 | Capacity of surface store 3 | mm | ≥ 0 | 150 |
| bfi | Baseflow index: the fraction of store excess that recharges the baseflow store | – | 0 – 1 | 0.35 |
| k_base | Baseflow recession constant: the fraction of the baseflow store *retained* each day | – | 0 – 1 | 0.95 |
| k_surf | Surface-runoff recession constant: the fraction of the surface store *retained* each day | – | 0 – 1 | 0.35 |

The third partial area is not a parameter; it is `a3 = 1 − a1 − a2`, so `a1 + a2` must not exceed 1. The defaults are Boughton's published values (a1, a2, a3 = 0.134, 0.433, 0.433; c1, c2, c3 = 7, 70, 150 mm; bfi 0.35; k_base 0.95; k_surf 0.35).

The node checks the parameter set once, before every run. A set outside the valid ranges above (including `a1 + a2 > 1`) stops the run with a message naming the node and the offending value. Parameters are never clamped: what you write is what runs.

### Timestep

For each surface store *i* (with capacity `ci` and current depth `Si`), rainfall `P` is added, evapotranspiration is taken at the potential rate `E` while water is available, and anything above capacity spills as excess:

```
Si      = Si + P
ETi     = min(E, Si)
Si      = Si − ETi
excessi = max(Si − ci, 0)
Si      = Si − excessi
```

The total excess is the area-weighted sum over the three stores, and is split by the baseflow index:

```
excess    = a1·excess1 + a2·excess2 + a3·excess3
recharge  = bfi · excess
surface   = (1 − bfi) · excess
```

Each of the two routing stores receives its share and releases a constant fraction of its contents:

```
BS       = BS + recharge
baseflow = (1 − k_base) · BS
BS       = BS − baseflow

SS       = SS + surface
routed   = (1 − k_surf) · SS
SS       = SS − routed

runoff_depth = baseflow + routed
```

All stores start empty. Water is conserved exactly: over any period, rainfall equals actual evapotranspiration plus runoff plus the change in the five stores.

### Seasonal capacities (`cap_ave`)

Boughton and Chiew (2003) showed that runoff volume depends mainly on the average surface capacity and much less on how it is spread over the three stores, and gave the standard spread: with the partial areas 0.134, 0.433, 0.433, the capacities are 0.075, 0.762 and 1.524 times the average capacity. Some practitioners, notably Hydro Tasmania's catchment models, go one step further and let that average capacity vary through the year to fit seasonal volumes.

Kalix supports this with the optional `cap_ave` property. It is an ordinary [expression](dynamic-expressions.md), evaluated each timestep, and the effective capacity of each store is `c_i × cap_ave`. Write the three `c` values as factors and put the average in `cap_ave`; a seasonal profile is a [1D table](tables.md) looked up on `sim.day_of_year`. Hydro Tasmania anchors each monthly value at the middle of its month and interpolates linearly between them, which a table does directly. The table below wraps the year by repeating December before January and January after December, so the clamping at the table ends never applies:

```ini
[table.capave]
values = doy, capave,
         -15.5, 30,
          15.5, 22,
          45.0, 44,
          74.5, 44,
         105.0, 85,
         135.5, 90,
         166.0, 90,
         196.5, 105,
         227.5, 96,
         258.0, 112,
         288.5, 87,
         319.0, 38,
         349.5, 30,
         380.5, 22,

[node.my_awbm_node]
type = awbm
...
cap_ave = table.capave(sim.day_of_year)
params = 0.134, 0.433, 0.075, 0.762, 1.524, 0.35, 0.95, 0.35
```

When the capacities fall during a step, water above the new capacity spills as excess in that step. The `cap_ave` value is not a parameter and is not optimisable; to calibrate an average capacity, tie `c1`, `c2` and `c3` to one gene in the optimisation file instead (for example `c1 = 0.075 * lin_range(g(1), 10, 300)` and likewise for `c2` and `c3`).

### The four-parameter (average capacity) form

Boughton and Chiew's ungauged-catchment form of the model, also called the revised AWBM, is the standard node with the partial areas fixed at 0.134, 0.433, 0.433 and the capacities tied to one average capacity through the factors 0.075, 0.762, 1.524. That leaves four parameters to calibrate: the average capacity, `bfi`, `k_base` and `k_surf`. It is the form used by Boughton's own UGAWBM program, by the Rainfall Runoff Library's average-capacity calibration, and by many Australian catchment models. Write the factors on the `params` line and the average in `cap_ave`:

```ini
[node.my_catchment]
type = awbm
loc = 20, 30
area = 165
rain = data.climate_csv.by_name.rain
evap = data.climate_csv.by_name.evap
cap_ave = 96
params = 0.134, 0.433, 0.075, 0.762, 1.524, 0.35, 0.95, 0.35
```

With `cap_ave = 96` this is exactly Boughton's default set (capacities of 7.2, 73 and 146 mm). To calibrate it, leave `a1` and `a2` alone and tie the three capacity factors to one gene, so the optimiser searches the average capacity while the spread stays fixed. `cap_ave` is not a parameter, so the gene goes into the factors:

```ini
[parameters]
node.my_catchment.c1 = 0.075 * lin_range(g(1), 10, 400)
node.my_catchment.c2 = 0.762 * lin_range(g(1), 10, 400)
node.my_catchment.c3 = 1.524 * lin_range(g(1), 10, 400)
node.my_catchment.bfi = lin_range(g(2), 0, 1)
node.my_catchment.k_base = lin_range(g(3), 0, 1)
node.my_catchment.k_surf = lin_range(g(4), 0, 1)
```

Here `cap_ave` is left at 1 in the model file (or omitted) so that the calibrated `c` values are the capacities themselves. Keep `cap_ave` for the seasonal case: a monthly profile in a table, with the factors on the `params` line and the average capacity of the profile calibrated by scaling the table's values, or by tying the factors to a gene exactly as above.

### Timestep length

This is the daily formulation. The recession constants `k_base` and `k_surf` are per-day fractions, so the model is intended for models running on a daily timestep. Using it at another timestep requires recalibrating those two constants; the node does not adjust them.

### Calibration

Typical calibration ranges (Boughton and Chiew, 2003; Rainfall Runoff Library) are 0 – 1 for the partial areas and the three unitless factors, 0 – 50 mm for `c1`, 0 – 200 mm for `c2` and 0 – 500 mm for `c3`. Because the optimiser searches each parameter in its own box, independent bounds of 0 – 1 on both `a1` and `a2` would generate infeasible candidates with `a1 + a2 > 1`, which the engine rejects and scores as infinity. KalixIDE's default expressions therefore search `a1` and `a2` over 0 – 0.5 each. If you need a larger `a1`, tie the two together in one expression, for example `a2 = lin_range(g(2), 0, 1) * (1 - lin_range(g(1), 0, 1))` alongside `a1 = lin_range(g(1), 0, 1)`.

## The two-tap variant

`variant = two_tap` selects the **AWBM Two Tap** developed by R. Parkyn at Hydro Tasmania (Parkyn and Wilson, 1997) and used in Kisters' Hydstra modelling package and in NRE Tasmania's TascatchSIM catchment models. It keeps the three surface stores and changes the groundwater side in two ways: the fraction of excess that recharges groundwater falls off as the store fills, and the groundwater store drains through two taps, so a recession can show two slopes. There is no surface routing store; direct runoff leaves the node in the step it is generated, and any catchment routing is done with a downstream [routing node](routing.md).

```ini
[node.my_two_tap_node]
type = awbm
variant = two_tap
loc = 20, 30
area = 30
rain = data.climate_csv.by_name.rain
evap = data.climate_csv.by_name.evap
cap_ave = table.capave(sim.day_of_year)
params = 0.134, 0.433, 0.075, 0.762, 1.524, 0.76, 90, 100, 0.98, 0.80, 22
```

### Parameters

The `params` line has eleven values. The first five are the standard surface-store parameters.

| # | Parameter | Hydstra name | Description | Units | Valid range |
| --- | --- | --- | --- | --- | --- |
| 1 | a1 | A1 | Partial area of surface store 1 | – | 0 – 1 |
| 2 | a2 | A2 | Partial area of surface store 2 | – | 0 – 1 |
| 3 | c1 | Cap1 | Capacity of surface store 1 (times `cap_ave`) | mm | ≥ 0 |
| 4 | c2 | Cap2 | Capacity of surface store 2 (times `cap_ave`) | mm | ≥ 0 |
| 5 | c3 | Cap3 | Capacity of surface store 3 (times `cap_ave`) | mm | ≥ 0 |
| 6 | inf_base | INFBase | Recharge fraction while the groundwater store is below `gw_sat` | – | 0 – 1 |
| 7 | gw_sat | GWstoreSat | Groundwater depth at which the recharge fraction starts to fall | mm | ≥ 0 |
| 8 | gw_max | GWstoreMax | Groundwater depth at which the recharge fraction reaches zero | mm | > gw_sat |
| 9 | k_base | K1 | Lower-tap retention: the fraction of the groundwater store *retained* each day | – | 0 – 1 |
| 10 | k2 | K2 | Upper-tap retention: the fraction of the depth above `h_gw` *retained* each day | – | 0 – 1 |
| 11 | h_gw | H_GW | Depth of the upper tap | mm | ≥ 0 |

The example values are Hydro Tasmania's 2007 Musselroe calibration, the only complete published set; they are a starting point, not defaults with any general standing. The lower tap is the slow one (K1 of 0.975 to 0.999 in published calibrations) and the upper tap the fast one (K2 of 0.80 to 0.97). Nothing enforces that ordering.

### Timestep

The surface stores are updated exactly as in the standard model, giving the area-weighted excess `EX`. Then, with `GW` the groundwater store:

```
lower_tap = (1 − k_base) · GW
upper_tap = (1 − k2) · max(GW − h_gw, 0)
GW        = GW − lower_tap − upper_tap

inf       = inf_base · clamp((gw_max − GW) / (gw_max − gw_sat), 0, 1)
recharge  = inf · EX
GW        = GW + recharge

direct    = EX − recharge
runoff_depth = direct + lower_tap + upper_tap
```

Both taps act on the store as it stands at the start of the step, and the recharge fraction is evaluated on the store after the taps have drained it. Water is conserved: over any period, rainfall equals actual evapotranspiration plus runoff plus the change in the four stores.

### Provenance and fidelity

The published descriptions of the Two Tap model (the Musselroe and Whanganui reports, and the TascatchSIM reference manual) give its structure as a schematic and describe the taps in words; they do not give the order of operations. The equations above are Kalix's definition, chosen as the reading of that schematic that best reproduces NRE Tasmania's Musselroe TascatchSIM output on 47 catchments over 85 years with the published parameters: recession ratios match exactly, volume bias is within 0.1 percent, and the Nash–Sutcliffe efficiency against the department's daily runoff is 0.975. The remaining difference is a within-event detail of the Hydstra implementation that its documentation does not settle, so results from Kalix and Hydstra should be expected to agree closely but not to the last decimal.

## References

Boughton, W. (2004). "The Australian water balance model." *Environmental Modelling & Software* 19(10): 943–956.

Boughton, W. and F. Chiew (2003). *Calibrations of the AWBM for use on ungauged catchments.* Technical Report 03/15, Cooperative Research Centre for Catchment Hydrology, Canberra.

Hydro Tasmania (2007). *Musselroe River Surface Water Model.* Report WR 2007/064 for the Department of Primary Industries and Water, Tasmania. Section 4.3 and Figure 4-2 describe the AWBM Two Tap model.

Entura (2011). *TascatchSIM Reference Manual, Draft 2.0.* Hydro Tasmania. Section 3.1 and Figure 3-2.
