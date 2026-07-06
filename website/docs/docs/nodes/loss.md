---
title: "Loss"
---

# Loss

## At a glance…

The loss node loses flow based on a provided flow-loss relationship.

```toml
[node.high_flow_loss]
type = loss
loc = 20, 30
table = 0, 0,
        1000, 0,
        2000, 500,
        1e8, 1e7
ds_1 = node_below_loss
```

## Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Node naming conventions are discussed at . Example: `[node.high_flow_loss]` |
| type (compulsory) | The node type, which is “loss” in this case. `type = loss` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| table (optional) | Loss table defines the relationship between the upsteam flow and the loss. Refer to this page to read more about in Kalix. Example: `table = 0, 0, 1000, 0, 2000, 500, 1e8, 1e7` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. Inflow nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

## Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Total downstream flow (=ds\_1) [ML] |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Orders on the link ds\_1 [ML] |
| loss | Amount of water lost at this node [ML] |

## How the node works

The flow on the secondary link is determine by interpolating the provided splitter table. This volume is sent to ds\_2, and the remainder of the flow is sent to ds\_1.

`loss=f(usflow)`

`ds1=usflow−loss`

## References

None.
