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
| table (optional) | Splitter table defines the relationship between the upstream flow and the flow sent to the secondary (effluent) outlet: a two-column table of upstream flow and effluent flow, laid out across lines however you like (see [Tables](conventions.md#tables)). If omitted, the splitter sends nothing down the effluent except what is ordered (see [A splitter with no table](#a-splitter-with-no-table)). Example: `table = 0, 0, 1000, 0, 2000, 500, 1e8, 1e7` |
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

In a regulated zone (see [Ordering](ordering.md)) nodes on either outlet may place orders. The splitter sends upstream the smallest order that meets both of them once its table has taken its share, as described under [The order sent upstream](#the-order-sent-upstream) below.

Ordered water takes time to arrive from the supply storage. The splitter holds each effluent order for that travel time, and reports the order that falls due today as `ds_2_order_due`. When the water arrives, the effluent receives the table flow or the order due, whichever is larger, up to the upstream flow:

`ds2=min(max(f(usflow),ds_2_order_due),usflow)`

`ds1=usflow−ds2`

- The table flow counts toward the order, in the same way that a storage's spill counts toward an order on its ds\_1 link.

- If the upstream flow cannot meet the orders on both outlets, the effluent order is met first and the main channel takes the shortfall.

- Outside a regulated zone there are no orders, and the split follows the table alone.

### A splitter with no table

The table is optional. A splitter without one diverts nothing of its own accord, so the effluent receives exactly what is ordered down it and everything else stays in the main channel. This represents a regulated offtake, such as the head of an irrigation channel.

```ini
[node.channel_offtake]
type = splitter
loc = 20, 30
ds_1 = next_river_node
ds_2 = irrigation_channel
```

Outside a regulated zone a splitter with no table sends all of the upstream flow to ds\_1.

### The order sent upstream

The table sends water down the effluent whether or not anyone ordered it, and that water does not reach the main channel. The splitter allows for this in the same way that a [loss node](loss.md) allows for its losses: it raises the order on ds\_1 to the upstream flow that leaves that much on ds\_1 after the table has taken its share. The order sent upstream is that flow, or the sum of the two orders, whichever is larger:

`upstream order=max(g(ds_1_order),ds_1_order+ds_2_order)`

where `g(x)` is the smallest upstream flow for which `usflow−f(usflow)` reaches `x`.

Take a table that sends 10% of the upstream flow to ds\_2, and an order of 100 ML on ds\_1.

- With an order of 5 ML on ds\_2, the splitter orders 111.1 ML. When it arrives the table sends 11.1 ML to ds\_2, which covers the 5 ML order, and ds\_1 receives 100 ML.

- With an order of 20 ML on ds\_2, the splitter orders 120 ML. The table would send 12 ML to ds\_2, the order raises that to 20 ML, and ds\_1 receives 100 ML.

Where the table diverts nothing at regulated flows, as for a high-flow breakout, the order sent upstream is simply the sum of the two orders. If the table sends all additional flow down the effluent above some point, no upstream flow can deliver more than that on ds\_1, and the order is capped there, as it is at a loss node.

## References

None.
