---
title: "Regulated_User"
---

# Regulated_User

## At a glance…

The regulated\_user node represents a water user with the ability to place orders. The user orders in anticipation of demands, and subsequently diverts water from the network to satisfy those demands. If the demand cannot be fully satisfied, this is called a shortfall.

```toml
[node.urban_user]
type = regulated_user
loc = 20, 30
order = data.extendeddataset.by_name.urban_demand
ds_1 = my_other_node
```

## Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Node naming conventions are discussed at . Example: `[node.urban_user]` |
| type (compulsory) | The node type, which is “user” in this case. `type = user` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| order (optional) | This property specifies how much the regulated user will order [ML] each timestep. In regulated systems with nonzero travel times, the demand will be lagged to give enough time for the water to arrive.  Example: `order = data.extendeddataset.by_name.urban_demand` |
| pump (optional) | Use this to limit the amount of water the user can extract each timestep. Example: `pump = 86.4` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. Inflow nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

## Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML] |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Order on link ds\_1 [ML] |
| order | The order placed today [ML] |
| order\_due | The order previously placed, which is due to be delivered today [ML] |
| demand | Demand at this node [ML] |
| diversion | Diverted volume [ML] |
| pump | Pump capacity value [ML] which may vary due to functions |

## How the node works

Demands and diversions must be positive. The user node extract flows to meet the demand as specified in the user node.

#### Pump capacity

Flow available for diversion is limited by the specified pump capacity.

`available=min(usflow,pump capacity)`

#### Diversion

`diversion=min(usflow,demand)`

`dsflow=usflow−diversion`

## References

None.
