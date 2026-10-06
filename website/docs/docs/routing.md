---
title: "Routing"
---

# Routing

## At a glance…

The routing node simulates streamflow routing. Each routing node incorporates a lag-routing component, and a storage routing component.

```ini
[node.reach_4_routing]
type = routing
loc = 20, 30
lag = 2
pwl = Flow [ML], Travel Time [steps],
      0,         3,
      100,       2,
      500,       1,
n_divs = 3
x = 0
ds_1 = my_other_node
```

## Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Node naming conventions are discussed at . Example: `[node.reach_4_routing]` |
| type (compulsory) | The node type, which is “routing” in this case. `type = routing` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| lag (optional) | Parameter for lag routing. This is an integer number of [timesteps], and in a daily model the units of this parameter are therefore [days].  Example: `lag = 2` |
| pwl (optional) | Comma delimited values representing the piecewise linear storage routing relationship: a two-column table of index flow and travel time, one pair per row, laid out across lines however you like (see [Tables](conventions.md#tables)). An optional header row of two column names may come first, as in the example above; it is ignored by the engine. Up to 32 rows. Example: `pwl = 0, 3, 10, 3, 100, 2, 200, 1, 500, 0, 1e8, 0` |
| nlm (optional) | Nonlinear Muskingum parameters: k, m. Using these parameters will activate nonlinear Muskingum routing algorithm. Cannot be used in conjunction with piecewise linear on the same reach. Units for k are [meters^(3(1-m)) · s^m]. Following the convention of other platforms, if n\_divs > 1 then k applies per division. Example: `nlm = 183000, 0.75` |
| n\_divs (optional) | The number of divisions used in the pwl storage routing solver. Default value is 1. Example: `n_divs = 10` |
| x (optional) | Inflow bias. This sets the bias of the upstream flow (as opposed to the downstream flow) in the index flow term used in the pwl storage routing solver. Default value is 0. Example: `x = 0` |
| evap (optional) | Evaporation from the water surface of the reach [mm]: a data reference, constant, or [dynamic expression](dynamic-expressions.md). Must not be negative. Given together with `loss_table`. See [Reach losses and dead storage](#reach-losses-and-dead-storage). Example: `evap = data.climate_csv.by_name.mpot` |
| loss\_table (optional) | A three-column table of flow [ML], dead storage volume [ML] and surface area [km²] for the whole reach, laid out across lines however you like (see [Tables](conventions.md#tables)). An optional header row of three column names may come first; it is ignored by the engine. Given together with `evap`. See [Reach losses and dead storage](#reach-losses-and-dead-storage) for what the rows mean and the rules they follow. |
| typical\_regulated\_flow (optional) | A representative regulated flow rate [ML], used to estimate travel time through this reach when propagating orders upstream (see [Ordering](ordering.md)). Default value is 0. Example: `typical_regulated_flow = 250` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. Inflow nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

The routing parameters — the `nlm` pair, or the travel times of the `pwl` table — can be calibrated with the built-in optimiser: see [Optimisable parameters](optimisable-parameters.md).

## Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML] |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Orders on the link ds\_1 [ML] |
| volume | Volume of water in the reach storage [ML], including any dead storage |
| evap | Input evaporation [mm]. Zero for a reach without reach losses. |
| area | Water surface area of the reach [km²]. Zero for a reach without reach losses. |
| loss | Volume lost to evaporation this timestep [ML]. Zero for a reach without reach losses. |
| x | The declared `x` value, static for the whole run. See [Static Node Properties](referencing-model-results.md#static-node-properties). |
| typical\_regulated\_flow | The declared `typical_regulated_flow` value, static for the whole run. See [Static Node Properties](referencing-model-results.md#static-node-properties). |

## How the node works

The node includes two routing functions which may be used together or individually.

**Lag routing** - flows are delayed by a fixed number of timesteps set by the node’s “lag” parameter.

**Piecewise-linear storage routing** - the node simulated storage routing through a certain number “n\_divs” of sections. For each section, the outflow is determined by solving the storage routing equation `Vi=V(qref,i)`, where the reference flow is

`qref,i=x qin,i+(1−x) qout,i`

and mass balance requires that

`Vi=Vi−1+qin,i−qout,i`

**Reach losses** - when the node has `evap` and `loss_table`, each section's mass balance also carries the evaporation loss and the section's share of the dead storage. See [Reach losses and dead storage](#reach-losses-and-dead-storage).

**Flows above the table** - when the reference flow exceeds the last row of the `pwl` table, the travel time is treated as flat beyond the table (flat extrapolation): the section's storage saturates at the storage integral evaluated at the last index flow, and the balance is released downstream, so mass always balances. Tables therefore do not need a synthetic huge-flow guard row.

## Reach losses and dead storage

A routing node can lose water to evaporation from its water surface, and can hold a **dead storage**: water that stays in the reach when it stops flowing, such as pools in an ephemeral river. Both are switched on by giving the node an `evap` and a `loss_table` together. A node with neither behaves exactly as described above.

```ini
[node.reach_4_routing]
type = routing
loc = 20, 30
pwl = Flow [ML], Travel Time [steps],
      0,         3,
      100,       2,
      500,       1,
n_divs = 3
evap = data.climate_csv.by_name.mpot
loss_table = Flow [ML], Dead storage [ML], Area [km2],
             0,         0,                 0,
             0,         130,               0.8,
             0,         150,               1.0,
             50,        150,               1.5,
             200,       150,               2.2,
ds_1 = my_other_node
```

**Reading the table.** The table describes the whole reach, and has two parts.

- The rows at zero flow describe the dead storage filling: how much surface area the reach has when it holds that volume and is not flowing. In the example the dead storage holds up to 150 ML, with 0.8 km² of surface at 130 ML and 1.0 km² when full.
- The rows above zero flow give the surface area when the reach is flowing at that rate. The dead storage is full whenever the reach flows, so these rows repeat the full dead storage volume (150 ML in the example).

Area is interpolated linearly between rows. Above the last row the area stays at the last row's value.

**Rules for the table.** The model will not run unless:

- the first row is `0, 0, 0`;
- no value is negative;
- flow and dead storage volume never decrease down the table, and flows above zero strictly increase (rows at zero flow may repeat);
- area never decreases down the table;
- every row above zero flow has the same dead storage volume as the last zero-flow row.

A table with no dead storage is allowed: give the zero-flow rows a dead storage volume of zero.

**How it behaves.**

- The loss each timestep is `evap × area`. With evaporation in mm and area in km², that is a volume in ML.
- While the reach flows, the area comes from the flow rows, at the reference flow `qref`.
- The reach does not flow until its dead storage is full. Inflow first fills the dead storage and covers the loss; only water above the dead storage is routed downstream.
- When the reach is not flowing, the dead storage drains by evaporation alone, and the area comes from the zero-flow rows at the volume held.
- A reach starts the simulation with its dead storage full. That starting water is not counted in the mass balance report, in the same way as a storage node's `initial_volume`.
- With `n_divs` greater than 1, the dead storage and area are shared equally between the divisions, so the table always describes the reach as a whole.

Reach losses work with every routing method: lag only, piecewise-linear and nonlinear Muskingum, for any `x`.

**Evaporation must not be negative.** `evap` is meant for evaporation, not net rainfall. Negative values are not checked for and the results are not guaranteed.

**Orders are not raised to cover the loss.** An order passes upstream through a routing node unchanged, so a user downstream of a reach with losses receives less than it ordered, by about the loss. See [How do routing nodes affect orders?](ordering.md#how-do-routing-nodes-affect-orders) for two ways to allow for this.

**Reach losses or a loss node?** Use reach losses when the loss is evaporation from a surface whose area changes with flow, or when the reach holds water after it stops flowing. For a loss that is simply a function of flow, a [loss node](loss.md) is the simpler tool.

!!! note
    Version 0.4.5 had alpha `loss_rate` and `dead_storage` properties on the routing node. These have been replaced by `evap` and `loss_table`. A model that still uses them will not load, and the error names the new properties.

## References

None.
