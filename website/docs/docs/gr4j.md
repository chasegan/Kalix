---
title: "GR4J"
---

# GR4J

## At a glance…

This node uses a GR4J rainfall-runoff model to represent catchment inflows from a catchment of a fixed area. The model takes rainfall and potential evapotranspiration data and determines inflows. The GR4J model has 4 parameters representing catchment characteristics.

```ini
[node.my_gr4j_node]
type = gr4j
loc = 20, 30
area = 165
rain = data.rex_rain_csv.by_name.value
evap = data.rex_mpot_csv.by_name.value
params = 1500, 4, 65, 0.38
ds_1 = my_other_node
```

## Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Node naming conventions are discussed at . Example: `[node.my_gr4j_node]` |
| type (compulsory) | The node type, which is “gr4j” in this case. `type = gr4j` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| area (compulsory) | The catchment area [km2].  Example `area = 165` |
| rain (compulsory) | Rainfall data [mm]. Example: `rain = data.rex_rain_csv.by_name.value` |
| evap (compulsory) | Potential evapotranspiration data [mm]. Example: `rain = data.rex_mpot_csv.by_name.value` |
| params (compulsory) | The four GR4J model parameters: X1, X2, X3, X4. Example: `params = 1500, 4, 65, 0.38` |
| variant (optional) | Selects the model formulation: `gr4j` (classic daily model, used by default if omitted) or `gr4h` (suitable for sub-daily timesteps). Example: `variant = gr4h` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. Gr4j nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

The four model parameters — and the rainfall-weighting terms, when `rain` is a linear combination of stations — can be calibrated with the built-in optimiser: see [Optimisable parameters](optimisable-parameters.md).

## Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML] |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Order on link ds\_1 [ML] |
| runoff\_volume | Catchment runoff volume from GR4J model [ML] |
| runoff\_depth | Catchment runoff depth from GR4J model [mm] |
| rain | Input rainfall [mm] |
| evap | Input evapotranspiration [mm] |
| area | Catchment area [km2] — the declared `area` value, static for the whole run. See [Static Node Properties](referencing-model-results.md#static-node-properties). |

## How the node works

The GR4J node simply adds inflows from a GR4J model (Perrin et al., 2003) to the system. The downstream flow is

`dsflow=usflow+runoff\_volume`

The GR4J model uses four parameters:

- X1 (Production Storage Capacity): This parameter represents the maximum capacity of the production store in millimetres (mm).
  - Bounds: A common range for X1 is from 0 mm to 6000 mm.

- X2 (Groundwater Exchange Coefficient): This parameter controls the exchange of water between the groundwater and the routing store, with units of mm/day.
  - Bounds: The typical range for X2 is -20 mm/day to 10 mm/day. Negative values indicate groundwater infiltration, and positive values indicate water leaving the aquifer.

- X3 (Routing Storage Capacity): This parameter refers to the 1-day-ahead maximum capacity of the routing store in mm.
  - Bounds: A common range for X3 is 0 mm to 4000 mm.

- X4 (Unit Hydrograph Base Time): This parameter is the time base of the unit hydrograph in days.
  - Bounds: A typical range for X4 is 0.04 days to 20 days.

Considerations:

- Variability: While these are common bounds, the exact parameter ranges for GR4J can vary depending on the specific study, the characteristics of the catchments being modelled, and the calibration dataset used.

- Calibration: These bounds are used during the model calibration process to help find the optimal parameter values for a given hydrological model application.

## Daily (GR4J) and sub-daily (GR4H) formulations

By default the node uses the standard daily **GR4J** model. Setting `variant = gr4h` switches it to **GR4H**, the sub-daily formulation from the airGR package (INRAE). GR4H differs from GR4J only in two constants that were recalibrated for short timesteps: the unit-hydrograph shape exponent (2.5 in GR4J, 1.25 in GR4H), and the percolation coefficient (9/4 in GR4J, 21/4 in GR4H).

GR4H is appropriate for sub-daily timesteps (e.g. 15-minute, 1-hour, etc). Time-related quantities are expressed per timestep rather than per day — i.e. X2 is in mm/timestep and X4 is a number of timesteps.

## References

Perrin, C., C. Michel, et al. (2003). "Improvement of a parsimonious model for streamflow simulation." *Journal of Hydrology* 279(1-4): 275-289
