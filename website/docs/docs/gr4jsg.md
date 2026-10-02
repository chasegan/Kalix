---
title: "GR4JSG"
---

# GR4JSG

## At a glance…

This node uses the GR4JSG rainfall-runoff model to represent inflows from a catchment where snow matters. GR4JSG is GR4J with a snow store in front of it and, optionally, a glacier. The model takes precipitation, potential evapotranspiration, and daily maximum and minimum temperature, and determines inflows. It has the four GR4J parameters and five snow parameters. A glacier adds five more.

```ini
[node.my_snowy_node]
type = gr4jsg
loc = 20, 30
area = 165
rain = data.rain_csv.by_name.value
evap = data.mpot_csv.by_name.value
tmax = data.temp_csv.by_name.tmax
tmin = data.temp_csv.by_name.tmin
params = 1500, 4, 65, 0.38
snow_params = 0.5, 0, 3.38, 1.3, 3
ds_1 = my_other_node
```

## Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Example: `[node.my_snowy_node]` |
| type (compulsory) | The node type, which is “gr4jsg” in this case. `type = gr4jsg` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| area (compulsory) | The catchment area [km2].  Example `area = 165` |
| rain (compulsory) | Precipitation data [mm]. The model decides each day whether it falls as rain or snow. Example: `rain = data.rain_csv.by_name.value` |
| evap (compulsory) | Potential evapotranspiration data [mm]. Example: `evap = data.mpot_csv.by_name.value` |
| tmax (compulsory) | Daily maximum temperature of the catchment [°C]. Example: `tmax = data.temp_csv.by_name.tmax` |
| tmin (compulsory) | Daily minimum temperature of the catchment [°C]. Example: `tmin = data.temp_csv.by_name.tmin` |
| params (compulsory) | The four GR4J model parameters: x1, x2, x3, x4. They mean what they mean on a [GR4J node](gr4j.md). Example: `params = 1500, 4, 65, 0.38` |
| snow\_params (compulsory) | The five snow parameters, in this order: tfrac, taccum, m\_rainfall, base\_rainfall, m\_nonrainfall. Example: `snow_params = 0.5, 0, 3.38, 1.3, 3` |
| ice\_params (optional) | Gives the catchment a glacier. The five glacier parameters, in this order: initial\_ice, ddfi, tmelt, return\_flow, accumulation. Example: `ice_params = 100000, 6, 0, 0.5, 0` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. GR4JSG nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

