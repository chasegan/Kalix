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
| typical\_regulated\_flow (optional) | A representative regulated flow rate [ML], used to estimate travel time through this reach when propagating orders upstream (see [Ordering](ordering.md)). Default value is 0. Example: `typical_regulated_flow = 250` |
| loss\_rate (optional) | Loss along the reach [ML per timestep]: a data reference, constant, or [dynamic expression](dynamic-expressions.md). The value is split equally across the divisions and taken inside each division's routing balance, so the water leaves the reach's storage rather than its inflow or outflow. It is bounded so that no division's outflow can go negative; a NaN or negative value loses nothing. See [Reach losses](#reach-losses). Example: `loss_rate = data.losses.reach_4` |
| dead\_storage (optional) | Water the reach holds at zero flow [ML], as one volume for the reach split equally across the `n_divs` divisions. Built into the routing law, so it costs nothing per step. The reach starts at dead level, passes nothing until its pools are full, and a `loss_rate` can drain them. Default 0. See [Dead storage](#dead-storage). Example: `dead_storage = 100` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. Inflow nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

The routing parameters — the `nlm` pair, or the travel times of the `pwl` table — can be calibrated with the built-in optimiser: see [Optimisable parameters](optimisable-parameters.md).

## Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML] |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Orders on the link ds\_1 [ML] |
| volume | Volume of water in the reach storage [ML], dead water included |
| x | The declared `x` value, static for the whole run. See [Static Node Properties](referencing-model-results.md#static-node-properties). |
| typical\_regulated\_flow | The declared `typical_regulated_flow` value, static for the whole run. See [Static Node Properties](referencing-model-results.md#static-node-properties). |
| dead\_storage | The declared `dead_storage` value, static for the whole run. See [Static Node Properties](referencing-model-results.md#static-node-properties). |
| loss\_rate | The evaluated `loss_rate` expression, before bounding [ML]. All missing values when no `loss_rate` property is set. |
| loss | Water actually lost from the reach this timestep [ML]: each division's bounded loss, summed. Zero when no `loss_rate` is set. |

## How the node works

The node includes two routing functions which may be used together or individually.

**Lag routing** - flows are delayed by a fixed number of timesteps set by the node’s “lag” parameter.

**Piecewise-linear storage routing** - the node simulated storage routing through a certain number “n\_divs” of sections. For each section, the outflow is determined by solving the storage routing equation `Vi=V(qref,i)`, where the reference flow is

`qref,i=x qin,i+(1−x) qout,i`

and mass balance requires that

`Vi=Vi−1+qin,i−qout,i`

**Flows above the table** - when the reference flow exceeds the last row of the `pwl` table, the travel time is treated as flat beyond the table (flat extrapolation): the section's storage saturates at the storage integral evaluated at the last index flow, and the balance is released downstream, so mass always balances. Tables therefore do not need a synthetic huge-flow guard row.

### Reach losses

With `loss_rate` set, the reach loses that much water each timestep, in ML. The amount is split equally across the `n_divs` divisions and taken inside each division's backward Euler balance, `V(q) = V₀ + inflow − loss − outflow`, so the loss comes out of the water in the reach - not off the inflow before routing, and not off the outflow after it.

Each division's loss is bounded so its outflow cannot go negative. The bound has a closed form: outflow reaches zero when the reference flow is `x·qin`, so a division can pass at most `V₀ + qin − V(x·qin)` to the loss before its outflow stops. Once it has, the rest of the request comes out of what the division holds, so a loss can empty a stagnant reach; nothing is ever invented, and a dry reach with no inflow loses nothing. The `loss` output reports what was actually taken. A NaN or negative `loss_rate` loses nothing.

Note on ordering: upstream orders pass through a routing node unchanged, and a `loss_rate` expression does not alter that - ordering has no way to foresee an arbitrary expression's value, so it assumes zero loss along the reach, as it does for a loss node with no table.

### Dead storage

`dead_storage` is the water a reach holds when nothing flows — the pools left in a channel between events — given as one volume for the reach, in ML, and split equally across the `n_divs` divisions. It enters each division's storage law as an offset, `V′(q) = dead + V(q)`, so the routing arithmetic is unchanged and the property costs nothing per step. A lag-only reach, whose law is otherwise zero, becomes a plain pool.

Below the dead level nothing routes. A division holding less than its share, counting the step's inflow, keeps everything and passes nothing on; outflow resumes once the pool is full. The reach starts at dead level, as its pools would be at the start of a simulation, and that initial water sits outside the mass balance like a storage's `initial_volume`.

A reach loss can drain the pool. The loss first reduces outflow to zero, then continues into whatever the division still holds, dead or live, so evaporation empties a stagnant reach; inflow then refills it before anything passes. The declared value is reported as the static output `dead_storage`.

## References

None.
