---
title: "Loss"
---

# Loss

## At a glance…

The loss node loses flow based on a provided flow-loss relationship.

```ini
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
| rate (optional) | Loss rate override: a data reference, constant, or [dynamic expression](dynamic-expressions.md). When set, the loss is taken from this value each timestep instead of the loss table (the table, if also present, is ignored for computing loss but still shapes ordering — see below). The value is clamped between 0 and the upstream flow. Example: `rate = data.losses.reach_3` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. Inflow nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

## Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Total downstream flow (=ds\_1) [ML] |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Orders on the link ds\_1 [ML] |
| loss | Amount of water lost at this node [ML] |
| rate | The evaluated `rate` expression (before clamping) [ML]. All missing values when no `rate` property is set. |

## How the node works

The attempted loss is determined by interpolating the provided loss table against the upstream flow — or, when the `rate` property is set, by evaluating that expression instead. The loss actually taken is the attempted loss clamped between 0 and the upstream flow, and the remainder continues to ds\_1.

`loss=f(usflow)` (table) or `loss=rate` (override)

`ds1=usflow−loss`

Note on ordering: upstream orders are translated through the loss **table** in both cases. A `rate` expression overrides how much water is actually lost, but ordering has no way to foresee an arbitrary expression's value, so it keeps using the table — supply a representative table alongside `rate` if downstream orders should account for expected losses (with no table, ordering assumes zero loss).

## References

None.
