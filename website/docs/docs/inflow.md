---
title: "Inflow"
---

# Inflow

## At a glance…

The inflow node represents an inflow to the system. This can be time-varying and therefore can represent natural inflows (e.g. from a catchment) or an inflow from another system. An inflow node adds water and never takes it.

```ini
[node.my_inflow_node]
type = inflow
loc = 20, 30
inflow = data.allflows_csv.by_name.little_river_inflow
ds_1 = my_other_node
```

## Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Node naming conventions are discussed at . Example: `[node.my_inflow_node]` |
| type (compulsory) | The node type, which is “inflow” in this case. `type = inflow` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| inflow (optional) | Inflow data [ML]. A negative value adds no water. A value that is not a number is carried by no link. Example: `inflow = data.allflows_csv.by_name.little_river_inflow` |
| expected\_inflow (optional) | Expected inflow for purpose of adjusting orders [ML]. A negative value is taken as no expected inflow. Example: `expected_inflow = 0.5 * this.inflow[-1,0]` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. Inflow nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

## Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML] |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Order on link ds\_1 [ML] |
| inflow | The value of the inflow expression [ML]. This is the lateral flow only, not including the component that came from any upstream nodes. A negative value is recorded as it was given, and adds no water. |
| expected\_inflow | The value of the expected\_inflow expression [ML] which is used to reduce required upstream flow when adjusting for orders. |

## How the node works

The inflow node simply adds inflows to the system as specified by the “inflow” property. The downstream flow is therefore

`dsflow=usflow+inflow`

## References

None.
