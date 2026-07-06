---
title: Blackhole
---

# Blackhole

*Page status:* ✅

# At a glance…

The blackhole node represents a total sink, whereby any incoming flows are lost and the downstream flow is always zero. This can be used to terminate the network’s end-of-system points, which will help the reported mass-balance → 0. (Using this node is totally optional).

```toml
[node.my_blackhole]
type = blackhole
loc = 20, 30
ds_1 = my_other_node
```

# Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Node naming conventions are discussed at . Example: `[node.my_blackhole]` |
| type (compulsory) | The node type, which is “blackhole” in this case. `type = blackhole` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. Blackhole nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

# Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML] |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Order on link ds\_1 [ML] |

# How the node works

Anything that goes in never comes out.

`dsflow=0`

# References

None.
