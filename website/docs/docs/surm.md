---
title: "SURM"
---

# SURM

## At a glance…

This node uses the Simple Urban Runoff Model (SURM) to represent catchment inflows from a catchment of a fixed area. SURM is the daily rainfall-runoff model used by MUSIC and Source for urban catchments: it generates runoff separately from the impervious and pervious parts of the catchment. The model takes rainfall and potential evapotranspiration data and determines inflows. SURM has 9 parameters.

```ini
[node.my_surm_node]
type = surm
loc = 20, 30
area = 12
rain = data.rain_csv.by_name.value
evap = data.mpot_csv.by_name.value
params = 0.45, 1, 97, 360, 0.5, 79, 1, 0.5, 0
ds_1 = my_other_node
```

## Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Example: `[node.my_surm_node]` |
| type (compulsory) | The node type, which is “surm” in this case. `type = surm` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| area (compulsory) | The catchment area [km2].  Example `area = 12` |
| rain (compulsory) | Rainfall data [mm]. Example: `rain = data.rain_csv.by_name.value` |
| evap (compulsory) | Potential evapotranspiration data [mm]. Example: `evap = data.mpot_csv.by_name.value` |
| params (compulsory) | The nine SURM model parameters, in this order: imp_fraction, impsc, smsc, coeff, sq, fc, rfac, bfac, sfac. Example: `params = 0.45, 1, 97, 360, 0.5, 79, 1, 0.5, 0` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. SURM nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

The nine model parameters — and the rainfall-weighting terms, when `rain` is a linear combination of stations — can be calibrated with the built-in optimiser: see [Optimisable parameters](optimisable-parameters.md).

## Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML] |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Order on link ds\_1 [ML] |
| runoff\_volume | Catchment runoff volume from the SURM model [ML] |
| runoff\_depth | Catchment runoff depth from the SURM model [mm] |
| rain | Input rainfall [mm] |
| evap | Input evapotranspiration [mm] |
| area | Catchment area [km2] — the declared `area` value, static for the whole run. See [Static Node Properties](referencing-model-results.md#static-node-properties). |

## How the node works

The SURM node adds inflows from a SURM model (Chiew et al., 1997) to the system. The downstream flow is

`dsflow = usflow + runoff_volume`

where `runoff_volume = runoff_depth × area`.

### Parameters

| Parameter | Description | Units | Valid range | Default |
| --- | --- | --- | --- | --- |
| imp_fraction | Impervious fraction of the catchment | – | 0 – 1 | 0 |
| impsc | Impervious initial loss: rain retained on the impervious area each day before runoff starts | mm | ≥ 0 | 1 |
| smsc | Soil moisture store capacity (pervious area) | mm | > 0 | 97 |
| coeff | Maximum infiltration rate, when the soil store is empty | mm/day | ≥ 0 | 360 |
| sq | Infiltration exponent: how quickly infiltration capacity falls as the soil store fills | – | ≥ 0 | 0.5 |
| fc | Field capacity: soil moisture above which groundwater recharge occurs | mm | ≥ 0 | 79 |
| rfac | Recharge factor: the fraction of soil moisture above field capacity recharged to groundwater each day | – | 0 – 1 | 1 |
| bfac | Baseflow factor: the fraction of the groundwater store released as baseflow each day | – | 0 – 1 | 0.5 |
| sfac | Deep-seepage factor: the fraction of the groundwater store lost from the catchment each day | – | 0 – 1 | 0 |

Note that `fc` is a depth in mm, not a fraction of `smsc` as in Source's parameter table. The default parameter set is an uncalibrated placeholder with no impervious area; many Australian authorities publish SURM parameter sets for MUSIC by region and soil type, and those should be consulted for real applications.

The node checks the parameter set once, before every run. A set outside the valid ranges above stops the run with a message naming the node and the offending value. Parameters are never clamped: what you write is what runs.

### Timestep

Rainfall `P` and potential evapotranspiration `E` are depths over the whole catchment. The soil store `S` and groundwater store `G` are depths over the pervious area.

**Impervious area.** Rain up to `impsc` is retained as an initial loss; the rest runs off. The loss store is emptied every day, so it never carries water forward.

```
imp_runoff = max(P − impsc, 0)
```

**Pervious area.** Infiltration capacity falls exponentially as the soil store fills. Rain beyond capacity is infiltration-excess runoff.

```
capacity     = coeff · exp(−sq · S / smsc)
infiltration = min(capacity, P)
infex        = P − infiltration
S            = S + infiltration
```

Evapotranspiration is taken from the soil store at a rate that rises with soil wetness, capped by potential evapotranspiration and by the water available:

```
ET = min(10 · S / smsc, E, S)
S  = S − ET
```

Water above the store's capacity is saturation-excess runoff, and water above field capacity recharges groundwater:

```
satex    = max(S − smsc, 0)
S        = S − satex
recharge = min(max(rfac · (S − fc), 0), S)
S        = S − recharge
G        = G + recharge
```

Groundwater releases baseflow to the stream and loses deep seepage from the catchment. Both are constant fractions of the store; seepage is limited so the store cannot go negative.

```
baseflow = bfac · G
seepage  = min(sfac · G, G − baseflow)
G        = G − baseflow − seepage
```

**Catchment runoff** weights the two areas by their fractions:

```
runoff_depth = imp_fraction · imp_runoff + (1 − imp_fraction) · (infex + satex + baseflow)
```

Both stores start empty. Water is conserved: over any period, rainfall equals runoff plus soil-store evapotranspiration plus the impervious initial loss plus deep seepage plus the change in the two stores.

### Timestep length

This is the daily formulation. `coeff` is a rate in mm/day and the evapotranspiration factor of 10 mm/day is a daily constant, so the model is intended for models running on a daily timestep. Using it at another timestep requires recalibration; the node does not adjust the constants.

## References

Chiew, F.H.S., L.B. Mudgway, H.P. Duncan and T.A. McMahon (1997). *Urban Stormwater Pollution.* Industry Report 97/5, Cooperative Research Centre for Catchment Hydrology, Canberra.

eWater (2009). *music v4 by eWater User Manual.* eWater, Canberra.
