---
title: "Routing"
---

# Routing

*Page status:* ✅

# At a glance…

The routing node simulates streamflow routing. Each routing node incorporates a lag-routing component, and a storage routing component.

```toml
[node.reach_4_routing]
type = routing
loc = 20, 30
lag = 2
pwl = dfd, sdfd,
      dfd, dfddf,
n_divs = 3
x = 0
ds_1 = my_other_node
```

# Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Node naming conventions are discussed at . Example: `[node.reach_4_routing]` |
| type (compulsory) | The node type, which is “routing” in this case. `type = routing` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| lag (optional) | Parameter for lag routing. This is an integer number of [timesteps], and in a daily model the units of this parameter are therefore [days].  Example: `lag = 2` |
| pwl (optional) | Comma delimited values representing the piecewise linear storage routing relationship. There must be an even number of values, with each consecutive pair representing a flow and a corresponding travel time. Refer to this page to read more about in Kalix. Example: `pwl = 0, 3, 10, 3, 100, 2, 200, 1, 500, 0, 1e8, 0` |
| nlm (optional) | Nonlinear Muskingum parameters: k, m. Using these parameters will activate nonlinear Muskingum routing algorithm. Cannot be used in conjunction with piecewise linear on the same reach. Units for k are [meters^(3(1-m)) · s^m]. Following the convention of other platforms, if n\_divs > 1 then k applies per division. Example: `nlm = 183000, 0.75` |
| n\_divs (optional) | The number of divisions used in the pwl storage routing solver. Default value is 1. Example: `n_divs = 10` |
| x (optional) | Inflow bias. This sets the bias of the upstream flow (as opposed to the downstream flow) in the index flow term used in the pwl storage routing solver. Default value is 0. Example: `x = 0` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. Inflow nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

# Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML] |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Orders on the link ds\_1 [ML] |
| volume | Volume of water in the reach storage [ML] |

# How the node works

The node includes two routing functions which may be used together or individually.

**Lag routing** - flows are delayed by a fixed number of timesteps set by the node’s “lag” parameter.

**Piecewise-linear storage routing** - the node simulated storage routing through a certain number “n\_divs” of sections. For each section, the outflow is determined by solving the storage routing equation `Vi=V(qref,i)`, where the reference flow is

`qref,i=x qin,i+(1−x) qout,i`

and mass balance requires that

`Vi=Vi−1+qin,i−qout,i`

# References

None.
