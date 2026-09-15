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
| params (compulsory) | The eight AWBM model parameters, in this order: a1, a2, c1, c2, c3, bfi, k_base, k_surf. Example: `params = 0.134, 0.433, 7, 70, 150, 0.35, 0.95, 0.35` |
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

### Timestep length

This is the daily formulation. The recession constants `k_base` and `k_surf` are per-day fractions, so the model is intended for models running on a daily timestep. Using it at another timestep requires recalibrating those two constants; the node does not adjust them.

### Calibration

Typical calibration ranges (Boughton and Chiew, 2003; Rainfall Runoff Library) are 0 – 1 for the partial areas and the three unitless factors, 0 – 50 mm for `c1`, 0 – 200 mm for `c2` and 0 – 500 mm for `c3`. Because the optimiser searches each parameter in its own box, independent bounds of 0 – 1 on both `a1` and `a2` would generate infeasible candidates with `a1 + a2 > 1`, which the engine rejects and scores as infinity. KalixIDE's default expressions therefore search `a1` and `a2` over 0 – 0.5 each. If you need a larger `a1`, tie the two together in one expression, for example `a2 = lin_range(g(2), 0, 1) * (1 - lin_range(g(1), 0, 1))` alongside `a1 = lin_range(g(1), 0, 1)`.

## References

Boughton, W. (2004). "The Australian water balance model." *Environmental Modelling & Software* 19(10): 943–956.

Boughton, W. and F. Chiew (2003). *Calibrations of the AWBM for use on ungauged catchments.* Technical Report 03/15, Cooperative Research Centre for Catchment Hydrology, Canberra.