The model parameters — and the rainfall-weighting terms, when `rain` is a linear combination of stations — can be calibrated with the built-in optimiser: see [Optimisable parameters](optimisable-parameters.md#gr4jsg-nodes-type-gr4jsg).

## Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML] |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Order on link ds\_1 [ML] |
| runoff\_volume | Catchment runoff volume from the GR4JSG model [ML] |
| runoff\_depth | Catchment runoff depth from the GR4JSG model [mm] |
| rain | Input precipitation [mm] |
| evap | Input evapotranspiration [mm] |
| production\_store | GR4J production store at the end of the day [mm] |
| routing\_store | GR4J routing store at the end of the day [mm] |
| snowfall | Precipitation that fell as snow [mm] |
| snow\_melt | Snow melted [mm] |
| snow\_store | Snow store at the end of the day [mm] |
| ice\_melt | Ice melted from the ice store, before routing [mm]. Zero for a node without a glacier. |
| ice\_store | Ice store at the end of the day [mm]. Zero for a node without a glacier. |
| area | Catchment area [km2] — the declared `area` value, static for the whole run. See [Static Node Properties](referencing-model-results.md#static-node-properties). |

## How the node works

The GR4JSG node adds inflows from a GR4JSG model to the system. The downstream flow is

`dsflow = usflow + runoff_volume`

where `runoff_volume = runoff_depth × area`.

The model is the formulation developed by the NSW Department of Planning, Industry and Environment (the Source plugin `GRSG_DPIE`). It puts a snow store, and optionally an ice store, in front of an unmodified [GR4J](gr4j.md) model:

- precipitation falls as snow on a cold day and is held in the snow store;
- snow melt joins the rain entering GR4J;
- ice melt, where there is a glacier, bypasses GR4J and goes to runoff through its own unit hydrograph.

A catchment that is never cold enough for snow gives exactly the results of a GR4J node with the same `params`.

### Snow parameters

| Parameter | Description | Units | Valid range | Default |
| --- | --- | --- | --- | --- |
| tfrac | Weight on `tmax` in the representative temperature. 0 uses `tmin`, 1 uses `tmax`, 0.5 uses their mean | – | 0 – 1 | 0.5 |
| taccum | Precipitation falls as snow when the representative temperature is below this | °C | any | 0 |
| m\_rainfall | Melt rate on a day with rain | mm/°C/day | ≥ 0 | 3.38 |
| base\_rainfall | Base melt on a day with rain | mm/day | ≥ 0 | 1.3 |
| m\_nonrainfall | Melt rate on a day without rain | mm/°C/day | ≥ 0 | 3 |

### Glacier parameters

| Parameter | Description | Units | Valid range | Default |
| --- | --- | --- | --- | --- |
| initial\_ice | Ice store at the start of the run | mm | ≥ 0 | – |
| ddfi | Ice degree-day factor | mm/°C/day | ≥ 0 | 6 |
| tmelt | Ice melts when the representative temperature is above this | °C | any | 0 |
| return\_flow | Time base of the ice-melt unit hydrograph. At 0.5 or less, ice melt reaches the stream the day it melts | days | > 0 | 0.5 |
| accumulation | Constant gain to the ice store, every day | mm/day | ≥ 0 | 0 |

The node checks the parameters once, before every run. A set outside the valid ranges above stops the run with a message naming the node and the offending value. Parameters are never clamped: what you write is what runs.

### Timestep

`P` is precipitation, and `tmax` and `tmin` are the day's temperatures. The representative temperature is

```
T = tfrac · tmax + (1 − tfrac) · tmin
```

**Rain or snow.** The whole day's precipitation is one or the other.

```
if T < taccum:  snowfall = P,  rain = 0,  snow_store = snow_store + P
otherwise:      snowfall = 0,  rain = P
```

**Snow melt.** While there is snow, it melts at a potential rate that depends on whether it rained. A day with rain uses the equation of USACE (1960); a day without uses that of Quick and Pipes (1976).

```
with rain:     potential = max(T, 0) · (m_rainfall + 0.0126 · rain) + base_rainfall
without rain:  beta      = min(max(tmin, 0) / 4.4, 1.5)
               potential = m_nonrainfall · (max(T, 0) + beta · ((tmax − tmin) / 8 + tmin))

snow_melt  = min(potential, snow_store)
snow_store = snow_store − snow_melt
```

Rain and snow melt together are the input to GR4J, with potential evapotranspiration `evap`:

```
runoff_depth = GR4J(rain + snow_melt, evap)
```

**Glacier.** A node with `ice_params` also has an ice store, which starts at `initial_ice`. Ice melts for the part of the day not spent melting snow:

```
snow_fraction = snow_melt / potential          (0 when potential is 0)

if ice_store > 0 and T > tmelt:
    ice_melt  = min((1 − snow_fraction) · ddfi · (T − tmelt), ice_store)
    ice_store = ice_store − ice_melt
```

Ice melt is routed through a unit hydrograph of the same shape as GR4J's second one, with `return_flow` in place of x4, and added to the runoff:

```
runoff_depth = GR4J(rain + snow_melt, evap) + UH(ice_melt)
```

At the start of each day `accumulation` is added to the ice store while there is ice, and to the snow store once the ice is gone.

All stores except the ice store start empty, so allow a warm-up period.

### What a glacier is in this model

Nothing in the model makes ice. Snow never turns into ice, so a glacier exists only where `initial_ice` puts one, and `accumulation` is the only thing that feeds it. Three things follow.

- **The glacier can melt out.** At the default `ddfi` a glacier loses 6 mm for every degree-day above `tmelt`, which is metres a year in a mild climate. When the ice store reaches zero it stays at zero, and from then on the node behaves as a node without a glacier. Record `ice_store` and check that this is what you intend.
- **`initial_ice` matters mainly by being large enough.** While there is ice, the melt rate does not depend on how much.
- **`accumulation` is water from outside the catchment.** It stands for snow arriving from slopes above, by avalanche or wind. It is not taken from `rain`, so it adds to the catchment's water balance.

### Elevation and lapse rates

`tmax`, `tmin`, `rain` and `evap` describe the catchment, not the climate station. Where the catchment is higher than the station, write the adjustment in the expression:

```ini
[const]
const.t_lapse = -0.0065    # °C per m
const.dz = 800             # m, catchment above the station

[node.my_snowy_node]
type = gr4jsg
tmax = data.temp_csv.by_name.tmax + const.t_lapse * const.dz
tmin = data.temp_csv.by_name.tmin + const.t_lapse * const.dz
evap = max(0, data.mpot_csv.by_name.value - 0.0005 * const.dz)
```

A lapse rate written as a constant can be calibrated as `const.t_lapse`. Modellers who know the Source plugin will find that its elevation, lapse-rate and rainfall-scaling parameters are not node parameters here; this is where they go.

### Timestep length

This is a daily model. The melt rates are per day and the melt equations carry daily constants, so the node is intended for models running on a daily timestep. It does not adjust the constants for another timestep.

## References

Perrin, C., C. Michel, et al. (2003). "Improvement of a parsimonious model for streamflow simulation." *Journal of Hydrology* 279(1-4): 275-289

Quick, M.C. and A. Pipes (1976). "A combined snowmelt and rainfall runoff model." *Canadian Journal of Civil Engineering* 3(3): 449-460

U.S. Army Corps of Engineers (1960). *Runoff from Snowmelt.* Engineering Manual 1110-2-1406, Washington, D.C.
