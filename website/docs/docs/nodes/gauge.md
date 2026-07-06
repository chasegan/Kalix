---
title: "Gauge"
---

# Gauge

## At a glance…

The gauge node is a reporting point. The node is otherwise passive.

```toml
[node.gs120001]
type = gauge
loc = 20, 30
reference_flow = data.my_file_csv.by_index.0
ds_1 = my_other_node
```

## Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Node naming conventions are discussed at . Example: `[node.walker_confluence]` |
| type (compulsory) | The node type, which is “gauge” in this case. `type = gauge` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| reference\_flow (optional) | An expression specifying reference flow values that are used to compare the flows entering the gauge. You can record the values of delta (= upstream flow - reference flow) via the “delta” result. Reference flow values may be missing (nan) and this will result in a missing (nan) value for delta in that timestep. Example `reference_flow = data.file_csv.by_index.1` |
| force\_flow (optional) | An expression specifying flow values to be forced at the node. This is done after the reference flow comparison. Example `force_flow = data.file_csv.by_index.1` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. Inflow nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

## Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML] |
| usflow | Upstream flow [ML] |
| reference\_flow | Reference flow for comparison [ML] |
| delta | Difference between upstream flow and the reference flow (i.e. delta = upstream flow - reference flow). If the reference flow is nan, then delta will also be nan. [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Order on link ds\_1 [ML] |
| force\_flow | Forced downstream flow [ML]. If this exists, it should equal dsflow. |

## How the node works

If the “force\_flow” parameter is used, then the downstream flow will be set equal to these values. Otherwise, all inflows are passed to the downstream node.

## References

None.
