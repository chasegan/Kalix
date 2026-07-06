---
title: "Order_Control"
---

# Order_Control

*Page status:* ✅

# At a glance…

The order\_control node allows the modeller to manipulate orders at a point in the network. This includes setting the orders (replacing the values) or applying minimum or maximum values, using dynamic expressions.

```toml
[node.fish_passage_demand]
type = order_control
loc = 20, 30
min_order = if(sim.month >= 6 && sim.month <= 8, 50, 0)
ds_1 = my_other_node
```

# Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Node naming conventions are discussed at . Example: `[node.walker_confluence]` |
| type (compulsory) | The node type, which is “gauge” in this case. `type = gauge` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| min\_order (optional) | A dynamic expression setting the minimum value for the order at this point in the network. Example `min_order = data.file_csv.by_index.1` |
| max\_order (optional) | A dynamic expression setting the maximum value for the order at this point in the network. Example `max_order = data.file_csv.by_index.1` |
| set\_order (optional) | A dynamic expression setting the order at this point in the network. This replaces the order value completely. Example `set_order = data.file_csv.by_index.1` |
| delay\_order\_steps (optional) | This property allows the modeller to delay downstream orders by the specified number of timesteps. This is an advanced feature allowing the modeller to manually delay orders on regulated pathways if they would otherwise deliver too early. Example `delay_order_steps = 2` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. Inflow nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

# Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML] |
| usflow | Upstream flow [ML] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Orders on the ds\_1 link [ML] |
| min\_order | Value of the min\_order property [ML] |
| max\_order | Value of the max\_order property [ML] |
| set\_order | Value of the set\_order property [ML] |
| order | The order sent upstream [ML] |
| order\_due | The order previously sent which is due this timestep [ML] |

# How the node works

If the modeller has specified `set_order`, then this value is ordered this timestep regardless of the downstream orders. In this case, this is all the node does.

Otherwise, if this modeller has specified `delay_order_steps`, then the downstream orders are lagged by this many steps. The downstream orders are subsequently subject to bounds specified by `min_order` and `max_order` . The min order functionality is useful for implementing environmental demands (or other non-consumptive demands). The max order functionality is useful for implementing operational constraints in the regulated network.

# References

None.

# Changelog

Formerly known as “Order\_Constraint” up to Kalix v0.3.2.
