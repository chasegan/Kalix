---
title: "Splitter"
---

# Splitter

## At a glance…

The splitter node splits upstream flows between its two outlets “ds\_1” (the primary outlet) and “ds\_2” (the secondary outlet, i.e. effluent outlet).

```ini
[node.high_flow_breakout]
type = splitter
loc = 20, 30
table = 0, 0,
        1000, 0,
        2000, 500,
        1e8, 1e7
ds_1 = next_river_node
ds_2 = node_on_breakout
```

## Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Node naming conventions are discussed at . Example: `[node.high_flow_splitter]` |
| type (compulsory) | The node type, which is “splitter” in this case. `type = splitter` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| table (optional) | Splitter table defines the relationship between the upsteam flow and the flow sent to the secondary (effluent) outlet. Refer to this page to read more about in Kalix. Example: `table = 0, 0, 1000, 0, 2000, 500, 1e8, 1e7` |
| ds\_1 (optional) | Name of the downstream node on the primary outlet (the main channel). Example: `ds_1 = next_river_node` |
| ds\_2 (optional) | Name of the downstream node on the secondary outlet (the effluent). Example: `ds_2 = node_on_breakout` |

## Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Total downstream flow (ds\_1 + ds\_2) [ML] |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Order on link ds\_1 [ML] |
| ds\_2 | Downstream flow on link ds\_2 [ML] |
| ds\_2\_order | Order on link ds\_2 [ML] |

## How the node works

The flow on the secondary link is determined by interpolating the provided splitter table against the upstream flow. Above the last row of the table, the last segment is extended. This volume is sent to ds\_2, and the remainder of the flow is sent to ds\_1.

`ds2=f(usflow)`

`ds1=usflow−ds2`

## References

None.
