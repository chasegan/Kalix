---
title: "Confluence"
---

# Confluence

## At a glance…

The confluence node can be used to merge flow pathways. The node is otherwise passive.

```ini
[node.walker_confluence]
type = confluence
loc = 20, 30
regulated = river_arm            ; orders go up the river arm, by name
ds_1 = my_other_node

[node.two_dam_junction]
type = confluence
loc = 40, 30
regulated = north_arm, south_arm ; split: 70% of orders up the north arm
harmony_fraction = 0.7
ds_1 = another_node
```

## Node properties

| Property | Description |
| --- | --- |
| [node.?] (compulsory) | Start of node declaration. This says we are creating a node, and also defines the name of the node. Node naming conventions are discussed at . Example: `[node.walker_confluence]` |
| type (compulsory) | The node type, which is “confluence” in this case. `type = confluence` |
| loc (compulsory) | The location of the node in cartesian coordinates.  Example: `loc = 20, 30` |
| regulated (optional) | The regulated ordering pathway(s), by upstream node name — the preferred, direction-unambiguous idiom. One name: that branch is the only regulated pathway and all orders propagate up it immediately. Two names: `harmony_fraction` is the fraction of orders sent to the *first listed*. Example: `regulated = north_arm, south_arm` |
| harmony\_fraction (optional) | A dynamic expression giving the fraction of orders directed up the first `regulated` pathway. Required with two `regulated` names; an error beside a single name (one pathway takes everything, so there is nothing to split). Without `regulated` it keeps its legacy meaning — the fraction to the first upstream link defined in the model file — which depends on link order and is better stated with `regulated`. Example: `harmony_fraction = if(sim.month > 6, 1, 0)` |
| expected\_inflow (optional) | Expected inflow joining at this confluence, for the purpose of adjusting orders [ML]. Reduces the order propagated upstream by this amount, the same way it does on an [inflow](inflow.md) node. Useful where an unregulated tributary or minor inflow joins at the confluence and is expected to help meet downstream demand. Example: `expected_inflow = 0.5 * this.dsflow[-1,0]` |
| ds\_1 (optional) | Name of the downstream node. This property defines a downstream link. Inflow nodes may only have 1 downstream link.  Example: `ds_1 = my_other_node` |

## Results associated with this node

| Result | Description |
| --- | --- |
| dsflow | Downstream flow [ML] |
| usflow | Upstream flow [ML] |
| harmony\_fraction | The harmony fraction value [proportion between 0 and 1] |
| ds\_1 | Downstream flow on link ds\_1 [ML] |
| ds\_1\_order | Orders on the link ds\_1 [ML] |
| expected\_inflow | The value of the expected\_inflow expression [ML], used to reduce the order propagated upstream. |

## How the node works

- All inflows are passed to the downstream node.

- Propagation of orders is as follows:
  - With one `regulated` name, every order propagates up the named branch immediately — there is no second pathway to synchronise with, so no lag buffering applies.
  - With two `regulated` names (or none — the legacy link-order convention), the harmony\_fraction is evaluated to determine the proportion of orders directed up the first pathway, with the complement up the second.
  - When splitting, if either upstream branch has a shorter lag time than the other, the orders designated for the short branch are delayed by n timesteps (n = long\_branch\_lag - short\_branch\_lag) so the water from both branches arrives on the correct timestep to meet downstream orders.
  - Each `regulated` name must be one of the confluence's upstream nodes (a load-time error otherwise).
  - This node should not have more than 2 upstream links.
  - If `expected_inflow` is set, it is subtracted from the sum of downstream orders before the remainder is propagated upstream (floored at zero) — the same order-adjustment mechanism used by the [inflow](inflow.md) node's `expected_inflow`.

## References

None.
