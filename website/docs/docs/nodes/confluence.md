---
title: "Confluence"
---

# Confluence

# At a glance…

The confluence node can be used to merge flow pathways. The node is otherwise passive.

```toml
[node.walker_confluence]
type = confluence
loc = 20, 30
harmony_fraction = if(node.little_dam.level > 24.0, 1, 0)
ds_1 = my_other_node
```

# Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Node naming conventions are discussed at . Example: `[node.walker_confluence]` |
| type (compulsory) | The node type, which is “confluence” in this case. `type = confluence` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| harmony\_fraction (optional) | A dynamic expression that determines the fraction of orders directed up the first upstream link (that is, the first link to this node defined in the model file). Example: `harmony_fraction = if(sim.month > 6, 1, 0)` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. Inflow nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

# Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML] |
| usflow | Upstream flow [ML] |
| harmony\_fraction | The harmony fraction value [proportion between 0 and 1] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Orders on the link ds\_1 [ML] |

# How the node works

- All inflows are passed to the downstream node.

- Propagation of orders is as follows:
  - The harmony\_fraction is evaluated to determine the proportion of orders to be directed to the first upstream link (that is, the first link to this node defined in the model file).
  - The complementary proportion will be directed to the second upstream link.
  - If either upstream branch has a shorter lag time than the other, then the orders designated for the short branch will be delayed by the n timesteps (n = long\_branch\_lag - short\_branch\_lag) such that the water would be delivered on the correct timestep to meet downstream orders.
  - This node should not have more than 2 upstream links.

# References

None.
