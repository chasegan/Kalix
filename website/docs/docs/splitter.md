---
title: "Splitter"
---

# Splitter

## At a glance…

The splitter node splits upstream flows between its two outlets “ds\_1” (the primary outlet) and “ds\_2” (the secondary outlet, i.e. effluent outlet). The split follows a table of upstream flow against effluent flow. In a regulated zone the splitter also delivers orders placed on the effluent.

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
| ds\_2\_order\_due | The order previously placed on link ds\_2 which is due to be delivered today [ML] |

## How the node works

The flow on the secondary link is determined by interpolating the provided splitter table against the upstream flow. Above the last row of the table, the last segment is extended. This volume is sent to ds\_2, and the remainder of the flow is sent to ds\_1.

`ds2=f(usflow)`

`ds1=usflow−ds2`

### Orders on the effluent

In a regulated zone (see [Ordering](ordering.md)) nodes on either outlet may place orders. The splitter adds the orders from both outlets together and sends the total upstream:

`upstream order=ds_1_order+ds_2_order`

Ordered water takes time to arrive from the supply storage. The splitter holds each effluent order for that travel time, and reports the order that falls due today as `ds_2_order_due`. When the water arrives, the effluent receives the table flow or the order due, whichever is larger, up to the upstream flow:

`ds2=min(max(f(usflow),ds_2_order_due),usflow)`

`ds1=usflow−ds2`

- The table flow counts toward the order, in the same way that a storage's spill counts toward an order on its ds\_1 link.

- If the upstream flow cannot meet the orders on both outlets, the effluent order is met first and the main channel takes the shortfall.

- Outside a regulated zone there are no orders, and the split follows the table alone.

Orders pass through the splitter without allowing for the flow that the table sends down the effluent. Take a table that sends 10% of the upstream flow to ds\_2, an order of 100 ML on ds\_1, and an order of 5 ML on ds\_2. The splitter orders 105 ML from upstream. When it arrives the table sends 10.5 ML to ds\_2, which is more than the 5 ML order, and ds\_1 receives 94.5 ML. Where a table diverts water at regulated flows, raise the orders to cover it, for example with the regulated user's [`order_factor`](regulated-user.md#order-factor).

## References

None.
